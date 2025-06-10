package com.odc.speedcheck;

import java.io.IOException;
import java.nio.file.Path;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

class JobTask implements Callable<Long> {
    private final DxrClient client;
    private final Path file;

    JobTask(DxrClient client, Path file) {
        this.client = client;
        this.file = file;
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
            String jobId = client.submitJob(dsId, file);
            String state;
            do {
                Thread.sleep(1000);
                state = client.getJobState(dsId, jobId);
            } while (!"FINISHED".equals(state) && !"FAILED".equals(state));
            System.out.printf("Thread %s job %s complete with state %s%n", Thread.currentThread().getName(), jobId, state);
            return Duration.between(start, Instant.now()).toMillis();
        } catch (IOException | InterruptedException e) {
            System.err.println("Error processing job on thread " + Thread.currentThread().getName());
            e.printStackTrace();
            return -1L;
        }
    }
}
