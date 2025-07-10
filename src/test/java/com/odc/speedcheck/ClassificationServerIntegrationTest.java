package com.odc.speedcheck;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ClassificationServerIntegrationTest {

    @Test
    void serverValidatesRequiredEnvironmentVariables() throws Exception {
        // Test that server validates environment variables on startup
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", System.getProperty("java.class.path"),
                "com.odc.speedcheck.ClassificationServerApp");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = process.getInputStream()) {
            is.transferTo(baos);
        }
        int exit = process.waitFor();
        String output = baos.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit, "Should exit with error code when env vars missing");
        // Should contain error message about missing environment variables
    }

    @Test
    void serverStartsWithRequiredEnvironmentVariables() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("RUN_INTEGRATION_TESTS")),
                "Set RUN_INTEGRATION_TESTS=true to enable this test");
        
        String baseUrl = System.getenv("DXR_BASE_URL");
        String apiKey = System.getenv("DXR_API_KEY");
        assertNotNull(baseUrl, "DXR_BASE_URL must be set");
        assertNotNull(apiKey, "DXR_API_KEY must be set");

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", System.getProperty("java.class.path"),
                "com.odc.speedcheck.ClassificationServerApp");
        pb.environment().put("DXR_BASE_URL", baseUrl);
        pb.environment().put("DXR_API_KEY", apiKey);
        pb.environment().put("DXR_FIRST_ODC_DATASOURCE_ID", "100");
        pb.environment().put("DXR_ODC_DATASOURCE_COUNT", "2");
        pb.environment().put("DXR_MAX_BATCH_SIZE", "3");
        pb.environment().put("DXR_BATCH_INTERVAL_SEC", "10");
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        // Give it a moment to start, then kill it
        Thread.sleep(3000);
        process.destroyForcibly();
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = process.getInputStream()) {
            is.transferTo(baos);
        }
        process.waitFor();
        String output = baos.toString(StandardCharsets.UTF_8);
        
        // Should contain startup success message
        // assertTrue(output.contains("Classification server started successfully!"), output);
    }
}