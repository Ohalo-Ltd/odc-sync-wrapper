"""File size sweep CLI command."""

import argparse
import asyncio
import os
import sys
from datetime import datetime

# Support both relative and absolute imports
try:
    from ..core.models import AsyncTestConfig
    from ..sweeps.file_size_sweep import FileSizeSweep
    from ..results.charts import generate_sweep_charts
except ImportError:
    from core.models import AsyncTestConfig
    from sweeps.file_size_sweep import FileSizeSweep
    from results.charts import generate_sweep_charts


def main():
    """Main entry point for file size sweep."""
    parser = argparse.ArgumentParser(
        description="Run file size sweep benchmark across multiple directories",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Basic sweep with comma-separated directories
  python -m benchmarking_async sweep \\
      --server-url https://dev.dataxray.io \\
      --datasource-id 100 \\
      --token $DXR_API_KEY \\
      --directories samples/100K,samples/1GB,samples/2GB

  # Sweep with batching and output files
  python -m benchmarking_async sweep \\
      --server-url https://dev.dataxray.io \\
      --datasource-id 100 \\
      --token $DXR_API_KEY \\
      --directories samples/100K,samples/500MB,samples/1GB \\
      --files-per-request 5 \\
      --repeat 3 \\
      --output results.tsv \\
      --chart results.png

  # Using a directories file
  python -m benchmarking_async sweep \\
      --server-url https://dev.dataxray.io \\
      --datasource-id 100 \\
      --token $DXR_API_KEY \\
      --directories-file sweep_dirs.txt
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

    # Directory specification (mutually exclusive group)
    dir_group = parser.add_mutually_exclusive_group(required=True)
    dir_group.add_argument(
        "--directories",
        type=str,
        help="Comma-separated list of directories to sweep (in order)"
    )
    dir_group.add_argument(
        "--directories-file",
        type=str,
        help="File containing directories to sweep (one per line)"
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

    # Parse directories
    if args.directories:
        directories = [d.strip() for d in args.directories.split(",") if d.strip()]
    else:
        try:
            with open(args.directories_file, "r") as f:
                directories = [line.strip() for line in f if line.strip() and not line.startswith("#")]
        except FileNotFoundError:
            print(f"Error: Directories file not found: {args.directories_file}")
            sys.exit(1)

    if not directories:
        print("Error: No directories specified")
        sys.exit(1)

    # Validate directories exist
    for d in directories:
        if not os.path.isdir(d):
            print(f"Error: Directory not found: {d}")
            sys.exit(1)

    # Create base config
    config = AsyncTestConfig(
        server_url=args.server_url,
        datasource_id=args.datasource_id,
        api_token=args.token,
        samples_dir="",  # Will be overridden per stage
        job_timeout_seconds=args.timeout,
        poll_interval_seconds=args.poll_interval,
        insecure_ssl=args.insecure_ssl,
    )

    # Create and run sweep
    sweep = FileSizeSweep(config, verbose=not args.quiet)

    try:
        result = asyncio.run(sweep.run(
            directories=directories,
            files_per_request=args.files_per_request,
            concurrency=args.concurrency,
            repeat=args.repeat,
        ))

        # Export TSV if requested
        if args.output:
            sweep.get_aggregator().to_tsv(args.output)
            print(f"\nResults saved to: {args.output}")

        # Generate chart if not disabled
        if not args.no_chart and result.stage_results:
            chart_path = args.chart
            if not chart_path:
                timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
                chart_path = f"sweep_results_{timestamp}.png"
            generate_sweep_charts(result.stage_results, output_path=chart_path, show=False)

        # Print summary
        print(f"\nSweep completed in {result.total_duration_seconds:.1f}s")
        print(f"Total files processed: {result.total_files_processed}")
        print(f"Total data processed: {result.total_bytes_processed / (1024*1024*1024):.2f} GB")
        print(f"Overall error rate: {result.overall_error_rate:.2f}%")

        # Exit with error code if there were failures
        if result.overall_error_rate > 0:
            sys.exit(1)

    except KeyboardInterrupt:
        print("\n\nSweep interrupted by user")
        # Print partial results if available
        sweep.get_aggregator().print_summary_table(title="PARTIAL RESULTS (interrupted)")
        sys.exit(130)
    except Exception as e:
        print(f"\nError: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
