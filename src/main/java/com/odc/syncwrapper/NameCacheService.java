package com.odc.syncwrapper;

import okhttp3.*;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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

    private final OkHttpClient client = getUnsafeOkHttpClient();

    private final RetryPolicy<Response> retryPolicy = RetryPolicy.<Response>builder()
            .handle(IOException.class)
            .handleResultIf(r -> r.code() >= 500)
            .withBackoff(Duration.ofMillis(100), Duration.ofSeconds(1))
            .withMaxAttempts(3)
            .onRetry(event -> {
                Throwable lastException = event.getLastException();
                Response lastResult = event.getLastResult();
                if (lastException != null) {
                    logger.warn("Retrying API call due to exception (attempt {}/{}): {}",
                        event.getAttemptCount(), 3, lastException.getMessage());
                } else if (lastResult != null) {
                    logger.warn("Retrying API call due to HTTP error (attempt {}/{}): HTTP {}",
                        event.getAttemptCount(), 3, lastResult.code());
                }
            })
            .onFailure(event -> {
                Throwable lastException = event.getException();
                Response lastResult = event.getResult();
                if (lastException != null) {
                    logger.error("API call failed after {} attempts: {}",
                        event.getAttemptCount(), lastException.getMessage());
                } else if (lastResult != null) {
                    logger.error("API call failed after {} attempts: HTTP {}",
                        event.getAttemptCount(), lastResult.code());
                }
            })
            .build();

    private static OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[]{}; }
                }
            };

            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager)trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
            builder.readTimeout(Duration.ofMinutes(20));
            builder.writeTimeout(Duration.ofMinutes(20));
            builder.connectTimeout(Duration.ofMinutes(20));

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
        try {
            return Failsafe.with(retryPolicy).get(() -> client.newCall(request).execute());
        } catch (Exception e) {
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException(e);
        }
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
