#!/usr/bin/env python3
"""
Initialize sample data files by repeating content from a source file
until the desired file size is reached.

Usage:
    python initialize_sample_data.py <file_size> <source_file>

Examples:
    python initialize_sample_data.py 1K examples_dir/plain/sample.txt
    python initialize_sample_data.py 10M examples_dir/plain/sample.txt
    python initialize_sample_data.py 1G examples_dir/plain/sample.txt
"""

import argparse
import os
import sys
from pathlib import Path


def parse_size(size_str: str) -> int:
    """Parse a size string like '1K', '10M', '1G' into bytes."""
    size_str = size_str.strip().upper()

    multipliers = {
        'K': 1024,
        'KB': 1024,
        'M': 1024 ** 2,
        'MB': 1024 ** 2,
        'G': 1024 ** 3,
        'GB': 1024 ** 3,
    }

    for suffix, multiplier in multipliers.items():
        if size_str.endswith(suffix):
            number_part = size_str[:-len(suffix)]
            try:
                return int(float(number_part) * multiplier)
            except ValueError:
                raise ValueError(f"Invalid size format: {size_str}")

    # No suffix, assume bytes
    try:
        return int(size_str)
    except ValueError:
        raise ValueError(f"Invalid size format: {size_str}")


def generate_file(source_path: Path, target_path: Path, target_size: int) -> int:
    """
    Generate a file of the target size by repeating lines from the source file.

    Returns the actual size of the generated file.
    """
    # Read source file lines
    with open(source_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    if not lines:
        raise ValueError(f"Source file is empty: {source_path}")

    # Ensure lines have newline endings for consistent output
    lines = [line if line.endswith('\n') else line + '\n' for line in lines]

    # Create target directory if it doesn't exist
    target_path.parent.mkdir(parents=True, exist_ok=True)

    # Write repeated content until we reach target size
    bytes_written = 0
    line_index = 0

    with open(target_path, 'w', encoding='utf-8') as f:
        while bytes_written < target_size:
            line = lines[line_index % len(lines)]
            line_bytes = line.encode('utf-8')
            line_size = len(line_bytes)

            remaining = target_size - bytes_written

            if line_size <= remaining:
                # Write the full line
                f.write(line)
                bytes_written += line_size
            else:
                # Truncate the line to fit remaining space
                # Decode partial bytes carefully to avoid breaking multi-byte characters
                truncated = truncate_to_bytes(line, remaining)
                if truncated:
                    f.write(truncated)
                    bytes_written += len(truncated.encode('utf-8'))
                break

            line_index += 1

    return bytes_written


def truncate_to_bytes(text: str, max_bytes: int) -> str:
    """
    Truncate a string to fit within max_bytes when encoded as UTF-8.
    Ensures we don't break multi-byte characters.
    """
    encoded = text.encode('utf-8')
    if len(encoded) <= max_bytes:
        return text

    # Binary search for the right character count
    low, high = 0, len(text)
    while low < high:
        mid = (low + high + 1) // 2
        if len(text[:mid].encode('utf-8')) <= max_bytes:
            low = mid
        else:
            high = mid - 1

    return text[:low]


def format_size(size_bytes: int) -> str:
    """Format a byte count as a human-readable string."""
    for unit in ['B', 'KB', 'MB', 'GB']:
        if size_bytes < 1024:
            return f"{size_bytes:.2f} {unit}"
        size_bytes /= 1024
    return f"{size_bytes:.2f} TB"


def main():
    parser = argparse.ArgumentParser(
        description='Generate sample data files of specified sizes by repeating source content.',
        epilog='Example: python initialize_sample_data.py 10M samples_dir/plain/sample.txt'
    )
    parser.add_argument(
        'file_size',
        help='Target file size (e.g., 1K, 10M, 1G, 2G)'
    )
    parser.add_argument(
        'source_file',
        help='Path to the source file to repeat'
    )
    parser.add_argument(
        '-o', '--output-dir',
        default='samples_dir',
        help='Base output directory (default: samples_dir)'
    )

    args = parser.parse_args()

    # Parse target size
    try:
        target_size = parse_size(args.file_size)
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

    # Validate source file
    source_path = Path(args.source_file)
    if not source_path.exists():
        print(f"Error: Source file not found: {source_path}", file=sys.stderr)
        sys.exit(1)

    # Construct output path: samples_dir/{file_size}/{filename}
    size_label = args.file_size.upper()
    output_path = Path(args.output_dir) / size_label / source_path.name

    print(f"Source: {source_path}")
    print(f"Target: {output_path}")
    print(f"Target size: {format_size(target_size)} ({target_size} bytes)")

    # Generate the file
    try:
        actual_size = generate_file(source_path, output_path, target_size)
        print(f"Generated: {output_path}")
        print(f"Actual size: {format_size(actual_size)} ({actual_size} bytes)")
    except Exception as e:
        print(f"Error generating file: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
