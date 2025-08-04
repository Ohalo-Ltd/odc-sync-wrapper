package com.odc.syncwrapper;

import org.junit.jupiter.api.Test;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import java.util.List;

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
                                "dxr#tags": [1, 2],
                                "extracted_metadata#1": "SSN",
                                "extracted_metadata#2": "Credit Card",
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
        // Mock tag name responses for tag IDs 1 and 2
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"Sensitive Data\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 2, \"name\": \"Personal Info\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        DxrClient client = new DxrClient(baseUrl, "test-key");
        
        DxrClient.ClassificationData data = client.getTagIds(1);
        
        // Verify metadata extraction
        List<DxrClient.MetadataItem> metadata = data.extractedMetadata();
        assertEquals(2, metadata.size());
        
        // Check that metadata items are sorted by ID and have correct values
        assertEquals(1, metadata.get(0).id());
        assertEquals("SSN", metadata.get(0).value());
        assertEquals(2, metadata.get(1).id());
        assertEquals("Credit Card", metadata.get(1).value());
        
        // Verify tags extraction with names
        List<DxrClient.TagItem> tags = data.tags();
        assertEquals(2, tags.size());
        assertEquals(1, tags.get(0).id());
        assertEquals("Sensitive Data", tags.get(0).name());
        assertEquals(2, tags.get(1).id());
        assertEquals("Personal Info", tags.get(1).name());
        
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
                                "dxr#tags": [3],
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
        // Mock tag name response for tag ID 3
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 3, \"name\": \"Public Data\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        DxrClient client = new DxrClient(baseUrl, "test-key");
        
        DxrClient.ClassificationData data = client.getTagIds(1);
        
        assertTrue(data.extractedMetadata().isEmpty());
        
        // Verify tags extraction with name
        List<DxrClient.TagItem> tags = data.tags();
        assertEquals(1, tags.size());
        assertEquals(3, tags.get(0).id());
        assertEquals("Public Data", tags.get(0).name());
        
        server.shutdown();
    }
    
    @Test
    void shouldHandleTagNameFetchFailure() throws Exception {
        MockWebServer server = new MockWebServer();
        
        String responseBody = """
            {
                "hits": {
                    "hits": [
                        {
                            "_source": {
                                "dxr#tags": [99],
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
        // Mock tag name response failure (404)
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"error\": \"Tag not found\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        DxrClient client = new DxrClient(baseUrl, "test-key");
        
        DxrClient.ClassificationData data = client.getTagIds(1);
        
        // Verify tags extraction with fallback name when API call fails
        List<DxrClient.TagItem> tags = data.tags();
        assertEquals(1, tags.size());
        assertEquals(99, tags.get(0).id());
        assertEquals("Tag 99", tags.get(0).name()); // Should fall back to "Tag {id}"
        
        server.shutdown();
    }
}