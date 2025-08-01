package com.odc.syncwrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
public class ClassificationController {

    private static final Logger logger = LoggerFactory.getLogger(ClassificationController.class);

    @Autowired
    private FileBatchingService fileBatchingService;

    private ResponseEntity<?> formatResponse(FileBatchingService.FileClassificationResult result, HttpStatus status, boolean pretty) {
        if (!pretty) {
            return ResponseEntity.status(status).body(result);
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            String prettyJson = mapper.writeValueAsString(result);
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(prettyJson);
        } catch (Exception e) {
            logger.error("Failed to format pretty JSON response: {}", e.getMessage());
            return ResponseEntity.status(status).body(result);
        }
    }

    @PostMapping("/classify-file")
    public ResponseEntity<?> classifyFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "pretty", required = false, defaultValue = "false") boolean pretty,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        logger.info("Received file for classification: {} (size: {} bytes)",
                   file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            String errorMsg = "Empty file provided";
            logger.error("Classification request failed for '{}': {}", file.getOriginalFilename(), errorMsg);
            FileBatchingService.FileClassificationResult errorResult = new FileBatchingService.FileClassificationResult(
                file.getOriginalFilename(),
                "FAILED",
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                null,
                java.util.Collections.emptyList()
            );
            return formatResponse(errorResult, HttpStatus.BAD_REQUEST, pretty);
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
                logger.warn("Classification failed for '{}' with status: {}", 
                    result.filename(), result.status());
            }

            return formatResponse(result, HttpStatus.OK, pretty);
        } catch (Exception e) {
            String errorMsg = "Internal server error: " + e.getMessage();
            logger.error("Error processing file classification for '{}': {}",
                        file.getOriginalFilename(), e.getMessage(), e);
            FileBatchingService.FileClassificationResult errorResult = new FileBatchingService.FileClassificationResult(
                file.getOriginalFilename(),
                "FAILED",
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                null,
                java.util.Collections.emptyList()
            );
            return formatResponse(errorResult, HttpStatus.INTERNAL_SERVER_ERROR, pretty);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}