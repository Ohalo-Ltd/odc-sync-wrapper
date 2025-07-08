package com.odc.syncwrapper;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping
public class ClassifyController {
    private final BatchService service;

    public ClassifyController(BatchService service) {
        this.service = service;
    }

    @PostMapping(path = "/classify-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> classifyFile(@RequestParam("file") MultipartFile file) throws Exception {
        return service.classify(file).get();
    }
}
