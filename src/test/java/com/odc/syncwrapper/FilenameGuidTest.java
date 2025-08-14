package com.odc.syncwrapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "DXR_BASE_URL=https://demo.dataxray.io/api",
    "DXR_API_KEY=test-key",
    "DXR_FIRST_ODC_DATASOURCE_ID=290",
    "DXR_ODC_DATASOURCE_COUNT=2",
    "DXR_MAX_BATCH_SIZE=2",
    "DXR_BATCH_INTERVAL_MS=1000"
})
public class FilenameGuidTest {

    @Autowired
    private FileBatchingService fileBatchingService;

    @Test
    public void testUniqueFilenameGeneration() {
        // Test that createUniqueFilename method generates unique filenames
        String originalFilename = "test.txt";
        
        // Use reflection to test the private method
        try {
            java.lang.reflect.Method method = FileBatchingService.class.getDeclaredMethod("createUniqueFilename", String.class);
            method.setAccessible(true);
            
            String uniqueFilename1 = (String) method.invoke(fileBatchingService, originalFilename);
            String uniqueFilename2 = (String) method.invoke(fileBatchingService, originalFilename);
            
            // Both should contain the original filename
            assertTrue(uniqueFilename1.contains("test"));
            assertTrue(uniqueFilename2.contains("test"));
            
            // Both should end with .txt
            assertTrue(uniqueFilename1.endsWith(".txt"));
            assertTrue(uniqueFilename2.endsWith(".txt"));
            
            // They should be different due to different GUIDs
            assertNotEquals(uniqueFilename1, uniqueFilename2);
            
            // Should contain UUID pattern (36 characters including hyphens)
            assertTrue(uniqueFilename1.length() > originalFilename.length() + 36);
            assertTrue(uniqueFilename2.length() > originalFilename.length() + 36);
            
        } catch (Exception e) {
            fail("Failed to test unique filename generation: " + e.getMessage());
        }
    }

    @Test
    public void testFilenameWithoutExtension() {
        try {
            java.lang.reflect.Method method = FileBatchingService.class.getDeclaredMethod("createUniqueFilename", String.class);
            method.setAccessible(true);
            
            String uniqueFilename = (String) method.invoke(fileBatchingService, "testfile");
            
            // Should contain the original filename
            assertTrue(uniqueFilename.contains("testfile"));
            
            // Should be longer due to GUID
            assertTrue(uniqueFilename.length() > "testfile".length() + 36);
            
        } catch (Exception e) {
            fail("Failed to test filename without extension: " + e.getMessage());
        }
    }

    @Test
    public void testNullFilename() {
        try {
            java.lang.reflect.Method method = FileBatchingService.class.getDeclaredMethod("createUniqueFilename", String.class);
            method.setAccessible(true);
            
            String uniqueFilename = (String) method.invoke(fileBatchingService, (String) null);
            
            // Should be just the GUID
            assertNotNull(uniqueFilename);
            assertTrue(uniqueFilename.length() == 36); // UUID length
            
        } catch (Exception e) {
            fail("Failed to test null filename: " + e.getMessage());
        }
    }
}