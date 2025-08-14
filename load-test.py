#!/usr/bin/env python3
"""
Performance Load Test Script for ODC Sync Wrapper API
Usage: python load-test.py --files-per-second X --duration Y [--server-url URL]
"""

import argparse
import asyncio
import aiohttp
import aiofiles
import time
import statistics
import sys
import os
import json
from pathlib import Path
from dataclasses import dataclass
from typing import List, Optional
import logging

@dataclass
class TestResult:
    total_requests: int
    successful_requests: int
    failed_requests: int
    avg_latency_ms: float
    min_latency_ms: float
    max_latency_ms: float
    p95_latency_ms: float
    p99_latency_ms: float
    throughput_rps: float
    error_rate: float

class LoadTester:
    def __init__(self, server_url: str, samples_dir: str, files_per_second: Optional[int] = None,
                 duration: Optional[int] = None, sequential_count: Optional[int] = None):
        self.server_url = server_url
        self.files_per_second = files_per_second
        self.duration = duration
        self.sequential_count = sequential_count
        self.samples_dir = Path(samples_dir)
        self.sample_files = []
        self.results = []
        self.start_time = None

        # Determine test mode
        self.is_sequential = sequential_count is not None

        # Setup logging
        logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
        self.logger = logging.getLogger(__name__)

    async def load_sample_files(self):
        """Load all sample files into memory"""
        sample_files = list(self.samples_dir.glob("testfile*")) + list(self.samples_dir.glob("sample*"))
        if not sample_files:
            raise FileNotFoundError(f"No sample files found in {self.samples_dir}")

        self.logger.info(f"Loading {len(sample_files)} sample files")

        for file_path in sample_files:
            async with aiofiles.open(file_path, 'rb') as f:
                content = await f.read()
                self.sample_files.append((file_path.name, content))

        self.logger.info(f"Loaded {len(self.sample_files)} sample files")

    async def send_file_request(self, session: aiohttp.ClientSession, file_name: str, file_content: bytes) -> dict:
        """Send a single file classification request"""
        start_time = time.time()

        try:
            data = aiohttp.FormData()
            data.add_field('file', file_content, filename=file_name, content_type='text/plain')

            async with session.post(f"{self.server_url}/classify-file", data=data) as response:
                end_time = time.time()
                latency_ms = (end_time - start_time) * 1000
                response_text = await response.text()

                # Check for success based on status code AND response content
                is_success = response.status == 200

                # Additional checks for common error patterns in successful HTTP responses
                if is_success and response_text:
                    # First try to parse JSON and check status field
                    try:
                        json_response = json.loads(response_text)
                        if isinstance(json_response, dict) and json_response.get('status') == 'FAILED':
                            is_success = False
                    except (json.JSONDecodeError, ValueError):
                        # If not valid JSON, fall back to text pattern matching
                        pass
                    
                    # If still successful, check for text-based error indicators
                    if is_success:
                        error_indicators = [
                            'API key is required',
                            'Unauthorized',
                            'Authentication failed',
                            'Invalid API key',
                            'Access denied',
                            '"error"',
                            '"message"',
                            '"status":"FAILED"',
                            '"status": "FAILED"'
                        ]

                        response_lower = response_text.lower()
                        for indicator in error_indicators:
                            if indicator.lower() in response_lower:
                                is_success = False
                                break

                result = {
                    'status_code': response.status,
                    'latency_ms': latency_ms,
                    'success': is_success,
                    'timestamp': start_time,
                    'file_name': file_name,
                    'response_text': response_text[:200] if response_text else None  # First 200 chars for debugging
                }

                if not is_success:
                    result['error'] = response_text

                return result

        except Exception as e:
            end_time = time.time()
            latency_ms = (end_time - start_time) * 1000
            return {
                'status_code': 0,
                'latency_ms': latency_ms,
                'success': False,
                'timestamp': start_time,
                'file_name': file_name,
                'error': str(e)
            }

    async def send_request_at_rate(self, session: aiohttp.ClientSession, file_name: str, file_content: bytes):
        """Send a request directly to the server and store result"""
        result = await self.send_file_request(session, file_name, file_content)
        self.results.append(result)

    async def run_load_test(self) -> TestResult:
        """Run the load test in either rate-limited or sequential mode"""
        await self.load_sample_files()

        if self.is_sequential:
            return await self.run_sequential_test()
        else:
            return await self.run_rate_limited_test()

    async def run_sequential_test(self) -> TestResult:
        """Run sequential test - send X files one after another"""
        if not self.sequential_count:
            raise ValueError("Sequential count must be specified for sequential mode")

        total_requests = self.sequential_count

        self.logger.info(f"Starting sequential test:")
        self.logger.info(f"  Sending {total_requests} files sequentially (one after another)")

        # Setup HTTP session
        timeout = aiohttp.ClientTimeout(total=1200)  # 20 minute timeout
        connector = aiohttp.TCPConnector(limit=10, limit_per_host=10)  # Lower limits for sequential

        async with aiohttp.ClientSession(timeout=timeout, connector=connector) as session:
            self.start_time = time.time()
            file_index = 0

            try:
                for i in range(total_requests):
                    # Cycle through sample files
                    file_name, file_content = self.sample_files[file_index % len(self.sample_files)]
                    file_index += 1

                    # Send request and wait for completion (sequential)
                    self.logger.info(f"Sending file {i + 1}/{total_requests}: {file_name}")
                    result = await self.send_file_request(session, file_name, file_content)
                    self.results.append(result)

                    # Log result
                    if result['success']:
                        self.logger.info(f"  ✓ Success - {result['latency_ms']:.0f}ms")
                    else:
                        self.logger.warning(f"  ✗ Failed - {result.get('error', 'Unknown error')[:50]}...")

            except Exception as e:
                self.logger.error(f"Error during sequential test: {e}")
                raise

        # Calculate results
        return self.calculate_results()

    async def run_rate_limited_test(self) -> TestResult:
        """Run rate-limited test - send files at specified rate for specified duration"""
        if not self.files_per_second or not self.duration:
            raise ValueError("Files per second and duration must be specified for rate-limited mode")

        # Calculate timing
        request_interval = 1.0 / self.files_per_second
        total_requests = self.files_per_second * self.duration

        self.logger.info(f"Starting rate-limited test:")
        self.logger.info(f"  Target: {self.files_per_second} files/second for {self.duration} seconds")
        self.logger.info(f"  Total requests: {total_requests}")
        self.logger.info(f"  Request interval: {request_interval:.3f}s")

        # Setup HTTP session
        timeout = aiohttp.ClientTimeout(total=1200)  # 20 minute timeout
        connector = aiohttp.TCPConnector(limit=200, limit_per_host=200)

        async with aiohttp.ClientSession(timeout=timeout, connector=connector) as session:
            # Start the test
            self.start_time = time.time()
            file_index = 0
            tasks = []

            try:
                for i in range(total_requests):
                    # Cycle through sample files
                    file_name, file_content = self.sample_files[file_index % len(self.sample_files)]
                    file_index += 1

                    # Send request directly to server (no queuing)
                    task = asyncio.create_task(
                        self.send_request_at_rate(session, file_name, file_content)
                    )
                    tasks.append(task)

                    # Wait for next request time
                    if i < total_requests - 1:  # Don't wait after the last request
                        await asyncio.sleep(request_interval)

                    # Progress logging
                    if (i + 1) % (self.files_per_second * 10) == 0:
                        elapsed = time.time() - self.start_time
                        self.logger.info(f"Sent {i + 1}/{total_requests} requests ({elapsed:.1f}s elapsed)")

                # Wait for all requests to complete
                self.logger.info("Waiting for all requests to complete...")
                await asyncio.gather(*tasks, return_exceptions=True)

            except Exception as e:
                self.logger.error(f"Error during rate-limited test: {e}")
                # Cancel remaining tasks
                for task in tasks:
                    if not task.done():
                        task.cancel()
                raise

        # Calculate results
        return self.calculate_results()

    def calculate_results(self) -> TestResult:
        """Calculate test results from collected data"""
        if not self.results:
            raise ValueError("No results to calculate")

        total_requests = len(self.results)
        successful_requests = sum(1 for r in self.results if r['success'])
        failed_requests = total_requests - successful_requests

        # Calculate latencies
        latencies = [r['latency_ms'] for r in self.results]
        avg_latency = statistics.mean(latencies)
        min_latency = min(latencies)
        max_latency = max(latencies)

        # Calculate percentiles
        sorted_latencies = sorted(latencies)
        p95_latency = sorted_latencies[int(0.95 * len(sorted_latencies))]
        p99_latency = sorted_latencies[int(0.99 * len(sorted_latencies))]

        # Calculate throughput - from first request start to last response completion
        if self.results:
            first_request_start = min(r['timestamp'] for r in self.results)
            last_request_completion = max(r['timestamp'] + r['latency_ms']/1000 for r in self.results)
            actual_test_duration = last_request_completion - first_request_start
            throughput = total_requests / actual_test_duration if actual_test_duration > 0 else 0
        else:
            throughput = 0

        error_rate = (failed_requests / total_requests) * 100

        return TestResult(
            total_requests=total_requests,
            successful_requests=successful_requests,
            failed_requests=failed_requests,
            avg_latency_ms=avg_latency,
            min_latency_ms=min_latency,
            max_latency_ms=max_latency,
            p95_latency_ms=p95_latency,
            p99_latency_ms=p99_latency,
            throughput_rps=throughput,
            error_rate=error_rate
        )

    def print_results(self, result: TestResult):
        """Print test results in a formatted way"""
        print("\n" + "="*60)
        print("LOAD TEST RESULTS")
        print("="*60)
        print(f"Total Requests:      {result.total_requests}")
        print(f"Successful Requests: {result.successful_requests}")
        print(f"Failed Requests:     {result.failed_requests}")
        print(f"Error Rate:          {result.error_rate:.2f}%")
        print()
        print("LATENCY STATISTICS (ms)")
        print("-"*30)
        print(f"Average:             {result.avg_latency_ms:.2f}")
        print(f"Minimum:             {result.min_latency_ms:.2f}")
        print(f"Maximum:             {result.max_latency_ms:.2f}")
        print(f"95th Percentile:     {result.p95_latency_ms:.2f}")
        print(f"99th Percentile:     {result.p99_latency_ms:.2f}")
        print()
        print("THROUGHPUT")
        print("-"*30)
        print(f"Actual Throughput:   {result.throughput_rps:.2f} requests/second")
        print("="*60)

        # Print error details if any
        if result.failed_requests > 0:
            print("\nERROR SUMMARY")
            print("-"*30)
            error_counts = {}
            sample_errors = {}

            for r in self.results:
                if not r['success']:
                    # Get first 100 chars of error for grouping
                    error_text = r.get('error', f"HTTP {r['status_code']}")
                    error_key = error_text[:100] if len(error_text) > 100 else error_text

                    error_counts[error_key] = error_counts.get(error_key, 0) + 1

                    # Keep a sample of the full error for reference
                    if error_key not in sample_errors:
                        sample_errors[error_key] = error_text

            for error_key, count in error_counts.items():
                print(f"Error ({count} occurrences): {error_key}")
                if error_key in sample_errors and len(sample_errors[error_key]) > 100:
                    print(f"  Full error: {sample_errors[error_key]}")

        # Debug info for first few results when there are issues
        if result.failed_requests > 0 or result.total_requests < 5:
            print("\nDEBUG: First few results for troubleshooting:")
            print("-"*50)
            for i, r in enumerate(self.results[:3]):
                print(f"Request {i+1}: Status={r['status_code']}, Success={r['success']}")
                if r.get('response_text'):
                    print(f"  Response: {r['response_text']}")
                if r.get('error'):
                    print(f"  Error: {r['error'][:200]}...")
                print()



