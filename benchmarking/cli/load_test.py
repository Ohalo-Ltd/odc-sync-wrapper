"""CLI for single load tests."""

import argparse
import asyncio
import sys
from pathlib import Path

from ..core.models import TestConfig
from ..core.load_tester import LoadTester


def main():
    """Main entry point for load-test CLI."""
    parser = argparse.ArgumentParser(
        description="Performance Load Test for ODC Sync Wrapper API"
    )

    # Create mutually exclusive group for test modes
    mode_group = parser.add_mutually_exclusive_group(required=True)
    mode_group.add_argument(
        "--rate-mode",
        action="store_true",
        help="Run in rate-limited mode (requires --files-per-second and --duration)",
    )
    mode_group.add_argument(
        "--sequential-mode",
        type=int,
        metavar="COUNT",
        help="Run in sequential mode, sending COUNT files one after another",
    )

    # Rate-limited mode arguments
    parser.add_argument(
        "--files-per-second",
        type=int,
        help="Number of files to send per second (required for --rate-mode)",
    )
    parser.add_argument(
        "--duration",
        type=int,
        help="Test duration in seconds (required for --rate-mode)",
    )

    # Common arguments
    parser.add_argument(
        "--server-url",
        type=str,
        default="http://localhost:8844",
        help="Server URL (default: http://localhost:8844)",
    )
    parser.add_argument(
        "--samples-dir",
        type=str,
        default="samples/plain_txt",
        help="Directory containing sample files (default: samples/plain_txt)",
    )
    parser.add_argument(
        "--api-key",
        type=str,
        default=None,
        help="API key for authentication (Bearer token)",
    )

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

    # Create config
    if args.rate_mode:
        config = TestConfig(
            server_url=args.server_url,
            samples_dir=args.samples_dir,
            files_per_second=args.files_per_second,
            duration=args.duration,
        )
    else:
        config = TestConfig(
            server_url=args.server_url,
            samples_dir=args.samples_dir,
            sequential_count=args.sequential_mode,
        )

    # Create and run load tester
    tester = LoadTester(config, api_key=args.api_key)

    try:
        result = asyncio.run(tester.run())
        tester.print_results(result)
    except KeyboardInterrupt:
        print("\nTest interrupted by user")
        if tester.results:
            result = tester._calculate_results(
                test_mode="interrupted",
                start_datetime=tester.start_time,
                end_datetime=None,
            )
            tester.print_results(result)
    except Exception as e:
        print(f"Error running load test: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
