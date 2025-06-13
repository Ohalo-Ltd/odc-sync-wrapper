package com.odc.speedcheck;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JobTaskFailedRetryTest {
    private PrintStream originalOut;
    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        JobTask.FAILED_RETRY_BACKOFF_MS = 1;
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        JobTask.FAILED_RETRY_BACKOFF_MS = 10_000L;
    }

    @Test
    void retriesWhenJobFails() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(202).setBody("{\"id\":\"job1\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"state\":\"FAILED\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"state\":\"FAILED\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"state\":\"FAILED\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"state\":\"FAILED\"}"));
            server.start();
            String baseUrl = server.url("/").toString().replaceAll("/$", "");
            DxrClient client = new DxrClient(baseUrl, "key");
            DatasourceContext.set(1);
            JobTask task = new JobTask(client, List.of(Path.of("samples/sample.txt")));
            task.call();
            String output = out.toString();
            assertTrue(output.contains("job job1 attempt 1 unsuccessful, retrying"), output);
            assertTrue(output.contains("job job1 attempt 2 unsuccessful, retrying"), output);
            assertTrue(output.contains("job job1 attempt 3 unsuccessful, retrying"), output);
            assertTrue(output.contains("job job1 FAILED"), output);
            assertEquals(5, server.getRequestCount());
        }
    }
}
