package com.odc.speedcheck;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
public class ClassificationController {

    @Autowired
    private FileBatchingService fileBatchingService;

    @PostMapping("/classify-file")
    public ResponseEntity<FileBatchingService.FileClassificationResult> classifyFile(
            @RequestParam("file") MultipartFile file) {
        
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                new FileBatchingService.FileClassificationResult(
                    file.getOriginalFilename(), 
                    "FAILED", 
                    java.util.Collections.emptyList()
                )
            );
        }

        try {
            CompletableFuture<FileBatchingService.FileClassificationResult> future = 
                fileBatchingService.processFile(file);
            
            FileBatchingService.FileClassificationResult result = future.get(300, TimeUnit.SECONDS);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new FileBatchingService.FileClassificationResult(
                    file.getOriginalFilename(), 
                    "FAILED", 
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