package com.odc.speedcheck;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class SpeedCheckApp {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java -jar odc-speed-check.jar <count> <firstDatasourceId> <datasourceCount>");
            System.exit(1);
        }
        int count = Integer.parseInt(args[0]);
        int firstDatasourceId = Integer.parseInt(args[1]);
        int datasourceCount = Integer.parseInt(args[2]);
        String baseUrl = System.getenv("DXR_BASE_URL");
        String apiKey = System.getenv("DXR_API_KEY");
        if (baseUrl == null || apiKey == null) {
            System.err.println("DXR_BASE_URL and DXR_API_KEY environment variables are required");
            System.exit(1);
        }
        Path file = Paths.get("samples/sample.txt");
        DxrClient client = new DxrClient(baseUrl, apiKey);

        ExecutorService executor = Executors.newFixedThreadPool(datasourceCount,
                new DatasourceThreadFactory(firstDatasourceId));

        Instant start = Instant.now();
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread.sleep(100); // simulate some delay in jobs
            futures.add(executor.submit(new JobTask(client, file)));
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
        double throughput = count / (elapsed.toMillis() / 1000.0);
        System.out.printf("All jobs completed in %d seconds.%n", elapsed.toSeconds());
        System.out.printf("Average latency %.2f seconds%n", avgLatencySec);
        System.out.printf("Throughput %.2f jobs/second%n", throughput);
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
