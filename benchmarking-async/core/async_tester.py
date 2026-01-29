"""Async API load tester with detailed metrics collection."""

import aiohttp
import asyncio
import json
import os
import ssl
import time
from datetime import datetime
from typing import List, Tuple, Optional

# Support both relative and absolute imports
try:
    from .models import AsyncTestConfig, AsyncTestResult, JobResult
    from .sample_loader import SampleLoader
except (ImportError, ValueError):
    from core.models import AsyncTestConfig, AsyncTestResult, JobResult
    from core.sample_loader import SampleLoader


def _calculate_percentile(values: List[float], percentile: float) -> float:
    """Calculate percentile value from a list."""
    if not values:
        return 0.0
    sorted_values = sorted(values)
    index = int(len(sorted_values) * percentile / 100)
    index = min(index, len(sorted_values) - 1)
    return sorted_values[index]


class AsyncTester:
    """Async API load tester with detailed metrics collection."""

    # Job states indicating completion
    FINISHED_STATES = {"finished", "completed", "successful", "done"}
    FAILED_STATES = {"failed", "cancelled"}

    # Human-readable state descriptions
    STATE_DESCRIPTIONS = {
        "uploading_files": "Files uploaded, staging on server",
        "awaiting_recrawl_to_complete": "Classifying files",
        "finished": "Classification complete",
        "completed": "Classification complete",
        "successful": "Classification complete",
        "done": "Classification complete",
        "failed": "Job failed",
        "cancelled": "Job cancelled",
        "pending": "Waiting to start",
        "queued": "Queued for processing",
        "processing": "Processing files",
        "running": "Running classification",
    }

    def __init__(self, config: AsyncTestConfig, verbose: bool = True):
        """
        Initialize the async tester.

        Args:
            config: Test configuration
            verbose: Whether to print progress messages
        """
        self.config = config
        self.verbose = verbose
        self.job_results: List[JobResult] = []
        self._ssl_ctx = self._create_ssl_context()

    def _create_ssl_context(self) -> Optional[ssl.SSLContext]:
        """Create SSL context if insecure mode is enabled."""
        if self.config.insecure_ssl:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            return ctx
        return None

    def _log(self, message: str) -> None:
        """Print message if verbose mode is enabled."""
        if self.verbose:
            print(message)

    async def _submit_batch(
        self,
        session: aiohttp.ClientSession,
        files: List[Tuple[str, str]],
        batch_id: int,
        file_size_bytes: int,
    ) -> JobResult:
        """
        Submit a batch of files and return the job result.

        Args:
            session: aiohttp session
            files: List of (filename, filepath) tuples
            batch_id: Batch identifier
            file_size_bytes: Size of each file in bytes

        Returns:
            JobResult with submission timing
        """
        data = aiohttp.FormData()
        opened_files = []

        submit_start = time.time()

        try:
            for idx, (fname, fpath) in enumerate(files, 1):
                base, ext = os.path.splitext(fname)
                numbered_name = f"{base}_b{batch_id:03d}_f{idx:03d}{ext}"

                f = open(fpath, "rb")
                opened_files.append(f)

                data.add_field(
                    "files",
                    f,
                    filename=numbered_name,
                    content_type="application/octet-stream",
                )

                if self.verbose:
                    size = os.path.getsize(fpath)
                    size_mb = size / (1024 * 1024)
                    self._log(f"  [Uploading] {numbered_name} ({size_mb:.1f} MB)")

            headers = {"Authorization": f"Bearer {self.config.api_token}"}
            url = f"{self.config.server_url.rstrip('/')}/api/on-demand-classifiers/{self.config.datasource_id}/jobs"

            async with session.post(url, data=data, ssl=self._ssl_ctx, headers=headers) as response:
                text = await response.text()
                submit_end = time.time()
                submit_latency_ms = (submit_end - submit_start) * 1000

                if response.status != 202:
                    self._log(f"  X Batch {batch_id} failed: HTTP {response.status}")
                    self._log(f"    {text[:500]}")
                    return JobResult(
                        job_id=None,
                        batch_id=batch_id,
                        file_count=len(files),
                        file_size_bytes=file_size_bytes * len(files),
                        submit_start=submit_start,
                        submit_end=submit_end,
                        submit_latency_ms=submit_latency_ms,
                        job_start=submit_end,
                        job_end=submit_end,
                        job_duration_seconds=0,
                        poll_count=0,
                        final_state="SUBMIT_FAILED",
                        success=False,
                        error_message=f"HTTP {response.status}: {text[:200]}",
                    )

                try:
                    json_response = json.loads(text)
                    job_id = json_response.get("id")

                    # Calculate upload speed
                    total_bytes = file_size_bytes * len(files)
                    upload_seconds = submit_latency_ms / 1000
                    if upload_seconds > 0:
                        bytes_per_sec = total_bytes / upload_seconds
                        mb_per_sec = bytes_per_sec / (1024 * 1024)
                        self._log(f"  [Upload complete] job_id={job_id} ({self._format_duration(upload_seconds)}, {mb_per_sec:.1f} MB/s)")
                    else:
                        self._log(f"  [Upload complete] job_id={job_id} ({self._format_duration(upload_seconds)})")

                    return JobResult(
                        job_id=job_id,
                        batch_id=batch_id,
                        file_count=len(files),
                        file_size_bytes=file_size_bytes * len(files),
                        submit_start=submit_start,
                        submit_end=submit_end,
                        submit_latency_ms=submit_latency_ms,
                        job_start=submit_end,
                        job_end=0,
                        job_duration_seconds=0,
                        poll_count=0,
                        final_state="SUBMITTED",
                        success=False,
                    )
                except Exception as e:
                    self._log(f"  X Batch {batch_id} parse error: {e}")
                    return JobResult(
                        job_id=None,
                        batch_id=batch_id,
                        file_count=len(files),
                        file_size_bytes=file_size_bytes * len(files),
                        submit_start=submit_start,
                        submit_end=submit_end,
                        submit_latency_ms=submit_latency_ms,
                        job_start=submit_end,
                        job_end=submit_end,
                        job_duration_seconds=0,
                        poll_count=0,
                        final_state="PARSE_ERROR",
                        success=False,
                        error_message=str(e),
                    )
        finally:
            for f in opened_files:
                try:
                    f.close()
                except Exception:
                    pass

    def _format_duration(self, seconds: float) -> str:
        """Format duration in human-readable form."""
        if seconds < 60:
            return f"{seconds:.1f}s"
        elif seconds < 3600:
            mins = int(seconds // 60)
            secs = seconds % 60
            return f"{mins}m {secs:.1f}s"
        else:
            hours = int(seconds // 3600)
            mins = int((seconds % 3600) // 60)
            secs = seconds % 60
            return f"{hours}h {mins}m {secs:.1f}s"

    def _get_state_description(self, state: str) -> str:
        """Get human-readable description for a state."""
        state_lower = state.lower()
        return self.STATE_DESCRIPTIONS.get(state_lower, state)

    async def _poll_until_complete(
        self,
        session: aiohttp.ClientSession,
        job_result: JobResult,
    ) -> JobResult:
        """
        Poll job status until completion or timeout.

        Args:
            session: aiohttp session
            job_result: JobResult from submission

        Returns:
            Updated JobResult with completion data
        """
        if not job_result.job_id:
            return job_result

        url = f"{self.config.server_url.rstrip('/')}/api/on-demand-classifiers/{self.config.datasource_id}/jobs/{job_result.job_id}"
        headers = {"Authorization": f"Bearer {self.config.api_token}"}

        job_start = time.time()
        last_state = None
        last_state_time = job_start
        poll_count = 0
        max_attempts = int(self.config.job_timeout_seconds / self.config.poll_interval_seconds)

        self._log(f"  [Polling] Waiting for job to start...")

        for _ in range(max_attempts):
            poll_count += 1

            try:
                async with session.get(url, headers=headers, ssl=self._ssl_ctx) as response:
                    text = await response.text()

                    if response.status != 200:
                        await asyncio.sleep(self.config.poll_interval_seconds)
                        continue

                    try:
                        data = json.loads(text)
                    except Exception:
                        await asyncio.sleep(self.config.poll_interval_seconds)
                        continue

                    # Extract state from various possible fields
                    state = (
                        data.get("state")
                        or (data.get("job") or {}).get("state")
                        or data.get("status")
                        or data.get("jobState")
                    )

                    if state and state != last_state:
                        current_time = time.time()
                        state_description = self._get_state_description(state)

                        if last_state:
                            # Print duration of previous state
                            stage_duration = current_time - last_state_time
                            self._log(f"  [Stage complete] {self._get_state_description(last_state)} ({self._format_duration(stage_duration)})")

                        self._log(f"  [State] {state} -> {state_description}")
                        last_state = state
                        last_state_time = current_time

                    if state:
                        state_lower = str(state).lower()
                        job_end = time.time()

                        if state_lower in self.FINISHED_STATES:
                            # Print final stage duration
                            stage_duration = job_end - last_state_time
                            total_duration = job_end - job_start
                            self._log(f"  [Complete] Total processing time: {self._format_duration(total_duration)}")

                            job_result.job_end = job_end
                            job_result.job_duration_seconds = job_end - job_start
                            job_result.poll_count = poll_count
                            job_result.final_state = state
                            job_result.success = True
                            return job_result

                        if state_lower in self.FAILED_STATES:
                            stage_duration = job_end - last_state_time
                            total_duration = job_end - job_start
                            self._log(f"  [Failed] Total time: {self._format_duration(total_duration)}")

                            job_result.job_end = job_end
                            job_result.job_duration_seconds = job_end - job_start
                            job_result.poll_count = poll_count
                            job_result.final_state = state
                            job_result.success = False
                            job_result.error_message = f"Job ended with state: {state}"
                            return job_result

            except Exception as e:
                self._log(f"  [Poll error] {e}")

            await asyncio.sleep(self.config.poll_interval_seconds)

        # Timeout
        job_end = time.time()
        total_duration = job_end - job_start
        self._log(f"  [Timeout] Job timed out after {self._format_duration(total_duration)}")

        job_result.job_end = job_end
        job_result.job_duration_seconds = job_end - job_start
        job_result.poll_count = poll_count
        job_result.final_state = "TIMEOUT"
        job_result.success = False
        job_result.error_message = f"Timed out after {self.config.job_timeout_seconds}s"
        return job_result

    async def _run_batch_with_polling(
        self,
        session: aiohttp.ClientSession,
        batch: List[Tuple[str, str]],
        batch_id: int,
        file_size_bytes: int,
    ) -> JobResult:
        """Submit a batch and poll until completion."""
        job_result = await self._submit_batch(session, batch, batch_id, file_size_bytes)
        return await self._poll_until_complete(session, job_result)

    async def run(self) -> AsyncTestResult:
        """
        Execute the test and return structured results.

        Returns:
            AsyncTestResult with all metrics
        """
        # Load samples
        loader = SampleLoader(self.config.samples_dir, self.config.repeat)
        samples = loader.load()
        file_size_bytes = loader.get_file_size()
        file_size_label = loader.extract_size_label()

        # Create batches
        batches = [
            samples[i:i + self.config.files_per_request]
            for i in range(0, len(samples), self.config.files_per_request)
        ]

        self._log(f"\nTotal: {len(samples)} files -> {len(batches)} batches")
        self._log(f"Files per request: {self.config.files_per_request}")
        self._log(f"File size: {file_size_bytes} bytes ({file_size_label})")
        self._log(f"Concurrency: {self.config.concurrency} ({'sequential' if self.config.is_sequential else 'parallel'})\n")

        # Set up session
        timeout = aiohttp.ClientTimeout(
            total=None,
            sock_connect=30,
            sock_read=self.config.job_timeout_seconds
        )
        connector = aiohttp.TCPConnector(limit=max(self.config.concurrency, 20))

        start_time = datetime.now()
        self.job_results = []

        async with aiohttp.ClientSession(timeout=timeout, connector=connector) as session:
            if self.config.is_sequential:
                # Sequential: send one batch, wait for completion, then next
                for batch_id, batch in enumerate(batches, 1):
                    self._log(f"Batch {batch_id}/{len(batches)} ({len(batch)} files):")
                    result = await self._run_batch_with_polling(session, batch, batch_id, file_size_bytes)
                    self.job_results.append(result)
            else:
                # Parallel: use semaphore to limit concurrent requests
                semaphore = asyncio.Semaphore(self.config.concurrency)

                async def limited_run(batch: List[Tuple[str, str]], batch_id: int) -> JobResult:
                    async with semaphore:
                        self._log(f"Batch {batch_id}/{len(batches)} ({len(batch)} files):")
                        return await self._run_batch_with_polling(session, batch, batch_id, file_size_bytes)

                tasks = [
                    limited_run(batch, batch_id)
                    for batch_id, batch in enumerate(batches, 1)
                ]
                self.job_results = await asyncio.gather(*tasks)

        end_time = datetime.now()

        return self._calculate_results(
            start_time=start_time,
            end_time=end_time,
            file_size_bytes=file_size_bytes,
            file_size_label=file_size_label,
            total_files=len(samples),
        )

    def _calculate_results(
        self,
        start_time: datetime,
        end_time: datetime,
        file_size_bytes: int,
        file_size_label: str,
        total_files: int,
    ) -> AsyncTestResult:
        """Calculate aggregate metrics from job results."""
        total_duration = (end_time - start_time).total_seconds()

        # Separate successful and failed jobs
        successful = [r for r in self.job_results if r.success]
        failed = [r for r in self.job_results if not r.success and r.final_state != "TIMEOUT"]
        timed_out = [r for r in self.job_results if r.final_state == "TIMEOUT"]

        # Submit latency metrics (from all jobs that got a response)
        submit_latencies = [r.submit_latency_ms for r in self.job_results if r.submit_latency_ms > 0]
        if submit_latencies:
            submit_avg = sum(submit_latencies) / len(submit_latencies)
            submit_min = min(submit_latencies)
            submit_max = max(submit_latencies)
            submit_p95 = _calculate_percentile(submit_latencies, 95)
            submit_p99 = _calculate_percentile(submit_latencies, 99)
        else:
            submit_avg = submit_min = submit_max = submit_p95 = submit_p99 = 0.0

        # Job duration metrics (from successful jobs only)
        job_durations = [r.job_duration_seconds for r in successful if r.job_duration_seconds > 0]
        if job_durations:
            job_avg = sum(job_durations) / len(job_durations)
            job_min = min(job_durations)
            job_max = max(job_durations)
            job_p95 = _calculate_percentile(job_durations, 95)
            job_p99 = _calculate_percentile(job_durations, 99)
        else:
            job_avg = job_min = job_max = job_p95 = job_p99 = 0.0

        # Polling metrics
        poll_counts = [r.poll_count for r in self.job_results if r.poll_count > 0]
        avg_poll_count = sum(poll_counts) / len(poll_counts) if poll_counts else 0.0
        total_polls = sum(poll_counts)

        # Throughput
        successful_files = sum(r.file_count for r in successful)
        successful_bytes = sum(r.file_size_bytes for r in successful)

        throughput_files = successful_files / total_duration if total_duration > 0 else 0.0
        throughput_mb = (successful_bytes / (1024 * 1024)) / total_duration if total_duration > 0 else 0.0

        # Error rate
        total_jobs = len(self.job_results)
        error_rate = ((len(failed) + len(timed_out)) / total_jobs * 100) if total_jobs > 0 else 0.0

        return AsyncTestResult(
            samples_dir=self.config.samples_dir,
            file_size_label=file_size_label,
            file_size_bytes=file_size_bytes,
            files_per_request=self.config.files_per_request,
            concurrency=self.config.concurrency,
            total_files=total_files,
            total_jobs=total_jobs,
            successful_jobs=len(successful),
            failed_jobs=len(failed),
            timed_out_jobs=len(timed_out),
            submit_avg_ms=submit_avg,
            submit_min_ms=submit_min,
            submit_max_ms=submit_max,
            submit_p95_ms=submit_p95,
            submit_p99_ms=submit_p99,
            job_avg_seconds=job_avg,
            job_min_seconds=job_min,
            job_max_seconds=job_max,
            job_p95_seconds=job_p95,
            job_p99_seconds=job_p99,
            avg_poll_count=avg_poll_count,
            total_polls=total_polls,
            throughput_files_per_second=throughput_files,
            throughput_mb_per_second=throughput_mb,
            error_rate=error_rate,
            start_timestamp=start_time,
            end_timestamp=end_time,
            total_duration_seconds=total_duration,
            raw_job_results=self.job_results,
        )
