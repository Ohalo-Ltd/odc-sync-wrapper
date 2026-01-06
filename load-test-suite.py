#!/usr/bin/env python3
"""
Performance Load Test Suite for ODC Sync Wrapper API

This is a backwards-compatibility wrapper. The implementation has moved to:
    python -m benchmarking test-suite

Usage: python load-test-suite.py [--server-url URL] [--samples-dir DIR] [--duration SECONDS]
"""

from benchmarking.cli.test_suite import main

if __name__ == "__main__":
    main()
