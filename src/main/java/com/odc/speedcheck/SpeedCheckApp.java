package com.odc.speedcheck;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class SpeedCheckApp {

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("Usage: java -jar odc-speed-check.jar <jobcount> <delayBetweenJobsMs> <firstDatasourceId> <datasourceCount> <batchSize>");
            System.exit(1);
        }
        int count = Integer.parseInt(args[0]);
        int delayBetweenJobsMs = Integer.parseInt(args[1]);
        int firstDatasourceId = Integer.parseInt(args[2]);
        int datasourceCount = Integer.parseInt(args[3]);
        int batchSize = Integer.parseInt(args[4]);
        if (batchSize < 1 || batchSize > 100) {
            System.err.println("batchSize must be between 1 and 100");
            System.exit(1);
        }
        String baseUrl = System.getenv("DXR_BASE_URL");
        String apiKey = System.getenv("DXR_API_KEY");
        if (baseUrl == null || apiKey == null) {
            System.err.println("DXR_BASE_URL and DXR_API_KEY environment variables are required");
            System.exit(1);
        }
        List<Path> allFiles = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> Paths.get("samples/sample" + i + ".txt"))
                .collect(Collectors.toList());
        DxrClient client = new DxrClient(baseUrl, apiKey);

        ExecutorService executor = Executors.newFixedThreadPool(datasourceCount,
                new DatasourceThreadFactory(firstDatasourceId));

        Instant start = Instant.now();
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread.sleep(delayBetweenJobsMs); // simulate some delay in jobs
            List<Path> batch = new ArrayList<>();
            for (int j = 0; j < batchSize; j++) {
                batch.add(allFiles.get((i * batchSize + j) % allFiles.size()));
            }
            futures.add(executor.submit(new JobTask(client, batch)));
        }
        // wait for tasks
        long totalLatency = 0;
        for (Future<Long> f : futures) {
            totalLatency += f.get();
        }
        executor.shutdown();
        Instant end = Instant.now();
        Duration elapsed = Duration.between(start, end);
        double avgLatencySec = (double) totalLatency / count / 1000.0;
        int totalFiles = count * batchSize;
        double fileThroughput = totalFiles / (elapsed.toMillis() / 1000.0);
        System.out.printf("All jobs completed in %d seconds.%n", elapsed.toSeconds());
        System.out.printf("Total files %d%n", totalFiles);
        System.out.printf("Average latency %.2f seconds%n", avgLatencySec);
        System.out.printf("Throughput %.2f files/second%n", fileThroughput);
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
