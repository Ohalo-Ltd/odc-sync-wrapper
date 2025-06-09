package com.odc.speedcheck;

import okhttp3.*;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


class DxrClient {

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
