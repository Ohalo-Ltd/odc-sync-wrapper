"""Sample file loading utilities for async benchmarking."""

import os
from pathlib import Path
from typing import List, Tuple


class SampleLoader:
    """Loads sample files from a directory for async testing."""

    def __init__(self, samples_dir: str, repeat: int = 1):
        """
        Initialize the sample loader.

        Args:
            samples_dir: Directory containing sample files
            repeat: Number of times to repeat the sample files
        """
        self.samples_dir = Path(samples_dir)
        self.repeat = max(repeat, 1)
        self.files: List[Tuple[str, str]] = []

    def load(self) -> List[Tuple[str, str]]:
        """
        Load file paths from the samples directory.

        Returns:
            List of (filename, filepath) tuples
        """
        if not self.samples_dir.exists():
            raise FileNotFoundError(f"Samples directory not found: {self.samples_dir}")

        if not self.samples_dir.is_dir():
            raise NotADirectoryError(f"Path is not a directory: {self.samples_dir}")

        files = []
        for f in sorted(self.samples_dir.iterdir()):
            if f.is_file():
                files.append((f.name, str(f)))

        if not files:
            raise ValueError(f"No files found in: {self.samples_dir}")

        # Repeat files if requested
        self.files = files * self.repeat
        return self.files

    def get_file_size(self) -> int:
        """
        Get size of the first file (assumes uniform sizes in directory).

        Returns:
            File size in bytes
        """
        if not self.files:
            self.load()

        if self.files:
            return os.path.getsize(self.files[0][1])
        return 0

    def get_total_size(self) -> int:
        """
        Get total size of all loaded files.

        Returns:
            Total size in bytes
        """
        if not self.files:
            self.load()

        total = 0
        for _, filepath in self.files:
            total += os.path.getsize(filepath)
        return total

    def extract_size_label(self) -> str:
        """
        Extract size label from directory name.

        Examples:
            'samples/100K' -> '100K'
            'samples/1GB' -> '1GB'
            'samples/plain' -> 'plain'

        Returns:
            Size label string
        """
        return self.samples_dir.name

    def __len__(self) -> int:
        """Return number of loaded files."""
        return len(self.files)


def format_bytes(size_bytes: int) -> str:
    """
    Format byte size to human-readable string.

    Args:
        size_bytes: Size in bytes

    Returns:
        Human-readable size string (e.g., "1.5 MB")
    """
    if size_bytes < 1024:
        return f"{size_bytes} B"
    elif size_bytes < 1024 * 1024:
        return f"{size_bytes / 1024:.1f} KB"
    elif size_bytes < 1024 * 1024 * 1024:
        return f"{size_bytes / (1024 * 1024):.1f} MB"
    else:
        return f"{size_bytes / (1024 * 1024 * 1024):.2f} GB"


def parse_size_label(label: str) -> int:
    """
    Parse a size label to bytes.

    Args:
        label: Size label (e.g., "100K", "1GB", "500MB")

    Returns:
        Size in bytes
    """
    label = label.upper().strip()

    # Extract numeric part and unit
    numeric = ""
    unit = ""
    for char in label:
        if char.isdigit() or char == ".":
            numeric += char
        else:
            unit += char

    if not numeric:
        return 0

    value = float(numeric)

    # Convert based on unit
    if unit in ("K", "KB"):
        return int(value * 1024)
    elif unit in ("M", "MB"):
        return int(value * 1024 * 1024)
    elif unit in ("G", "GB"):
        return int(value * 1024 * 1024 * 1024)
    elif unit in ("T", "TB"):
        return int(value * 1024 * 1024 * 1024 * 1024)
    else:
        return int(value)
