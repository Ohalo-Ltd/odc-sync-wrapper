package com.odc.syncwrapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.time.Duration;

/**
 * Shared HTTP client utilities for consistent configuration across the application.
 */
public final class HttpClientUtils {
    private static final Logger logger = LoggerFactory.getLogger(HttpClientUtils.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private HttpClientUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Creates an OkHttpClient that trusts all certificates.
     * WARNING: This should only be used in development/testing environments.
     */
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

    /**
     * Creates a standard retry policy for HTTP requests with exponential backoff.
     * Retries on IOException and HTTP 5xx errors.
     */
    public static RetryPolicy<Response> createRetryPolicy() {
        return RetryPolicy.<Response>builder()
                .handle(IOException.class)
                .handleResultIf(r -> r.code() >= 500)
                .withBackoff(Duration.ofMillis(100), Duration.ofSeconds(1))
                .withMaxAttempts(MAX_RETRY_ATTEMPTS)
                .onRetry(event -> {
                    Throwable lastException = event.getLastException();
                    Response lastResult = event.getLastResult();
                    if (lastException != null) {
                        logger.warn("Retrying API call due to exception (attempt {}/{}): {}",
                            event.getAttemptCount(), MAX_RETRY_ATTEMPTS, lastException.getMessage());
                    } else if (lastResult != null) {
                        logger.warn("Retrying API call due to HTTP error (attempt {}/{}): HTTP {}",
                            event.getAttemptCount(), MAX_RETRY_ATTEMPTS, lastResult.code());
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
    }

    /**
     * Executes an HTTP request with the configured retry policy.
     */
    public static Response executeWithRetry(OkHttpClient client, RetryPolicy<Response> retryPolicy, Request request) throws IOException {
        try {
            return Failsafe.with(retryPolicy).get(() -> client.newCall(request).execute());
        } catch (Exception e) {
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException(e);
        }
    }
}
