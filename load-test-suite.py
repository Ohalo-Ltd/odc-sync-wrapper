#!/usr/bin/env python3
"""
Performance Load Test Suite for ODC Sync Wrapper API
Runs multiple load tests with incrementing rates and generates summary report
Usage: python load-test-suite.py [--server-url URL] [--samples-dir DIR] [--api-key KEY] [--duration SECONDS]
"""

import argparse
import asyncio
import aiohttp
import aiofiles
import time
import statistics
import sys
import os
from pathlib import Path
from dataclasses import dataclass
from typing import List, Optional
import logging
import matplotlib.pyplot as plt
import pandas as pd
from datetime import datetime

@dataclass
class TestResult:
    files_per_second: int
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
    def __init__(self, server_url: str, files_per_second: int, duration: int, samples_dir: str, api_key: Optional[str] = None):
        self.server_url = server_url
        self.files_per_second = files_per_second
        self.duration = duration
        self.samples_dir = Path(samples_dir)
        self.api_key = api_key
        self.sample_files = []
        self.results = []
        self.start_time = None

        # Setup logging
        logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
        self.logger = logging.getLogger(__name__)

    async def load_sample_files(self):
        """Load all sample files into memory"""
        # Look for both .txt and .pdf files
        txt_files = list(self.samples_dir.glob("sample*.txt"))
        pdf_files = list(self.samples_dir.glob("sample*.pdf"))
        sample_files = txt_files + pdf_files

        if not sample_files:
            raise FileNotFoundError(f"No sample files (.txt or .pdf) found in {self.samples_dir}")

        self.logger.info(f"Loading {len(sample_files)} sample files ({len(txt_files)} .txt, {len(pdf_files)} .pdf)")

        for file_path in sample_files:
            async with aiofiles.open(file_path, 'rb') as f:
                content = await f.read()
                self.sample_files.append((file_path.name, content))

        self.logger.info(f"Loaded {len(self.sample_files)} sample files")

    async def send_file_request(self, session: aiohttp.ClientSession, file_name: str, file_content: bytes) -> dict:
        """Send a single file classification request"""
        start_time = time.time()

        try:
            # Determine content type based on file extension
            content_type = 'application/pdf' if file_name.lower().endswith('.pdf') else 'text/plain'

            data = aiohttp.FormData()
            data.add_field('file', file_content, filename=file_name, content_type=content_type)

            # Prepare headers with API key if provided
            headers = {}
            if self.api_key:
                headers['Authorization'] = f'Bearer {self.api_key}'

            async with session.post(f"{self.server_url}/classify-file", data=data, headers=headers) as response:
                end_time = time.time()
                latency_ms = (end_time - start_time) * 1000

                result = {
                    'status_code': response.status,
                    'latency_ms': latency_ms,
                    'success': response.status == 200,
                    'start_time': start_time,
                    'end_time': end_time,
                    'file_name': file_name
                }

                if response.status != 200:
                    result['error'] = await response.text()

                return result

        except Exception as e:
            end_time = time.time()
            latency_ms = (end_time - start_time) * 1000
            return {
                'status_code': 0,
                'latency_ms': latency_ms,
                'success': False,
                'start_time': start_time,
                'end_time': end_time,
                'file_name': file_name,
                'error': str(e)
            }

    async def send_request_at_rate(self, session: aiohttp.ClientSession, file_name: str, file_content: bytes):
        """Send a request directly to the server and store result"""
        result = await self.send_file_request(session, file_name, file_content)
        self.results.append(result)

    async def run_load_test(self) -> TestResult:
        """Run the load test"""
        await self.load_sample_files()

        # Calculate timing
        request_interval = 1.0 / self.files_per_second
        total_requests = self.files_per_second * self.duration

        self.logger.info(f"Starting load test:")
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
                self.logger.error(f"Error during load test: {e}")
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

        # Calculate throughput using actual test duration
        if self.results:
            # Use the time from first request start to last request completion
            test_start = min(r['start_time'] for r in self.results)
            test_end = max(r['end_time'] for r in self.results)
            test_duration = test_end - test_start
            throughput = total_requests / test_duration if test_duration > 0 else 0
        else:
            throughput = 0

        error_rate = (failed_requests / total_requests) * 100

        return TestResult(
            files_per_second=self.files_per_second,
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

class LoadTestSuite:
    def __init__(self, server_url: str, samples_dir: str, duration: int = 180, api_key: Optional[str] = None):
        self.server_url = server_url
        self.samples_dir = samples_dir
        self.duration = duration
        self.api_key = api_key
        self.results = []

        # Setup logging
        logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
        self.logger = logging.getLogger(__name__)

    async def run_test_suite(self) -> List[TestResult]:
        """Run a series of load tests with incrementing rates"""
        test_rates = [1, 2, 4, 8, 16, 32] # list(range(2, 21, 2))  # 10, 20, 30, ..., 100 files per second

        self.logger.info(f"Starting load test suite with {len(test_rates)} test configurations")
        self.logger.info(f"Test rates: {test_rates}")
        self.logger.info(f"Duration per test: {self.duration} seconds")

        for i, rate in enumerate(test_rates):
            self.logger.info(f"\n{'='*60}")
            self.logger.info(f"Running test {i+1}/{len(test_rates)}: {rate} files/second")
            self.logger.info(f"{'='*60}")

            tester = LoadTester(self.server_url, rate, self.duration, self.samples_dir, self.api_key)

            try:
                result = await tester.run_load_test()
                self.results.append(result)

                # Print immediate results
                self.print_single_test_result(result)

            except Exception as e:
                self.logger.error(f"Test failed for rate {rate}: {e}")
                # Create a failed result
                failed_result = TestResult(
                    files_per_second=rate,
                    total_requests=0,
                    successful_requests=0,
                    failed_requests=0,
                    avg_latency_ms=0,
                    min_latency_ms=0,
                    max_latency_ms=0,
                    p95_latency_ms=0,
                    p99_latency_ms=0,
                    throughput_rps=0,
                    error_rate=100.0
                )
                self.results.append(failed_result)

        return self.results

    def print_single_test_result(self, result: TestResult):
        """Print results for a single test"""
        print(f"\nTest Results for {result.files_per_second} files/second:")
        print(f"  Throughput: {result.throughput_rps:.2f} req/s")
        print(f"  Avg Latency: {result.avg_latency_ms:.2f}ms")
        print(f"  P95 Latency: {result.p95_latency_ms:.2f}ms")
        print(f"  Error Rate: {result.error_rate:.2f}%")

    def print_summary_table(self):
        """Print a comprehensive summary table"""
        print("\n" + "="*120)
        print("LOAD TEST SUITE SUMMARY")
        print("="*120)

        # Create DataFrame for better formatting
        data = []
        for result in self.results:
            data.append({
                'Target Rate': f"{result.files_per_second} req/s",
                'Actual Throughput': f"{result.throughput_rps:.2f} req/s",
                'Avg Latency': f"{result.avg_latency_ms:.2f}ms",
                'P95 Latency': f"{result.p95_latency_ms:.2f}ms",
                'P99 Latency': f"{result.p99_latency_ms:.2f}ms",
                'Error Rate': f"{result.error_rate:.2f}%",
                'Success Rate': f"{(100 - result.error_rate):.2f}%"
            })

        df = pd.DataFrame(data)
        print(df.to_string(index=False))
        print("="*120)

    def generate_charts(self):
        """Generate performance charts"""
        if not self.results:
            return

        # Extract data for plotting
        rates = [r.files_per_second for r in self.results]
        throughput = [r.throughput_rps for r in self.results]
        avg_latency = [r.avg_latency_ms for r in self.results]
        p95_latency = [r.p95_latency_ms for r in self.results]
        error_rates = [r.error_rate for r in self.results]

        # Create subplots
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
        fig.suptitle('Load Test Suite Results', fontsize=16, fontweight='bold')

        # Throughput chart
        ax1.plot(rates, throughput, 'b-o', linewidth=2, markersize=6)
        ax1.set_xlabel('Target Rate (files/second)')
        ax1.set_ylabel('Actual Throughput (requests/second)')
        ax1.set_title('Throughput vs Target Rate')
        ax1.grid(True, alpha=0.3)

        # Latency chart
        ax2.plot(rates, avg_latency, 'g-o', label='Average', linewidth=2, markersize=6)
        ax2.plot(rates, p95_latency, 'r-o', label='95th Percentile', linewidth=2, markersize=6)
        ax2.set_xlabel('Target Rate (files/second)')
        ax2.set_ylabel('Latency (ms)')
        ax2.set_title('Latency vs Target Rate')
        ax2.legend()
        ax2.grid(True, alpha=0.3)

        # Error rate chart
        ax3.plot(rates, error_rates, 'r-o', linewidth=2, markersize=6)
        ax3.set_xlabel('Target Rate (files/second)')
        ax3.set_ylabel('Error Rate (%)')
        ax3.set_title('Error Rate vs Target Rate')
        ax3.grid(True, alpha=0.3)

        # Efficiency chart (throughput vs target rate)
        efficiency = [t/r*100 for t, r in zip(throughput, rates)]
        ax4.plot(rates, efficiency, 'purple', marker='o', linewidth=2, markersize=6)
        ax4.set_xlabel('Target Rate (files/second)')
        ax4.set_ylabel('Efficiency (%)')
        ax4.set_title('System Efficiency')
        ax4.grid(True, alpha=0.3)

        plt.tight_layout()

        # Save chart
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"load_test_results_{timestamp}.png"
        plt.savefig(filename, dpi=300, bbox_inches='tight')
        print(f"\nChart saved as: {filename}")

        # Show chart
        plt.show()

    def save_results_csv(self):
        """Save results to CSV file"""
        if not self.results:
            return

        data = []
        for result in self.results:
            data.append({
                'target_rate_rps': result.files_per_second,
                'actual_throughput_rps': result.throughput_rps,
                'total_requests': result.total_requests,
                'successful_requests': result.successful_requests,
                'failed_requests': result.failed_requests,
                'avg_latency_ms': result.avg_latency_ms,
                'min_latency_ms': result.min_latency_ms,
                'max_latency_ms': result.max_latency_ms,
                'p95_latency_ms': result.p95_latency_ms,
                'p99_latency_ms': result.p99_latency_ms,
                'error_rate_percent': result.error_rate
            })

        df = pd.DataFrame(data)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"load_test_results_{timestamp}.csv"
        df.to_csv(filename, index=False)
        print(f"Results saved to: {filename}")

async def main():
    parser = argparse.ArgumentParser(description='Performance Load Test Suite for ODC Sync Wrapper API')
    parser.add_argument('--server-url', type=str, default='http://localhost:8844',
                        help='Server URL (default: http://localhost:8844)')
    parser.add_argument('--samples-dir', type=str, default='samples',
                        help='Directory containing sample files (default: samples)')
    parser.add_argument('--duration', type=int, default=180,
                        help='Duration per test in seconds (default: 180)')
    parser.add_argument('--api-key', type=str, default=None,
                        help='API key for authentication (Bearer token)')

    args = parser.parse_args()

    # Check if samples directory exists
    samples_path = Path(args.samples_dir)
    if not samples_path.exists():
        print(f"Error: Samples directory '{args.samples_dir}' does not exist")
        sys.exit(1)

    # Create and run load test suite
    suite = LoadTestSuite(args.server_url, args.samples_dir, args.duration, args.api_key)

    try:
        print("Starting Load Test Suite...")
        print(f"Server URL: {args.server_url}")
        print(f"Samples Directory: {args.samples_dir}")
        print(f"Duration per test: {args.duration} seconds")
        print(f"API Key: {'***provided***' if args.api_key else 'None'}")
        print(f"Test rates: {', '.join(map(str, [1, 2, 4, 8, 16, 32]))} files/second")

        results = await suite.run_test_suite()

        # Print summary and generate charts
        suite.print_summary_table()
        suite.save_results_csv()
        suite.generate_charts()

    except KeyboardInterrupt:
        print("\nTest suite interrupted by user")
        if suite.results:
            suite.print_summary_table()
            suite.save_results_csv()
            suite.generate_charts()
    except Exception as e:
        print(f"Error running test suite: {e}")
        sys.exit(1)

if __name__ == '__main__':
    asyncio.run(main())