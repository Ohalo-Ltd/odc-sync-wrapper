package com.odc.syncwrapper;

import org.junit.jupiter.api.Test;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataExtractionTest {

    @Test
    void shouldExtractAllMetadataFields() throws Exception {
        MockWebServer server = new MockWebServer();
        
        // Mock response with multiple metadata fields
        String responseBody = """
            {
                "hits": {
                    "hits": [
                        {
                            "_source": {
                                "dxr#tags": ["SENSITIVE", "PII"],
                                "extracted_metadata#1": "SSN",
                                "extracted_metadata#2": "Credit Card",
                                "extracted_metadata#custom": "Custom Field",
                                "other_field": "Should be ignored"
                            }
                        }
                    ]
                }
            }
            """;

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(responseBody));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        DxrClient client = new DxrClient(baseUrl, "test-key");
        
        DxrClient.ClassificationData data = client.getTagIds(1);
        
        // Verify tags
        assertEquals(List.of("SENSITIVE", "PII"), data.tags());
        
        // Verify metadata extraction
        Map<String, String> metadata = data.extractedMetadata();
        assertEquals(3, metadata.size());
        assertEquals("SSN", metadata.get("extracted_metadata#1"));
        assertEquals("Credit Card", metadata.get("extracted_metadata#2"));
        assertEquals("Custom Field", metadata.get("extracted_metadata#custom"));
        
        // Verify non-metadata fields are ignored
        assertFalse(metadata.containsKey("other_field"));
        
        server.shutdown();
    }
    
    @Test
    void shouldHandleEmptyMetadata() throws Exception {
        MockWebServer server = new MockWebServer();
        
        String responseBody = """
            {
                "hits": {
                    "hits": [
                        {
                            "_source": {
                                "dxr#tags": ["PUBLIC"],
                                "some_other_field": "value"
                            }
                        }
                    ]
                }
            }
            """;

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(responseBody));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        DxrClient client = new DxrClient(baseUrl, "test-key");
        
        DxrClient.ClassificationData data = client.getTagIds(1);
        
        assertEquals(List.of("PUBLIC"), data.tags());
        assertTrue(data.extractedMetadata().isEmpty());
        
        server.shutdown();
    }
}