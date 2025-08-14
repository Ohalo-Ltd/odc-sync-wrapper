package com.odc.syncwrapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
public class ClassificationServerApp {

    private static final Logger logger = LoggerFactory.getLogger(ClassificationServerApp.class);

    public static void main(String[] args) {
        validateEnvironmentVariables();
        
        ConfigurableApplicationContext context = SpringApplication.run(ClassificationServerApp.class, args);
        
        FileBatchingService fileBatchingService = context.getBean(FileBatchingService.class);
        fileBatchingService.initialize();
        
        logger.info("Classification server started successfully!");
    }
    
    private static void validateEnvironmentVariables() {
        String[] requiredEnvVars = {
            "DXR_BASE_URL",
            "DXR_FIRST_ODC_DATASOURCE_ID",
            "DXR_ODC_DATASOURCE_COUNT",
            "DXR_MAX_BATCH_SIZE"
        };
        
        for (String envVar : requiredEnvVars) {
            String value = System.getenv(envVar);
            if (value == null) {
                System.err.println("Required environment variable " + envVar + " is not set");
                System.exit(1);
            }
        }
        
        // Validate that at least one batch interval variable is set
        String batchIntervalMs = System.getenv("DXR_BATCH_INTERVAL_MS");
        String batchIntervalSec = System.getenv("DXR_BATCH_INTERVAL_SEC");
        if (batchIntervalMs == null && batchIntervalSec == null) {
            System.err.println("Either DXR_BATCH_INTERVAL_MS or DXR_BATCH_INTERVAL_SEC must be set");
            System.exit(1);
        }
        
        // Validate job status polling interval if provided
        String jobStatusPollInterval = System.getenv("DXR_JOB_STATUS_POLL_INTERVAL_MS");
        if (jobStatusPollInterval != null) {
            try {
                int pollInterval = Integer.parseInt(jobStatusPollInterval);
                if (pollInterval <= 0) {
                    System.err.println("DXR_JOB_STATUS_POLL_INTERVAL_MS must be a positive integer (milliseconds)");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("DXR_JOB_STATUS_POLL_INTERVAL_MS must be a valid integer (milliseconds)");
                System.exit(1);
            }
        }
        
        // Check DXR_API_KEY separately as it's optional (can be provided via Authorization header)
        String apiKey = System.getenv("DXR_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            String preview = apiKey.length() > 40 ? apiKey.substring(0, 40) + "..." : apiKey;
            logger.info("DXR_API_KEY (first 40 chars): {}", preview);
        } else {
            logger.info("DXR_API_KEY not set. API key must be provided via Authorization header.");
        }
    }
}