package com.odc.speedcheck;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class FileBatchingService {

    private static final long FAILED_RETRY_BACKOFF_MS = 10_000L;
    private static final int FAILED_RETRY_ATTEMPTS = 3;

    @Value("${DXR_BASE_URL}")
    private String baseUrl;

    @Value("${DXR_API_KEY}")
    private String apiKey;

    @Value("${DXR_FIRST_ODC_DATASOURCE_ID}")
    private int firstDatasourceId;

    @Value("${DXR_ODC_DATASOURCE_COUNT}")
    private int datasourceCount;

    @Value("${DXR_MAX_BATCH_SIZE}")
    private int maxBatchSize;

    @Value("${DXR_BATCH_INTERVAL_SEC}")
    private int batchIntervalSec;

    private final AtomicInteger datasourceIdCounter = new AtomicInteger();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1);

    private final List<FileRequest> currentBatch = Collections.synchronizedList(new ArrayList<>());
    private ScheduledFuture<?> batchTimer;
    private final Object batchLock = new Object();

    private DxrClient dxrClient;

    @PostConstruct
    public void initialize() {
        // Print first 40 characters of DXR_API_KEY for verification
        if (apiKey != null) {
            String preview = apiKey.length() > 40 ? apiKey.substring(0, 40) + "..." : apiKey;
            System.out.println("DXR_API_KEY (first 40 chars): " + preview);
        } else {
            System.err.println("ERROR: DXR_API_KEY is not set!");
        }
        
        this.dxrClient = new DxrClient(baseUrl, apiKey);
        this.datasourceIdCounter.set(firstDatasourceId);
    }

    public CompletableFuture<FileClassificationResult> processFile(MultipartFile file) {
        CompletableFuture<FileClassificationResult> result = new CompletableFuture<>();
        FileRequest request = new FileRequest(file, result);

        synchronized (batchLock) {
            currentBatch.add(request);

            if (currentBatch.size() == 1) {
                batchTimer = scheduledExecutor.schedule(this::processBatch, batchIntervalSec, TimeUnit.SECONDS);
            }

            if (currentBatch.size() >= maxBatchSize) {
                if (batchTimer != null) {
                    batchTimer.cancel(false);
                }
                processBatch();
            }
        }

        return result;
    }

    private void processBatch() {
        List<FileRequest> batch;
        synchronized (batchLock) {
            if (currentBatch.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(currentBatch);
            currentBatch.clear();
            batchTimer = null;
        }

        executorService.submit(() -> {
            try {
                int datasourceId = getNextDatasourceId();
                DatasourceContext.set(datasourceId);

                List<FileData> fileDataList = new ArrayList<>();
                for (FileRequest request : batch) {
                    try {
                        FileData fileData = new FileData(
                            request.file().getOriginalFilename(),
                            request.file().getBytes(),
                            request.file().getContentType()
                        );
                        fileDataList.add(fileData);
                    } catch (IOException e) {
                        request.result().completeExceptionally(e);
                    }
                }

                if (fileDataList.isEmpty()) {
                    return;
                }

                String jobId = dxrClient.submitJob(datasourceId, fileDataList);

                DxrClient.JobStatus status;
                int attempts = 0;
                do {
                    do {
                        Thread.sleep(1000);
                        status = dxrClient.getJobStatus(datasourceId, jobId);
                    } while (!"FINISHED".equals(status.state()) && !"FAILED".equals(status.state()));

                    if (!"FAILED".equals(status.state())) {
                        break;
                    }

                    if (attempts >= FAILED_RETRY_ATTEMPTS) {
                        break;
                    }
                    attempts++;
                    Thread.sleep(FAILED_RETRY_BACKOFF_MS);
                    jobId = dxrClient.submitJob(datasourceId, fileDataList);
                } while (true);

                if ("FINISHED".equals(status.state())) {
                    List<String> tags = dxrClient.getTagIds(status.datasourceScanId());
                    for (int i = 0; i < batch.size() && i < fileDataList.size(); i++) {
                        FileRequest request = batch.get(i);
                        FileData fileData = fileDataList.get(i);
                        FileClassificationResult result = new FileClassificationResult(
                            fileData.filename(),
                            "FINISHED",
                            tags
                        );
                        request.result().complete(result);
                    }
                } else {
                    for (FileRequest request : batch) {
                        FileClassificationResult result = new FileClassificationResult(
                            request.file().getOriginalFilename(),
                            "FAILED",
                            Collections.emptyList()
                        );
                        request.result().complete(result);
                    }
                }

            } catch (Exception e) {
                for (FileRequest request : batch) {
                    request.result().completeExceptionally(e);
                }
            }
        });
    }

    private int getNextDatasourceId() {
        int currentValue = datasourceIdCounter.getAndIncrement();
        if (currentValue >= firstDatasourceId + datasourceCount) {
            datasourceIdCounter.set(firstDatasourceId);
            return firstDatasourceId;
        }
        return currentValue;
    }

    public static record FileRequest(MultipartFile file, CompletableFuture<FileClassificationResult> result) {}

    public static record FileData(String filename, byte[] content, String contentType) {}

    public static record FileClassificationResult(String filename, String status, List<String> tags) {}
}