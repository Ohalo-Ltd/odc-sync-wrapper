"""Main entry point for the async benchmarking package.

Usage (from within benchmarking-async directory):
    python -m . single --server-url URL --datasource-id ID --token TOKEN --samples-dir DIR
    python -m . sweep --server-url URL --datasource-id ID --token TOKEN --directories DIR1,DIR2

Or run directly:
    python __main__.py single ...
    python __main__.py sweep ...
"""

import sys
import os

# Add parent directory to path for direct execution
if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def print_help():
    """Print usage help."""
    print("""
Async Benchmarking Tool for DXR On-Demand Classifier API

Usage (from benchmarking-async directory):
    python -m . <command> [options]

Or run directly:
    python __main__.py <command> [options]

Commands:
    single    Run a single load test against one directory
    sweep     Run file size sweep across multiple directories

Examples:
    # Single directory test
    python -m . single \\
        --server-url https://dev.dataxray.io \\
        --datasource-id 100 \\
        --token $DXR_API_KEY \\
        --samples-dir samples/100K

    # File size sweep
    python -m . sweep \\
        --server-url https://dev.dataxray.io \\
        --datasource-id 100 \\
        --token $DXR_API_KEY \\
        --directories samples/100K,samples/1GB,samples/2GB

Use '<command> --help' for more information on a specific command.
""")


def main():
    """Main entry point."""
    if len(sys.argv) < 2:
        print_help()
        sys.exit(1)

    command = sys.argv[1]

    if command in ("-h", "--help", "help"):
        print_help()
        sys.exit(0)

    # Remove the command from argv so subcommand parsers work correctly
    sys.argv = [sys.argv[0]] + sys.argv[2:]

    # Try relative imports first (when run as module), fall back to absolute (when run directly)
    try:
        if command == "single":
            from .cli.single import main as single_main
            single_main()
        elif command == "sweep":
            from .cli.sweep import main as sweep_main
            sweep_main()
        else:
            print(f"Unknown command: {command}")
            print_help()
            sys.exit(1)
    except ImportError:
        # Fall back to absolute imports for direct execution
        if command == "single":
            from cli.single import main as single_main
            single_main()
        elif command == "sweep":
            from cli.sweep import main as sweep_main
            sweep_main()
        else:
            print(f"Unknown command: {command}")
            print_help()
            sys.exit(1)


if __name__ == "__main__":
    main()