async def main():
    parser = argparse.ArgumentParser(description='Performance Load Test for ODC Sync Wrapper API')

    # Create mutually exclusive group for test modes
    mode_group = parser.add_mutually_exclusive_group(required=True)
    mode_group.add_argument('--rate-mode', action='store_true',
                           help='Run in rate-limited mode (requires --files-per-second and --duration)')
    mode_group.add_argument('--sequential-mode', type=int, metavar='COUNT',
                           help='Run in sequential mode, sending COUNT files one after another for testing single-file latency')

    # Rate-limited mode arguments
    parser.add_argument('--files-per-second', type=int,
                        help='Number of files to send per second (required for --rate-mode)')
    parser.add_argument('--duration', type=int,
                        help='Test duration in seconds (required for --rate-mode)')

    # Common arguments
    parser.add_argument('--server-url', type=str, default='http://localhost:8844',
                        help='Server URL (default: http://localhost:8844)')
    parser.add_argument('--samples-dir', type=str, default='samples/plain_txt',
                        help='Directory containing sample files (default: samples/plain_txt)')

    args = parser.parse_args()

    # Validate arguments based on mode
    if args.rate_mode:
        if not args.files_per_second or not args.duration:
            print("Error: --rate-mode requires both --files-per-second and --duration")
            sys.exit(1)
        if args.files_per_second <= 0:
            print("Error: files-per-second must be positive")
            sys.exit(1)
        if args.duration <= 0:
            print("Error: duration must be positive")
            sys.exit(1)
    elif args.sequential_mode:
        if args.sequential_mode <= 0:
            print("Error: sequential count must be positive")
            sys.exit(1)

    # Check if samples directory exists
    samples_path = Path(args.samples_dir)
    if not samples_path.exists():
        print(f"Error: Samples directory '{args.samples_dir}' does not exist")
        sys.exit(1)

    # Create and run load tester
    if args.rate_mode:
        tester = LoadTester(args.server_url, args.samples_dir,
                           files_per_second=args.files_per_second, duration=args.duration)
    else:  # sequential mode
        tester = LoadTester(args.server_url, args.samples_dir,
                           sequential_count=args.sequential_mode)

    try:
        result = await tester.run_load_test()
        tester.print_results(result)


    except KeyboardInterrupt:
        print("\nTest interrupted by user")
        if tester.results:
            result = tester.calculate_results()
            tester.print_results(result)
    except Exception as e:
        print(f"Error running load test: {e}")
        sys.exit(1)


if __name__ == '__main__':
    asyncio.run(main())