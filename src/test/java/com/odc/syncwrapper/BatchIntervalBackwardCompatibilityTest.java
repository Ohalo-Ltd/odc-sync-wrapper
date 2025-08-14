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

public class BatchIntervalBackwardCompatibilityTest {

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
        "DXR_BASE_URL=https://demo.dataxray.io/api",
        "DXR_API_KEY=test-key",
        "DXR_FIRST_ODC_DATASOURCE_ID=290",
        "DXR_ODC_DATASOURCE_COUNT=2",
        "DXR_MAX_BATCH_SIZE=2",
        "DXR_BATCH_INTERVAL_SEC=5"
    })
    @ExtendWith(OutputCaptureExtension.class)
    class DeprecatedSecOnlyTest {

        @Autowired
        private FileBatchingService fileBatchingService;

        @Test
        public void testDeprecationWarningForSecOnly(CapturedOutput output) {
            // Just initializing the service should trigger the deprecation warning
            assertNotNull(fileBatchingService);
            
            // Check that deprecation warning is logged
            String logOutput = output.getOut();
            assertTrue(logOutput.contains("DEPRECATED: DXR_BATCH_INTERVAL_SEC is deprecated"), 
                "Should log deprecation warning: " + logOutput);
            assertTrue(logOutput.contains("Please use DXR_BATCH_INTERVAL_MS instead"), 
                "Should suggest new variable: " + logOutput);
            assertTrue(logOutput.contains("Converting DXR_BATCH_INTERVAL_SEC=5 seconds to DXR_BATCH_INTERVAL_MS=5000 milliseconds"), 
                "Should log conversion: " + logOutput);
            assertTrue(logOutput.contains("Using batch interval of 5000 milliseconds"), 
                "Should log effective interval: " + logOutput);
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
        "DXR_BATCH_INTERVAL_MS=3000"
    })
    @ExtendWith(OutputCaptureExtension.class)
    class NewMsOnlyTest {

        @Autowired
        private FileBatchingService fileBatchingService;

        @Test
        public void testNoDeprecationWarningForMsOnly(CapturedOutput output) {
            // Just initializing the service should not trigger deprecation warning
            assertNotNull(fileBatchingService);
            
            // Check that no deprecation warning is logged
            String logOutput = output.getOut();
            assertFalse(logOutput.contains("DEPRECATED"), 
                "Should not log deprecation warning: " + logOutput);
            assertTrue(logOutput.contains("Using batch interval of 3000 milliseconds"), 
                "Should log effective interval: " + logOutput);
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
        "DXR_BATCH_INTERVAL_MS=2000",
        "DXR_BATCH_INTERVAL_SEC=5"
    })
    @ExtendWith(OutputCaptureExtension.class)
    class BothSetTest {

        @Autowired
        private FileBatchingService fileBatchingService;

        @Test
        public void testBothVariablesSetPreferMs(CapturedOutput output) {
            // Just initializing the service should prefer MS and warn about SEC
            assertNotNull(fileBatchingService);
            
            // Check that both deprecation and precedence warnings are logged
            String logOutput = output.getOut();
            assertTrue(logOutput.contains("Both DXR_BATCH_INTERVAL_MS and DXR_BATCH_INTERVAL_SEC are set"), 
                "Should log precedence warning: " + logOutput);
            assertTrue(logOutput.contains("Using DXR_BATCH_INTERVAL_MS and ignoring DXR_BATCH_INTERVAL_SEC"), 
                "Should log which one is used: " + logOutput);
            assertTrue(logOutput.contains("DEPRECATED: DXR_BATCH_INTERVAL_SEC is deprecated"), 
                "Should log deprecation warning: " + logOutput);
            assertTrue(logOutput.contains("Using batch interval of 2000 milliseconds"), 
                "Should use MS value: " + logOutput);
        }
    }
}