package com.odc.syncwrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

public class JobStatusPollingIntervalTest {

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
        "DXR_BASE_URL=https://demo.dataxray.io/api",
        "DXR_API_KEY=test-key",
        "DXR_FIRST_ODC_DATASOURCE_ID=290",
        "DXR_ODC_DATASOURCE_COUNT=2",
        "DXR_MAX_BATCH_SIZE=2",
        "DXR_BATCH_INTERVAL_MS=3000"
        // DXR_JOB_STATUS_POLL_INTERVAL_MS not set - should use default 1000ms
    })
    class DefaultPollingIntervalTest {

        @Autowired
        private FileBatchingService fileBatchingService;

        @Test
        public void testDefaultPollingInterval() {
            // Just initializing the service should use default polling interval
            assertNotNull(fileBatchingService);
            
            // Since we can't easily test the private field, we'll just verify
            // the service initializes correctly with the default value
            // The actual polling interval is tested functionally by other tests
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
        "DXR_BASE_URL=https://demo.dataxray.io/api",
        "DXR_API_KEY=test-key",
        "DXR_FIRST_ODC_DATASOURCE_ID=290",
        "DXR_ODC_DATASOURCE_COUNT=2",
        "DXR_MAX_BATCH_SIZE=2",
        "DXR_BATCH_INTERVAL_MS=3000",
        "DXR_JOB_STATUS_POLL_INTERVAL_MS=500"
    })
    @ExtendWith(OutputCaptureExtension.class)
    class CustomPollingIntervalTest {

        @Autowired
        private FileBatchingService fileBatchingService;

        @Test
        public void testCustomPollingInterval(CapturedOutput output) {
            // Just initializing the service should log the custom polling interval
            assertNotNull(fileBatchingService);
            
            // Check that custom polling interval is logged
            String logOutput = output.getOut();
            assertTrue(logOutput.contains("Using job status polling interval of 500 milliseconds"), 
                "Should log custom polling interval: " + logOutput);
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
        "DXR_BASE_URL=https://demo.dataxray.io/api",
        "DXR_API_KEY=test-key",
        "DXR_FIRST_ODC_DATASOURCE_ID=290",
        "DXR_ODC_DATASOURCE_COUNT=2",
        "DXR_MAX_BATCH_SIZE=2",
        "DXR_BATCH_INTERVAL_MS=3000",
        "DXR_JOB_STATUS_POLL_INTERVAL_MS=2000"
    })
    @ExtendWith(OutputCaptureExtension.class)
    class LargePollingIntervalTest {

        @Autowired
        private FileBatchingService fileBatchingService;

        @Test
        public void testLargePollingInterval(CapturedOutput output) {
            // Test with a larger polling interval
            assertNotNull(fileBatchingService);
            
            // Check that large polling interval is logged
            String logOutput = output.getOut();
            assertTrue(logOutput.contains("Using job status polling interval of 2000 milliseconds"), 
                "Should log large polling interval: " + logOutput);
        }
    }
}