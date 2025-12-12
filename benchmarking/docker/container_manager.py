"""Docker container management for load testing."""

import logging
import os
import subprocess
import time
from contextlib import contextmanager
from typing import Dict, Generator, Optional


class DockerContainerManager:
    """Manages Docker container lifecycle for load testing."""

    def __init__(
        self,
        image: str,
        container_name: str = "odc-sync",
        port: int = 8844,
    ):
        self.image = image
        self.container_name = container_name
        self.port = port
        self._container_id: Optional[str] = None

        logging.basicConfig(
            level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
        )
        self.logger = logging.getLogger(__name__)

    def get_env_vars(
        self,
        datasource_count: int,
        dxr_base_url: Optional[str] = None,
        dxr_api_key: Optional[str] = None,
        first_datasource_id: int = 100,
        max_batch_size: int = 1000,
        batch_interval_ms: int = 1000,
        job_status_poll_interval_ms: int = 333,
        name_cache_expiry_ms: int = 36000000,
        preload_annotation_ids: Optional[str] = None,
    ) -> Dict[str, str]:
        """
        Build environment variable dictionary for the container.

        Args:
            datasource_count: Number of datasources to configure
            dxr_base_url: Base URL for DXR API (defaults to env var DXR_BASE_URL)
            dxr_api_key: API key for DXR (defaults to env var DXR_API_KEY)
            first_datasource_id: Starting datasource ID
            max_batch_size: Maximum batch size
            batch_interval_ms: Batch interval in milliseconds
            job_status_poll_interval_ms: Job status polling interval
            name_cache_expiry_ms: Name cache expiry time
            preload_annotation_ids: Comma-separated annotation IDs to preload

        Returns:
            Dictionary of environment variables
        """
        base_url = dxr_base_url or os.environ.get("DXR_BASE_URL", "")
        api_key = dxr_api_key or os.environ.get("DXR_API_KEY", "")

        # Ensure base URL has /api suffix
        if base_url and not base_url.endswith("/api"):
            base_url = f"{base_url}/api"

        env_vars = {
            "DXR_BASE_URL": base_url,
            "DXR_API_KEY": api_key,
            "DXR_FIRST_ODC_DATASOURCE_ID": str(first_datasource_id),
            "DXR_ODC_DATASOURCE_COUNT": str(datasource_count),
            "DXR_MAX_BATCH_SIZE": str(max_batch_size),
            "DXR_BATCH_INTERVAL_MS": str(batch_interval_ms),
            "DXR_JOB_STATUS_POLL_INTERVAL_MS": str(job_status_poll_interval_ms),
            "DXR_NAME_CACHE_EXPIRY_MS": str(name_cache_expiry_ms),
        }

        if preload_annotation_ids:
            env_vars["DXR_PRELOAD_ANNOTATION_IDS"] = preload_annotation_ids

        return env_vars

    def start(
        self,
        env_vars: Dict[str, str],
        startup_wait_seconds: int = 20,
    ) -> None:
        """
        Start the container with given environment variables.

        Args:
            env_vars: Environment variables to pass to the container
            startup_wait_seconds: Seconds to wait for container startup
        """
        self.logger.info(f"Starting container {self.container_name} from {self.image}")

        # Build docker run command
        cmd = [
            "docker",
            "run",
            "-d",
            "-p",
            f"{self.port}:{self.port}",
            f"--name={self.container_name}",
        ]

        # Add environment variables
        for key, value in env_vars.items():
            cmd.extend(["-e", f"{key}={value}"])

        cmd.append(self.image)

        # Run the command
        result = subprocess.run(cmd, capture_output=True, text=True)

        if result.returncode != 0:
            raise RuntimeError(
                f"Failed to start container: {result.stderr}\nCommand: {' '.join(cmd)}"
            )

        self._container_id = result.stdout.strip()
        self.logger.info(f"Container started: {self._container_id[:12]}")

        # Wait for startup
        self.logger.info(f"Waiting {startup_wait_seconds} seconds for startup...")
        time.sleep(startup_wait_seconds)

    def stop(self) -> None:
        """Stop and remove the container."""
        self.logger.info(f"Stopping container {self.container_name}")

        # Stop the container
        stop_result = subprocess.run(
            ["docker", "stop", self.container_name], capture_output=True, text=True
        )

        if stop_result.returncode != 0:
            self.logger.warning(f"Failed to stop container: {stop_result.stderr}")

        # Remove the container
        rm_result = subprocess.run(
            ["docker", "rm", self.container_name], capture_output=True, text=True
        )

        if rm_result.returncode != 0:
            self.logger.warning(f"Failed to remove container: {rm_result.stderr}")

        self._container_id = None
        self.logger.info("Container stopped and removed")

    def is_running(self) -> bool:
        """Check if the container is currently running."""
        result = subprocess.run(
            [
                "docker",
                "ps",
                "-q",
                "-f",
                f"name={self.container_name}",
            ],
            capture_output=True,
            text=True,
        )
        return bool(result.stdout.strip())

    @contextmanager
    def container(
        self,
        env_vars: Dict[str, str],
        startup_wait_seconds: int = 20,
    ) -> Generator[None, None, None]:
        """
        Context manager for container lifecycle.

        Ensures container is stopped and removed even if an exception occurs.

        Usage:
            with manager.container(env_vars):
                # Run tests
                pass
        """
        try:
            self.start(env_vars, startup_wait_seconds)
            yield
        finally:
            self.stop()
