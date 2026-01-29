"""Chart generation for async benchmarking results."""

from datetime import datetime
from typing import List, Optional

# Support both relative and absolute imports
try:
    from ..core.models import AsyncTestResult
except (ImportError, ValueError):
    from core.models import AsyncTestResult


def generate_sweep_charts(
    results: List[AsyncTestResult],
    output_path: Optional[str] = None,
    show: bool = True,
) -> Optional[str]:
    """
    Generate charts for file size sweep results.

    Args:
        results: List of AsyncTestResult from sweep stages
        output_path: Path to save PNG file (optional)
        show: Whether to display the chart

    Returns:
        Path to saved chart file, or None if not saved
    """
    try:
        import matplotlib.pyplot as plt
        import matplotlib.ticker as ticker
    except ImportError:
        print("Warning: matplotlib not installed. Skipping chart generation.")
        print("Install with: pip install matplotlib")
        return None

    if not results:
        print("No results to chart.")
        return None

    # Extract data for plotting
    x_values = [r.file_size_bytes for r in results]
    x_labels = [r.file_size_label for r in results]

    # Create figure with 4 subplots
    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
    fig.suptitle("File Size Sweep Results - Async API Benchmark", fontsize=16, fontweight="bold")

    # Chart 1: Throughput (MB/s) vs File Size
    throughput_mb = [r.throughput_mb_per_second for r in results]
    ax1.plot(range(len(results)), throughput_mb, 'b-o', linewidth=2, markersize=8)
    ax1.set_xlabel("File Size", fontsize=11)
    ax1.set_ylabel("Throughput (MB/s)", fontsize=11)
    ax1.set_title("Throughput vs File Size", fontsize=12, fontweight="bold")
    ax1.set_xticks(range(len(results)))
    ax1.set_xticklabels(x_labels, rotation=45, ha="right")
    ax1.grid(True, alpha=0.3)
    ax1.set_ylim(bottom=0)

    # Add value labels
    for i, v in enumerate(throughput_mb):
        ax1.annotate(f"{v:.2f}", (i, v), textcoords="offset points", xytext=(0, 5), ha="center", fontsize=9)

    # Chart 2: Job Duration vs File Size
    job_avg = [r.job_avg_seconds for r in results]
    job_p95 = [r.job_p95_seconds for r in results]
    ax2.plot(range(len(results)), job_avg, 'g-o', linewidth=2, markersize=8, label='Average')
    ax2.plot(range(len(results)), job_p95, 'r-o', linewidth=2, markersize=8, label='P95')
    ax2.set_xlabel("File Size", fontsize=11)
    ax2.set_ylabel("Job Duration (seconds)", fontsize=11)
    ax2.set_title("Job Duration vs File Size", fontsize=12, fontweight="bold")
    ax2.set_xticks(range(len(results)))
    ax2.set_xticklabels(x_labels, rotation=45, ha="right")
    ax2.legend(loc="upper left")
    ax2.grid(True, alpha=0.3)
    ax2.set_ylim(bottom=0)

    # Chart 3: Upload Time vs File Size (converted to seconds)
    upload_avg = [r.submit_avg_ms / 1000 for r in results]
    upload_p95 = [r.submit_p95_ms / 1000 for r in results]
    ax3.plot(range(len(results)), upload_avg, 'g-o', linewidth=2, markersize=8, label='Average')
    ax3.plot(range(len(results)), upload_p95, 'r-o', linewidth=2, markersize=8, label='P95')
    ax3.set_xlabel("File Size", fontsize=11)
    ax3.set_ylabel("Upload Time (seconds)", fontsize=11)
    ax3.set_title("Upload Time vs File Size", fontsize=12, fontweight="bold")
    ax3.set_xticks(range(len(results)))
    ax3.set_xticklabels(x_labels, rotation=45, ha="right")
    ax3.legend(loc="upper left")
    ax3.grid(True, alpha=0.3)
    ax3.set_ylim(bottom=0)

    # Chart 4: Error Rate vs File Size
    error_rates = [r.error_rate for r in results]
    colors = ['green' if e == 0 else 'red' for e in error_rates]
    bars = ax4.bar(range(len(results)), error_rates, color=colors, alpha=0.7)
    ax4.set_xlabel("File Size", fontsize=11)
    ax4.set_ylabel("Error Rate (%)", fontsize=11)
    ax4.set_title("Error Rate vs File Size", fontsize=12, fontweight="bold")
    ax4.set_xticks(range(len(results)))
    ax4.set_xticklabels(x_labels, rotation=45, ha="right")
    ax4.grid(True, alpha=0.3, axis='y')
    ax4.set_ylim(0, max(max(error_rates) * 1.2, 5))  # At least show 0-5%

    # Add value labels on bars
    for i, v in enumerate(error_rates):
        ax4.annotate(f"{v:.1f}%", (i, v), textcoords="offset points", xytext=(0, 3), ha="center", fontsize=9)

    # Adjust layout
    plt.tight_layout()
    plt.subplots_adjust(top=0.93)

    # Save if path provided
    saved_path = None
    if output_path:
        plt.savefig(output_path, dpi=150, bbox_inches='tight')
        saved_path = output_path
        print(f"\nChart saved to: {output_path}")
    elif show:
        # Generate default filename
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        default_path = f"sweep_results_{timestamp}.png"
        plt.savefig(default_path, dpi=150, bbox_inches='tight')
        saved_path = default_path
        print(f"\nChart saved to: {default_path}")

    # Show if requested
    if show:
        try:
            plt.show()
        except Exception:
            # Non-interactive backend
            pass

    plt.close()
    return saved_path


