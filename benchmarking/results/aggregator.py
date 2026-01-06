"""Result aggregation and reporting."""

import pandas as pd
from typing import List, Optional

from ..core.models import TestResult


class ResultAggregator:
    """Aggregates and formats test results for export."""

    def __init__(self):
        self.results: List[TestResult] = []

    def add_result(self, result: TestResult) -> None:
        """Add a single test result."""
        self.results.append(result)

    def add_results(self, results: List[TestResult]) -> None:
        """Add multiple test results."""
        self.results.extend(results)

    def clear(self) -> None:
        """Clear all results."""
        self.results = []

    def to_dataframe(self) -> pd.DataFrame:
        """Convert results to pandas DataFrame."""
        data = []
        for result in self.results:
            data.append({
                "DS_Count": result.datasource_count,
                "Mode": result.test_mode,
                "Total": result.total_requests,
                "Success": result.successful_requests,
                "Failed": result.failed_requests,
                "Error%": f"{result.error_rate:.2f}",
                "Avg_ms": f"{result.avg_latency_ms:.2f}",
                "Min_ms": f"{result.min_latency_ms:.2f}",
                "Max_ms": f"{result.max_latency_ms:.2f}",
                "P95_ms": f"{result.p95_latency_ms:.2f}",
                "P99_ms": f"{result.p99_latency_ms:.2f}",
                "RPS": f"{result.throughput_rps:.2f}",
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
        print("=" * 100)
        if title:
            print(title.center(100))
        else:
            print("BENCHMARK RESULTS SUMMARY".center(100))
        print("=" * 100)

        if description:
            print(description)
            print("-" * 100)

        # Print the formatted table
        df = self.to_dataframe()
        print(df.to_string(index=False))

        print()
        print("=" * 100)
        print("TSV OUTPUT (copy to spreadsheet):")
        print("=" * 100)
        print(self.get_tsv_string())
        print("=" * 100)

    def print_single_result(self, result: TestResult) -> None:
        """Print a single test result during sweep."""
        print(f"\nResults for datasource_count={result.datasource_count}:")
        print(f"  Throughput: {result.throughput_rps:.2f} req/s")
        print(f"  Avg Latency: {result.avg_latency_ms:.2f}ms")
        print(f"  P95 Latency: {result.p95_latency_ms:.2f}ms")
        print(f"  Error Rate: {result.error_rate:.2f}%")
