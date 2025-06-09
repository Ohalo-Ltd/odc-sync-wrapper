package com.odc.speedcheck;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SampleFileTest {
    @Test
    void sampleFileExists() {
        Path file = Path.of("samples/sample.txt");
        assertTrue(Files.exists(file), "Sample file should exist");
    }
}
