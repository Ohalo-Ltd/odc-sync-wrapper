"""Chart generation for benchmark results."""

import matplotlib.pyplot as plt
from datetime import datetime
from typing import List, Optional

from ..core.models import TestResult


def generate_charts(
    results: List[TestResult],
    output_path: Optional[str] = None,
    show: bool = True,
    x_label: str = "Datasource Count",
) -> Optional[str]:
    """
    Generate performance charts from test results.

    Args:
        results: List of test results to plot
        output_path: Path to save the chart (auto-generated if None)
        show: Whether to display the chart
        x_label: Label for x-axis

    Returns:
        Path to saved chart file, or None if not saved
    """
    if not results:
        print("No results to chart.")
        return None

    # Extract data for plotting
    x_values = [r.datasource_count or 0 for r in results]
    throughput = [r.throughput_rps for r in results]
    avg_latency = [r.avg_latency_ms for r in results]
    p95_latency = [r.p95_latency_ms for r in results]
    error_rates = [r.error_rate for r in results]

    # Create subplots
    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
    fig.suptitle("Benchmark Results", fontsize=16, fontweight="bold")

    # Throughput chart
    ax1.plot(x_values, throughput, "b-o", linewidth=2, markersize=6)
    ax1.set_xlabel(x_label)
    ax1.set_ylabel("Throughput (requests/second)")
    ax1.set_title("Throughput vs " + x_label)
    ax1.grid(True, alpha=0.3)

    # Latency chart
    ax2.plot(x_values, avg_latency, "g-o", label="Average", linewidth=2, markersize=6)
    ax2.plot(
        x_values, p95_latency, "r-o", label="95th Percentile", linewidth=2, markersize=6
    )
    ax2.set_xlabel(x_label)
    ax2.set_ylabel("Latency (ms)")
    ax2.set_title("Latency vs " + x_label)
    ax2.legend()
    ax2.grid(True, alpha=0.3)

    # Error rate chart
    ax3.plot(x_values, error_rates, "r-o", linewidth=2, markersize=6)
    ax3.set_xlabel(x_label)
    ax3.set_ylabel("Error Rate (%)")
    ax3.set_title("Error Rate vs " + x_label)
    ax3.grid(True, alpha=0.3)

    # Efficiency chart (for rate-limited tests with target rate)
    if results[0].target_files_per_second:
        efficiency = [
            (r.throughput_rps / r.target_files_per_second * 100)
            if r.target_files_per_second
            else 0
            for r in results
        ]
        ax4.plot(x_values, efficiency, "purple", marker="o", linewidth=2, markersize=6)
        ax4.set_xlabel(x_label)
        ax4.set_ylabel("Efficiency (%)")
        ax4.set_title("System Efficiency (Actual/Target Rate)")
    else:
        # For sequential tests, show min/max latency range
        min_latency = [r.min_latency_ms for r in results]
        max_latency = [r.max_latency_ms for r in results]
        ax4.fill_between(
            x_values, min_latency, max_latency, alpha=0.3, label="Min-Max Range"
        )
        ax4.plot(x_values, avg_latency, "g-o", label="Average", linewidth=2)
        ax4.set_xlabel(x_label)
        ax4.set_ylabel("Latency (ms)")
        ax4.set_title("Latency Range")
        ax4.legend()

    ax4.grid(True, alpha=0.3)

    plt.tight_layout()

    # Save chart
    saved_path = None
    if output_path:
        saved_path = output_path
    else:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        saved_path = f"benchmark_results_{timestamp}.png"

    plt.savefig(saved_path, dpi=300, bbox_inches="tight")
    print(f"\nChart saved as: {saved_path}")

    if show:
        plt.show()

    return saved_path
