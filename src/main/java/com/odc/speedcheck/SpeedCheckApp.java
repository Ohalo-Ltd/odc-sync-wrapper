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

public class SpeedCheckApp {
    private static final int[] DATASOURCE_IDS = {100, 101, 102, 103, 104};

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java -jar odc-speed-check.jar <count>");
            System.exit(1);
        }
        int count = Integer.parseInt(args[0]);
        String baseUrl = System.getenv("DXR_BASE_URL");
        String apiKey = System.getenv("DXR_API_KEY");
        if (baseUrl == null || apiKey == null) {
            System.err.println("DXR_BASE_URL and DXR_API_KEY environment variables are required");
            System.exit(1);
        }
        Path file = Paths.get("samples/sample.txt");
        DxrClient client = new DxrClient(baseUrl, apiKey);

        ExecutorService executor = Executors.newFixedThreadPool(DATASOURCE_IDS.length, new DatasourceThreadFactory());

        Instant start = Instant.now();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(new JobTask(client, file)));
        }
        // wait for tasks
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();
        Instant end = Instant.now();
        Duration elapsed = Duration.between(start, end);
        System.out.printf("All jobs completed in %d seconds.%n", elapsed.toSeconds());
    }

    private static class DatasourceThreadFactory implements ThreadFactory {
        private int index = 0;
        @Override
        public Thread newThread(Runnable r) {
            int dsId = DATASOURCE_IDS[index % DATASOURCE_IDS.length];
            index++;
            Thread t = new Thread(() -> {
                DatasourceContext.set(dsId);
                r.run();
            });
            t.setName("dxr-" + dsId);
            return t;
        }
    }
}
