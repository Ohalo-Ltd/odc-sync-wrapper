# Python Testing Scripts

This directory contains Python scripts for testing and load testing the ODC Sync Wrapper API.

## Prerequisites

- Python 3.7 or higher
- Required Python packages (install with requirements.txt)

## Installation

```bash
# Install Python dependencies
pip install -r requirements.txt
```

## Available Scripts

### 1. Individual Load Test (`load-test.py`)

For targeted performance testing with detailed console output. Supports two testing modes:

#### Rate-Limited Mode
Sends files at a specified rate with concurrent processing (good for load testing):

```bash
# Basic rate-limited test - 5 files/second for 60 seconds
python load-test.py --rate-mode --files-per-second 5 --duration 60

# High-throughput test with custom server
python load-test.py --rate-mode \
  --files-per-second 20 \
  --duration 120 \
  --server-url http://your-server:8844 \
  --samples-dir samples/plain_txt
```

#### Sequential Mode
Sends files one after another, waiting for each to complete (good for debugging and baseline testing):

```bash
# Send 10 files sequentially, waiting for each to complete
python load-test.py --sequential 10

# Sequential test with custom server and samples directory
python load-test.py --sequential 25 \
  --server-url http://your-server:8844 \
  --samples-dir samples/plain_txt
```

#### Features:
- **Two Test Modes**: Rate-limited for load testing, sequential for debugging
- **Comprehensive Metrics**: Reports detailed latency statistics (avg, min, max, P95, P99) and throughput
- **Real-time Progress**: Shows progress updates during test execution
- **Enhanced Error Detection**: Properly detects API failures even when server returns HTTP 200 with `"status": "FAILED"`
- **Error Analysis**: Groups and reports error patterns with occurrence counts

### 2. Load Test Suite (`load-test-suite.py`)

For comprehensive multi-rate performance analysis across different load levels:

```bash
# Basic suite test with default settings
python load-test-suite.py

# Full suite with custom parameters
python load-test-suite.py \
  --server-url http://localhost:8844 \
  --api-key "your-api-key" \
  --samples-dir samples/plain_txt \
  --duration 180
```

#### Features:
- **Mixed File Types**: Automatically discovers and uses both `.txt` and `.pdf` sample files
- **API Key Authentication**: Supports Bearer token authentication for testing live instances  
- **Incremental Load Testing**: Tests with rates from 2-20 files/second
- **Comprehensive Metrics**: Measures throughput, latency (avg, P95, P99), and error rates
- **CSV Reports**: Generates detailed performance data in CSV format
- **Concurrent Testing**: Simulates realistic concurrent file upload scenarios

## Sample Files

The scripts require sample files for testing:
- **Text files**: Place in `samples/plain_txt/` directory
- **PDF files**: Place in `samples/pdf_ocr/` directory (for PDF OCR testing)

Sample files should be named with patterns like `sample1.txt`, `sample2.txt`, etc.

## Understanding Results

### Console Output
Both scripts provide detailed console output including:
- **Total/Successful/Failed Requests**: Request counts and success rates
- **Latency Statistics**: Average, minimum, maximum, 95th and 99th percentile latencies
- **Throughput**: Actual requests per second achieved
- **Error Analysis**: Detailed breakdown of any failures

### Performance Targets
- **Latency**: P95 < 5000ms for typical workloads
- **Throughput**: Should meet or exceed target files/second
- **Error Rate**: Should be 0% under normal operating conditions

### Error Detection
The scripts detect various failure conditions:
- HTTP errors (4xx, 5xx status codes)
- API authentication failures
- JSON responses with `"status": "FAILED"`
- Network timeouts and connection issues

## Examples

### Quick Performance Check
```bash
# Test basic functionality with 5 files
python load-test.py --sequential 5
```

### Load Testing
```bash
# Test sustained load of 10 files/second for 2 minutes
python load-test.py --rate-mode --files-per-second 10 --duration 120
```

### Comprehensive Analysis
```bash
# Run full test suite across multiple rates
python load-test-suite.py --duration 300
```

## Troubleshooting

### Common Issues

1. **Import Error**: Install required packages with `pip install -r requirements.txt`
2. **No Sample Files**: Ensure sample files exist in `samples/plain_txt/` directory  
3. **Connection Refused**: Verify the server is running on the specified URL
4. **Authentication Errors**: Check API key is valid (for live testing)
5. **High Error Rates**: May indicate server overload or configuration issues

### Debug Tips
- Use sequential mode for debugging individual requests
- Check server logs for detailed error information
- Verify environment variables are set correctly on the server
- Test with a single file first before running larger tests