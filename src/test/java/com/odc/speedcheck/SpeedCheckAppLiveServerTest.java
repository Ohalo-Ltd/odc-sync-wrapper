package com.odc.speedcheck;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpeedCheckAppLiveServerTest {
    @Test
    @Disabled("Requires live server; run manually with mvn -Dtest=SpeedCheckAppLiveServerTest test")
    void applicationRunsAgainstLiveServer() throws Exception {
        String baseUrl = System.getenv("DXR_BASE_URL");
        String apiKey = System.getenv("DXR_API_KEY");
        assertNotNull(baseUrl, "DXR_BASE_URL must be set");
        assertNotNull(apiKey, "DXR_API_KEY must be set");

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", System.getProperty("java.class.path"),
                "com.odc.speedcheck.SpeedCheckApp", "1");
        pb.environment().put("DXR_BASE_URL", baseUrl);
        pb.environment().put("DXR_API_KEY", apiKey);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = process.getInputStream()) {
            is.transferTo(baos);
        }
        int exit = process.waitFor();
        String output = baos.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, output);
        assertTrue(output.contains("All jobs completed"), output);
    }
}
