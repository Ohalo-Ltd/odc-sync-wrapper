"""Data models for async benchmarking."""

from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, List, Dict, Any


@dataclass
class AsyncTestConfig:
    """Configuration for an async API load test."""

    # Connection settings
    server_url: str
    datasource_id: int
    api_token: str

    # Test parameters
    samples_dir: str = ""
    files_per_request: int = 1
    concurrency: int = 1
    repeat: int = 1

    # Timeouts
    job_timeout_seconds: int = 1200
    poll_interval_seconds: int = 5

    # SSL
    insecure_ssl: bool = False

    @property
    def is_sequential(self) -> bool:
        """Check if this config is for sequential mode."""
        return self.concurrency == 1


@dataclass
class JobResult:
    """Result from a single job submission and completion."""

    job_id: Optional[str]
    batch_id: int
    file_count: int
    file_size_bytes: int

    # Timing
    submit_start: float
    submit_end: float
    submit_latency_ms: float

    job_start: float
    job_end: float
    job_duration_seconds: float

    # Polling
    poll_count: int

    # Status
    final_state: str
    success: bool
    error_message: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            "job_id": self.job_id,
            "batch_id": self.batch_id,
            "file_count": self.file_count,
            "file_size_bytes": self.file_size_bytes,
            "submit_latency_ms": self.submit_latency_ms,
            "job_duration_seconds": self.job_duration_seconds,
            "poll_count": self.poll_count,
            "final_state": self.final_state,
            "success": self.success,
            "error_message": self.error_message,
        }


@dataclass
class AsyncTestResult:
    """Results from an async API load test."""

    # Test identification
    samples_dir: str
    file_size_label: str
    file_size_bytes: int
    files_per_request: int
    concurrency: int

    # Request/job counts
    total_files: int
    total_jobs: int
    successful_jobs: int
    failed_jobs: int
    timed_out_jobs: int

    # Submission latency metrics (time to get job_id back) in ms
    submit_avg_ms: float
    submit_min_ms: float
    submit_max_ms: float
    submit_p95_ms: float
    submit_p99_ms: float

    # Job duration metrics (time from submit to completion) in seconds
    job_avg_seconds: float
    job_min_seconds: float
    job_max_seconds: float
    job_p95_seconds: float
    job_p99_seconds: float

    # Polling metrics
    avg_poll_count: float
    total_polls: int

    # Throughput metrics
    throughput_files_per_second: float
    throughput_mb_per_second: float

    # Error metrics
    error_rate: float

    # Timing metadata
    start_timestamp: Optional[datetime] = None
    end_timestamp: Optional[datetime] = None
    total_duration_seconds: Optional[float] = None

    # Raw data for debugging
    raw_job_results: Optional[List[JobResult]] = field(default=None, repr=False)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            "samples_dir": self.samples_dir,
            "file_size_label": self.file_size_label,
            "file_size_bytes": self.file_size_bytes,
            "files_per_request": self.files_per_request,
            "concurrency": self.concurrency,
            "total_files": self.total_files,
            "total_jobs": self.total_jobs,
            "successful_jobs": self.successful_jobs,
            "failed_jobs": self.failed_jobs,
            "timed_out_jobs": self.timed_out_jobs,
            "submit_avg_ms": self.submit_avg_ms,
            "submit_min_ms": self.submit_min_ms,
            "submit_max_ms": self.submit_max_ms,
            "submit_p95_ms": self.submit_p95_ms,
            "submit_p99_ms": self.submit_p99_ms,
            "job_avg_seconds": self.job_avg_seconds,
            "job_min_seconds": self.job_min_seconds,
            "job_max_seconds": self.job_max_seconds,
            "job_p95_seconds": self.job_p95_seconds,
            "job_p99_seconds": self.job_p99_seconds,
            "avg_poll_count": self.avg_poll_count,
            "total_polls": self.total_polls,
            "throughput_files_per_second": self.throughput_files_per_second,
            "throughput_mb_per_second": self.throughput_mb_per_second,
            "error_rate": self.error_rate,
            "total_duration_seconds": self.total_duration_seconds,
        }


@dataclass
class SweepResult:
    """Results from a multi-directory sweep."""

    # Sweep configuration
    directories: List[str]
    files_per_request: int
    concurrency: int

    # Individual stage results
    stage_results: List[AsyncTestResult]

    # Aggregate metrics
    total_files_processed: int
    total_bytes_processed: int
    total_duration_seconds: float
    overall_error_rate: float

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            "directories": self.directories,
            "files_per_request": self.files_per_request,
            "concurrency": self.concurrency,
            "stage_results": [r.to_dict() for r in self.stage_results],
            "total_files_processed": self.total_files_processed,
            "total_bytes_processed": self.total_bytes_processed,
            "total_duration_seconds": self.total_duration_seconds,
            "overall_error_rate": self.overall_error_rate,
        }
