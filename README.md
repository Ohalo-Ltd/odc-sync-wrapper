# odc-speed-check

## Building and running speed test

This project uses Maven. Ensure `DXR_BASE_URL` and `DXR_API_KEY` environment variables are set.
- `DXR_BASE_URL` should begin with `https://` and end with `/api`
- `DXR_API_KEY` is the PAT from the console.

Run the application with:

```
mvn package
java -jar target/odc-speed-check-0.1.0-SNAPSHOT.jar <jobCount> <timeInBetweenJobs> <firstDatasourceId> <datasourceCount> <batchSize>
```

So `java -jar target/odc-speed-check-0.1.0-SNAPSHOT.jar 100 1000 200 10 5` will send 100 jobs, 1 every second, to datasources with ids 200 to 210. Each job will include 5 files and it will use a fixed pool so each datasource will probably get 10 jobs each.

At the end you will see a summary similar to:

```
All jobs completed in 125 seconds.
Total files 500
Average latency 11.57 seconds
Throughput 4.00 files/second
```


## Initializing the DXR


### Create your on-demand classifiers

The DXR server must have the on demand classifier datasources created. Create one on-demand classifier per concurrency that you would like to have.

You can initialize datasources in bulk using the following:

```
for i in {1..200}; do
  curl -k -X 'POST' \
    "${DXR_BASE_URL}/datasources/with-attributes" \
    -H "Authorization: Bearer ${DXR_API_KEY}" \
    -H "Content-Type: application/json" \
    -d '{"name":"odc-xxx","datasourceConnectorTypeId":21,"status":"ENABLED","datasourceAttributesDTOList":[{"datasourceConnectorTypeAttributeId":93,"value":""}]}'
done
```

In the UI, 
1. (optional) create a setting profile and then bulk-add all 200 datasources to the settings profile if you need to change the annotators
2. (optional) create a smart label of the on-demand classifier if you need to see tags.


# More information

## Live Integration Test

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
3. **Search**
   - Used to pull back the label of the file that was submitted.
