package com.odc.syncwrapper;

import org.junit.jupiter.api.Test;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataExtractionTest {

    private NameCacheService createNameCacheService(String baseUrl, String apiKey) throws Exception {
        NameCacheService service = new NameCacheService();
        setField(service, "baseUrl", baseUrl);
        setField(service, "apiKey", apiKey);
        setField(service, "cacheExpiryMs", 300000L);
        setField(service, "preloadTagIds", "");
        setField(service, "preloadMetadataExtractorIds", "");
        setField(service, "preloadAnnotationIds", "");
        return service;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

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
        // Mock metadata extractor name responses for IDs 1 and 2
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"SSN Detector\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 2, \"name\": \"Credit Card Detector\"}"));
        // Mock tag name responses for tag IDs 1 and 2
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"Sensitive Data\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 2, \"name\": \"Personal Info\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");
        DxrClient client = new DxrClient(baseUrl, "test-key", nameCacheService);

        DxrClient.ClassificationData data = client.getTagIds(1);

        // Verify metadata extraction
        List<DxrClient.MetadataItem> metadata = data.extractedMetadata();
        assertEquals(2, metadata.size());

        // Check that metadata items are sorted by ID and have correct names and values
        assertEquals(1, metadata.get(0).id());
        assertEquals("SSN Detector", metadata.get(0).name());
        assertEquals("SSN", metadata.get(0).value());
        assertEquals(2, metadata.get(1).id());
        assertEquals("Credit Card Detector", metadata.get(1).name());
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
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");
        DxrClient client = new DxrClient(baseUrl, "test-key", nameCacheService);
        
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
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");
        DxrClient client = new DxrClient(baseUrl, "test-key", nameCacheService);

        DxrClient.ClassificationData data = client.getTagIds(1);

        // Verify tags extraction with fallback name when API call fails
        List<DxrClient.TagItem> tags = data.tags();
        assertEquals(1, tags.size());
        assertEquals(99, tags.get(0).id());
        assertEquals("Tag 99", tags.get(0).name()); // Should fall back to "Tag {id}"

        server.shutdown();
    }

    @Test
    void shouldExtractAnnotationStatsWithNames() throws Exception {
        MockWebServer server = new MockWebServer();
        
        // Mock response with annotation statistics
        String responseBody = """
            {
                "hits": {
                    "hits": [
                        {
                            "_source": {
                                "dxr#tags": [1],
                                "annotation_stats#count.10": "5",
                                "annotation_stats#count.20": "3"
                            }
                        }
                    ]
                }
            }
            """;

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(responseBody));
        // Mock annotation name responses for annotation IDs 10 and 20 (need to match HashMap iteration order)
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 20, \"name\": \"Credit Card Pattern\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 10, \"name\": \"SSN Pattern\"}"));
        // Mock tag name response for tag ID 1
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"Sensitive Data\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");
        DxrClient client = new DxrClient(baseUrl, "test-key", nameCacheService);

        DxrClient.ClassificationData data = client.getTagIds(1);

        // Verify annotation extraction with names
        List<DxrClient.AnnotationStat> annotations = data.annotations();
        assertEquals(2, annotations.size());

        // Check that annotation stats are sorted by ID and have correct names and counts
        assertEquals(10, annotations.get(0).id());
        assertEquals("SSN Pattern", annotations.get(0).name());
        assertEquals(5, annotations.get(0).count());
        assertTrue(annotations.get(0).phraseMatches().isEmpty()); // No phrase matches in this test
        assertEquals(20, annotations.get(1).id());
        assertEquals("Credit Card Pattern", annotations.get(1).name());
        assertEquals(3, annotations.get(1).count());
        assertTrue(annotations.get(1).phraseMatches().isEmpty()); // No phrase matches in this test

        // Verify tags extraction with names
        List<DxrClient.TagItem> tags = data.tags();
        assertEquals(1, tags.size());
        assertEquals(1, tags.get(0).id());
        assertEquals("Sensitive Data", tags.get(0).name());

        server.shutdown();
    }

    @Test
    void shouldHandleAnnotationNameFetchFailure() throws Exception {
        MockWebServer server = new MockWebServer();
        
        String responseBody = """
            {
                "hits": {
                    "hits": [
                        {
                            "_source": {
                                "annotation_stats#count.99": "2"
                            }
                        }
                    ]
                }
            }
            """;

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(responseBody));
        // Mock annotation name response failure (404)
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"error\": \"Annotation not found\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");
        DxrClient client = new DxrClient(baseUrl, "test-key", nameCacheService);

        DxrClient.ClassificationData data = client.getTagIds(1);

        // Verify annotation extraction with fallback name when API call fails
        List<DxrClient.AnnotationStat> annotations = data.annotations();
        assertEquals(1, annotations.size());
        assertEquals(99, annotations.get(0).id());
        assertEquals("Annotation 99", annotations.get(0).name()); // Should fall back to "Annotation {id}"
        assertEquals(2, annotations.get(0).count());
        assertTrue(annotations.get(0).phraseMatches().isEmpty()); // No phrase matches in this test

        server.shutdown();
    }

    @Test
    void shouldExtractAnnotationPhraseMatches() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            // Mock response with annotation phrase matches
            String responseBody = """
                {
                    "hits": {
                        "hits": [
                            {
                                "_source": {
                                    "annotation_stats#count.18": "2",
                                    "annotation.18": [
                                        "Lorem",
                                        "tempor incididunt ut labore et"
                                    ]
                                }
                            }
                        ]
                    }
                }
                """;

            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody(responseBody));
            // Mock annotation name response for annotation ID 18
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"id\": 18, \"name\": \"Lorem Pattern\"}"));
            server.start();

            String baseUrl = server.url("/").toString().replaceAll("/$", "");
            NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");
            DxrClient client = new DxrClient(baseUrl, "test-key", nameCacheService);

            DxrClient.ClassificationData data = client.getTagIds(1);

            // Verify annotation extraction with phrase matches
            List<DxrClient.AnnotationStat> annotations = data.annotations();
            assertEquals(1, annotations.size());
            
            // Check that annotation has correct name, count, and phrase matches
            assertEquals(18, annotations.get(0).id());
            assertEquals("Lorem Pattern", annotations.get(0).name());
            assertEquals(2, annotations.get(0).count());
            
            List<String> phraseMatches = annotations.get(0).phraseMatches();
            assertEquals(2, phraseMatches.size());
            assertEquals("Lorem", phraseMatches.get(0));
            assertEquals("tempor incididunt ut labore et", phraseMatches.get(1));
        }
    }
}