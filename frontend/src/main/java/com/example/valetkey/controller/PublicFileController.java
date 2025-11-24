package com.example.valetkey.controller;

import com.example.valetkey.model.Resource;
import com.example.valetkey.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/public/files")
public class    PublicFileController {

    @Autowired
    private FileService fileService;

    /**
     * Get public file information by token
     */
    @GetMapping("/{token}")
    public ResponseEntity<?> getPublicFile(@PathVariable String token) {
        try {
            Resource resource = fileService.getFileByPublicToken(token);

            Map<String, Object> response = new HashMap<>();
            response.put("id", resource.getId());
            response.put("fileName", resource.getFileName());
            response.put("fileSize", resource.getFileSize());
            response.put("contentType", resource.getContentType());
            response.put("uploadedAt", resource.getUploadedAt());
            response.put("uploader", resource.getUploader().getUsername());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting public file", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Invalid or expired public link"));
        }
    }

    /**
     * Get download URL for a public file
     */
    @GetMapping("/{token}/download")
    public ResponseEntity<?> getPublicDownloadUrl(@PathVariable String token) {
        try {
            String downloadUrl = fileService.getPublicDownloadUrl(token);

            return ResponseEntity.ok(Map.of(
                "downloadUrl", downloadUrl,
                "expiresInMinutes", 60
            ));

        } catch (Exception e) {
            log.error("Error generating public download URL", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Invalid or expired public link"));
        }
    }
}

