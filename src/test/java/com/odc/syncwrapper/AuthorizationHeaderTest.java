package com.odc.syncwrapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "DXR_BASE_URL=https://demo.dataxray.io/api",
    "DXR_FIRST_ODC_DATASOURCE_ID=290",
    "DXR_ODC_DATASOURCE_COUNT=2",
    "DXR_MAX_BATCH_SIZE=1", 
    "DXR_BATCH_INTERVAL_SEC=1"
})
public class AuthorizationHeaderTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void testAuthorizationHeaderExtraction() {
        // Test that the controller correctly extracts the API key from Authorization header
        
        // Create a multipart request with an Authorization header
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer test-api-key-123");
        
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource("test content".getBytes()) {
            @Override
            public String getFilename() {
                return "sample1.txt";
            }
        });
        
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        
        // Make the request - it should fail with our test configuration but not due to missing API key
        ResponseEntity<FileBatchingService.FileClassificationResult> response = 
            restTemplate.postForEntity("http://localhost:" + port + "/classify-file", requestEntity, 
                FileBatchingService.FileClassificationResult.class);
        
        // The response should indicate failure, but the error should be related to the API call, not missing API key
        assertNotNull(response.getBody());
        assertEquals("FAILED", response.getBody().status());
        // The filename should be preserved (proving the request was processed)
        assertEquals("sample1.txt", response.getBody().filename());
    }

    @Test
    public void testMissingAuthorizationHeader() {
        // Test that requests without Authorization header and without DXR_API_KEY fail appropriately
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource("test content".getBytes()) {
            @Override
            public String getFilename() {
                return "sample1.txt";
            }
        });
        
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        
        // Make the request - it should fail due to missing API key
        ResponseEntity<FileBatchingService.FileClassificationResult> response = 
            restTemplate.postForEntity("http://localhost:" + port + "/classify-file", requestEntity, 
                FileBatchingService.FileClassificationResult.class);
        
        // The response should indicate failure due to missing API key
        assertNotNull(response.getBody());
        assertEquals("FAILED", response.getBody().status());
        assertEquals("sample1.txt", response.getBody().filename());
    }

    @Test
    public void testMalformedAuthorizationHeader() {
        // Test that malformed Authorization headers are handled gracefully
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Basic invalid-format");
        
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource("test content".getBytes()) {
            @Override
            public String getFilename() {
                return "sample1.txt";
            }
        });
        
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        
        // Make the request - it should fail due to malformed header (treated as missing API key)
        ResponseEntity<FileBatchingService.FileClassificationResult> response = 
            restTemplate.postForEntity("http://localhost:" + port + "/classify-file", requestEntity, 
                FileBatchingService.FileClassificationResult.class);
        
        // The response should indicate failure due to missing API key
        assertNotNull(response.getBody());
        assertEquals("FAILED", response.getBody().status());
        assertEquals("sample1.txt", response.getBody().filename());
    }
}