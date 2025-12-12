"""Main entry point for the benchmarking package.

Usage:
    python -m benchmarking load-test --sequential-mode 10
    python -m benchmarking load-test --rate-mode --files-per-second 20 --duration 60
    python -m benchmarking test-suite --duration 180
    python -m benchmarking sweep sequential --docker-image ghcr.io/ohalo-ltd/odc-sync-wrapper:1.3.0
    python -m benchmarking sweep rate-limited --docker-image ghcr.io/ohalo-ltd/odc-sync-wrapper:1.3.0
"""

import sys


def main():
    """Main entry point that dispatches to subcommands."""
    if len(sys.argv) < 2:
        print_help()
        sys.exit(1)

    command = sys.argv[1]

    if command in ["-h", "--help", "help"]:
        print_help()
        sys.exit(0)

    # Remove the command from argv so subcommand parsers see correct args
    sys.argv = [sys.argv[0]] + sys.argv[2:]

    if command == "load-test":
        from .cli.load_test import main as load_test_main

        load_test_main()
    elif command == "test-suite":
        from .cli.test_suite import main as test_suite_main

        test_suite_main()
    elif command == "sweep":
        from .cli.sweep import main as sweep_main

        sweep_main()
    else:
        print(f"Unknown command: {command}")
        print_help()
        sys.exit(1)


def print_help():
    """Print help message."""
    print(
        """ODC Sync Wrapper Benchmarking Framework

Usage: python -m benchmarking <command> [options]

Commands:
    load-test     Run a single load test (sequential or rate-limited mode)
    test-suite    Run a multi-rate test suite with charts
    sweep         Run datasource count sweep tests (replaces shell scripts)

Examples:
    # Run sequential load test with 10 files
    python -m benchmarking load-test --sequential-mode 10

    # Run rate-limited load test
    python -m benchmarking load-test --rate-mode --files-per-second 20 --duration 60

    # Run test suite with multiple rates
    python -m benchmarking test-suite --duration 180

    # Run sequential datasource sweep (like script_helper.sh)
    python -m benchmarking sweep sequential --docker-image ghcr.io/ohalo-ltd/odc-sync-wrapper:1.3.0

    # Run rate-limited datasource sweep (like script_helper_load.sh)
    python -m benchmarking sweep rate-limited --docker-image ghcr.io/ohalo-ltd/odc-sync-wrapper:1.3.0

For command-specific help:
    python -m benchmarking <command> --help
"""
    )


if __name__ == "__main__":
    main()
