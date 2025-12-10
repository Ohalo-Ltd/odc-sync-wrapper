# ODC Sync Wrapper

`odc-sync-wrapper` is a REST API server that provides synchronous file classification using the asynchronous Data X-Ray API. It accepts individual file uploads via HTTP and returns the results of all metadata extractors that are configured on that organization in a synchronous API call, while internally optimizing Data X-Ray API usage through intelligent batching.

## Key Features

1. **Synchronous API**: Upload a file via `POST /classify-file` and receive classification results immediately
2. **Intelligent Batching**: Automatically batches multiple file uploads to optimize Data X-Ray API efficiency
3. **Name Resolution with Caching**: Automatically resolves tag IDs, metadata extractor IDs, and annotation IDs to human-readable names with 5-minute caching
4. **Client-side Retries**: All internal API calls to Data X-Ray use retries with exponential backoff
5. **Multi-datasource Load Balancing**: Distributes requests across multiple Data X-Ray on-demand classifiers
6. **Configurable Batching**: Control batch size and timeout via environment variables
7. **Comprehensive Classification Results**: Returns tags, metadata extractors, annotations, and categories with both IDs and names

## Configuration

The server is configured via environment variables:

- `DXR_BASE_URL`: Data X-Ray API base URL (must end with `/api`)
- `DXR_API_KEY`: Personal Access Token for authentication (optional, can be provided via Authorization header)
- `DXR_FIRST_ODC_DATASOURCE_ID`: ID of the first on-demand classifier datasource
- `DXR_ODC_DATASOURCE_COUNT`: Number of datasources to distribute load across
- `DXR_MAX_BATCH_SIZE`: Maximum files per batch (e.g., 5)
- `DXR_BATCH_INTERVAL_MS`: Maximum time to wait for additional files in milliseconds (e.g., 30000)
- `DXR_JOB_STATUS_POLL_INTERVAL_MS`: Job status polling interval in milliseconds (default: 1000, optional)
- `DXR_NAME_CACHE_EXPIRY_MS`: Cache expiration time for name lookups in milliseconds (default: 300000 = 5 minutes, optional)
- `DXR_PRELOAD_TAG_IDS`: Comma-separated list of tag IDs to preload into cache at startup (e.g., "1,2,3", optional)
- `DXR_PRELOAD_METADATA_EXTRACTOR_IDS`: Comma-separated list of metadata extractor IDs to preload (e.g., "10,20", optional)
- `DXR_PRELOAD_ANNOTATION_IDS`: Comma-separated list of annotation/data class IDs to preload (e.g., "100,101", optional)

### API Key Authentication

The server supports two methods for providing the Data X-Ray API key:

1. **Environment Variable**: Set `DXR_API_KEY` as an environment variable (traditional method)
2. **Authorization Header**: Pass the API key in the `Authorization: Bearer <token>` header with each request (new method)

If both are provided, the Authorization header takes precedence. If neither is provided, requests will fail with an error message.

## Building and Running

This project uses Maven with Spring Boot. Build the application with:

```bash
mvn package
```

This creates a fully executable Spring Boot JAR with all dependencies included.

### Running as Java Application

Set the required environment variables and run:

```bash
export DXR_BASE_URL="https://your-dxr-instance.com/api"
export DXR_API_KEY="your-personal-access-token"  # Optional if using Authorization header
export DXR_FIRST_ODC_DATASOURCE_ID="200"
export DXR_ODC_DATASOURCE_COUNT="10"
export DXR_MAX_BATCH_SIZE="5"
export DXR_BATCH_INTERVAL_MS="30000"
export DXR_JOB_STATUS_POLL_INTERVAL_MS="1000"  # Optional

java -jar target/odc-sync-wrapper-0.1.0-SNAPSHOT.jar
```

**Alternative: Run without DXR_API_KEY environment variable**

If you prefer to use Authorization headers exclusively:

```bash
export DXR_BASE_URL="https://your-dxr-instance.com/api"
export DXR_FIRST_ODC_DATASOURCE_ID="200"
export DXR_ODC_DATASOURCE_COUNT="10"
export DXR_MAX_BATCH_SIZE="5"
export DXR_BATCH_INTERVAL_MS="30000"
export DXR_JOB_STATUS_POLL_INTERVAL_MS="1000"  # Optional

java -jar target/odc-sync-wrapper-0.1.0-SNAPSHOT.jar
```

### Running with Docker

