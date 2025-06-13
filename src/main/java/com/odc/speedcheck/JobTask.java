package com.odc.speedcheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

class JobTask implements Callable<Long> {
    static long FAILED_RETRY_BACKOFF_MS = 10_000L;
    static int FAILED_RETRY_ATTEMPTS = 3;

    private final DxrClient client;
    private final List<Path> files;

    JobTask(DxrClient client, List<Path> files) {
        this.client = client;
        this.files = files;
    }

    @Override
    public Long call() {
        int dsId = DatasourceContext.get();
        if (dsId == 0) {
            System.err.println("No datasource id available for thread " + Thread.currentThread().getName());
            return -1L;
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
                java.util.List<String> tags = client.getTagIds(status.datasourceScanId());
                String tagStr = String.join(",", tags);
                for (Path p : files) {
                    System.out.printf("%s Thread %s job %s file:%s completed with state %s and tag_ids:%s%n",
                            java.time.Instant.now(), Thread.currentThread().getName(), jobId, p.toString(), status.state(), tagStr);
                }
            } else {
                System.out.printf("%s Thread %s job %s FAILED%n",
                        java.time.Instant.now(), Thread.currentThread().getName(), jobId);
            }
            return Duration.between(start, Instant.now()).toMillis();
        } catch (IOException | InterruptedException e) {
            System.err.println("Error processing job on thread " + Thread.currentThread().getName());
            e.printStackTrace();
            return -1L;
        }
    }
}
