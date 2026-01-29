"""Single directory load test CLI command."""

import argparse
import asyncio
import sys

# Support both relative and absolute imports
try:
    from ..core.models import AsyncTestConfig
    from ..core.async_tester import AsyncTester
    from ..results.aggregator import AsyncResultAggregator
    from ..results.charts import generate_single_test_chart
except (ImportError, ValueError):
    from core.models import AsyncTestConfig
    from core.async_tester import AsyncTester
    from results.aggregator import AsyncResultAggregator
    from results.charts import generate_single_test_chart


def main():
    """Main entry point for single directory test."""
    parser = argparse.ArgumentParser(
        description="Run a single async load test against one directory",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Basic test with default settings
  python -m benchmarking_async single \\
      --server-url https://dev.dataxray.io \\
      --datasource-id 100 \\
      --token $DXR_API_KEY \\
      --samples-dir samples/100K

  # Test with batching (5 files per request)
  python -m benchmarking_async single \\
      --server-url https://dev.dataxray.io \\
      --datasource-id 100 \\
      --token $DXR_API_KEY \\
      --samples-dir samples/1GB \\
      --files-per-request 5

  # Parallel test with 3 concurrent requests
  python -m benchmarking_async single \\
      --server-url https://dev.dataxray.io \\
      --datasource-id 100 \\
      --token $DXR_API_KEY \\
      --samples-dir samples/100K \\
      --concurrency 3 \\
      --repeat 10
        """,
    )

    # Required connection arguments
    parser.add_argument(
        "--server-url",
        required=True,
        help="DXR server URL (e.g., https://dev.dataxray.io)"
    )
    parser.add_argument(
        "--datasource-id",
        type=int,
        required=True,
        help="On-demand classifier datasource ID"
    )
    parser.add_argument(
        "--token",
        required=True,
        help="API bearer token"
    )
    parser.add_argument(
        "--samples-dir",
        required=True,
        help="Directory containing sample files to test"
    )

    # Test parameters
    parser.add_argument(
        "--files-per-request",
        type=int,
        default=1,
        help="Number of files to bundle in each API request (default: 1)"
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=1,
        help="Number of requests to run in parallel (default: 1 = sequential)"
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=1,
        help="Repeat sample files N times to increase load (default: 1)"
    )

    # Timeout settings
    parser.add_argument(
        "--timeout",
        type=int,
        default=1200,
        help="Job timeout in seconds (default: 1200)"
    )
    parser.add_argument(
        "--poll-interval",
        type=int,
        default=5,
        help="Job polling interval in seconds (default: 5)"
    )

    # SSL
    parser.add_argument(
        "--insecure-ssl",
        action="store_true",
        help="Disable TLS certificate verification (for self-signed certs)"
    )

    # Output options
    parser.add_argument(
        "--output",
        type=str,
        help="Output TSV file path for results"
    )
    parser.add_argument(
        "--chart",
        type=str,
        help="Output chart PNG path"
    )
    parser.add_argument(
        "--no-chart",
        action="store_true",
        help="Skip chart generation"
    )
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="Suppress progress output (only show final results)"
    )

    args = parser.parse_args()

    # Validate samples directory
    import os
    if not os.path.isdir(args.samples_dir):
        print(f"Error: Samples directory not found: {args.samples_dir}")
        sys.exit(1)

    # Create config
    config = AsyncTestConfig(
        server_url=args.server_url,
        datasource_id=args.datasource_id,
        api_token=args.token,
        samples_dir=args.samples_dir,
        files_per_request=args.files_per_request,
        concurrency=args.concurrency,
        repeat=args.repeat,
        job_timeout_seconds=args.timeout,
        poll_interval_seconds=args.poll_interval,
        insecure_ssl=args.insecure_ssl,
    )

    # Run test
    print(f"\nAsync Load Test: {args.samples_dir}")
    print("=" * 60)

    tester = AsyncTester(config, verbose=not args.quiet)

    try:
        result = asyncio.run(tester.run())

        # Print results
        aggregator = AsyncResultAggregator()
        aggregator.print_detailed_result(result)

        # Export TSV if requested
        if args.output:
            aggregator.add_result(result)
            aggregator.to_tsv(args.output)
            print(f"\nResults saved to: {args.output}")

        # Generate chart if not disabled
        if not args.no_chart and result.raw_job_results:
            chart_path = args.chart
            generate_single_test_chart(result, output_path=chart_path, show=False)

        # Exit with error code if there were failures
        if result.error_rate > 0:
            sys.exit(1)

    except KeyboardInterrupt:
        print("\nTest interrupted by user")
        sys.exit(130)
    except Exception as e:
        print(f"\nError: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
