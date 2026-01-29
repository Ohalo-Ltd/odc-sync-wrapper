"""Result aggregation and reporting for async benchmarking."""

import pandas as pd
from typing import List, Optional

# Support both relative and absolute imports
try:
    from ..core.models import AsyncTestResult
except ImportError:
    from core.models import AsyncTestResult


class AsyncResultAggregator:
    """Aggregates and formats async test results for export."""

    def __init__(self):
        self.results: List[AsyncTestResult] = []

    def add_result(self, result: AsyncTestResult) -> None:
        """Add a single test result."""
        self.results.append(result)

    def add_results(self, results: List[AsyncTestResult]) -> None:
        """Add multiple test results."""
        self.results.extend(results)

    def clear(self) -> None:
        """Clear all results."""
        self.results = []

    def to_dataframe(self) -> pd.DataFrame:
        """Convert results to pandas DataFrame with file-size-specific columns."""
        data = []
        for r in self.results:
            size_mb = r.file_size_bytes / (1024 * 1024)
            # Convert submit latency from ms to seconds
            upload_avg_s = r.submit_avg_ms / 1000
            upload_p95_s = r.submit_p95_ms / 1000
            data.append({
                "Size": r.file_size_label,
                "Size_MB": f"{size_mb:.3f}",
                "Files": r.total_files,
                "Batch": r.files_per_request,
                "Jobs": r.total_jobs,
                "Success": r.successful_jobs,
                "Failed": r.failed_jobs,
                "Timeout": r.timed_out_jobs,
                "Error%": f"{r.error_rate:.3f}",
                "Upload_s": f"{upload_avg_s:.3f}",
                "Upload_P95": f"{upload_p95_s:.3f}",
                "Job_s": f"{r.job_avg_seconds:.3f}",
                "Job_P95": f"{r.job_p95_seconds:.3f}",
                "Files/s": f"{r.throughput_files_per_second:.3f}",
                "MB/s": f"{r.throughput_mb_per_second:.3f}",
            })
        return pd.DataFrame(data)

    def to_csv(self, path: str) -> None:
        """Export to CSV file."""
        df = self.to_dataframe()
        df.to_csv(path, index=False)

    def to_tsv(self, path: str) -> None:
        """Export to TSV file."""
        df = self.to_dataframe()
        df.to_csv(path, sep="\t", index=False)

    def get_tsv_string(self) -> str:
        """Get results as TSV string for easy copy/paste to spreadsheet."""
        df = self.to_dataframe()
        return df.to_csv(sep="\t", index=False)

    def print_summary_table(
        self,
        title: Optional[str] = None,
        description: Optional[str] = None,
    ) -> None:
        """Print formatted summary table to console."""
        if not self.results:
            print("No results to display.")
            return

        print()
        print("=" * 120)
        if title:
            print(title.center(120))
        else:
            print("ASYNC BENCHMARK RESULTS SUMMARY".center(120))
        print("=" * 120)

        if description:
            print(description)
            print("-" * 120)

        # Print the formatted table
        df = self.to_dataframe()
        print(df.to_string(index=False))

        print()
        print("=" * 120)
        print("TSV OUTPUT (copy to spreadsheet):")
        print("=" * 120)
        print(self.get_tsv_string())
        print("=" * 120)

    def print_single_result(self, result: AsyncTestResult) -> None:
        """Print a single test result during sweep."""
        upload_avg_s = result.submit_avg_ms / 1000
        upload_p95_s = result.submit_p95_ms / 1000
        print(f"\nResults for {result.file_size_label} ({result.samples_dir}):")
        print(f"  Files: {result.total_files} ({result.files_per_request} per request)")
        print(f"  Jobs: {result.successful_jobs}/{result.total_jobs} succeeded")
        print(f"  Upload: avg={upload_avg_s:.3f}s, p95={upload_p95_s:.3f}s")
        print(f"  Job Duration: avg={result.job_avg_seconds:.3f}s, p95={result.job_p95_seconds:.3f}s")
        print(f"  Throughput: {result.throughput_files_per_second:.3f} files/s, {result.throughput_mb_per_second:.3f} MB/s")
        print(f"  Error Rate: {result.error_rate:.3f}%")

    def print_detailed_result(self, result: AsyncTestResult) -> None:
        """Print detailed single test result."""
        print()
        print("=" * 60)
        print("ASYNC LOAD TEST RESULTS")
        print("=" * 60)

        print(f"\nTEST CONFIGURATION")
        print("-" * 30)
        print(f"Samples Directory:   {result.samples_dir}")
        print(f"File Size:           {result.file_size_label} ({result.file_size_bytes} bytes)")
        print(f"Files per Request:   {result.files_per_request}")
        print(f"Concurrency:         {result.concurrency}")

        print(f"\nJOB STATISTICS")
        print("-" * 30)
        print(f"Total Files:         {result.total_files}")
        print(f"Total Jobs:          {result.total_jobs}")
        print(f"Successful Jobs:     {result.successful_jobs}")
        print(f"Failed Jobs:         {result.failed_jobs}")
        print(f"Timed Out Jobs:      {result.timed_out_jobs}")
        print(f"Error Rate:          {result.error_rate:.3f}%")

        print(f"\nUPLOAD TIME (seconds)")
        print("-" * 30)
        print(f"Average:             {result.submit_avg_ms / 1000:.3f}")
        print(f"Minimum:             {result.submit_min_ms / 1000:.3f}")
        print(f"Maximum:             {result.submit_max_ms / 1000:.3f}")
        print(f"95th Percentile:     {result.submit_p95_ms / 1000:.3f}")
        print(f"99th Percentile:     {result.submit_p99_ms / 1000:.3f}")

        print(f"\nJOB DURATION (seconds)")
        print("-" * 30)
        print(f"Average:             {result.job_avg_seconds:.3f}")
        print(f"Minimum:             {result.job_min_seconds:.3f}")
        print(f"Maximum:             {result.job_max_seconds:.3f}")
        print(f"95th Percentile:     {result.job_p95_seconds:.3f}")
        print(f"99th Percentile:     {result.job_p99_seconds:.3f}")

        print(f"\nPOLLING STATISTICS")
        print("-" * 30)
        print(f"Average Polls/Job:   {result.avg_poll_count:.1f}")
        print(f"Total Polls:         {result.total_polls}")

        print(f"\nTHROUGHPUT")
        print("-" * 30)
        print(f"Files/Second:        {result.throughput_files_per_second:.3f}")
        print(f"MB/Second:           {result.throughput_mb_per_second:.3f}")
        print(f"Total Duration:      {result.total_duration_seconds:.3f}s")

        print("=" * 60)
