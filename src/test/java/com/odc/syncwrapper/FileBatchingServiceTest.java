package com.odc.syncwrapper;

import static com.odc.syncwrapper.TestHelper.createNameCacheService;
import static com.odc.syncwrapper.TestHelper.setField;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Replicates the result-mixing bug reported in issue #9859:
 */
class FileBatchingServiceTest {

    private static final long RESULT_TIMEOUT_MS = 10_000;

    private MockWebServer server;
    private FileBatchingService fileBatchingService;
    private FileNamingStrategy fileNamingStrategy;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        var baseUrl = server.url("/").toString().replaceAll("/$", "");
        var nameCacheService = createNameCacheService(baseUrl, "test-key");

        fileNamingStrategy = mock(FileNamingStrategy.class);

        fileBatchingService = new FileBatchingService();
        setField(fileBatchingService, "nameCacheService", nameCacheService);
        setField(fileBatchingService, "fileNamingStrategy", fileNamingStrategy);
        setField(fileBatchingService, "baseUrl", baseUrl);
        setField(fileBatchingService, "apiKey", "test-key");
        setField(fileBatchingService, "firstDatasourceId", 200);
        setField(fileBatchingService, "datasourceCount", 1);
        setField(fileBatchingService, "maxBatchSize", 2);
        setField(fileBatchingService, "batchIntervalMs", 5000);
        setField(fileBatchingService, "jobStatusPollIntervalMs", 1000);
        fileBatchingService.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void whenBatchSubmitted_thenEachFileShouldReceiveOnlyItsOwnClassificationResults() throws Exception {
        // given
        var sensitiveFileAnnotationStat = new FileBatchingService.AnnotationStat(
            10, "SSN Pattern", 3, List.of("sensitive phrase"));

        var cleanFileAnnotationStat = new FileBatchingService.AnnotationStat(
            10, "SSN Pattern", 0, List.of());

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": \"job-123\"}"));

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("""
                        {
                            "state": {
                                "value": "FINISHED",
                                "datasourceScanId": 42
                            }
                        }
                        """));

        // Search response: two hits representing the two files.
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("""
                        {
                            "hits": {
                                "hits": [
                                    {
                                        "_source": {
                                            "ds#file_name": "sensitive_file_0ce08472-e9fb-40bf-83ff-825df4441876",
                                            "annotation_stats#count.10": "3",
                                            "annotation.10": ["sensitive phrase"]
                                        }
                                    },
                                    {
                                        "_source": {
                                            "ds#file_name": "clean_file_17f8aa9e-d8d6-4d4c-99e5-d7f37cb1e220",
                                            "annotation_stats#count.10": "0"
                                        }
                                    }
                                ]
                            }
                        }
                        """));

        // Annotation name lookup for annotation ID 10
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\": 10, \"name\": \"SSN Pattern\"}"));

        var sensitiveFile = new MockMultipartFile(
                "file", "sensitive_file.txt", "text/plain", "sensitive content".getBytes());
        var cleanFile = new MockMultipartFile(
                "file", "clean_file.txt", "text/plain", "clean content".getBytes());

        when(fileNamingStrategy.createUniqueFilename("sensitive_file.txt"))
            .thenReturn("sensitive_file_0ce08472-e9fb-40bf-83ff-825df4441876");
        when(fileNamingStrategy.createUniqueFilename("clean_file.txt"))
            .thenReturn("clean_file_17f8aa9e-d8d6-4d4c-99e5-d7f37cb1e220");

        // when
        // Submit both files - with maxBatchSize=2 they are guaranteed to land in the same batch
        var sensitiveFileFuture = fileBatchingService.processFile(sensitiveFile);
        var cleanFileFuture = fileBatchingService.processFile(cleanFile);

        var sensitiveFileResult = sensitiveFileFuture.get(RESULT_TIMEOUT_MS, MILLISECONDS);
        var cleanFileResult = cleanFileFuture.get(RESULT_TIMEOUT_MS, MILLISECONDS);

        // then
        assertAll(
            () -> assertThat(sensitiveFileResult.filename()).isEqualTo("sensitive_file.txt"),
            () -> assertThat(sensitiveFileResult.status()).isEqualTo("FINISHED"),
            () -> assertThat(sensitiveFileResult.annotations()).containsOnly(sensitiveFileAnnotationStat),
            () -> assertThat(cleanFileResult.filename()).isEqualTo("clean_file.txt"),
            () -> assertThat(cleanFileResult.status()).isEqualTo("FINISHED"),
            () -> assertThat(cleanFileResult.annotations()).containsOnly(cleanFileAnnotationStat)
        );
    }
}
