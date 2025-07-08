# odc-sync-wrapper

`odc-sync-wrapper` exposes a synchronous HTTP API for classifying files with the
Data X-Ray.  Internally it batches uploaded files and uses the asynchronous
Data X-Ray endpoints.  Batching improves throughput when many files are
uploaded at once.

## Building and running

The application requires several environment variables:

- `DXR_BASE_URL` – the base URL of the Data X-Ray server (end with `/api`)
- `DXR_API_KEY` – API key for authentication
- `DXR_FIRST_ODC_DATASOURCE_ID` – id of the first on‑demand classifier
- `DXR_ODC_DATASOURCE_COUNT` – number of on‑demand classifiers to use
- `DXR_MAX_BATCH_SIZE` – maximum number of files per batch
- `DXR_BATCH_INTERVAL_SEC` – interval in seconds before a batch is sent

Build the jar with Maven:

```bash
mvn package
```

Run the server:

```bash
java -jar target/odc-sync-wrapper-0.1.0-SNAPSHOT.jar
```

The server exposes one endpoint:

```
POST /classify-file
```

Send a single file as multipart form data under the `file` field.  The response
is a JSON array of tag ids returned by the Data X-Ray.

## Docker

A `Dockerfile` is provided.  Build the image with

```bash
docker build -t odc-sync-wrapper .
```

and run it with the required environment variables.

## Testing with a live server

To run the optional live integration test set the `RUN_LIVE_TESTS` environment
variable to `true` and ensure `DXR_BASE_URL` and `DXR_API_KEY` are set:

```bash
RUN_LIVE_TESTS=true mvn -Dtest=SyncWrapperServerLiveServerTest test
```
