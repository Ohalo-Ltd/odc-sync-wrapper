# odc-sync-wrapper

`odc-sync-wrapper` is a server which provides a synchronous API call for on-demand classification, using the asynchronous API interface of the Data X-Ray. Internally, it uses several different technicals, to optimize the interfaces with Data X-Ray API calls. 
1. **Client-side retries:** Every internal API call to Data X-Ray uses retries, with a backoff function. There is also 3 retries if the job status returns as FAILED.
2. **Multi-threading:** Every on-demand classifier inside Data X-Ray is single threaded, so concurrency is achieved by creating multiple on-demand classifiers, and distributing load to these on-demand classifiers. So when this project calls the Data X-Ray API, it does so using a fixed thread pool. In Java, this is easily achieved using a `FixedThreadPool`.
3. **Batching:** Uploading 10 files per second is perhaps the most intensive part of the process and batching will allow the server to handle many more files. If you are expecting to upload multpile files per second, it is highly recommended to collect these files in a batch, and then only send them together in one batch for classification, e.g. 1 batch per second.

This tool allows you specify
1. the total number of files you want to upload
2. the number of files in a batch job
3. the wait time in between batches
4. the datasource id of the first on-demand classifier
5. the number of on-demand classifiers you are using.

It will use sample files from the `samples/` directory in this repo. When the program completes, you should see a summary of the different results.
 
## Building and running speed test

This project uses Maven. Ensure `DXR_BASE_URL` and `DXR_API_KEY` environment variables are set.
- `DXR_BASE_URL` should begin with `https://` and end with `/api`
- `DXR_API_KEY` is the PAT from the console.

Run the application with:

```
mvn package
java -jar target/odc-speed-check-0.1.0-SNAPSHOT.jar <fileCount> <batchSize> <timeInBetweenJobs> <firstDatasourceId> <datasourceCount>
```

So `java -jar target/odc-speed-check-0.1.0-SNAPSHOT.jar 500 5 1000 200 10` will send 500 files total, 5 files per job, with a 1000ms wait in between jobs to datasources with ids 200 to 210. It will create 100 jobs int total across those 10 different datasources. 

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
for i in {1..50}; do
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

An additional test hits a real Data X-Ray server using the `DXR_BASE_URL` and `DXR_API_KEY` environment variables. It is skipped unless the `RUN_LIVE_TESTS` environment variable is set to `true`. Run it manually with:

```
RUN_LIVE_TESTS=true mvn -Dtest=SpeedCheckAppLiveServerTest test
```

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
