package com.odc.syncwrapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

@Service
public class FileBatchingService {

    private static final Logger logger = LoggerFactory.getLogger(FileBatchingService.class);

    private static final long FAILED_RETRY_BACKOFF_MS = 10_000L;
    private static final int FAILED_RETRY_ATTEMPTS = 3;

    @Value("${DXR_BASE_URL}")
    private String baseUrl;

    @Value("${DXR_API_KEY:}")
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

    @PostConstruct
    public void initialize() {
        this.datasourceIdCounter.set(firstDatasourceId);
    }

    public CompletableFuture<FileClassificationResult> processFile(MultipartFile file) {
        return processFile(file, null);
    }

    public CompletableFuture<FileClassificationResult> processFile(MultipartFile file, String requestApiKey) {
        CompletableFuture<FileClassificationResult> result = new CompletableFuture<>();
        FileRequest request = new FileRequest(file, result, requestApiKey);

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

                // Determine which API key to use - prefer request-specific API key over configured one
                String effectiveApiKey = null;
                for (FileRequest request : batch) {
                    if (request.requestApiKey() != null && !request.requestApiKey().isEmpty()) {
                        effectiveApiKey = request.requestApiKey();
                        break;
                    }
                }
                if (effectiveApiKey == null) {
                    effectiveApiKey = apiKey;
                }

                // If no API key is available, fail all requests
                if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
                    String errorMsg = "No API key available for batch processing";
                    logger.error("{} - failing {} files in batch", errorMsg, batch.size());
                    for (FileRequest request : batch) {
                        logger.error("File '{}' failed: {}", request.file().getOriginalFilename(), errorMsg);
                        FileClassificationResult result = new FileClassificationResult(
                            request.file().getOriginalFilename(),
                            "FAILED",
                            Collections.emptyList(),
                            Collections.emptyList(),
                            null,
                            Collections.emptyList()
                        );
                        request.result().complete(result);
                    }
                    return;
                }

                // Create DxrClient with the effective API key
                DxrClient effectiveClient = new DxrClient(baseUrl, effectiveApiKey);

                List<FileData> fileDataList = new ArrayList<>();
                for (FileRequest request : batch) {
                    try {
                        String originalFilename = request.file().getOriginalFilename();
                        String enhancedFilename = createUniqueFilename(originalFilename);
                        FileData fileData = new FileData(
                            originalFilename,
                            enhancedFilename,
                            request.file().getBytes(),
                            request.file().getContentType()
                        );
                        fileDataList.add(fileData);
                    } catch (IOException e) {
                        logger.error("Failed to read file '{}': {}", request.file().getOriginalFilename(), e.getMessage(), e);
                        FileClassificationResult result = new FileClassificationResult(
                            request.file().getOriginalFilename(),
                            "FAILED",
                            Collections.emptyList(),
                            Collections.emptyList(),
                            null,
                            Collections.emptyList()
                        );
                        request.result().complete(result);
                    }
                }

                if (fileDataList.isEmpty()) {
                    return;
                }

                logger.info("Submitting batch of {} files to datasource {} for processing", fileDataList.size(), datasourceId);
                String jobId = effectiveClient.submitJob(datasourceId, fileDataList);
                logger.info("Submitted job {} to datasource {} for {} files", jobId, datasourceId, fileDataList.size());

                DxrClient.JobStatus status;
                int attemptNumber = 1; // Start with attempt 1 (the initial submission)
                
                do {
                    // Wait for job to complete
                    do {
                        Thread.sleep(1000);
                        status = effectiveClient.getJobStatus(datasourceId, jobId);
                        logger.debug("Job {} status: {}", jobId, status.state());
                    } while (!"FINISHED".equals(status.state()) && !"FAILED".equals(status.state()));

                    if (!"FAILED".equals(status.state())) {
                        break; // Job succeeded, exit retry loop
                    }

                    if (status.errorMessage() != null && !status.errorMessage().isEmpty()) {
                        logger.warn("Job {} failed (attempt {}/{}). Status: {} - Error: {}", 
                            jobId, attemptNumber, FAILED_RETRY_ATTEMPTS, status.state(), status.errorMessage());
                    } else {
                        logger.warn("Job {} failed (attempt {}/{}). Status: {}", 
                            jobId, attemptNumber, FAILED_RETRY_ATTEMPTS, status.state());
                    }
                    
                    // Check if we've exhausted all attempts
                    if (attemptNumber >= FAILED_RETRY_ATTEMPTS) {
                        if (status.errorMessage() != null && !status.errorMessage().isEmpty()) {
                            logger.error("Job {} failed after {} attempts. Final status: {} - Error: {}", 
                                jobId, FAILED_RETRY_ATTEMPTS, status.state(), status.errorMessage());
                        } else {
                            logger.error("Job {} failed after {} attempts. Final status: {}", 
                                jobId, FAILED_RETRY_ATTEMPTS, status.state());
                        }
                        break;
                    }
                    
                    // Retry the job submission
                    attemptNumber++;
                    logger.info("Retrying job submission (attempt {}/{}) after {}ms delay", attemptNumber, FAILED_RETRY_ATTEMPTS, FAILED_RETRY_BACKOFF_MS);
                    Thread.sleep(FAILED_RETRY_BACKOFF_MS);
                    jobId = effectiveClient.submitJob(datasourceId, fileDataList);
                    logger.info("Retried job submission, new job ID: {}", jobId);
                } while (true);

