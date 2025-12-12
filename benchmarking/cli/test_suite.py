"""CLI for multi-rate test suite."""

import argparse
import asyncio
import sys
from pathlib import Path
from typing import List

from ..core.models import TestConfig, TestResult
from ..core.load_tester import LoadTester
from ..results.aggregator import ResultAggregator
from ..results.charts import generate_charts


async def run_test_suite(
    server_url: str,
    samples_dir: str,
    duration: int,
    api_key: str = None,
    test_rates: List[int] = None,
) -> List[TestResult]:
    """Run a series of load tests with incrementing rates."""
    if test_rates is None:
        test_rates = [1, 2, 4, 8, 16, 32]

    aggregator = ResultAggregator()

    print(f"\nStarting Load Test Suite with {len(test_rates)} test configurations")
    print(f"Test rates: {test_rates}")
    print(f"Duration per test: {duration} seconds")

    for i, rate in enumerate(test_rates):
        print(f"\n{'='*60}")
        print(f"Running test {i+1}/{len(test_rates)}: {rate} files/second")
        print(f"{'='*60}")

        config = TestConfig(
            server_url=server_url,
            samples_dir=samples_dir,
            files_per_second=rate,
            duration=duration,
        )

        tester = LoadTester(config, api_key=api_key)

        try:
            result = await tester.run()
            aggregator.add_result(result)

            # Print immediate results
            print(f"\nTest Results for {rate} files/second:")
            print(f"  Throughput: {result.throughput_rps:.2f} req/s")
            print(f"  Avg Latency: {result.avg_latency_ms:.2f}ms")
            print(f"  P95 Latency: {result.p95_latency_ms:.2f}ms")
            print(f"  Error Rate: {result.error_rate:.2f}%")

        except Exception as e:
            print(f"Test failed for rate {rate}: {e}")
            # Create a failed result
            failed_result = TestResult(
                datasource_count=None,
                test_mode="rate_limited",
                total_requests=0,
                successful_requests=0,
                failed_requests=0,
                avg_latency_ms=0,
                min_latency_ms=0,
                max_latency_ms=0,
                p95_latency_ms=0,
                p99_latency_ms=0,
                throughput_rps=0,
                error_rate=100.0,
                target_files_per_second=rate,
            )
            aggregator.add_result(failed_result)

    return aggregator.results


def main():
    """Main entry point for test-suite CLI."""
    parser = argparse.ArgumentParser(
        description="Performance Load Test Suite for ODC Sync Wrapper API"
    )
    parser.add_argument(
        "--server-url",
        type=str,
        default="http://localhost:8844",
        help="Server URL (default: http://localhost:8844)",
    )
    parser.add_argument(
        "--samples-dir",
        type=str,
        default="samples",
        help="Directory containing sample files (default: samples)",
    )
    parser.add_argument(
        "--duration",
        type=int,
        default=180,
        help="Duration per test in seconds (default: 180)",
    )
    parser.add_argument(
        "--api-key",
        type=str,
        default=None,
        help="API key for authentication (Bearer token)",
    )
    parser.add_argument(
        "--rates",
        type=str,
        default="1,2,4,8,16,32",
        help="Comma-separated list of rates to test (default: 1,2,4,8,16,32)",
    )
    parser.add_argument(
        "--no-charts",
        action="store_true",
        help="Skip chart generation",
    )

    args = parser.parse_args()

    # Check if samples directory exists
    samples_path = Path(args.samples_dir)
    if not samples_path.exists():
        print(f"Error: Samples directory '{args.samples_dir}' does not exist")
        sys.exit(1)

    # Parse rates
    test_rates = [int(r.strip()) for r in args.rates.split(",")]

    # Print configuration
    print("Starting Load Test Suite...")
    print(f"Server URL: {args.server_url}")
    print(f"Samples Directory: {args.samples_dir}")
    print(f"Duration per test: {args.duration} seconds")
    print(f"API Key: {'***provided***' if args.api_key else 'None'}")
    print(f"Test rates: {', '.join(map(str, test_rates))} files/second")

    aggregator = ResultAggregator()

    try:
        results = asyncio.run(
            run_test_suite(
                server_url=args.server_url,
                samples_dir=args.samples_dir,
                duration=args.duration,
                api_key=args.api_key,
                test_rates=test_rates,
            )
        )

        aggregator.add_results(results)

        # Print summary and generate charts
        aggregator.print_summary_table(
            title="LOAD TEST SUITE RESULTS",
            description=f"Duration per test: {args.duration}s | Rates: {args.rates}",
        )

        if not args.no_charts and results:
            generate_charts(results, x_label="Target Rate (files/second)")

    except KeyboardInterrupt:
        print("\nTest suite interrupted by user")
        if aggregator.results:
            aggregator.print_summary_table(
                title="PARTIAL TEST SUITE RESULTS (interrupted)"
            )
    except Exception as e:
        print(f"Error running test suite: {e}")
        import traceback

        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
