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
            if (System.getenv(envVar) == null) {
                System.err.println("Required environment variable " + envVar + " is not set");
                System.exit(1);
            }
        }
    }
}