**With environment variable authentication:**
```bash
docker build -t odc-sync-wrapper .
docker run -p 8844:8844 \
  -e DXR_BASE_URL="https://your-dxr-instance.com/api" \
  -e DXR_API_KEY="your-personal-access-token" \
  -e DXR_FIRST_ODC_DATASOURCE_ID="200" \
  -e DXR_ODC_DATASOURCE_COUNT="10" \
  -e DXR_MAX_BATCH_SIZE="5" \
  -e DXR_BATCH_INTERVAL_MS="30000" \
  -e DXR_JOB_STATUS_POLL_INTERVAL_MS="1000" \
  odc-sync-wrapper
```

**With Authorization header authentication only:**
```bash
docker build -t odc-sync-wrapper .
docker run -p 8844:8844 \
  -e DXR_BASE_URL="https://your-dxr-instance.com/api" \
  -e DXR_FIRST_ODC_DATASOURCE_ID="200" \
  -e DXR_ODC_DATASOURCE_COUNT="10" \
  -e DXR_MAX_BATCH_SIZE="5" \
  -e DXR_BATCH_INTERVAL_MS="30000" \
  -e DXR_JOB_STATUS_POLL_INTERVAL_MS="1000" \
  odc-sync-wrapper
```

## API Usage

Once running, the server exposes a REST API on port 8844:

### Classify File

**Using Environment Variable Authentication:**
```bash
curl -X POST \
  http://localhost:8844/classify-file \
  -F "file=@your-document.txt"
```

**Using Authorization Header Authentication:**
```bash
curl -X POST \
  http://localhost:8844/classify-file \
  -H "Authorization: Bearer your-personal-access-token" \
  -F "file=@your-document.txt"
```

Response:
```json
{
  "filename": "your-document.txt",
  "status": "FINISHED",
  "extractedMetadata": [
    {
      "id": 1,
      "name": "SSN Detector",
      "value": "SSN"
    },
    {
      "id": 2,
      "name": "Credit Card Detector", 
      "value": "Credit Card"
    }
  ],
  "tags": [
    {
      "id": 10,
      "name": "Sensitive Data"
    },
    {
      "id": 11,
      "name": "Personal Information"
    }
  ],
  "category": "Sensitive Document",
  "annotations": [
    {
      "id": 25,
      "name": "SSN Pattern",
      "count": 3
    },
    {
      "id": 26,
      "name": "Credit Card Pattern",
      "count": 1
    }
  ]
}
```

### Health Check
```bash
curl http://localhost:8844/health
```


## Setting Up Data X-Ray

### Create On-Demand Classifiers

Before using the sync wrapper, you need to create on-demand classifier datasources in Data X-Ray. Create one datasource per level of concurrency you want to achieve.

You can create datasources in bulk using:

```bash
for i in {1..10}; do
  curl -k -X 'POST' \
    "${DXR_BASE_URL}/datasources/with-attributes" \
    -H "Authorization: Bearer ${DXR_API_KEY}" \
    -H "Content-Type: application/json" \
    -d '{"name":"odc-sync-wrapper-'$i'","datasourceConnectorTypeId":21,"status":"ENABLED","datasourceAttributesDTOList":[{"datasourceConnectorTypeAttributeId":93,"value":""}]}'
done
```

In the Data X-Ray UI:
1. (Optional) Create a settings profile and add all datasources if you need to configure annotators
2. (Optional) Create smart labels for the on-demand classifiers if you need to see classification tags

### Configuring on a single server Data X-Ray VM

#### Update NGINX to redirect all requests to the server

Note that this is a manual step that should be done any time you update your ohalo-ansible configuration files (e.g. when you are updating the Data X-Ray version).

On the machine where you run ansible, edit the nginx template.
```
vim ohalo-ansible/roles/nginx/templates/proxy_block.conf.j2
```

Under the `location` block for the `/health` endpoint, add the following lines
```
location /classify-file {
  proxy_pass  http://host.containers.internal:8844/classify-file;
}
```

Then restart the Data X-Ray server, with a full stop and start:
```
dxr-ansible --tags="stop" && dxr-ansible
```

#### Pull and run the `odc-sync-wrapper` docker container

This will be run as a docker container that is not managed by `dxr-ansible`. You should be familiar with pulling images, starting and stopping containers, and managing logs of docker containers.

As the `ohalo` user, run the following:

```
docker pull ghcr.io/ohalo-ltd/odc-sync-wrapper:latest
```

```
docker run -d -p 8844:8844 \
  -e DXR_BASE_URL="http://host.containers.internal:8081/api" \
  -e DXR_FIRST_ODC_DATASOURCE_ID="100" \
  -e DXR_ODC_DATASOURCE_COUNT="2" \
  -e DXR_MAX_BATCH_SIZE="5" \
  -e DXR_BATCH_INTERVAL_MS="1000" \
  -e DXR_JOB_STATUS_POLL_INTERVAL_MS="500" \
  ghcr.io/ohalo-ltd/odc-sync-wrapper:latest
```

