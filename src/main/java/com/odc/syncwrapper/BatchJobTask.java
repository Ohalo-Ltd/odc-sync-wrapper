package com.odc.syncwrapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

class BatchJobTask implements Callable<List<String>> {
    static long FAILED_RETRY_BACKOFF_MS = 10_000L;
    static int FAILED_RETRY_ATTEMPTS = 3;

    private final DxrClient client;
    private final List<Path> files;

    BatchJobTask(DxrClient client, List<Path> files) {
        this.client = client;
        this.files = files;
    }

    @Override
    public List<String> call() {
        int dsId = DatasourceContext.get();
        if (dsId == 0) {
            System.err.println("No datasource id available for thread " + Thread.currentThread().getName());
            return List.of();
        }
        Instant start = Instant.now();
        try {
            int attempts = 0;
            String jobId;
            DxrClient.JobStatus status;
            do {
                jobId = client.submitJob(dsId, files);
                do {
                    Thread.sleep(1000);
                    status = client.getJobStatus(dsId, jobId);
                } while (!"FINISHED".equals(status.state()) && !"FAILED".equals(status.state()));
                if (!"FAILED".equals(status.state())) {
                    break;
                }
                if (attempts >= FAILED_RETRY_ATTEMPTS) {
                    break;
                }
                attempts++;
                System.out.printf("%s Thread %s job %s attempt %d unsuccessful, retrying%n",
                        java.time.Instant.now(), Thread.currentThread().getName(), jobId, attempts);
                Thread.sleep(FAILED_RETRY_BACKOFF_MS);
            } while (true);

            if ("FINISHED".equals(status.state())) {
                List<String> tags = client.getTagIds(status.datasourceScanId());
                String tagStr = String.join(",", tags);
                for (Path p : files) {
                    System.out.printf("%s Thread %s job %s file:%s completed with state %s and tag_ids:%s%n",
                            java.time.Instant.now(), Thread.currentThread().getName(), jobId, p.toString(), status.state(), tagStr);
                }
                return tags;
            } else {
                System.out.printf("%s Thread %s job %s FAILED%n",
                        java.time.Instant.now(), Thread.currentThread().getName(), jobId);
                return new ArrayList<>();
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error processing job on thread " + Thread.currentThread().getName());
            e.printStackTrace();
            return new ArrayList<>();
        } finally {
            Duration.between(start, Instant.now()).toMillis();
        }
    }
}
