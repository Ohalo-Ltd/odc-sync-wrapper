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
                                "dxr#tags": ["SENSITIVE", "PII"],
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
        
        assertTrue(data.extractedMetadata().isEmpty());
        
        server.shutdown();
    }
}