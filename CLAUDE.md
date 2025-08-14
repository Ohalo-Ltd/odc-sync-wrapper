# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Common Commands

### Build and Package
```bash
mvn package
```

### Run Tests
```bash
mvn test
```

### Run Live Integration Tests
```bash
RUN_INTEGRATION_TESTS=true mvn -Dtest=ClassificationServerIntegrationTest test
```

### Run Live End-to-End Tests
```bash
RUN_LIVE_TESTS=true mvn -Dtest=LiveEndToEndTest test
```

This test uploads 6 sample files simultaneously to test the real batching functionality against the live Data X-Ray API. It requires:
- `DXR_API_KEY` environment variable to be set
- Uses hardcoded test configuration for dev.dataxray.io
- Tests concurrent file uploads and batching behavior

### Run Application as Server
```bash
java -jar target/odc-sync-wrapper-0.1.0-SNAPSHOT.jar
```

### Run with Docker
```bash
docker build -t odc-sync-wrapper .
docker run -p 8844:8844 \
  -e DXR_BASE_URL="https://your-dxr-instance.com/api" \
  -e DXR_API_KEY="your-api-key" \
  -e DXR_FIRST_ODC_DATASOURCE_ID="200" \
  -e DXR_ODC_DATASOURCE_COUNT="10" \
  -e DXR_MAX_BATCH_SIZE="5" \
  -e DXR_BATCH_INTERVAL_MS="30000" \
  -e DXR_JOB_STATUS_POLL_INTERVAL_MS="1000" \
  odc-sync-wrapper
```

## Development Guidelines

This repository contains a Java 21 Spring Boot REST API server built with Maven. The project has a small test suite under `src/test/java` using JUnit 5. Use the following rules when contributing:

* Use four spaces for indentation in Java source files.
* Run `mvn test` before every commit to ensure tests pass. Don't use the `-q` option.
* Add new unit tests for any new functionality.
* The application expects environment variables: `DXR_BASE_URL`, `DXR_API_KEY`, `DXR_FIRST_ODC_DATASOURCE_ID`, `DXR_ODC_DATASOURCE_COUNT`, `DXR_MAX_BATCH_SIZE`, `DXR_BATCH_INTERVAL_MS`, and optionally `DXR_JOB_STATUS_POLL_INTERVAL_MS`.
* Keep sample data in `samples/plain_txt/sample.txt` so tests continue to work.

## Architecture

This is the **ODC Sync Wrapper** - a REST API server for the Data X-Ray on-demand classification system. It provides a synchronous API wrapper around the asynchronous Data X-Ray API with intelligent batching.

### Core Components

- **ClassificationServerApp**: Main Spring Boot application class
- **ClassificationController**: REST controller with `/classify-file` endpoint
- **FileBatchingService**: Service that batches files and manages synchronous responses
- **DxrClient**: HTTP client for Data X-Ray API interactions with retry logic
- **DatasourceContext**: Thread-local storage for datasource IDs

### Key Design Patterns

1. **File Batching**: Accumulates files for `DXR_BATCH_INTERVAL_MS` milliseconds or until `DXR_MAX_BATCH_SIZE` is reached
2. **Synchronous API**: Each `/classify-file` call returns a synchronous response despite internal batching
3. **Datasource Round-Robin**: Distributes batches across configured datasources
4. **Retry Logic**: 
   - HTTP-level retries with exponential backoff using Failsafe library
   - Application-level retries for FAILED job status (3 attempts with 10s backoff)

### API Endpoints

- **POST /classify-file**: Accepts a single file for classification
- **GET /health**: Health check endpoint

### Data X-Ray API Integration

The application integrates with three Data X-Ray endpoints:
- Submit Job: `POST /on-demand-classifiers/{datasource_id}/jobs`
- Check Status: `GET /on-demand-classifiers/{datasource_id}/jobs/{job_id}`
- Search Results: `POST /indexed-files/search`

### Environment Variables

Required:
- `DXR_BASE_URL`: Base URL for Data X-Ray API (must include `/api` suffix)
- `DXR_API_KEY`: Personal Access Token for authentication
- `DXR_FIRST_ODC_DATASOURCE_ID`: First datasource ID to use (e.g., 200)
- `DXR_ODC_DATASOURCE_COUNT`: Number of datasources to rotate through (e.g., 10)
- `DXR_MAX_BATCH_SIZE`: Maximum files per batch (e.g., 5)
- `DXR_BATCH_INTERVAL_MS`: Batch timeout in milliseconds (e.g., 30000)

Optional:
- `DXR_JOB_STATUS_POLL_INTERVAL_MS`: Job status polling interval in milliseconds (default: 1000)
- `RUN_LIVE_TESTS`: Set to "true" to enable live integration tests

### Sample Data

The `samples/plain_txt/` directory contains 100 sample text files (sample1.txt through sample100.txt) used for testing. Files are cycled through when the test requires more files than available samples. The `samples/pdf_ocr/` directory contains PDF files used for OCR testing.

## Technology Stack

- Java 21
- Spring Boot 3.2.0 for REST API framework
- Maven build system
- OkHttp for HTTP client
- Failsafe for retry logic
- JUnit 5 for testing
- org.json for JSON parsing