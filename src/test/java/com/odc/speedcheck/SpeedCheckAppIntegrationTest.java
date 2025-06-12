package com.odc.speedcheck;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpeedCheckAppIntegrationTest {
    /**
     * This test runs the SpeedCheckApp with a mock server to simulate the DXR API.
     * It checks if the application can start, submit jobs, and complete them successfully.
     */
    @Test
    void applicationRunsWithEnvVars() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicInteger counter = new AtomicInteger();
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {

                    if (request.getMethod().equals("POST") && request.getPath().contains("on-demand-classifiers")) {
                        int id = counter.incrementAndGet();
                        return new MockResponse()
                                .setResponseCode(202)
                                .setBody("{\"id\":\"job" + id + "\"}");
                    }
                    if (request.getMethod().equals("POST") && request.getPath().contains("indexed-files/search")) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setBody("{\"hits\":{\"hits\":[{\"_source\":{\"dxr#tags\":[1]}}]}}" );
                    }
                    if (request.getMethod().equals("GET")) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setBody("{\"state\":\"FINISHED\",\"datasourceScanId\":1}");
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            server.start();
            String baseUrl = server.url("/").toString().replaceAll("/$", "");
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-cp", System.getProperty("java.class.path"),
                    "com.odc.speedcheck.SpeedCheckApp", "2", "1000", "100", "2", "2");
            pb.environment().put("DXR_BASE_URL", baseUrl);
            pb.environment().put("DXR_API_KEY", "test-key");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                is.transferTo(baos);
            }
            int exit = process.waitFor();
            String output = baos.toString(StandardCharsets.UTF_8);
            assertEquals(0, exit, output);
            assertTrue(output.contains("file:samples/sample1.txt completed with state FINISHED and tag_ids:1"), output);
            assertTrue(output.contains("All jobs completed"), output);
            assertTrue(output.contains("Total files"), output);
            assertTrue(output.contains("Average latency"), output);
            assertTrue(output.contains("files/second"), output);
        }
    }
}
