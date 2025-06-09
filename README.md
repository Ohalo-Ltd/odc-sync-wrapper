# odc-speed-check

## Building

This project uses Maven. Ensure `DXR_BASE_URL` and `DXR_API_KEY` environment variables are set.
Run the application with:

```
mvn package
java -jar target/odc-speed-check-0.1.0-SNAPSHOT.jar <count>
```

### Live Integration Test

An additional test hits a real Data X-Ray server using the `DXR_BASE_URL` and
`DXR_API_KEY` environment variables. It is skipped unless the
`RUN_LIVE_TESTS` environment variable is set to `true`. Run it manually with:

```
RUN_LIVE_TESTS=true mvn -Dtest=SpeedCheckAppLiveServerTest test
```

## API Endpoints

The application calls the Data X-Ray API at two endpoints:

1. **Submit Job**
   - `POST {DXR_BASE_URL}/on-demand-classifiers/{datasource_id}/jobs`
   - Sends the file as multipart form data under `files` and includes an
     `Authorization: Bearer {DXR_API_KEY}` header.
   - Returns a `202` response containing JSON with an `id` value for the job.

2. **Check Job Status**
   - `GET {DXR_BASE_URL}/on-demand-classifiers/{datasource_id}/jobs/{job_id}`
   - Uses the same authorization header.
   - Responds with JSON containing a `state` field. The job is finished when
     this value is `FINISHED`.
