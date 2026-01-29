# Async Benchmarking Tool

This directory contains a CLI tool for benchmarking the DXR On-Demand Classifier async API, with support for file size sweeps and detailed metrics collection.

## Installation

```bash
cd benchmarking-async
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Quick Start

All commands should be run from the `benchmarking-async` directory with the virtual environment activated:

```bash
cd benchmarking-async
source .venv/bin/activate
```

### Single Directory Test

Test against one directory of sample files:

```bash
python3 __main__.py single \
    --server-url https://dev.dataxray.io \
    --datasource-id 100 \
    --token $DXR_API_KEY \
    --samples-dir samples_dir/100K
```

### File Size Sweep

Run a sweep across multiple directories (different file sizes):

```bash
python3 __main__.py sweep \
    --server-url https://dev.dataxray.io \
    --datasource-id 100 \
    --token $DXR_API_KEY \
    --directories samples_dir/100K,samples_dir/1GB,samples_dir/2GB \
    --output results.tsv \
    --chart results.png
```

## Commands

### `single` - Single Directory Test

Run a load test against one directory of sample files.

```bash
python3 __main__.py single [options]
```

#### Required Arguments

| Argument | Description |
|----------|-------------|
| `--server-url` | DXR server URL (e.g., https://dev.dataxray.io) |
| `--datasource-id` | On-demand classifier datasource ID |
| `--token` | API bearer token |
| `--samples-dir` | Directory containing sample files to test |

#### Optional Arguments

| Argument | Default | Description |
|----------|---------|-------------|
| `--files-per-request` | 1 | Number of files to bundle in each API request |
| `--concurrency` | 1 | Number of requests to run in parallel (1 = sequential) |
| `--repeat` | 1 | Repeat sample files N times to increase load |
| `--timeout` | 1200 | Job timeout in seconds |
| `--poll-interval` | 5 | Job polling interval in seconds |
| `--insecure-ssl` | false | Disable TLS certificate verification |
| `--output` | - | Output TSV file path for results |
| `--chart` | - | Output chart PNG path |
| `--no-chart` | false | Skip chart generation |
| `--quiet` | false | Suppress progress output |

#### Examples

```bash
# Basic test
python3 __main__.py single \
    --server-url https://dev.dataxray.io \
    --datasource-id 100 \
    --token $DXR_API_KEY \
    --samples-dir samples_dir/100K

# Test with batching (5 files per request)
python3 __main__.py single \
    --server-url https://dev.dataxray.io \
    --datasource-id 100 \
    --token $DXR_API_KEY \
    --samples-dir samples_dir/1GB \
    --files-per-request 5

# Parallel test with 3 concurrent requests
python3 __main__.py single \
    --server-url https://dev.dataxray.io \
    --datasource-id 100 \
    --token $DXR_API_KEY \
    --samples-dir samples_dir/100K \
    --concurrency 3 \
    --repeat 10
```

### `sweep` - File Size Sweep

Run a benchmark sweep across multiple directories (representing different file sizes).

```bash
python3 __main__.py sweep [options]
```

#### Required Arguments

| Argument | Description |
|----------|-------------|
| `--server-url` | DXR server URL |
| `--datasource-id` | On-demand classifier datasource ID |
| `--token` | API bearer token |
| `--directories` | Comma-separated list of directories (in order) |

*OR*

| Argument | Description |
|----------|-------------|
| `--directories-file` | File containing directories to sweep (one per line) |

#### Optional Arguments

| Argument | Default | Description |
|----------|---------|-------------|
| `--files-per-request` | 1 | Number of files per API request |
| `--concurrency` | 1 | Parallel requests (1 = sequential) |
| `--repeat` | 1 | Repeat sample files N times |
| `--timeout` | 1200 | Job timeout in seconds |
| `--poll-interval` | 5 | Job polling interval in seconds |
| `--insecure-ssl` | false | Disable TLS verification |
| `--output` | - | Output TSV file path |
| `--chart` | auto | Output chart PNG path |
| `--no-chart` | false | Skip chart generation |
| `--quiet` | false | Suppress progress output |

#### Examples

```bash
# Basic sweep
python3 __main__.py sweep \
    --server-url https://dev.dataxray.io \
    --datasource-id 100 \
    --token $DXR_API_KEY \
    --directories samples_dir/100K,samples_dir/1GB,samples_dir/2GB

