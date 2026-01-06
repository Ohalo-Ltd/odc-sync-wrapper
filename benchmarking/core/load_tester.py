"""Core load testing functionality."""

import asyncio
import aiohttp
import json
import logging
import statistics
import time
from datetime import datetime
from typing import List, Dict, Any, Optional

from .models import TestConfig, TestResult
from .sample_loader import SampleLoader


class LoadTester:
    """
    Load tester for the ODC Sync Wrapper API.

    Supports two modes:
    - Sequential: Sends files one after another
    - Rate-limited: Sends files at a specified rate for a specified duration
    """

    def __init__(self, config: TestConfig, api_key: Optional[str] = None):
        self.config = config
        self.api_key = api_key
        self.sample_loader = SampleLoader(config.samples_dir)
        self.results: List[Dict[str, Any]] = []
        self.start_time: Optional[float] = None

        logging.basicConfig(
            level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
        )
        self.logger = logging.getLogger(__name__)

    async def run(self) -> TestResult:
        """Run load test based on config (rate-limited or sequential)."""
        await self.sample_loader.load()

        if self.config.is_sequential:
            return await self.run_sequential()
        elif self.config.is_rate_limited:
            return await self.run_rate_limited()
        else:
            raise ValueError(
                "Invalid config: must specify either sequential_count or "
                "(files_per_second and duration)"
            )

    async def send_file_request(
        self, session: aiohttp.ClientSession, file_name: str, file_content: bytes
    ) -> Dict[str, Any]:
        """Send a single file classification request."""
        start_time = time.time()

        try:
            content_type = self.sample_loader.get_content_type(file_name)
            data = aiohttp.FormData()
            data.add_field(
                "file", file_content, filename=file_name, content_type=content_type
            )

            headers = {}
            if self.api_key:
                headers["Authorization"] = f"Bearer {self.api_key}"

            async with session.post(
                f"{self.config.server_url}/classify-file", data=data, headers=headers
            ) as response:
                end_time = time.time()
                latency_ms = (end_time - start_time) * 1000
                response_text = await response.text()

                is_success = response.status == 200

                # Additional validation for error patterns in successful HTTP responses
                if is_success and response_text:
                    try:
                        json_response = json.loads(response_text)
                        if (
                            isinstance(json_response, dict)
                            and json_response.get("status") == "FAILED"
                        ):
                            is_success = False
                    except (json.JSONDecodeError, ValueError):
                        pass

                    if is_success:
                        error_indicators = [
                            "API key is required",
                            "Unauthorized",
                            "Authentication failed",
                            "Invalid API key",
                            "Access denied",
                            '"error"',
                            '"message"',
                            '"status":"FAILED"',
                            '"status": "FAILED"',
                        ]
                        response_lower = response_text.lower()
                        for indicator in error_indicators:
                            if indicator.lower() in response_lower:
                                is_success = False
                                break

                result = {
                    "status_code": response.status,
                    "latency_ms": latency_ms,
                    "success": is_success,
                    "timestamp": start_time,
                    "end_time": end_time,
                    "file_name": file_name,
                    "response_text": response_text[:200] if response_text else None,
                }

                if not is_success:
                    result["error"] = response_text

                return result

        except Exception as e:
            end_time = time.time()
            latency_ms = (end_time - start_time) * 1000
            return {
                "status_code": 0,
                "latency_ms": latency_ms,
                "success": False,
                "timestamp": start_time,
                "end_time": end_time,
                "file_name": file_name,
                "error": str(e),
            }

    async def run_sequential(self) -> TestResult:
        """Run sequential test - send files one after another."""
        if not self.config.sequential_count:
            raise ValueError("Sequential count must be specified for sequential mode")

        total_requests = self.config.sequential_count

        self.logger.info("Starting sequential test:")
        self.logger.info(f"  Sending {total_requests} files sequentially")

        timeout = aiohttp.ClientTimeout(total=1200)  # 20 minute timeout
        connector = aiohttp.TCPConnector(limit=10, limit_per_host=10)

        start_datetime = datetime.now()
        async with aiohttp.ClientSession(
            timeout=timeout, connector=connector
        ) as session:
            self.start_time = time.time()
            file_index = 0

            for i in range(total_requests):
                file_name, file_content = self.sample_loader.get_file(file_index)
                file_index += 1

                self.logger.info(f"Sending file {i + 1}/{total_requests}: {file_name}")
                result = await self.send_file_request(session, file_name, file_content)
                self.results.append(result)

                if result["success"]:
                    self.logger.info(f"  Success - {result['latency_ms']:.0f}ms")
                else:
                    self.logger.warning(
                        f"  Failed - {result.get('error', 'Unknown error')[:50]}..."
                    )

        end_datetime = datetime.now()
        return self._calculate_results(
            test_mode="sequential",
            start_datetime=start_datetime,
            end_datetime=end_datetime,
        )

    async def run_rate_limited(self) -> TestResult:
        """Run rate-limited test - send files at specified rate for specified duration."""
        if not self.config.files_per_second or not self.config.duration:
            raise ValueError(
                "Files per second and duration must be specified for rate-limited mode"
            )

        request_interval = 1.0 / self.config.files_per_second
        total_requests = self.config.files_per_second * self.config.duration

        self.logger.info("Starting rate-limited test:")
        self.logger.info(
            f"  Target: {self.config.files_per_second} files/second "
            f"for {self.config.duration} seconds"
        )
        self.logger.info(f"  Total requests: {total_requests}")
        self.logger.info(f"  Request interval: {request_interval:.3f}s")

        timeout = aiohttp.ClientTimeout(total=1200)
        connector = aiohttp.TCPConnector(limit=200, limit_per_host=200)

        start_datetime = datetime.now()
        async with aiohttp.ClientSession(
            timeout=timeout, connector=connector
        ) as session:
            self.start_time = time.time()
            file_index = 0
            tasks = []

            for i in range(total_requests):
                file_name, file_content = self.sample_loader.get_file(file_index)
                file_index += 1

                task = asyncio.create_task(
                    self._send_and_store(session, file_name, file_content)
                )
                tasks.append(task)

                if i < total_requests - 1:
                    await asyncio.sleep(request_interval)

                if (i + 1) % (self.config.files_per_second * 10) == 0:
                    elapsed = time.time() - self.start_time
                    self.logger.info(
                        f"Sent {i + 1}/{total_requests} requests ({elapsed:.1f}s elapsed)"
                    )

            self.logger.info("Waiting for all requests to complete...")
            await asyncio.gather(*tasks, return_exceptions=True)

        end_datetime = datetime.now()
        return self._calculate_results(
            test_mode="rate_limited",
            start_datetime=start_datetime,
            end_datetime=end_datetime,
            target_files_per_second=self.config.files_per_second,
        )

    async def _send_and_store(
        self, session: aiohttp.ClientSession, file_name: str, file_content: bytes
    ):
        """Send a request and store the result."""
        result = await self.send_file_request(session, file_name, file_content)
        self.results.append(result)

    def _calculate_results(
        self,
        test_mode: str,
        start_datetime: datetime,
        end_datetime: datetime,
        target_files_per_second: Optional[int] = None,
    ) -> TestResult:
        """Calculate test results from collected data."""
        if not self.results:
            raise ValueError("No results to calculate")

        total_requests = len(self.results)
        successful_requests = sum(1 for r in self.results if r["success"])
        failed_requests = total_requests - successful_requests

        latencies = [r["latency_ms"] for r in self.results]
        avg_latency = statistics.mean(latencies)
        min_latency = min(latencies)
        max_latency = max(latencies)

        sorted_latencies = sorted(latencies)
        p95_index = int(0.95 * len(sorted_latencies))
        p99_index = int(0.99 * len(sorted_latencies))
        p95_latency = sorted_latencies[min(p95_index, len(sorted_latencies) - 1)]
        p99_latency = sorted_latencies[min(p99_index, len(sorted_latencies) - 1)]

        # Calculate throughput from first request to last completion
        first_request_start = min(r["timestamp"] for r in self.results)
        last_request_completion = max(
            r["timestamp"] + r["latency_ms"] / 1000 for r in self.results
        )
        actual_duration = last_request_completion - first_request_start
        throughput = total_requests / actual_duration if actual_duration > 0 else 0

        error_rate = (failed_requests / total_requests) * 100

        return TestResult(
            datasource_count=self.config.datasource_count,
            test_mode=test_mode,
            total_requests=total_requests,
            successful_requests=successful_requests,
            failed_requests=failed_requests,
            avg_latency_ms=avg_latency,
            min_latency_ms=min_latency,
            max_latency_ms=max_latency,
            p95_latency_ms=p95_latency,
            p99_latency_ms=p99_latency,
            throughput_rps=throughput,
            error_rate=error_rate,
            start_timestamp=start_datetime,
            end_timestamp=end_datetime,
            duration_seconds=actual_duration,
            target_files_per_second=target_files_per_second,
            raw_results=self.results,
        )

    def print_results(self, result: TestResult):
        """Print test results in a formatted way."""
        print("\n" + "=" * 60)
        print("LOAD TEST RESULTS")
        print("=" * 60)
        print(f"Total Requests:      {result.total_requests}")
        print(f"Successful Requests: {result.successful_requests}")
        print(f"Failed Requests:     {result.failed_requests}")
        print(f"Error Rate:          {result.error_rate:.2f}%")
        print()
        print("LATENCY STATISTICS (ms)")
        print("-" * 30)
        print(f"Average:             {result.avg_latency_ms:.2f}")
        print(f"Minimum:             {result.min_latency_ms:.2f}")
        print(f"Maximum:             {result.max_latency_ms:.2f}")
        print(f"95th Percentile:     {result.p95_latency_ms:.2f}")
        print(f"99th Percentile:     {result.p99_latency_ms:.2f}")
        print()
        print("THROUGHPUT")
        print("-" * 30)
        print(f"Actual Throughput:   {result.throughput_rps:.2f} requests/second")
        print("=" * 60)

        # Print error details if any
        if result.failed_requests > 0 and result.raw_results:
            print("\nERROR SUMMARY")
            print("-" * 30)
            error_counts: Dict[str, int] = {}
            sample_errors: Dict[str, str] = {}

            for r in result.raw_results:
                if not r["success"]:
                    error_text = r.get("error", f"HTTP {r['status_code']}")
                    error_key = error_text[:100] if len(error_text) > 100 else error_text
                    error_counts[error_key] = error_counts.get(error_key, 0) + 1
                    if error_key not in sample_errors:
                        sample_errors[error_key] = error_text

            for error_key, count in error_counts.items():
                print(f"Error ({count} occurrences): {error_key}")
                if error_key in sample_errors and len(sample_errors[error_key]) > 100:
                    print(f"  Full error: {sample_errors[error_key]}")