Now you should be able to call the API endpoint `https://<your_dxr_address>/classify-file` with the API key from your DXR user.

## Docker Images and Versioning

The project uses automated Docker image building with version tagging:

### Available Images

Docker images are automatically built and published to GitHub Container Registry:

- **Latest**: `ghcr.io/ohalo-ltd/odc-sync-wrapper:latest` (from main branch)
- **Versioned**: `ghcr.io/ohalo-ltd/odc-sync-wrapper:1.1.1` (from git tags)
- **Branch**: `ghcr.io/ohalo-ltd/odc-sync-wrapper:branch-name` (from feature branches)

### Creating New Releases

To create a new versioned release:

```bash
# Create and push a version tag
git tag v1.2.0
git push origin v1.2.0
```

This automatically triggers a GitHub workflow that:
- Builds multi-platform Docker images (linux/amd64, linux/arm64)
- Creates a versioned tag (e.g., `1.2.0` from `v1.2.0`)
- Publishes to GitHub Container Registry
- Does **not** update the `latest` tag (only main branch updates `latest`)

### Using Specific Versions

```bash
# Use latest version
docker pull ghcr.io/ohalo-ltd/odc-sync-wrapper:latest

# Use specific version
docker pull ghcr.io/ohalo-ltd/odc-sync-wrapper:1.1.1

# Use development branch
docker pull ghcr.io/ohalo-ltd/odc-sync-wrapper:feature-branch
```

## Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests

Run integration tests against a live Data X-Ray server:

```bash
RUN_INTEGRATION_TESTS=true mvn -Dtest=ClassificationServerIntegrationTest test
```

This requires `DXR_BASE_URL` and `DXR_API_KEY` environment variables to be set.

### Live End-to-End Tests

Run comprehensive end-to-end tests with concurrent file uploads:

```bash
RUN_LIVE_TESTS=true mvn -Dtest=LiveEndToEndTest test
```

This test:
- Uploads 6 sample files simultaneously to test batching behavior
- Uses the live dev.dataxray.io environment
- Requires only `DXR_API_KEY` environment variable
- Tests the complete workflow from file upload to classification results

### Load Testing

The project includes Python scripts for comprehensive load testing and performance analysis.

**📖 See [README_PYTHON_SCRIPTS.md](README_PYTHON_SCRIPTS.md) for detailed documentation on:**
- Installation and setup instructions
- Available testing scripts (`load-test.py` and `load-test-suite.py`)
- Usage examples and command-line options
- Performance metrics and result interpretation
- Troubleshooting guide

## Data X-Ray API Endpoints Used

The application calls the Data X-Ray API at the following endpoints:

### Core Classification Endpoints

1. **Submit Job**
   - `POST {DXR_BASE_URL}/on-demand-classifiers/{datasource_id}/jobs`
   - Sends the file as multipart form data under `files` and includes an `Authorization: Bearer {DXR_API_KEY}` header
   - Returns a `202` response containing JSON with an `id` value for the job

2. **Check Job Status**
   - `GET {DXR_BASE_URL}/on-demand-classifiers/{datasource_id}/jobs/{job_id}`
   - Uses the same authorization header
   - Responds with JSON containing a `state` field. The job is finished when this value is `FINISHED`

3. **Search Results**
   - `POST {DXR_BASE_URL}/indexed-files/search`
   - Used to retrieve classification results including tags, metadata, annotations, and categories
   - Returns comprehensive classification data with IDs

### Name Resolution Endpoints (with 5-minute caching)

4. **Tag Name Lookup**
   - `GET {DXR_BASE_URL}/tags/{tag_id}`
   - Resolves tag IDs to human-readable names
   - Cached for 5 minutes to optimize performance

5. **Metadata Extractor Name Lookup**
   - `GET {DXR_BASE_URL}/metadata-extractors/{metadata_extractor_id}`
   - Resolves metadata extractor IDs to descriptive names
   - Cached for 5 minutes to optimize performance

6. **Annotation Name Lookup**
   - `GET {DXR_BASE_URL}/data-classes/{annotation_id}`
   - Resolves annotation class IDs to annotator names
   - Cached for 5 minutes to optimize performance

### Performance Optimizations

- **Intelligent Caching**: All name lookups are cached for 5 minutes to reduce API calls
- **Cache Preloading**: Optionally preload tag, metadata extractor, and annotation names at startup using `DXR_PRELOAD_*` environment variables to eliminate cold-start latency
- **Fallback Names**: If name resolution fails, fallback names like "Tag 123" are used
- **Concurrent Requests**: Name resolution requests are made concurrently for optimal performance
- **Cache Cleanup**: Expired cache entries are automatically cleaned up to prevent memory leaks
