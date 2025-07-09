package com.odc.speedcheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class ClassificationServerApp {

    public static void main(String[] args) {
        validateEnvironmentVariables();
        
        ConfigurableApplicationContext context = SpringApplication.run(ClassificationServerApp.class, args);
        
        FileBatchingService fileBatchingService = context.getBean(FileBatchingService.class);
        fileBatchingService.initialize();
        
        System.out.println("Classification server started successfully!");
    }
    
    private static void validateEnvironmentVariables() {
        String[] requiredEnvVars = {
            "DXR_BASE_URL",
            "DXR_API_KEY", 
            "DXR_FIRST_ODC_DATASOURCE_ID",
            "DXR_ODC_DATASOURCE_COUNT",
            "DXR_MAX_BATCH_SIZE",
            "DXR_BATCH_INTERVAL_SEC"
        };
        
        for (String envVar : requiredEnvVars) {
            String value = System.getenv(envVar);
            if (value == null) {
                if ("DXR_API_KEY".equals(envVar)) {
                    System.err.println("ERROR: DXR_API_KEY environment variable is not set!");
                    System.err.println("Please set your Data X-Ray API key: export DXR_API_KEY=\"your-api-key-here\"");
                } else {
                    System.err.println("Required environment variable " + envVar + " is not set");
                }
                System.exit(1);
            }
            
            // Print first 40 characters of DXR_API_KEY for verification
            if ("DXR_API_KEY".equals(envVar)) {
                String preview = value.length() > 40 ? value.substring(0, 40) + "..." : value;
                System.out.println("DXR_API_KEY (first 40 chars): " + preview);
            }
        }
    }
}