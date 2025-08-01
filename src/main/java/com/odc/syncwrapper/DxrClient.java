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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


import java.time.Duration;

class DxrClient {
    private static final Logger logger = LoggerFactory.getLogger(DxrClient.class);
    record JobStatus(String state, long datasourceScanId) {}
    record AnnotationStat(int id, int count) {}
    record ClassificationData(java.util.Map<String, String> extractedMetadata, java.util.List<String> annotators, java.util.List<String> labels, String aiCategory, java.util.List<AnnotationStat> annotationResults) {}

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
            if (obj.optJSONObject("state") != null) {
                JSONObject stateObj = obj.getJSONObject("state");
                state = stateObj.optString("value", stateObj.optString("state", "UNKNOWN"));
                scanId = stateObj.optLong("datasourceScanId", -1);
            } else {
                state = obj.optString("state", "UNKNOWN");
                if (obj.has("datasourceScanId")) {
                    scanId = obj.optLong("datasourceScanId", -1);
                } else if (obj.has("state.datasourceScanId")) {
                    scanId = obj.optLong("state.datasourceScanId", -1);
                }
            }
            return new JobStatus(state, scanId);
        }
    }

    ClassificationData getTagIds(long scanId) throws IOException {
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
            java.util.Map<String, String> extractedMetadata = new java.util.HashMap<>();
            java.util.List<String> annotators = new java.util.ArrayList<>();
            java.util.List<String> labels = new java.util.ArrayList<>();
            String aiCategory = null;
            java.util.Map<Integer, Integer> annotationCounts = new java.util.HashMap<>();

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
                                    Object value = src.get(key);
                                    if (value != null) {
                                        extractedMetadata.put(key, String.valueOf(value));
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
                                }
                            }

                            // Extract annotators
                            if (src.has("annotators")) {
                                Object annotatorsValue = src.get("annotators");
                                if (annotatorsValue instanceof JSONArray annotatorsArray) {
                                    for (int j = 0; j < annotatorsArray.length(); j++) {
                                        String annotator = annotatorsArray.optString(j);
                                        if (annotator != null && !annotator.isEmpty() && !annotators.contains(annotator)) {
                                            annotators.add(annotator);
                                        }
                                    }
                                } else if (annotatorsValue != null) {
                                    String annotator = String.valueOf(annotatorsValue);
                                    if (!annotator.isEmpty() && !annotators.contains(annotator)) {
                                        annotators.add(annotator);
                                    }
                                }
                            }

                            // Extract labels
                            if (src.has("labels")) {
                                Object labelsValue = src.get("labels");
                                if (labelsValue instanceof JSONArray labelsArray) {
                                    for (int j = 0; j < labelsArray.length(); j++) {
                                        String label = labelsArray.optString(j);
                                        if (label != null && !label.isEmpty() && !labels.contains(label)) {
                                            labels.add(label);
                                        }
                                    }
                                } else if (labelsValue != null) {
                                    String label = String.valueOf(labelsValue);
                                    if (!label.isEmpty() && !labels.contains(label)) {
                                        labels.add(label);
                                    }
                                }
                            }

                            // Extract ai#category (take the first non-null value found)
                            if (aiCategory == null && src.has("ai#category")) {
                                Object aiCategoryValue = src.get("ai#category");
                                if (aiCategoryValue != null) {
                                    aiCategory = String.valueOf(aiCategoryValue);
                                }
                            }
                        }
                    }
                }
            }
            
            // Convert annotation counts map to list of AnnotationStat records
            java.util.List<AnnotationStat> annotationResults = annotationCounts.entrySet().stream()
                .map(entry -> new AnnotationStat(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Integer.compare(a.id(), b.id()))
                .toList();
            
            if (extractedMetadata.isEmpty() && annotators.isEmpty() && labels.isEmpty() && aiCategory == null && annotationResults.isEmpty()) {
                logger.warn("No classification data found for scan ID {}", scanId);
            } else {
                logger.info("Successfully fetched search results for scan ID {}: {} metadata fields, {} annotators, {} labels, {} annotation results, ai#category: {}",
                    scanId, extractedMetadata.size(), annotators.size(), labels.size(), annotationResults.size(), aiCategory);
            }
            return new ClassificationData(extractedMetadata, annotators, labels, aiCategory, annotationResults);
        }
    }
}
