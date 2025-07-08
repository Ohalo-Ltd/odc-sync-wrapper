package com.odc.syncwrapper;

import okhttp3.*;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SyncWrapperServerIntegrationTest {
    @Test
    void serverProcessesFile() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicInteger counter = new AtomicInteger();
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getMethod().equals("POST") && request.getPath().contains("on-demand-classifiers")) {
                        int id = counter.incrementAndGet();
                        return new MockResponse().setResponseCode(202).setBody("{\"id\":\"job" + id + "\"}");
                    }
                    if (request.getMethod().equals("POST") && request.getPath().contains("indexed-files/search")) {
                        return new MockResponse().setResponseCode(200)
                                .setBody("{\"hits\":{\"hits\":[{\"_source\":{\"dxr#tags\":[1]}}]}}" );
                    }
                    if (request.getMethod().equals("GET")) {
                        return new MockResponse().setResponseCode(200)
                                .setBody("{\"state\":\"FINISHED\",\"datasourceScanId\":1}");
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getPort();

            Map<String, Object> props = new HashMap<>();
            props.put("DXR_BASE_URL", baseUrl);
            props.put("DXR_API_KEY", "test-key");
            props.put("DXR_FIRST_ODC_DATASOURCE_ID", "100");
            props.put("DXR_ODC_DATASOURCE_COUNT", "1");
            props.put("DXR_MAX_BATCH_SIZE", "1");
            props.put("DXR_BATCH_INTERVAL_SEC", "1");
            props.put("server.port", "0");

            SpringApplication app = new SpringApplication(SyncWrapperServer.class);
            app.setDefaultProperties(props);
            ConfigurableApplicationContext ctx = app.run();
            int port = ctx.getEnvironment().getProperty("local.server.port", Integer.class);

            OkHttpClient httpClient = new OkHttpClient();
            RequestBody fileBody = RequestBody.create(Path.of("samples/sample.txt").toFile(), MediaType.parse("text/plain"));
            MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "sample.txt", fileBody).build();
            Request request = new Request.Builder().url("http://localhost:" + port + "/classify-file").post(body).build();
            try (Response resp = httpClient.newCall(request).execute()) {
                assertEquals(200, resp.code());
            }
            ctx.close();
        }
    }
}
