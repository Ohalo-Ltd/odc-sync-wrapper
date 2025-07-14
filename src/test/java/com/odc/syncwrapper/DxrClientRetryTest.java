package com.odc.syncwrapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DxrClientRetryTest {

    @Test
    void submitJobRetriesOnError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(202).setBody("{\"id\":\"job1\"}"));
            server.start();
            String baseUrl = server.url("/").toString().replaceAll("/$", "");
            DxrClient client = new DxrClient(baseUrl, "key");
            FileBatchingService.FileData fileData = new FileBatchingService.FileData(
                "sample.txt", "sample_guid.txt", "test content".getBytes(), "text/plain");
            String id = client.submitJob(1, List.of(fileData));
            assertEquals("job1", id);
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    void getJobStatusRetriesOnError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"state\":\"FINISHED\",\"datasourceScanId\":5}"));
            server.start();
            String baseUrl = server.url("/").toString().replaceAll("/$", "");
            DxrClient client = new DxrClient(baseUrl, "key");
            DxrClient.JobStatus status = client.getJobStatus(1, "job5");
            assertEquals("FINISHED", status.state());
            assertEquals(5, status.datasourceScanId());
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    void getTagIdsRetriesOnError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"hits\":{\"hits\":[{\"_source\":{\"dxr#tags\":[\"A\"]}}]}}"));
            server.start();
            String baseUrl = server.url("/").toString().replaceAll("/$", "");
            DxrClient client = new DxrClient(baseUrl, "key");
            DxrClient.ClassificationData data = client.getTagIds(1);
            assertEquals(List.of("A"), data.tags());
            assertEquals(3, server.getRequestCount());
        }
    }
}
