"""File size sweep orchestrator for async benchmarking."""

import asyncio
import logging
from datetime import datetime
from typing import List, Optional

# Support both relative and absolute imports
try:
    from ..core.models import AsyncTestConfig, AsyncTestResult, SweepResult
    from ..core.async_tester import AsyncTester
    from ..results.aggregator import AsyncResultAggregator
except (ImportError, ValueError):
    from core.models import AsyncTestConfig, AsyncTestResult, SweepResult
    from core.async_tester import AsyncTester
    from results.aggregator import AsyncResultAggregator


class FileSizeSweep:
    """Orchestrates tests across multiple file size directories."""

    def __init__(
        self,
        base_config: AsyncTestConfig,
        verbose: bool = True,
    ):
        """
        Initialize the sweep orchestrator.

        Args:
            base_config: Base configuration (server_url, token, etc.)
                        samples_dir will be overridden per stage
            verbose: Whether to print progress messages
        """
        self.base_config = base_config
        self.verbose = verbose
        self.aggregator = AsyncResultAggregator()
        self.logger = logging.getLogger(__name__)

    def _log(self, message: str) -> None:
        """Print message if verbose mode is enabled."""
        if self.verbose:
            print(message)

    async def run(
        self,
        directories: List[str],
        files_per_request: int = 1,
        concurrency: int = 1,
        repeat: int = 1,
    ) -> SweepResult:
        """
        Run sweep through all directories sequentially.

        Args:
            directories: List of directories to test (in order)
            files_per_request: Files per API request batch
            concurrency: Parallel requests (1 = sequential)
            repeat: Repeat sample files N times

        Returns:
            SweepResult with all stage results
        """
        self.aggregator.clear()
        stage_results: List[AsyncTestResult] = []
        start_time = datetime.now()

        self._log(f"\n{'=' * 80}")
        self._log("FILE SIZE SWEEP - ASYNC API BENCHMARK".center(80))
        self._log(f"{'=' * 80}")
        self._log(f"\nDirectories to test: {len(directories)}")
        for i, d in enumerate(directories, 1):
            self._log(f"  {i}. {d}")
        self._log(f"\nFiles per request: {files_per_request}")
        self._log(f"Concurrency: {concurrency}")
        self._log(f"Repeat: {repeat}x")
        self._log(f"{'=' * 80}\n")

        for i, directory in enumerate(directories, 1):
            self._log(f"\n{'=' * 60}")
            self._log(f"Stage {i}/{len(directories)}: {directory}")
            self._log(f"{'=' * 60}")

            # Create stage-specific config
            stage_config = AsyncTestConfig(
                server_url=self.base_config.server_url,
                datasource_id=self.base_config.datasource_id,
                api_token=self.base_config.api_token,
                samples_dir=directory,
                files_per_request=files_per_request,
                concurrency=concurrency,
                repeat=repeat,
                job_timeout_seconds=self.base_config.job_timeout_seconds,
                poll_interval_seconds=self.base_config.poll_interval_seconds,
                insecure_ssl=self.base_config.insecure_ssl,
            )

            try:
                tester = AsyncTester(stage_config, verbose=self.verbose)
                result = await tester.run()
                stage_results.append(result)
                self.aggregator.add_result(result)

                # Print intermediate result
                self.aggregator.print_single_result(result)

            except Exception as e:
                self._log(f"\nError in stage {i}: {e}")
                self.logger.exception(f"Error processing directory {directory}")
                # Continue with next directory

        end_time = datetime.now()
        total_duration = (end_time - start_time).total_seconds()

        # Print final summary
        self._log(f"\n{'=' * 80}")
        self.aggregator.print_summary_table(title="FILE SIZE SWEEP RESULTS")

        # Calculate aggregates
        total_files = sum(r.total_files for r in stage_results)
        total_bytes = sum(r.total_files * r.file_size_bytes for r in stage_results)
        total_jobs = sum(r.total_jobs for r in stage_results)
        failed_jobs = sum(r.failed_jobs + r.timed_out_jobs for r in stage_results)
        overall_error_rate = (failed_jobs / total_jobs * 100) if total_jobs > 0 else 0.0

        return SweepResult(
            directories=directories,
            files_per_request=files_per_request,
            concurrency=concurrency,
            stage_results=stage_results,
            total_files_processed=total_files,
            total_bytes_processed=total_bytes,
            total_duration_seconds=total_duration,
            overall_error_rate=overall_error_rate,
        )

    def get_aggregator(self) -> AsyncResultAggregator:
        """Get the result aggregator for additional operations."""
        return self.aggregator
