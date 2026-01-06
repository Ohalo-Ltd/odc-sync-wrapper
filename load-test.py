#!/usr/bin/env python3
"""
Performance Load Test Script for ODC Sync Wrapper API

This is a backwards-compatibility wrapper. The implementation has moved to:
    python -m benchmarking load-test

Usage: python load-test.py --files-per-second X --duration Y [--server-url URL]
       python load-test.py --sequential-mode COUNT [--server-url URL]
"""

from benchmarking.cli.load_test import main

if __name__ == "__main__":
    main()
