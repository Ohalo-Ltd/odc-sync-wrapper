package com.odc.syncwrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
public class ClassificationController {

    private static final Logger logger = LoggerFactory.getLogger(ClassificationController.class);

    @Autowired
    private FileBatchingService fileBatchingService;

    @PostMapping("/classify-file")
    public ResponseEntity<FileBatchingService.FileClassificationResult> classifyFile(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        logger.info("Received file for classification: {} (size: {} bytes)",
                   file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            String errorMsg = "Empty file provided";
            logger.error("Classification request failed for '{}': {}", file.getOriginalFilename(), errorMsg);
            return ResponseEntity.badRequest().body(
                new FileBatchingService.FileClassificationResult(
                    file.getOriginalFilename(),
                    "FAILED",
                    java.util.Collections.singletonMap("error", errorMsg),
                    java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(),
                    null,
                    java.util.Collections.emptyList()
                )
            );
        }

        String apiKey = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            apiKey = authHeader.substring(7);
        }

        try {
            CompletableFuture<FileBatchingService.FileClassificationResult> future =
                fileBatchingService.processFile(file, apiKey);

            FileBatchingService.FileClassificationResult result = future.get(1200, TimeUnit.SECONDS);
            
            if ("FINISHED".equals(result.status())) {
                logger.info("Classification completed successfully for '{}' with {} metadata fields, {} annotators, {} labels, {} annotation results", 
                    result.filename(), result.extractedMetadata().size(), result.annotators().size(), result.labels().size(), result.annotationResults().size());
            } else {
                logger.warn("Classification failed for '{}' with status: {} and error: {}", 
                    result.filename(), result.status(), result.extractedMetadata().get("error"));
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            String errorMsg = "Internal server error: " + e.getMessage();
            logger.error("Error processing file classification for '{}': {}",
                        file.getOriginalFilename(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new FileBatchingService.FileClassificationResult(
                    file.getOriginalFilename(),
                    "FAILED",
                    java.util.Collections.singletonMap("error", errorMsg),
                    java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(),
                    null,
                    java.util.Collections.emptyList()
                )
            );
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}