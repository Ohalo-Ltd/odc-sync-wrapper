package com.odc.syncwrapper;

import okhttp3.*;
import dev.failsafe.RetryPolicy;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NameCacheService {
    private static final Logger logger = LoggerFactory.getLogger(NameCacheService.class);

    record CachedTagName(String name, long timestamp) {}
    record CachedMetadataName(String name, long timestamp) {}
    record CachedAnnotationName(String name, long timestamp) {}

    private final ConcurrentHashMap<Integer, CachedTagName> tagNameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CachedMetadataName> metadataNameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CachedAnnotationName> annotationNameCache = new ConcurrentHashMap<>();

    @Value("${DXR_BASE_URL}")
    private String baseUrl;

    @Value("${DXR_NAME_CACHE_EXPIRY_MS:300000}")
    private long cacheExpiryMs;

    @Value("${DXR_API_KEY:}")
    private String apiKey;

    @Value("${DXR_PRELOAD_TAG_IDS:}")
    private String preloadTagIds;

    @Value("${DXR_PRELOAD_METADATA_EXTRACTOR_IDS:}")
    private String preloadMetadataExtractorIds;

    @Value("${DXR_PRELOAD_ANNOTATION_IDS:}")
    private String preloadAnnotationIds;

    private final OkHttpClient client = HttpClientUtils.getUnsafeOkHttpClient();
    private final RetryPolicy<Response> retryPolicy = HttpClientUtils.createRetryPolicy();

    @PostConstruct
    public void initialize() {
        logger.info("Name cache expiry set to {} ms ({} minutes)", cacheExpiryMs, cacheExpiryMs / 60000.0);
        preloadCaches();
    }

    private void preloadCaches() {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.info("No API key configured, skipping cache preloading");
            return;
        }

        List<Integer> tagIds = parseIds(preloadTagIds);
        List<Integer> metadataIds = parseIds(preloadMetadataExtractorIds);
        List<Integer> annotationIds = parseIds(preloadAnnotationIds);

        if (tagIds.isEmpty() && metadataIds.isEmpty() && annotationIds.isEmpty()) {
            logger.debug("No cache preload IDs configured");
            return;
        }

        logger.info("Preloading caches: {} tags, {} metadata extractors, {} annotations",
            tagIds.size(), metadataIds.size(), annotationIds.size());

        int successCount = 0;
        int failCount = 0;

        for (int id : tagIds) {
            try {
                String name = getTagName(id, apiKey);
                logger.debug("Preloaded tag {}: {}", id, name);
                successCount++;
            } catch (IOException e) {
                logger.warn("Failed to preload tag {}: {}", id, e.getMessage());
                failCount++;
            }
        }

        for (int id : metadataIds) {
            try {
                String name = getMetadataExtractorName(id, apiKey);
                logger.debug("Preloaded metadata extractor {}: {}", id, name);
                successCount++;
            } catch (IOException e) {
                logger.warn("Failed to preload metadata extractor {}: {}", id, e.getMessage());
                failCount++;
            }
        }

        for (int id : annotationIds) {
            try {
                String name = getAnnotationName(id, apiKey);
                logger.debug("Preloaded annotation {}: {}", id, name);
                successCount++;
            } catch (IOException e) {
                logger.warn("Failed to preload annotation {}: {}", id, e.getMessage());
                failCount++;
            }
        }

        logger.info("Cache preloading complete: {} succeeded, {} failed", successCount, failCount);
    }

    private List<Integer> parseIds(String idsString) {
        if (idsString == null || idsString.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(idsString.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid ID in preload config: '{}'", s);
                    return null;
                }
            })
            .filter(id -> id != null)
            .toList();
    }

    private Response executeWithRetry(Request request) throws IOException {
        return HttpClientUtils.executeWithRetry(client, retryPolicy, request);
    }

    public void cleanupExpiredEntries() {
        long currentTime = System.currentTimeMillis();
        tagNameCache.entrySet().removeIf(entry ->
            (currentTime - entry.getValue().timestamp()) >= cacheExpiryMs);
        metadataNameCache.entrySet().removeIf(entry ->
            (currentTime - entry.getValue().timestamp()) >= cacheExpiryMs);
        annotationNameCache.entrySet().removeIf(entry ->
            (currentTime - entry.getValue().timestamp()) >= cacheExpiryMs);
    }

    public String getTagName(int tagId, String requestApiKey) throws IOException {
        // Check cache first
        CachedTagName cached = tagNameCache.get(tagId);
        long currentTime = System.currentTimeMillis();

        if (cached != null && (currentTime - cached.timestamp()) < cacheExpiryMs) {
            logger.debug("Using cached tag name for tag {}: {}", tagId, cached.name());
            return cached.name();
        }

        // Cache miss or expired, fetch from API
        String effectiveApiKey = (requestApiKey != null && !requestApiKey.isEmpty()) ? requestApiKey : apiKey;
        Request request = new Request.Builder()
                .url(baseUrl + "/tags/" + tagId)
                .header("Authorization", "Bearer " + effectiveApiKey)
                .get()
                .build();
        try (Response response = executeWithRetry(request)) {
            String body = response.body() != null ? response.body().string() : "";
            String tagName;

            if (!response.isSuccessful()) {
                String errorMsg = "Tag fetch failed: HTTP " + response.code() + " - " + body;
                logger.error("Failed to fetch tag {} details: {}", tagId, errorMsg);
                // Return a fallback name instead of throwing exception to not break the entire response
                tagName = "Tag " + tagId;
            } else {
                JSONObject obj = new JSONObject(body);
                tagName = obj.optString("name", "Tag " + tagId);
                logger.debug("Fetched tag name for tag {}: {}", tagId, tagName);
            }

            // Cache the result (even fallback names to avoid repeated failed API calls)
            tagNameCache.put(tagId, new CachedTagName(tagName, currentTime));
            return tagName;
        }
    }

    public String getMetadataExtractorName(int metadataExtractorId, String requestApiKey) throws IOException {
        // Check cache first
        CachedMetadataName cached = metadataNameCache.get(metadataExtractorId);
        long currentTime = System.currentTimeMillis();

        if (cached != null && (currentTime - cached.timestamp()) < cacheExpiryMs) {
            logger.debug("Using cached metadata extractor name for ID {}: {}", metadataExtractorId, cached.name());
            return cached.name();
        }

        // Cache miss or expired, fetch from API
        String effectiveApiKey = (requestApiKey != null && !requestApiKey.isEmpty()) ? requestApiKey : apiKey;
        Request request = new Request.Builder()
                .url(baseUrl + "/metadata-extractors/" + metadataExtractorId)
                .header("Authorization", "Bearer " + effectiveApiKey)
                .get()
                .build();
        try (Response response = executeWithRetry(request)) {
            String body = response.body() != null ? response.body().string() : "";
            String extractorName;

            if (!response.isSuccessful()) {
                String errorMsg = "Metadata extractor fetch failed: HTTP " + response.code() + " - " + body;
                logger.error("Failed to fetch metadata extractor {} details: {}", metadataExtractorId, errorMsg);
                // Return a fallback name instead of throwing exception to not break the entire response
                extractorName = "Metadata " + metadataExtractorId;
            } else {
                JSONObject obj = new JSONObject(body);
                extractorName = obj.optString("name", "Metadata " + metadataExtractorId);
                logger.debug("Fetched metadata extractor name for ID {}: {}", metadataExtractorId, extractorName);
            }

            // Cache the result (even fallback names to avoid repeated failed API calls)
            metadataNameCache.put(metadataExtractorId, new CachedMetadataName(extractorName, currentTime));
            return extractorName;
        }
    }

    public String getAnnotationName(int annotationId, String requestApiKey) throws IOException {
        // Check cache first
        CachedAnnotationName cached = annotationNameCache.get(annotationId);
        long currentTime = System.currentTimeMillis();

        if (cached != null && (currentTime - cached.timestamp()) < cacheExpiryMs) {
            logger.debug("Using cached annotation name for ID {}: {}", annotationId, cached.name());
            return cached.name();
        }

        // Cache miss or expired, fetch from API
        String effectiveApiKey = (requestApiKey != null && !requestApiKey.isEmpty()) ? requestApiKey : apiKey;
        Request request = new Request.Builder()
                .url(baseUrl + "/data-classes/" + annotationId)
                .header("Authorization", "Bearer " + effectiveApiKey)
                .get()
                .build();
        try (Response response = executeWithRetry(request)) {
            String body = response.body() != null ? response.body().string() : "";
            String annotationName;

            if (!response.isSuccessful()) {
                String errorMsg = "Annotation fetch failed: HTTP " + response.code() + " - " + body;
                logger.error("Failed to fetch annotation {} details: {}", annotationId, errorMsg);
                // Return a fallback name instead of throwing exception to not break the entire response
                annotationName = "Annotation " + annotationId;
            } else {
                JSONObject obj = new JSONObject(body);
                annotationName = obj.optString("name", "Annotation " + annotationId);
                logger.debug("Fetched annotation name for ID {}: {}", annotationId, annotationName);
            }

            // Cache the result (even fallback names to avoid repeated failed API calls)
            annotationNameCache.put(annotationId, new CachedAnnotationName(annotationName, currentTime));
            return annotationName;
        }
    }
}