def generate_single_test_chart(
    result: AsyncTestResult,
    output_path: Optional[str] = None,
    show: bool = True,
) -> Optional[str]:
    """
    Generate a chart for a single test result showing job durations.

    Args:
        result: AsyncTestResult from a single test
        output_path: Path to save PNG file (optional)
        show: Whether to display the chart

    Returns:
        Path to saved chart file, or None if not saved
    """
    try:
        import matplotlib.pyplot as plt
    except ImportError:
        print("Warning: matplotlib not installed. Skipping chart generation.")
        return None

    if not result.raw_job_results:
        print("No raw job results available for charting.")
        return None

    # Extract data
    job_results = result.raw_job_results
    batch_ids = [r.batch_id for r in job_results]
    submit_latencies = [r.submit_latency_ms for r in job_results]
    job_durations = [r.job_duration_seconds for r in job_results]
    successes = [r.success for r in job_results]

    # Create figure with 2 subplots
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))
    fig.suptitle(f"Single Test Results - {result.file_size_label}", fontsize=14, fontweight="bold")

    # Chart 1: Submit Latency per batch
    colors1 = ['green' if s else 'red' for s in successes]
    ax1.bar(batch_ids, submit_latencies, color=colors1, alpha=0.7)
    ax1.set_xlabel("Batch ID", fontsize=11)
    ax1.set_ylabel("Submit Latency (ms)", fontsize=11)
    ax1.set_title("Submit Latency per Batch", fontsize=12)
    ax1.grid(True, alpha=0.3, axis='y')

    # Chart 2: Job Duration per batch
    colors2 = ['green' if s else 'red' for s in successes]
    ax2.bar(batch_ids, job_durations, color=colors2, alpha=0.7)
    ax2.set_xlabel("Batch ID", fontsize=11)
    ax2.set_ylabel("Job Duration (seconds)", fontsize=11)
    ax2.set_title("Job Duration per Batch", fontsize=12)
    ax2.grid(True, alpha=0.3, axis='y')

    # Adjust layout
    plt.tight_layout()
    plt.subplots_adjust(top=0.88)

    # Save if path provided
    saved_path = None
    if output_path:
        plt.savefig(output_path, dpi=150, bbox_inches='tight')
        saved_path = output_path
        print(f"\nChart saved to: {output_path}")

    if show:
        try:
            plt.show()
        except Exception:
            pass

    plt.close()
    return saved_path
