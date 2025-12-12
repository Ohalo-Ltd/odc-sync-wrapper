"""Datasource sweep orchestration - replaces shell scripts."""

import logging
from typing import List, Optional

from ..core.models import TestConfig, TestResult
from ..core.load_tester import LoadTester
from ..docker.container_manager import DockerContainerManager
from ..results.aggregator import ResultAggregator
from .presets import (
    DEFAULT_PRELOAD_ANNOTATION_IDS,
    DOCKER_DEFAULTS,
    SEQUENTIAL_SWEEP_DEFAULTS,
    RATE_LIMITED_SWEEP_DEFAULTS,
)


class DatasourceSweep:
    """
    Orchestrates load tests across multiple datasource count configurations.

    This class replaces script_helper.sh and script_helper_load.sh by:
    - Starting Docker containers with different datasource counts
    - Running load tests for each configuration
    - Aggregating results into a single summary table
    """

    def __init__(
        self,
        docker_image: str,
        dxr_base_url: Optional[str] = None,
        dxr_api_key: Optional[str] = None,
    ):
        """
        Initialize the sweep orchestrator.

        Args:
            docker_image: Docker image to use (required, no default)
            dxr_base_url: Base URL for DXR API (defaults to env var)
            dxr_api_key: API key for DXR (defaults to env var)
        """
        self.docker_image = docker_image
        self.dxr_base_url = dxr_base_url
        self.dxr_api_key = dxr_api_key
        self.docker_manager = DockerContainerManager(
            image=docker_image,
            container_name=DOCKER_DEFAULTS["container_name"],
            port=DOCKER_DEFAULTS["port"],
        )
        self.aggregator = ResultAggregator()

        logging.basicConfig(
            level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
        )
        self.logger = logging.getLogger(__name__)

    async def run_sequential_sweep(
        self,
        datasource_counts: Optional[List[int]] = None,
        files_per_test: Optional[int] = None,
        samples_dir: Optional[str] = None,
        startup_wait_seconds: Optional[int] = None,
    ) -> List[TestResult]:
        """
        Run sequential mode tests across datasource counts.

        Equivalent to script_helper.sh:
        - Tests datasource counts: 1, 2, 4, 8, 16, 32, 64 (default)
        - Runs: sequential mode with 10 files per test

        Args:
            datasource_counts: List of datasource counts to test
            files_per_test: Number of files per test
            samples_dir: Directory containing sample files
            startup_wait_seconds: Seconds to wait for container startup

        Returns:
            List of test results
        """
        counts = datasource_counts or SEQUENTIAL_SWEEP_DEFAULTS["datasource_counts"]
        files = files_per_test or SEQUENTIAL_SWEEP_DEFAULTS["files_per_test"]
        samples = samples_dir or SEQUENTIAL_SWEEP_DEFAULTS["samples_dir"]
        wait_seconds = startup_wait_seconds or DOCKER_DEFAULTS["startup_wait_seconds"]

        self.logger.info("=" * 60)
        self.logger.info("Starting Sequential Datasource Sweep")
        self.logger.info("=" * 60)
        self.logger.info(f"  Docker image: {self.docker_image}")
        self.logger.info(f"  Datasource counts: {counts}")
        self.logger.info(f"  Files per test: {files}")
        self.logger.info(f"  Samples directory: {samples}")
        self.logger.info("=" * 60)

        self.aggregator.clear()
        results = []

        for count in counts:
            self.logger.info("")
            self.logger.info("=" * 50)
            self.logger.info(f" Testing with DXR_ODC_DATASOURCE_COUNT={count}")
            self.logger.info("=" * 50)

            env_vars = self.docker_manager.get_env_vars(
                datasource_count=count,
                dxr_base_url=self.dxr_base_url,
                dxr_api_key=self.dxr_api_key,
                first_datasource_id=DOCKER_DEFAULTS["first_datasource_id"],
                max_batch_size=DOCKER_DEFAULTS["max_batch_size"],
                batch_interval_ms=DOCKER_DEFAULTS["batch_interval_ms"],
                job_status_poll_interval_ms=DOCKER_DEFAULTS[
                    "job_status_poll_interval_ms"
                ],
                name_cache_expiry_ms=DOCKER_DEFAULTS["name_cache_expiry_ms"],
                preload_annotation_ids=DEFAULT_PRELOAD_ANNOTATION_IDS,
            )

            with self.docker_manager.container(env_vars, wait_seconds):
                config = TestConfig(
                    sequential_count=files,
                    samples_dir=samples,
                    datasource_count=count,
                )
                tester = LoadTester(config)
                result = await tester.run()
                results.append(result)
                self.aggregator.add_result(result)
                self.aggregator.print_single_result(result)

            self.logger.info(f"Completed run with count={count}")

        # Print final summary
        self.aggregator.print_summary_table(
            title="SEQUENTIAL SWEEP RESULTS",
            description=f"Mode: Sequential | Files per test: {files} | Samples: {samples}",
        )

        return results

    async def run_rate_limited_sweep(
        self,
        datasource_counts: Optional[List[int]] = None,
        files_per_second: Optional[int] = None,
        duration_seconds: Optional[int] = None,
        samples_dir: Optional[str] = None,
        startup_wait_seconds: Optional[int] = None,
    ) -> List[TestResult]:
        """
        Run rate-limited mode tests across datasource counts.

        Equivalent to script_helper_load.sh:
        - Tests datasource counts: 32, 64, 128, 256, 512, 1024 (default)
        - Runs: 20 files/second for 60 seconds

        Args:
            datasource_counts: List of datasource counts to test
            files_per_second: Target files per second
            duration_seconds: Test duration in seconds
            samples_dir: Directory containing sample files
            startup_wait_seconds: Seconds to wait for container startup

        Returns:
            List of test results
        """
        counts = datasource_counts or RATE_LIMITED_SWEEP_DEFAULTS["datasource_counts"]
        fps = files_per_second or RATE_LIMITED_SWEEP_DEFAULTS["files_per_second"]
        duration = duration_seconds or RATE_LIMITED_SWEEP_DEFAULTS["duration_seconds"]
        samples = samples_dir or RATE_LIMITED_SWEEP_DEFAULTS["samples_dir"]
        wait_seconds = startup_wait_seconds or DOCKER_DEFAULTS["startup_wait_seconds"]

        self.logger.info("=" * 60)
        self.logger.info("Starting Rate-Limited Datasource Sweep")
        self.logger.info("=" * 60)
        self.logger.info(f"  Docker image: {self.docker_image}")
        self.logger.info(f"  Datasource counts: {counts}")
        self.logger.info(f"  Files per second: {fps}")
        self.logger.info(f"  Duration: {duration} seconds")
        self.logger.info(f"  Samples directory: {samples}")
        self.logger.info("=" * 60)

        self.aggregator.clear()
        results = []

        for count in counts:
            self.logger.info("")
            self.logger.info("=" * 50)
            self.logger.info(f" Testing with DXR_ODC_DATASOURCE_COUNT={count}")
            self.logger.info("=" * 50)

            env_vars = self.docker_manager.get_env_vars(
                datasource_count=count,
                dxr_base_url=self.dxr_base_url,
                dxr_api_key=self.dxr_api_key,
                first_datasource_id=DOCKER_DEFAULTS["first_datasource_id"],
                max_batch_size=DOCKER_DEFAULTS["max_batch_size"],
                batch_interval_ms=DOCKER_DEFAULTS["batch_interval_ms"],
                job_status_poll_interval_ms=DOCKER_DEFAULTS[
                    "job_status_poll_interval_ms"
                ],
                name_cache_expiry_ms=DOCKER_DEFAULTS["name_cache_expiry_ms"],
                preload_annotation_ids=DEFAULT_PRELOAD_ANNOTATION_IDS,
            )

            with self.docker_manager.container(env_vars, wait_seconds):
                config = TestConfig(
                    files_per_second=fps,
                    duration=duration,
                    samples_dir=samples,
                    datasource_count=count,
                )
                tester = LoadTester(config)
                result = await tester.run()
                results.append(result)
                self.aggregator.add_result(result)
                self.aggregator.print_single_result(result)

            self.logger.info(f"Completed run with count={count}")

        # Print final summary
        self.aggregator.print_summary_table(
            title="RATE-LIMITED SWEEP RESULTS",
            description=(
                f"Mode: Rate-Limited | {fps} files/sec for {duration}s | "
                f"Samples: {samples}"
            ),
        )

        return results

    def get_aggregator(self) -> ResultAggregator:
        """Get the result aggregator for additional processing."""
        return self.aggregator