                if ("FINISHED".equals(status.state())) {
                    logger.info("Job {} completed successfully. Fetching classification results for {} files", jobId, batch.size());
                    DxrClient.ClassificationData classificationData = effectiveClient.getTagIds(status.datasourceScanId());
                    for (int i = 0; i < batch.size() && i < fileDataList.size(); i++) {
                        FileRequest request = batch.get(i);
                        FileData fileData = fileDataList.get(i);
                        // Convert DxrClient records to FileBatchingService records
                        java.util.List<MetadataItem> extractedMetadata = classificationData.extractedMetadata().stream()
                            .map(item -> new MetadataItem(item.id(), item.value()))
                            .toList();
                        java.util.List<AnnotationStat> annotations = classificationData.annotations().stream()
                            .map(stat -> new AnnotationStat(stat.id(), stat.count()))
                            .toList();
                        java.util.List<TagItem> tags = classificationData.tags().stream()
                            .map(tag -> new TagItem(tag.id(), tag.name()))
                            .toList();
                        logger.info("File '{}' classified successfully with {} metadata fields, {} tags, {} annotations", 
                            fileData.originalFilename(), extractedMetadata.size(), tags.size(), annotations.size());
                        FileClassificationResult result = new FileClassificationResult(
                            fileData.originalFilename(),
                            "FINISHED",
                            extractedMetadata,
                            tags,
                            classificationData.category(),
                            annotations
                        );
                        request.result().complete(result);
                    }
                } else {
                    String errorMsg;
                    if (status.errorMessage() != null && !status.errorMessage().isEmpty()) {
                        errorMsg = String.format("Job %s failed after %d attempts. Final status: %s - Error: %s", 
                            jobId, attemptNumber, status.state(), status.errorMessage());
                    } else {
                        errorMsg = String.format("Job %s failed after %d attempts. Final status: %s", 
                            jobId, attemptNumber, status.state());
                    }
                    logger.error("{} - failing {} files in batch", errorMsg, batch.size());
                    for (FileRequest request : batch) {
                        logger.error("File '{}' failed: {}", request.file().getOriginalFilename(), errorMsg);
                        FileClassificationResult result = new FileClassificationResult(
                            request.file().getOriginalFilename(),
                            "FAILED",
                            Collections.emptyList(),
                            Collections.emptyList(),
                            null,
                            Collections.emptyList()
                        );
                        request.result().complete(result);
                    }
                }

            } catch (Exception e) {
                String errorMsg = "Batch processing failed: " + e.getMessage();
                logger.error("{} - failing {} files in batch", errorMsg, batch.size(), e);
                for (FileRequest request : batch) {
                    logger.error("File '{}' failed due to batch processing error: {}", 
                        request.file().getOriginalFilename(), e.getMessage());
                    FileClassificationResult result = new FileClassificationResult(
                        request.file().getOriginalFilename(),
                        "FAILED",
                        Collections.emptyList(),
                        Collections.emptyList(),
                        null,
                        Collections.emptyList()
                    );
                    request.result().complete(result);
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

    private String createUniqueFilename(String originalFilename) {
        String uuid = UUID.randomUUID().toString();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return uuid;
        }
        
        int lastDotIndex = originalFilename.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return originalFilename + "_" + uuid;
        } else {
            String nameWithoutExtension = originalFilename.substring(0, lastDotIndex);
            String extension = originalFilename.substring(lastDotIndex);
            return nameWithoutExtension + "_" + uuid + extension;
        }
    }

    public static record FileRequest(MultipartFile file, CompletableFuture<FileClassificationResult> result, String requestApiKey) {}

    public static record FileData(String originalFilename, String enhancedFilename, byte[] content, String contentType) {}

    public static record AnnotationStat(int id, int count) {}
    public static record MetadataItem(int id, String value) {}
    public static record TagItem(int id, String name) {}
    public static record FileClassificationResult(String filename, String status, java.util.List<MetadataItem> extractedMetadata, java.util.List<TagItem> tags, String category, java.util.List<AnnotationStat> annotations) {}
}