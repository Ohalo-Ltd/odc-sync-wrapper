package com.odc.syncwrapper;

import java.lang.reflect.Field;

/**
 * Shared test utility methods for creating test fixtures and setting private fields.
 */
public final class TestHelper {

    private TestHelper() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates a NameCacheService configured for testing with the given base URL and API key.
     */
    public static NameCacheService createNameCacheService(String baseUrl, String apiKey) throws Exception {
        NameCacheService service = new NameCacheService();
        setField(service, "baseUrl", baseUrl);
        setField(service, "apiKey", apiKey);
        setField(service, "cacheExpiryMs", 300000L);
        setField(service, "preloadTagIds", "");
        setField(service, "preloadMetadataExtractorIds", "");
        setField(service, "preloadAnnotationIds", "");
        return service;
    }

    /**
     * Sets a private field on an object using reflection.
     */
    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
