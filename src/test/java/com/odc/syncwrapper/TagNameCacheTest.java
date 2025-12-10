package com.odc.syncwrapper;

import org.junit.jupiter.api.Test;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import static com.odc.syncwrapper.TestHelper.createNameCacheService;
import static org.junit.jupiter.api.Assertions.*;

class TagNameCacheTest {

    @Test
    void shouldCacheTagNamesAndReuseForMultipleCalls() throws Exception {
        MockWebServer server = new MockWebServer();

        // Mock search response with same tag appearing twice
        String searchResponse = """
            {
                "hits": {
                    "hits": [
                        {
                            "_source": {
                                "dxr#tags": [1, 1],
                                "extracted_metadata#1": "SSN"
                            }
                        }
                    ]
                }
            }
            """;

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(searchResponse));
        // Mock metadata extractor name response
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"SSN Detector\"}"));
        // Mock tag name response - should only be called once due to caching
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"Sensitive Data\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");
        DxrClient client = new DxrClient(baseUrl, "test-key", nameCacheService);

        DxrClient.ClassificationData data = client.getTagIds(1);

        // Verify we got the tags
        assertEquals(1, data.tags().size());
        assertEquals(1, data.tags().get(0).id());
        assertEquals("Sensitive Data", data.tags().get(0).name());

        // Verify only 3 requests were made: 1 search + 1 metadata extractor + 1 tag name
        assertEquals(3, server.getRequestCount());

        // Verify the requests
        RecordedRequest searchRequest = server.takeRequest();
        assertTrue(searchRequest.getPath().contains("/indexed-files/search"));

        RecordedRequest metadataRequest = server.takeRequest();
        assertTrue(metadataRequest.getPath().contains("/metadata-extractors/1"));

        RecordedRequest tagRequest = server.takeRequest();
        assertTrue(tagRequest.getPath().contains("/tags/1"));

        server.shutdown();
    }

    @Test
    void shouldCacheIndependentTagIds() throws Exception {
        MockWebServer server = new MockWebServer();

        // Mock responses for different tag IDs
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"First Tag\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 2, \"name\": \"Second Tag\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");

        // First call to tag 1 - should hit API and cache
        String firstName = nameCacheService.getTagName(1, "test-key");
        assertEquals("First Tag", firstName);
        assertEquals(1, server.getRequestCount());

        // Second call to tag 1 - should use cache
        String secondName = nameCacheService.getTagName(1, "test-key");
        assertEquals("First Tag", secondName);
        assertEquals(1, server.getRequestCount()); // Still only 1 request

        // Call to different tag 2 - should hit API and cache separately
        String differentTag = nameCacheService.getTagName(2, "test-key");
        assertEquals("Second Tag", differentTag);
        assertEquals(2, server.getRequestCount()); // Now 2 requests

        // Call to tag 2 again - should use cache
        String secondDifferentTag = nameCacheService.getTagName(2, "test-key");
        assertEquals("Second Tag", secondDifferentTag);
        assertEquals(2, server.getRequestCount()); // Still 2 requests

        // Original tag should still be cached
        String thirdName = nameCacheService.getTagName(1, "test-key");
        assertEquals("First Tag", thirdName);
        assertEquals(2, server.getRequestCount()); // Still 2 requests

        server.shutdown();
    }

    @Test
    void shouldCacheFailedLookups() throws Exception {
        MockWebServer server = new MockWebServer();

        // Mock failed response
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"error\": \"Tag not found\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");

        // First call - should hit API and cache the fallback name
        String firstName = nameCacheService.getTagName(99, "test-key");
        assertEquals("Tag 99", firstName);
        assertEquals(1, server.getRequestCount());

        // Second call immediately - should use cache (no additional API call)
        String secondName = nameCacheService.getTagName(99, "test-key");
        assertEquals("Tag 99", secondName);
        assertEquals(1, server.getRequestCount()); // Still only 1 request

        server.shutdown();
    }


    @Test
    void shouldCacheFailedMetadataExtractorLookups() throws Exception {
        MockWebServer server = new MockWebServer();

        // Mock failed metadata extractor response
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"error\": \"Metadata extractor not found\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");

        // First call - should hit API and cache the fallback name
        String firstName = nameCacheService.getMetadataExtractorName(99, "test-key");
        assertEquals("Metadata 99", firstName);
        assertEquals(1, server.getRequestCount());

        // Second call immediately - should use cache (no additional API call)
        String secondName = nameCacheService.getMetadataExtractorName(99, "test-key");
        assertEquals("Metadata 99", secondName);
        assertEquals(1, server.getRequestCount()); // Still only 1 request

        server.shutdown();
    }

    @Test
    void shouldCacheAnnotationNamesAndReuseForMultipleCalls() throws Exception {
        MockWebServer server = new MockWebServer();

        // Mock responses for different annotation IDs
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"First Annotation\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 2, \"name\": \"Second Annotation\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");

        // First call to annotation 1 - should hit API and cache
        String firstName = nameCacheService.getAnnotationName(1, "test-key");
        assertEquals("First Annotation", firstName);
        assertEquals(1, server.getRequestCount());

        // Second call to annotation 1 - should use cache
        String secondName = nameCacheService.getAnnotationName(1, "test-key");
        assertEquals("First Annotation", secondName);
        assertEquals(1, server.getRequestCount()); // Still only 1 request

        // Call to different annotation 2 - should hit API and cache separately
        String differentAnnotation = nameCacheService.getAnnotationName(2, "test-key");
        assertEquals("Second Annotation", differentAnnotation);
        assertEquals(2, server.getRequestCount()); // Now 2 requests

        // Call to annotation 2 again - should use cache
        String secondDifferentAnnotation = nameCacheService.getAnnotationName(2, "test-key");
        assertEquals("Second Annotation", secondDifferentAnnotation);
        assertEquals(2, server.getRequestCount()); // Still 2 requests

        // Original annotation should still be cached
        String thirdName = nameCacheService.getAnnotationName(1, "test-key");
        assertEquals("First Annotation", thirdName);
        assertEquals(2, server.getRequestCount()); // Still 2 requests

        server.shutdown();
    }

    @Test
    void shouldCacheFailedAnnotationLookups() throws Exception {
        MockWebServer server = new MockWebServer();

        // Mock failed annotation response
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"error\": \"Annotation not found\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService nameCacheService = createNameCacheService(baseUrl, "test-key");

        // First call - should hit API and cache the fallback name
        String firstName = nameCacheService.getAnnotationName(99, "test-key");
        assertEquals("Annotation 99", firstName);
        assertEquals(1, server.getRequestCount());

        // Second call immediately - should use cache (no additional API call)
        String secondName = nameCacheService.getAnnotationName(99, "test-key");
        assertEquals("Annotation 99", secondName);
        assertEquals(1, server.getRequestCount()); // Still only 1 request

        server.shutdown();
    }

    @Test
    void shouldShareCacheAcrossMultipleDxrClients() throws Exception {
        MockWebServer server = new MockWebServer();

        // Only one tag response - shared cache should prevent second fetch
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 1, \"name\": \"Shared Tag\"}"));
        server.start();

        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        NameCacheService sharedCacheService = createNameCacheService(baseUrl, "test-key");

        // First client fetches the tag
        String firstName = sharedCacheService.getTagName(1, "test-key");
        assertEquals("Shared Tag", firstName);
        assertEquals(1, server.getRequestCount());

        // Second client should use the same cached value
        String secondName = sharedCacheService.getTagName(1, "different-key");
        assertEquals("Shared Tag", secondName);
        assertEquals(1, server.getRequestCount()); // Still only 1 request - cache was shared

        server.shutdown();
    }
}
