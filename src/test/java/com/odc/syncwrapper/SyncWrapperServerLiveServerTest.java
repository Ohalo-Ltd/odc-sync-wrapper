package com.odc.syncwrapper;

import okhttp3.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SyncWrapperServerLiveServerTest {
    @Test
    void applicationRunsAgainstLiveServer() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("RUN_LIVE_TESTS")),
                "Set RUN_LIVE_TESTS=true to enable this test");
        String baseUrl = System.getenv("DXR_BASE_URL");
        String apiKey = System.getenv("DXR_API_KEY");
        assertNotNull(baseUrl, "DXR_BASE_URL must be set");
        assertNotNull(apiKey, "DXR_API_KEY must be set");

        Map<String, Object> props = new HashMap<>();
        props.put("DXR_BASE_URL", baseUrl);
        props.put("DXR_API_KEY", apiKey);
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
