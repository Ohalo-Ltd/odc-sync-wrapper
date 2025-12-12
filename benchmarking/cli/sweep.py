"""CLI for datasource sweep tests."""

import argparse
import asyncio
import sys
from typing import List

from ..sweeps.datasource_sweep import DatasourceSweep
from ..sweeps.presets import (
    SEQUENTIAL_SWEEP_DEFAULTS,
    RATE_LIMITED_SWEEP_DEFAULTS,
)


def parse_counts(counts_str: str) -> List[int]:
    """Parse comma-separated counts string into list of integers."""
    return [int(c.strip()) for c in counts_str.split(",")]


def main():
    """Main entry point for datasource sweep CLI."""
    parser = argparse.ArgumentParser(
        description="Datasource Count Sweep Tests for ODC Sync Wrapper"
    )

    subparsers = parser.add_subparsers(dest="mode", required=True)

    # Sequential mode subcommand
    seq_parser = subparsers.add_parser(
        "sequential",
        help="Run sequential sweep (like script_helper.sh)",
    )
    default_seq_counts = ",".join(
        str(c) for c in SEQUENTIAL_SWEEP_DEFAULTS["datasource_counts"]
    )
    seq_parser.add_argument(
        "--counts",
        type=str,
        default=default_seq_counts,
        help=f"Comma-separated datasource counts to test (default: {default_seq_counts})",
    )
    seq_parser.add_argument(
        "--files",
        type=int,
        default=SEQUENTIAL_SWEEP_DEFAULTS["files_per_test"],
        help=f"Number of files per test (default: {SEQUENTIAL_SWEEP_DEFAULTS['files_per_test']})",
    )
    seq_parser.add_argument(
        "--samples-dir",
        type=str,
        default=SEQUENTIAL_SWEEP_DEFAULTS["samples_dir"],
        help=f"Sample files directory (default: {SEQUENTIAL_SWEEP_DEFAULTS['samples_dir']})",
    )

    # Rate-limited mode subcommand
    rate_parser = subparsers.add_parser(
        "rate-limited",
        help="Run rate-limited sweep (like script_helper_load.sh)",
    )
    default_rate_counts = ",".join(
        str(c) for c in RATE_LIMITED_SWEEP_DEFAULTS["datasource_counts"]
    )
    rate_parser.add_argument(
        "--counts",
        type=str,
        default=default_rate_counts,
        help=f"Comma-separated datasource counts to test (default: {default_rate_counts})",
    )
    rate_parser.add_argument(
        "--files-per-second",
        type=int,
        default=RATE_LIMITED_SWEEP_DEFAULTS["files_per_second"],
        help=f"Target files per second (default: {RATE_LIMITED_SWEEP_DEFAULTS['files_per_second']})",
    )
    rate_parser.add_argument(
        "--duration",
        type=int,
        default=RATE_LIMITED_SWEEP_DEFAULTS["duration_seconds"],
        help=f"Test duration in seconds (default: {RATE_LIMITED_SWEEP_DEFAULTS['duration_seconds']})",
    )
    rate_parser.add_argument(
        "--samples-dir",
        type=str,
        default=RATE_LIMITED_SWEEP_DEFAULTS["samples_dir"],
        help=f"Sample files directory (default: {RATE_LIMITED_SWEEP_DEFAULTS['samples_dir']})",
    )

    # Common arguments for both modes
    for subparser in [seq_parser, rate_parser]:
        subparser.add_argument(
            "--docker-image",
            type=str,
            required=True,
            help="Docker image to use (required)",
        )
        subparser.add_argument(
            "--startup-wait",
            type=int,
            default=20,
            help="Seconds to wait for container startup (default: 20)",
        )

    args = parser.parse_args()

    # Create sweep orchestrator
    sweep = DatasourceSweep(docker_image=args.docker_image)

    try:
        if args.mode == "sequential":
            counts = parse_counts(args.counts)
            asyncio.run(
                sweep.run_sequential_sweep(
                    datasource_counts=counts,
                    files_per_test=args.files,
                    samples_dir=args.samples_dir,
                    startup_wait_seconds=args.startup_wait,
                )
            )
        elif args.mode == "rate-limited":
            counts = parse_counts(args.counts)
            asyncio.run(
                sweep.run_rate_limited_sweep(
                    datasource_counts=counts,
                    files_per_second=args.files_per_second,
                    duration_seconds=args.duration,
                    samples_dir=args.samples_dir,
                    startup_wait_seconds=args.startup_wait,
                )
            )

    except KeyboardInterrupt:
        print("\nSweep interrupted by user")
        # Still print results collected so far
        if sweep.aggregator.results:
            sweep.aggregator.print_summary_table(
                title="PARTIAL SWEEP RESULTS (interrupted)"
            )
    except Exception as e:
        print(f"Error running sweep: {e}")
        import traceback

        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
