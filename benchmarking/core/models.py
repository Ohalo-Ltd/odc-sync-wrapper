"""Data models for benchmarking."""

from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, List, Dict, Any


@dataclass
class TestConfig:
    """Configuration for a single load test run."""

    server_url: str = "http://localhost:8844"
    samples_dir: str = "samples/plain_txt"

    # Rate-limited mode settings
    files_per_second: Optional[int] = None
    duration: Optional[int] = None

    # Sequential mode settings
    sequential_count: Optional[int] = None

    # Container config (for sweep tests)
    datasource_count: Optional[int] = None

    @property
    def is_sequential(self) -> bool:
        """Check if this config is for sequential mode."""
        return self.sequential_count is not None

    @property
    def is_rate_limited(self) -> bool:
        """Check if this config is for rate-limited mode."""
        return self.files_per_second is not None and self.duration is not None


@dataclass
class TestResult:
    """Results from a single load test run."""

    # Test identification
    datasource_count: Optional[int]
    test_mode: str  # "sequential" or "rate_limited"

    # Request metrics
    total_requests: int
    successful_requests: int
    failed_requests: int

    # Latency metrics (milliseconds)
    avg_latency_ms: float
    min_latency_ms: float
    max_latency_ms: float
    p95_latency_ms: float
    p99_latency_ms: float

    # Throughput metrics
    throughput_rps: float
    error_rate: float

    # Optional timing metadata
    start_timestamp: Optional[datetime] = None
    end_timestamp: Optional[datetime] = None
    duration_seconds: Optional[float] = None

    # Optional: files per second target (for rate-limited mode)
    target_files_per_second: Optional[int] = None

    # Optional: raw results for debugging
    raw_results: Optional[List[Dict[str, Any]]] = field(default=None, repr=False)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            "datasource_count": self.datasource_count,
            "test_mode": self.test_mode,
            "total_requests": self.total_requests,
            "successful_requests": self.successful_requests,
            "failed_requests": self.failed_requests,
            "avg_latency_ms": self.avg_latency_ms,
            "min_latency_ms": self.min_latency_ms,
            "max_latency_ms": self.max_latency_ms,
            "p95_latency_ms": self.p95_latency_ms,
            "p99_latency_ms": self.p99_latency_ms,
            "throughput_rps": self.throughput_rps,
            "error_rate": self.error_rate,
            "target_files_per_second": self.target_files_per_second,
            "duration_seconds": self.duration_seconds,
        }
