"""Sample file loading utilities."""

import aiofiles
import logging
from pathlib import Path
from typing import List, Tuple


class SampleLoader:
    """Loads sample files for load testing."""

    def __init__(self, samples_dir: str):
        self.samples_dir = Path(samples_dir)
        self.sample_files: List[Tuple[str, bytes]] = []
        self.logger = logging.getLogger(__name__)

    async def load(self) -> List[Tuple[str, bytes]]:
        """
        Load all sample files into memory.

        Returns:
            List of tuples (filename, content_bytes)

        Raises:
            FileNotFoundError: If no sample files are found
        """
        # Look for various sample file patterns
        txt_files = list(self.samples_dir.glob("sample*.txt"))
        pdf_files = list(self.samples_dir.glob("sample*.pdf"))
        test_files = list(self.samples_dir.glob("testfile*"))

        sample_files = txt_files + pdf_files + test_files

        if not sample_files:
            raise FileNotFoundError(f"No sample files found in {self.samples_dir}")

        self.logger.info(
            f"Loading {len(sample_files)} sample files "
            f"({len(txt_files)} .txt, {len(pdf_files)} .pdf, {len(test_files)} testfile*)"
        )

        self.sample_files = []
        for file_path in sample_files:
            async with aiofiles.open(file_path, "rb") as f:
                content = await f.read()
                self.sample_files.append((file_path.name, content))

        self.logger.info(f"Loaded {len(self.sample_files)} sample files")
        return self.sample_files

    def get_file(self, index: int) -> Tuple[str, bytes]:
        """
        Get a sample file by index, cycling if necessary.

        Args:
            index: The index to get (will wrap around if larger than file count)

        Returns:
            Tuple of (filename, content_bytes)
        """
        if not self.sample_files:
            raise RuntimeError("Sample files not loaded. Call load() first.")
        return self.sample_files[index % len(self.sample_files)]

    def __len__(self) -> int:
        return len(self.sample_files)

    def get_content_type(self, filename: str) -> str:
        """Determine content type based on file extension."""
        if filename.lower().endswith(".pdf"):
            return "application/pdf"
        return "text/plain"
