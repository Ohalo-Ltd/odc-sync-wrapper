package com.odc.syncwrapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class BatchService {
    private final DxrClient client;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final int maxBatchSize;
    private final long intervalMillis;

    private final Object lock = new Object();
    private final List<Pending> pending = new ArrayList<>();
    private ScheduledFuture<?> timer;

    public BatchService(
            @Value("${DXR_BASE_URL}") String baseUrl,
            @Value("${DXR_API_KEY}") String apiKey,
            @Value("${DXR_FIRST_ODC_DATASOURCE_ID}") int firstDatasourceId,
            @Value("${DXR_ODC_DATASOURCE_COUNT}") int datasourceCount,
            @Value("${DXR_MAX_BATCH_SIZE}") int maxBatchSize,
            @Value("${DXR_BATCH_INTERVAL_SEC}") int batchIntervalSec) {
        this.client = new DxrClient(baseUrl, apiKey);
        this.executor = Executors.newFixedThreadPool(datasourceCount, new DatasourceThreadFactory(firstDatasourceId));
        this.maxBatchSize = maxBatchSize;
        this.intervalMillis = batchIntervalSec * 1000L;
    }

    public CompletableFuture<List<String>> classify(MultipartFile file) throws IOException {
        Path temp = Files.createTempFile("upload-", file.getOriginalFilename());
        file.transferTo(temp);
        Pending p = new Pending(temp, new CompletableFuture<>());
        boolean schedule = false;
        synchronized (lock) {
            pending.add(p);
            if (pending.size() >= maxBatchSize) {
                List<Pending> batch = new ArrayList<>(pending);
                pending.clear();
                submitBatch(batch);
            } else if (timer == null) {
                schedule = true;
            }
        }
        if (schedule) {
            timer = scheduler.schedule(this::onTimer, intervalMillis, TimeUnit.MILLISECONDS);
        }
        return p.future;
    }

    private void onTimer() {
        List<Pending> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                timer = null;
                return;
            }
            batch = new ArrayList<>(pending);
            pending.clear();
            timer = null;
        }
        submitBatch(batch);
    }

    private void submitBatch(List<Pending> batch) {
        executor.submit(() -> {
            try {
                List<Path> files = batch.stream().map(p -> p.path).toList();
                List<String> tags = new BatchJobTask(client, files).call();
                for (Pending p : batch) {
                    p.future.complete(new ArrayList<>(tags));
                    Files.deleteIfExists(p.path);
                }
            } catch (Exception e) {
                for (Pending p : batch) {
                    p.future.completeExceptionally(e);
                    try { Files.deleteIfExists(p.path); } catch (IOException ignore) {}
                }
            }
        });
    }

    private static class Pending {
        final Path path;
        final CompletableFuture<List<String>> future;
        Pending(Path path, CompletableFuture<List<String>> future) {
            this.path = path;
            this.future = future;
        }
    }

    private static class DatasourceThreadFactory implements ThreadFactory {
        private final AtomicInteger datasourceIdIndex;
        DatasourceThreadFactory(int firstId) {
            this.datasourceIdIndex = new AtomicInteger(firstId);
        }
        @Override
        public Thread newThread(Runnable r) {
            int dsId = datasourceIdIndex.getAndIncrement();
            Thread t = new Thread(() -> {
                DatasourceContext.set(dsId);
                r.run();
            });
            t.setName("dxr-" + dsId);
            System.out.printf("Creating thread %s with datasource id %d%n", t.getName(), dsId);
            return t;
        }
    }
}
