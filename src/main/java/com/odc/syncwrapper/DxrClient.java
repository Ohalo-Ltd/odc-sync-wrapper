package com.odc.syncwrapper;

import okhttp3.*;
import dev.failsafe.RetryPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

class DxrClient {
    private static final Logger logger = LoggerFactory.getLogger(DxrClient.class);

    record JobStatus(String state, long datasourceScanId, String errorMessage) {}
    record AnnotationStat(int id, String name, int count, java.util.List<String> phraseMatches) {}
    record MetadataItem(int id, String name, String value) {}
    record TagItem(int id, String name) {}
    record ClassificationData(java.util.List<MetadataItem> extractedMetadata, java.util.List<TagItem> tags, String category, java.util.List<AnnotationStat> annotations) {}

    private final RetryPolicy<Response> retryPolicy = HttpClientUtils.createRetryPolicy();
    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient client = HttpClientUtils.getUnsafeOkHttpClient();
    private final NameCacheService nameCacheService;

    DxrClient(String baseUrl, String apiKey, NameCacheService nameCacheService) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.nameCacheService = nameCacheService;
    }

    private Response executeWithRetry(Request request) throws IOException {
        return HttpClientUtils.executeWithRetry(client, retryPolicy, request);
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

    Map<String, ClassificationData> getTagIdsPerFile(long scanId, List<String> enhancedFilenames) throws IOException {
        nameCacheService.cleanupExpiredEntries();

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

            java.util.Map<String, ClassificationData> result = new java.util.LinkedHashMap<>();
            java.util.List<String> unmatchedFilenames = new java.util.ArrayList<>(enhancedFilenames);

            JSONObject obj = new JSONObject(respBody);
            JSONObject hits = obj.optJSONObject("hits");
            if (hits != null) {
                JSONArray arr = hits.optJSONArray("hits");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject hit = arr.getJSONObject(i);
                        String hitIdentifier = extractHitIdentifier(hit);
                        logger.debug("Search hit [{}] identifier: '{}'", i, hitIdentifier);

                        String matchedFilename = findMatchingEnhancedFilename(hitIdentifier, unmatchedFilenames);
                        if (matchedFilename == null) {
                            logger.warn("Search hit '{}' for scan ID {} did not match any expected file. Skipping.", hitIdentifier, scanId);
                            continue;
                        }

                        JSONObject src = hit.optJSONObject("_source");
                        if (src == null) continue;
                        ClassificationData data = parseSourceIntoClassificationData(src);
                        result.put(matchedFilename, data);
                        unmatchedFilenames.remove(matchedFilename);
                        logger.info("Matched search hit '{}' to file '{}': {} metadata, {} tags, {} annotations, category: {}",
                            hitIdentifier, matchedFilename, data.extractedMetadata().size(), data.tags().size(), data.annotations().size(), data.category());
                    }
                }
            }

            if (!unmatchedFilenames.isEmpty()) {
                logger.warn("No search hit found for {} file(s) in scan ID {}: {}", unmatchedFilenames.size(), scanId, unmatchedFilenames);
            }
            logger.info("Per-file search complete for scan ID {}: {}/{} files matched",
                scanId, result.size(), enhancedFilenames.size());
            return result;
        }
    }

    private String extractHitIdentifier(JSONObject hit) {
        JSONObject src = hit.optJSONObject("_source");
        if (src != null) {
            String fileName = src.optString("ds#file_name", null);
            if (fileName != null && !fileName.isEmpty()) return fileName;
        }
        return hit.optString("_id", null);
    }

    private String findMatchingEnhancedFilename(String identifier, List<String> enhancedFilenames) {
        if (identifier == null) return null;
        return enhancedFilenames.contains(identifier) ? identifier : null;
    }

    private ClassificationData parseSourceIntoClassificationData(JSONObject src) {
        java.util.Map<Integer, String> extractedMetadataMap = new java.util.HashMap<>();
        java.util.Set<Integer> tagIds = new java.util.HashSet<>();
        String category = null;
        java.util.Map<Integer, Integer> annotationCounts = new java.util.HashMap<>();
        java.util.Map<Integer, java.util.List<String>> annotationPhraseMatches = new java.util.HashMap<>();

        for (String key : src.keySet()) {
            if (key.startsWith("extracted_metadata#")) {
                try {
                    int id = Integer.parseInt(key.substring("extracted_metadata#".length()));
                    Object value = src.get(key);
                    if (value != null) extractedMetadataMap.put(id, String.valueOf(value));
                } catch (NumberFormatException e) {
                    logger.debug("Skipping invalid metadata key: {}", key);
                }
            } else if (key.startsWith("annotation_stats#count.")) {
                try {
                    int id = Integer.parseInt(key.substring("annotation_stats#count.".length()));
                    Object value = src.get(key);
                    if (value != null) annotationCounts.put(id, Integer.parseInt(String.valueOf(value)));
                } catch (NumberFormatException e) {
                    logger.debug("Skipping invalid annotation count key: {}", key);
                }
            } else if (key.startsWith("annotation.")) {
                try {
                    int id = Integer.parseInt(key.substring("annotation.".length()));
                    Object value = src.get(key);
                    if (value instanceof JSONArray phraseArray) {
                        java.util.List<String> phrases = new java.util.ArrayList<>();
                        for (int j = 0; j < phraseArray.length(); j++) {
                            Object phrase = phraseArray.get(j);
                            if (phrase != null) phrases.add(String.valueOf(phrase));
                        }
                        annotationPhraseMatches.put(id, phrases);
                    }
                } catch (NumberFormatException e) {
                    logger.debug("Skipping invalid annotation phrase match key: {}", key);
                }
            }
        }

        if (src.has("dxr#tags")) {
            Object tagsValue = src.get("dxr#tags");
            if (tagsValue instanceof JSONArray tagsArray) {
                for (int j = 0; j < tagsArray.length(); j++) {
                    try {
                        tagIds.add(tagsArray.getInt(j));
                    } catch (Exception e) {
                        logger.debug("Skipping invalid tag ID: {}", tagsArray.opt(j));
                    }
                }
            }
        }

        if (src.has("ai#category")) {
            Object categoryValue = src.get("ai#category");
            if (categoryValue != null) category = String.valueOf(categoryValue);
        }

        java.util.List<MetadataItem> extractedMetadata = extractedMetadataMap.entrySet().stream()
            .map(entry -> {
                try {
                    String name = nameCacheService.getMetadataExtractorName(entry.getKey(), apiKey);
                    return new MetadataItem(entry.getKey(), name, entry.getValue());
                } catch (IOException e) {
                    logger.warn("Failed to fetch name for metadata extractor {}: {}. Using fallback name.", entry.getKey(), e.getMessage());
                    return new MetadataItem(entry.getKey(), "Metadata " + entry.getKey(), entry.getValue());
                }
            })
            .sorted((a, b) -> Integer.compare(a.id(), b.id()))
            .toList();

        java.util.List<AnnotationStat> annotations = annotationCounts.entrySet().stream()
            .map(entry -> {
                try {
                    String name = nameCacheService.getAnnotationName(entry.getKey(), apiKey);
                    java.util.List<String> phrases = annotationPhraseMatches.getOrDefault(entry.getKey(), java.util.List.of());
                    return new AnnotationStat(entry.getKey(), name, entry.getValue(), phrases);
                } catch (IOException e) {
                    logger.warn("Failed to fetch name for annotation {}: {}. Using fallback name.", entry.getKey(), e.getMessage());
                    java.util.List<String> phrases = annotationPhraseMatches.getOrDefault(entry.getKey(), java.util.List.of());
                    return new AnnotationStat(entry.getKey(), "Annotation " + entry.getKey(), entry.getValue(), phrases);
                }
            })
            .sorted((a, b) -> Integer.compare(a.id(), b.id()))
            .toList();

        java.util.List<TagItem> tags = tagIds.stream()
            .map(tagId -> {
                try {
                    String name = nameCacheService.getTagName(tagId, apiKey);
                    return new TagItem(tagId, name);
                } catch (IOException e) {
                    logger.warn("Failed to fetch name for tag {}: {}. Using fallback name.", tagId, e.getMessage());
                    return new TagItem(tagId, "Tag " + tagId);
                }
            })
            .sorted((a, b) -> Integer.compare(a.id(), b.id()))
            .toList();

        return new ClassificationData(extractedMetadata, tags, category, annotations);
    }

}