# Sweep with batching and exports
python3 __main__.py sweep \
    --server-url https://dev.dataxray.io \
    --datasource-id 100 \
    --token $DXR_API_KEY \
    --directories samples_dir/100K,samples_dir/500MB,samples_dir/1GB \
    --files-per-request 5 \
    --repeat 3 \
    --output results.tsv \
    --chart results.png

# Using a directories file
python3 __main__.py sweep \
    --server-url https://dev.dataxray.io \
    --datasource-id 100 \
    --token $DXR_API_KEY \
    --directories-file sweep_dirs.txt
```

## Sample Data Generation

Use `initialize_sample_data.py` to generate sample files of specific sizes by repeating content from a source file.

### Usage

```bash
python3 initialize_sample_data.py <size> <source_file> [-o output_dir]
```

### Examples

```bash
python3 initialize_sample_data.py 1K examples_dir/plain/sample.txt
python3 initialize_sample_data.py 10K examples_dir/plain/sample.txt
python3 initialize_sample_data.py 100K examples_dir/plain/sample.txt
python3 initialize_sample_data.py 1M examples_dir/plain/sample.txt
python3 initialize_sample_data.py 10M examples_dir/plain/sample.txt
python3 initialize_sample_data.py 100M examples_dir/plain/sample.txt
python3 initialize_sample_data.py 1G examples_dir/plain/sample.txt
```

### Supported Size Formats

- `K` or `KB` - Kilobytes (1024 bytes)
- `M` or `MB` - Megabytes (1024² bytes)
- `G` or `GB` - Gigabytes (1024³ bytes)

## Output Formats

### Console Table

```
====================================================================================================
                                   FILE SIZE SWEEP RESULTS
====================================================================================================
Size    Size_MB  Files  Batch  Jobs  Success  Failed  Timeout  Error%  Upload_s  Job_s  Files/s  MB/s
100K       0.10     50      5     10       10       0        0    0.00      0.05   2.34     4.27  0.43
1GB     1024.00     25      5      5        5       0        0    0.00      0.89  45.67     0.55  5.62
2GB     2048.00     10      5      2        2       0        0    0.00      1.83  98.23     0.10  2.09
====================================================================================================
TSV OUTPUT (copy to spreadsheet):
====================================================================================================
Size    Size_MB Files   Batch   Jobs    Success Failed  Timeout Error%  Upload_s  Job_s   Files/s MB/s
...
```

### Charts

The tool generates a 4-subplot PNG chart showing:

1. **Throughput (MB/s) vs File Size** - How throughput varies with file size
2. **Job Duration vs File Size** - Average and P95 job completion times
3. **Upload Time vs File Size** - Upload time in seconds (scales with file size)
4. **Error Rate vs File Size** - Identifies if larger files cause more failures

## Metrics Collected

### Per-Job Metrics

- Upload time (time to upload files and receive job_id)
- Job duration (total time from submit to completion)
- Poll count
- Final state (FINISHED, FAILED, TIMEOUT)

### Aggregate Metrics

- Upload time: avg, min, max, p95, p99 (seconds)
- Job duration: avg, min, max, p95, p99 (seconds)
- Throughput: files/second, MB/second
- Error rate (%)
- Average polls per job

## Directory Structure

```
benchmarking-async/
├── __init__.py
├── __main__.py                    # Entry point
├── requirements.txt
├── README.md
├── initialize_sample_data.py      # Sample data generator
│
├── cli/
│   ├── single.py                  # Single directory test command
│   └── sweep.py                   # Multi-directory sweep command
│
├── core/
│   ├── models.py                  # Data models
│   ├── async_tester.py            # Main async testing engine
│   └── sample_loader.py           # File loading utility
│
├── results/
│   ├── aggregator.py              # Table formatting, TSV export
│   └── charts.py                  # Chart generation
│
├── sweeps/
│   └── file_size_sweep.py         # Sweep orchestrator
│
└── samples_dir/                       # Pre-generated sample files
    ├── plain/                     # Source data
    ├── 100K/
    ├── 1GB/
    ├── 2GB/
    └── ...
```

## How Concurrency Works

- `--concurrency 1` (default): **Sequential** - each request completes fully (upload + job finishes) before the next starts
- `--concurrency N`: **Parallel** - up to N requests run simultaneously, useful for stress testing
