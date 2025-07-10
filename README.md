# ODC Sync Wrapper

`odc-sync-wrapper` is a REST API server that provides synchronous file classification using the asynchronous Data X-Ray API. It accepts individual file uploads via HTTP and returns classification results synchronously, while internally optimizing Data X-Ray API usage through intelligent batching.

## Key Features

1. **Synchronous API**: Upload a file via `POST /classify-file` and receive classification results immediately
2. **Intelligent Batching**: Automatically batches multiple file uploads to optimize Data X-Ray API efficiency
3. **Client-side Retries**: All internal API calls to Data X-Ray use retries with exponential backoff
4. **Multi-datasource Load Balancing**: Distributes requests across multiple Data X-Ray on-demand classifiers
5. **Configurable Batching**: Control batch size and timeout via environment variables

## Configuration

The server is configured via environment variables:

- `DXR_BASE_URL`: Data X-Ray API base URL (must end with `/api`)
- `DXR_API_KEY`: Personal Access Token for authentication (optional, can be provided via Authorization header)
- `DXR_FIRST_ODC_DATASOURCE_ID`: ID of the first on-demand classifier datasource
- `DXR_ODC_DATASOURCE_COUNT`: Number of datasources to distribute load across
- `DXR_MAX_BATCH_SIZE`: Maximum files per batch (e.g., 5)
- `DXR_BATCH_INTERVAL_SEC`: Maximum time to wait for additional files (e.g., 30)

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
export DXR_BATCH_INTERVAL_SEC="30"

java -jar target/odc-sync-wrapper-0.1.0-SNAPSHOT.jar
```

**Alternative: Run without DXR_API_KEY environment variable**

If you prefer to use Authorization headers exclusively:

```bash
export DXR_BASE_URL="https://your-dxr-instance.com/api"
export DXR_FIRST_ODC_DATASOURCE_ID="200"
export DXR_ODC_DATASOURCE_COUNT="10" 
export DXR_MAX_BATCH_SIZE="5"
export DXR_BATCH_INTERVAL_SEC="30"

java -jar target/odc-sync-wrapper-0.1.0-SNAPSHOT.jar
```

### Running with Docker

**With environment variable authentication:**
```bash
docker build -t odc-sync-wrapper .
docker run -p 8080:8080 \
  -e DXR_BASE_URL="https://your-dxr-instance.com/api" \
  -e DXR_API_KEY="your-personal-access-token" \
  -e DXR_FIRST_ODC_DATASOURCE_ID="200" \
  -e DXR_ODC_DATASOURCE_COUNT="10" \
  -e DXR_MAX_BATCH_SIZE="5" \
  -e DXR_BATCH_INTERVAL_SEC="30" \
  odc-sync-wrapper
```

**With Authorization header authentication only:**
```bash
docker build -t odc-sync-wrapper .
docker run -p 8080:8080 \
  -e DXR_BASE_URL="https://your-dxr-instance.com/api" \
  -e DXR_FIRST_ODC_DATASOURCE_ID="200" \
  -e DXR_ODC_DATASOURCE_COUNT="10" \
  -e DXR_MAX_BATCH_SIZE="5" \
  -e DXR_BATCH_INTERVAL_SEC="30" \
  odc-sync-wrapper
```

## API Usage

Once running, the server exposes a REST API on port 8080:

### Classify File

**Using Environment Variable Authentication:**
```bash
curl -X POST \
  http://localhost:8080/classify-file \
  -F "file=@your-document.txt"
```

**Using Authorization Header Authentication:**
```bash
curl -X POST \
  http://localhost:8080/classify-file \
  -H "Authorization: Bearer your-personal-access-token" \
  -F "file=@your-document.txt"
```

Response:
```json
{
  "filename": "your-document.txt",
  "status": "FINISHED", 
  "tags": ["tag1", "tag2"]
}
```

### Health Check
```bash
curl http://localhost:8080/health
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

## Data X-Ray API Endpoints used.

The application calls the Data X-Ray API at three endpoints:

1. **Submit Job**
   - `POST {DXR_BASE_URL}/on-demand-classifiers/{datasource_id}/jobs`
   - Sends the file as multipart form data under `files` and includes an `Authorization: Bearer {DXR_API_KEY}` header.
   - Returns a `202` response containing JSON with an `id` value for the job.

2. **Check Job Status**
   - `GET {DXR_BASE_URL}/on-demand-classifiers/{datasource_id}/jobs/{job_id}`
   - Uses the same authorization header.
   - Responds with JSON containing a `state` field. The job is finished when this value is `FINISHED`.
3. **Search**
   - Used to pull back the label of the file that was submitted.
