package com.odc.syncwrapper;

import okhttp3.*;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


import java.time.Duration;

class DxrClient {
    private static final Logger logger = LoggerFactory.getLogger(DxrClient.class);
    private static final long TAG_CACHE_EXPIRY_MS = 5 * 60 * 1000L; // 5 minutes
    
    record JobStatus(String state, long datasourceScanId, String errorMessage) {}
    record AnnotationStat(int id, String name, int count, java.util.List<String> phraseMatches) {}
    record MetadataItem(int id, String name, String value) {}
    record TagItem(int id, String name) {}
    record ClassificationData(java.util.List<MetadataItem> extractedMetadata, java.util.List<TagItem> tags, String category, java.util.List<AnnotationStat> annotations) {}
    record CachedTagName(String name, long timestamp) {}
    record CachedMetadataName(String name, long timestamp) {}
    record CachedAnnotationName(String name, long timestamp) {}

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

    public static OkHttpClient getUnsafeOkHttpClient() {
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
    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient client = getUnsafeOkHttpClient();
    private final ConcurrentHashMap<Integer, CachedTagName> tagNameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CachedMetadataName> metadataNameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CachedAnnotationName> annotationNameCache = new ConcurrentHashMap<>();

    DxrClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
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


    String submitJob(int datasourceId, List<FileBatchingService.FileData> fileDataList) throws IOException {

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);
        for (FileBatchingService.FileData fileData : fileDataList) {
            RequestBody fileBody = RequestBody.create(fileData.content(),
                MediaType.parse(fileData.contentType() != null ? fileData.contentType() : "text/plain"));
            bodyBuilder.addFormDataPart("files", fileData.enhancedFilename(), fileBody);
        }
        RequestBody multipartBody = bodyBuilder.build();
        Request request = new Request.Builder()
                .url(baseUrl + "/on-demand-classifiers/" + datasourceId + "/jobs")
                .header("Authorization", "Bearer " + apiKey)
                .post(multipartBody)
                .build();
        try (Response response = executeWithRetry(request)) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String errorMsg = "Job submission failed: HTTP " + response.code() + " - " + body;
                logger.error("Failed to submit job to datasource {}: {}", datasourceId, errorMsg);
                throw new IOException(errorMsg);
            }
            JSONObject obj = new JSONObject(body);
            String jobId = obj.getString("id");
            logger.debug("Successfully submitted job {} to datasource {}", jobId, datasourceId);
            return jobId;
        }
    }


    JobStatus getJobStatus(int datasourceId, String jobId) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/on-demand-classifiers/" + datasourceId + "/jobs/" + jobId)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        try (Response response = executeWithRetry(request)) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String errorMsg = "Job status check failed: HTTP " + response.code() + " - " + body;
                logger.error("Failed to get status for job {} on datasource {}: {}", jobId, datasourceId, errorMsg);
                throw new IOException(errorMsg);
            }
            JSONObject obj = new JSONObject(body);
            String state;
            long scanId = -1;
            String errorMessage = null;
            
            if (obj.optJSONObject("state") != null) {
                JSONObject stateObj = obj.getJSONObject("state");
                state = stateObj.optString("value", stateObj.optString("state", "UNKNOWN"));
                scanId = stateObj.optLong("datasourceScanId", -1);
                
                // Extract error message if present
                if (stateObj.has("errorMessage")) {
                    errorMessage = stateObj.optString("errorMessage");
                } else if (stateObj.has("message")) {
                    errorMessage = stateObj.optString("message");
                }
            } else {
                state = obj.optString("state", "UNKNOWN");
                if (obj.has("datasourceScanId")) {
                    scanId = obj.optLong("datasourceScanId", -1);
                } else if (obj.has("state.datasourceScanId")) {
                    scanId = obj.optLong("state.datasourceScanId", -1);
                }
                
                // Extract error message from top level if present
                if (obj.has("errorMessage")) {
                    errorMessage = obj.optString("errorMessage");
                } else if (obj.has("message")) {
                    errorMessage = obj.optString("message");
                } else if (obj.has("error")) {
                    errorMessage = obj.optString("error");
                }
            }
            
            return new JobStatus(state, scanId, errorMessage);
        }
    }

    String getTagName(int tagId) throws IOException {
        // Check cache first
        CachedTagName cached = tagNameCache.get(tagId);
        long currentTime = System.currentTimeMillis();
        
        if (cached != null && (currentTime - cached.timestamp()) < TAG_CACHE_EXPIRY_MS) {
            logger.debug("Using cached tag name for tag {}: {}", tagId, cached.name());
            return cached.name();
        }
        
        // Cache miss or expired, fetch from API
        Request request = new Request.Builder()
                .url(baseUrl + "/tags/" + tagId)
                .header("Authorization", "Bearer " + apiKey)
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

    String getMetadataExtractorName(int metadataExtractorId) throws IOException {
        // Check cache first
        CachedMetadataName cached = metadataNameCache.get(metadataExtractorId);
        long currentTime = System.currentTimeMillis();
        
        if (cached != null && (currentTime - cached.timestamp()) < TAG_CACHE_EXPIRY_MS) {
            logger.debug("Using cached metadata extractor name for ID {}: {}", metadataExtractorId, cached.name());
            return cached.name();
        }
        
        // Cache miss or expired, fetch from API
        Request request = new Request.Builder()
                .url(baseUrl + "/metadata-extractors/" + metadataExtractorId)
                .header("Authorization", "Bearer " + apiKey)
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

    private void cleanupExpiredTagCacheEntries() {
        long currentTime = System.currentTimeMillis();
        tagNameCache.entrySet().removeIf(entry -> 
            (currentTime - entry.getValue().timestamp()) >= TAG_CACHE_EXPIRY_MS);
    }

    private void cleanupExpiredMetadataCacheEntries() {
        long currentTime = System.currentTimeMillis();
        metadataNameCache.entrySet().removeIf(entry -> 
            (currentTime - entry.getValue().timestamp()) >= TAG_CACHE_EXPIRY_MS);
    }

    String getAnnotationName(int annotationId) throws IOException {
        // Check cache first
        CachedAnnotationName cached = annotationNameCache.get(annotationId);
        long currentTime = System.currentTimeMillis();
        
        if (cached != null && (currentTime - cached.timestamp()) < TAG_CACHE_EXPIRY_MS) {
            logger.debug("Using cached annotation name for ID {}: {}", annotationId, cached.name());
            return cached.name();
        }
        
        // Cache miss or expired, fetch from API
        Request request = new Request.Builder()
                .url(baseUrl + "/data-classes/" + annotationId)
                .header("Authorization", "Bearer " + apiKey)
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

    private void cleanupExpiredAnnotationCacheEntries() {
        long currentTime = System.currentTimeMillis();
        annotationNameCache.entrySet().removeIf(entry -> 
            (currentTime - entry.getValue().timestamp()) >= TAG_CACHE_EXPIRY_MS);
    }

    ClassificationData getTagIds(long scanId) throws IOException {
        // Cleanup expired cache entries periodically
        cleanupExpiredTagCacheEntries();
        cleanupExpiredMetadataCacheEntries();
        cleanupExpiredAnnotationCacheEntries();
        
        JSONObject item = new JSONObject()
                .put("parameter", "dxr#datasource_scan_id")
                .put("value", scanId)
                .put("type", "number")
                .put("match_strategy", "exact")
                .put("operator", "AND")
                .put("group_id", 0)
                .put("group_order", 0);
        JSONArray queryItems = new JSONArray().put(item);
        JSONObject filter = new JSONObject().put("query_items", queryItems);
        JSONArray sort = new JSONArray().put(new JSONObject()
                .put("property", "_score")
                .put("order", "DESCENDING"));
        JSONObject payload = new JSONObject()
                .put("mode", "DXR_JSON_QUERY")
                .put("datasourceIds", new JSONArray())
                .put("pageNumber", 0)
                .put("pageSize", 20)
                .put("filter", filter)
                .put("sort", sort);
        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(baseUrl + "/indexed-files/search")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();
        try (Response response = executeWithRetry(request)) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String errorMsg = "Search request failed: HTTP " + response.code() + " - " + respBody;
                logger.error("Failed to search for scan ID {}: {}", scanId, errorMsg);
                throw new IOException(errorMsg);
            }
            java.util.Map<Integer, String> extractedMetadataMap = new java.util.HashMap<>();
            java.util.Set<Integer> tagIds = new java.util.HashSet<>();
            String category = null;
            java.util.Map<Integer, Integer> annotationCounts = new java.util.HashMap<>();
            java.util.Map<Integer, java.util.List<String>> annotationPhraseMatches = new java.util.HashMap<>();

            JSONObject obj = new JSONObject(respBody);
            JSONObject hits = obj.optJSONObject("hits");
            if (hits != null) {
                JSONArray arr = hits.optJSONArray("hits");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject hit = arr.getJSONObject(i);
                        JSONObject src = hit.optJSONObject("_source");
                        if (src != null) {
                            // Extract all metadata fields that start with "extracted_metadata#"
                            for (String key : src.keySet()) {
                                if (key.startsWith("extracted_metadata#")) {
                                    try {
                                        String idStr = key.substring("extracted_metadata#".length());
                                        int metadataId = Integer.parseInt(idStr);
                                        Object value = src.get(key);
                                        if (value != null) {
                                            extractedMetadataMap.put(metadataId, String.valueOf(value));
                                        }
                                    } catch (NumberFormatException e) {
                                        logger.debug("Skipping invalid metadata key: {}", key);
                                    }
                                } else if (key.startsWith("annotation_stats#count.")) {
                                    // Extract annotation statistics
                                    try {
                                        String idStr = key.substring("annotation_stats#count.".length());
                                        int annotationId = Integer.parseInt(idStr);
                                        Object value = src.get(key);
                                        if (value != null) {
                                            int count = Integer.parseInt(String.valueOf(value));
                                            annotationCounts.put(annotationId, count);
                                        }
                                    } catch (NumberFormatException e) {
                                        logger.debug("Skipping invalid annotation count key: {}", key);
                                    }
                                } else if (key.startsWith("annotation.")) {
                                    // Extract annotation phrase matches
                                    try {
                                        String idStr = key.substring("annotation.".length());
                                        int annotationId = Integer.parseInt(idStr);
                                        Object value = src.get(key);
                                        if (value instanceof JSONArray) {
                                            JSONArray phraseArray = (JSONArray) value;
                                            java.util.List<String> phrases = new java.util.ArrayList<>();
                                            for (int j = 0; j < phraseArray.length(); j++) {
                                                Object phrase = phraseArray.get(j);
                                                if (phrase != null) {
                                                    phrases.add(String.valueOf(phrase));
                                                }
                                            }
                                            annotationPhraseMatches.put(annotationId, phrases);
                                        }
                                    } catch (NumberFormatException e) {
                                        logger.debug("Skipping invalid annotation phrase match key: {}", key);
                                    }
                                }
                            }

                            // Extract tags from dxr#tags
                            if (src.has("dxr#tags")) {
                                Object tagsValue = src.get("dxr#tags");
                                if (tagsValue instanceof JSONArray tagsArray) {
                                    for (int j = 0; j < tagsArray.length(); j++) {
                                        try {
                                            int tagId = tagsArray.getInt(j);
                                            tagIds.add(tagId);
                                        } catch (Exception e) {
                                            logger.debug("Skipping invalid tag ID: {}", tagsArray.opt(j));
                                        }
                                    }
                                }
                            }

                            // Extract ai#category (take the first non-null value found)
                            if (category == null && src.has("ai#category")) {
                                Object categoryValue = src.get("ai#category");
                                if (categoryValue != null) {
                                    category = String.valueOf(categoryValue);
                                }
                            }
                        }
                    }
                }
            }
            
            // Convert metadata map to list of MetadataItem records with names
            java.util.List<MetadataItem> extractedMetadata = extractedMetadataMap.entrySet().stream()
                .map(entry -> {
                    try {
                        String extractorName = getMetadataExtractorName(entry.getKey());
                        return new MetadataItem(entry.getKey(), extractorName, entry.getValue());
                    } catch (IOException e) {
                        logger.warn("Failed to fetch name for metadata extractor {}: {}. Using fallback name.", entry.getKey(), e.getMessage());
                        return new MetadataItem(entry.getKey(), "Metadata " + entry.getKey(), entry.getValue());
                    }
                })
                .sorted((a, b) -> Integer.compare(a.id(), b.id()))
                .toList();
            
            // Convert annotation counts map to list of AnnotationStat records with names and phrase matches
            java.util.List<AnnotationStat> annotations = annotationCounts.entrySet().stream()
                .map(entry -> {
                    try {
                        String annotationName = getAnnotationName(entry.getKey());
                        java.util.List<String> phraseMatches = annotationPhraseMatches.getOrDefault(entry.getKey(), java.util.List.of());
                        return new AnnotationStat(entry.getKey(), annotationName, entry.getValue(), phraseMatches);
                    } catch (IOException e) {
                        logger.warn("Failed to fetch name for annotation {}: {}. Using fallback name.", entry.getKey(), e.getMessage());
                        java.util.List<String> phraseMatches = annotationPhraseMatches.getOrDefault(entry.getKey(), java.util.List.of());
                        return new AnnotationStat(entry.getKey(), "Annotation " + entry.getKey(), entry.getValue(), phraseMatches);
                    }
                })
                .sorted((a, b) -> Integer.compare(a.id(), b.id()))
                .toList();
            
            // Convert tag IDs to list of TagItem records with names
            java.util.List<TagItem> tags = tagIds.stream()
                .map(tagId -> {
                    try {
                        String tagName = getTagName(tagId);
                        return new TagItem(tagId, tagName);
                    } catch (IOException e) {
                        logger.warn("Failed to fetch name for tag {}: {}. Using fallback name.", tagId, e.getMessage());
                        return new TagItem(tagId, "Tag " + tagId);
                    }
                })
                .sorted((a, b) -> Integer.compare(a.id(), b.id()))
                .toList();
            
            if (extractedMetadata.isEmpty() && tags.isEmpty() && category == null && annotations.isEmpty()) {
                logger.warn("No classification data found for scan ID {}", scanId);
            } else {
                logger.info("Successfully fetched search results for scan ID {}: {} metadata fields, {} tags, {} annotations, category: {}",
                    scanId, extractedMetadata.size(), tags.size(), annotations.size(), category);
            }
            return new ClassificationData(extractedMetadata, tags, category, annotations);
        }
    }
}
