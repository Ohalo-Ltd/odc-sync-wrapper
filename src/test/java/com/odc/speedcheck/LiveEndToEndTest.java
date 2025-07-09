package com.odc.speedcheck;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
    classes = ClassificationServerApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    "DXR_BASE_URL=https://demo.dataxray.io/api",
    "DXR_FIRST_ODC_DATASOURCE_ID=290",
    "DXR_ODC_DATASOURCE_COUNT=2",
    "DXR_MAX_BATCH_SIZE=2",
    "DXR_BATCH_INTERVAL_SEC=1"
})
public class LiveEndToEndTest {

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        String apiKey = System.getenv("DXR_API_KEY");
        if (apiKey != null) {
            registry.add("DXR_API_KEY", () -> apiKey);
        }
    }

    @LocalServerPort
    private int port;

    private OkHttpClient httpClient;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        // Only run if live tests are enabled
        Assumptions.assumeTrue("true".equals(System.getenv("RUN_LIVE_TESTS")),
                "Set RUN_LIVE_TESTS=true to enable live end-to-end tests");
        
        // Require DXR_API_KEY to be set
        String apiKey = System.getenv("DXR_API_KEY");
        assertNotNull(apiKey, "DXR_API_KEY environment variable must be set for live tests");
        
        httpClient = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
        
        baseUrl = "http://localhost:" + port;
        
        System.out.println("Starting live end-to-end test on port: " + port);
    }

    @Test
    void testConcurrentFileUploads() throws Exception {
        // Create 6 sample files for testing
        List<Path> sampleFiles = createSampleFiles();
        
        // Create executor for concurrent uploads
        ExecutorService executor = Executors.newFixedThreadPool(6);
        
        try {
            // Start all uploads concurrently
            CompletableFuture<FileUploadResult>[] futures = new CompletableFuture[6];
            
            for (int i = 0; i < 6; i++) {
                final int fileIndex = i;
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        Path sampleFile = sampleFiles.get(fileIndex);
                        return uploadFile(sampleFile, sampleFile.getFileName().toString());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to upload file " + fileIndex, e);
                    }
                }, executor);
            }
            
            // Wait for all uploads to complete
            System.out.println("Waiting for all 6 concurrent uploads to complete...");
            CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
            
            // Verify all results
            for (int i = 0; i < 6; i++) {
                FileUploadResult result = futures[i].get();
                
                System.out.println("File " + i + " result: " + result);
                
                // Verify the response
                assertNotNull(result, "Upload result should not be null for file " + i);
                assertTrue(result.success, "Upload should succeed for file " + i + ": " + result.error);
                assertNotNull(result.filename, "Filename should not be null for file " + i);
                assertTrue(result.filename.equals("sample" + (i + 1) + ".txt"), "Filename should match for file " + i);
                
                // Check if the classification was successful
                if ("FINISHED".equals(result.status)) {
                    System.out.println("File " + i + " classified successfully with tags: " + result.tags);
                } else {
                    System.out.println("File " + i + " classification status: " + result.status);
                }
            }
            
            System.out.println("All 6 concurrent uploads completed successfully!");
            
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    private List<Path> createSampleFiles() throws IOException {
        List<Path> files = new java.util.ArrayList<>();
        Path samplesDir = Path.of("samples");
        
        // Use the first 6 sample files from the samples directory
        for (int i = 1; i <= 6; i++) {
            Path sampleFile = samplesDir.resolve("sample" + i + ".txt");
            if (Files.exists(sampleFile)) {
                files.add(sampleFile);
            } else {
                throw new IOException("Sample file not found: " + sampleFile);
            }
        }
        
        return files;
    }

    private FileUploadResult uploadFile(Path filePath, String filename) throws IOException {
        RequestBody fileBody = RequestBody.create(
            Files.readAllBytes(filePath),
            MediaType.parse("text/plain")
        );
        
        RequestBody requestBody = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, fileBody)
            .build();
        
        Request request = new Request.Builder()
            .url(baseUrl + "/classify-file")
            .post(requestBody)
            .build();
        
        long startTime = System.currentTimeMillis();
        
        try (Response response = httpClient.newCall(request).execute()) {
            long duration = System.currentTimeMillis() - startTime;
            
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                return new FileUploadResult(false, filename, null, null, 
                    "HTTP " + response.code() + ": " + errorBody, duration);
            }
            
            String responseBody = response.body().string();
            System.out.println("Upload response for " + filename + " (took " + duration + "ms): " + responseBody);
            
            // Parse response (simplified JSON parsing)
            String status = extractJsonValue(responseBody, "status");
            String returnedFilename = extractJsonValue(responseBody, "filename");
            String tags = extractJsonValue(responseBody, "tags");
            
            return new FileUploadResult(true, returnedFilename, status, tags, null, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return new FileUploadResult(false, filename, null, null, 
                "Exception: " + e.getMessage(), duration);
        }
    }

    private String extractJsonValue(String json, String key) {
        // Simple JSON value extraction (not production-ready, but sufficient for tests)
        String searchPattern = "\"" + key + "\":";
        int startIndex = json.indexOf(searchPattern);
        if (startIndex == -1) return null;
        
        startIndex += searchPattern.length();
        
        // Skip whitespace
        while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
            startIndex++;
        }
        
        if (startIndex >= json.length()) return null;
        
        char firstChar = json.charAt(startIndex);
        if (firstChar == '"') {
            // String value
            startIndex++;
            int endIndex = json.indexOf('"', startIndex);
            if (endIndex == -1) return null;
            return json.substring(startIndex, endIndex);
        } else if (firstChar == '[') {
            // Array value
            int bracketCount = 1;
            int endIndex = startIndex + 1;
            while (endIndex < json.length() && bracketCount > 0) {
                if (json.charAt(endIndex) == '[') bracketCount++;
                else if (json.charAt(endIndex) == ']') bracketCount--;
                endIndex++;
            }
            return json.substring(startIndex, endIndex);
        } else {
            // Other value (number, boolean, null)
            int endIndex = startIndex;
            while (endIndex < json.length() && 
                   json.charAt(endIndex) != ',' && 
                   json.charAt(endIndex) != '}' && 
                   json.charAt(endIndex) != ']') {
                endIndex++;
            }
            return json.substring(startIndex, endIndex).trim();
        }
    }

    private static class FileUploadResult {
        final boolean success;
        final String filename;
        final String status;
        final String tags;
        final String error;
        final long durationMs;

        FileUploadResult(boolean success, String filename, String status, String tags, String error, long durationMs) {
            this.success = success;
            this.filename = filename;
            this.status = status;
            this.tags = tags;
            this.error = error;
            this.durationMs = durationMs;
        }

        @Override
        public String toString() {
            if (success) {
                return String.format("SUCCESS: %s -> %s (tags: %s) in %dms", 
                    filename, status, tags, durationMs);
            } else {
                return String.format("FAILED: %s -> %s in %dms", 
                    filename, error, durationMs);
            }
        }
    }
}
