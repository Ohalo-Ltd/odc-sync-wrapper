package com.odc.speedcheck;

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
                throw new IOException("Unexpected response: " + response.code() + " " + body);
            }
            JSONObject obj = new JSONObject(body);
            return obj.getString("id");
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
                throw new IOException("Unexpected response: " + response.code() + " " + body);
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

    java.util.List<String> getTagIds(long scanId) throws IOException {
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
                throw new IOException("Unexpected response: " + response.code() + " " + respBody);
            }
            java.util.List<String> tags = new java.util.ArrayList<>();
            java.util.List<String> metadataList = new java.util.ArrayList<>();

            JSONObject obj = new JSONObject(respBody);
            JSONObject hits = obj.optJSONObject("hits");
            if (hits != null) {
                JSONArray arr = hits.optJSONArray("hits");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject hit = arr.getJSONObject(i);
                        JSONObject src = hit.optJSONObject("_source");
                        if (src != null && src.has("dxr#tags")) {
                            JSONArray t = src.getJSONArray("dxr#tags");
                            for (int j = 0; j < t.length(); j++) {
                                tags.add(String.valueOf(t.get(j)));
                            }
                        }
                        if (src != null && src.has("extracted_metadata#1")) {
                            metadataList.add(src.getString("extracted_metadata#1"));
                        }
                    }
                }
            }
            logger.info("Successfully fetched search results for scan ID {}: {} tags found: {}, extractedMeta: {}",
                scanId, tags.size(),
                tags.isEmpty() ? "none" : String.join(", ", tags),
                metadataList.isEmpty() ? "none" : String.join(", ", metadataList));
            return tags;
        }
    }
}
