package com.odc.speedcheck;

import okhttp3.*;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;

class DxrClient {
    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient client = new OkHttpClient();

    DxrClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    String submitJob(int datasourceId, Path file) throws IOException {
        RequestBody fileBody = RequestBody.create(file.toFile(), MediaType.parse("text/plain"));
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files", file.getFileName().toString(), fileBody)
                .build();
        Request request = new Request.Builder()
                .url(baseUrl + "/on-demand-classifiers/" + datasourceId + "/jobs")
                .header("Authorization", "Bearer " + apiKey)
                .post(multipartBody)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response: " + response.code() + " " + body);
            }
            JSONObject obj = new JSONObject(body);
            return obj.getString("id");
        }
    }

    String getJobState(int datasourceId, String jobId) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/on-demand-classifiers/" + datasourceId + "/jobs/" + jobId)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response: " + response.code() + " " + body);
            }
            JSONObject obj = new JSONObject(body);
            return obj.getString("state");
        }
    }
}
