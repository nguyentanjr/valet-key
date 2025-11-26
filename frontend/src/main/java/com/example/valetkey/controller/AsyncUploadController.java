package com.example.valetkey.controller;

import com.example.valetkey.model.User;
import com.example.valetkey.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/async-upload")
public class AsyncUploadController {

    @Autowired
    private AsyncUploadService asyncUploadService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Initiate async upload for large file
     */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiateAsyncUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) Long folderId,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());

            // Initiate async upload job
            UploadJob job = asyncUploadService.initiateAsyncUpload(file, user, folderId);

            // Start processing in background
            asyncUploadService.processUploadJob(job.getJobId());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Upload job created and processing in background");
            response.put("jobId", job.getJobId());
            response.put("status", job.getStatus());
            response.put("fileName", job.getFileName());
            response.put("fileSize", job.getFileSize());

            log.info("Async upload initiated: jobId={}, file={}, user={}", 
                job.getJobId(), file.getOriginalFilename(), user.getUsername());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error initiating async upload", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to initiate upload: " + e.getMessage()));
        }
    }

    /**
     * Get upload job status
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            UploadJob job = asyncUploadService.getJobStatus(jobId, user);

            Map<String, Object> response = jobToMap(job);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting job status", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get all active upload jobs for current user
     */
    @GetMapping("/active")
    public ResponseEntity<?> getActiveJobs(HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            List<UploadJob> jobs = asyncUploadService.getActiveJobs(user);

            List<Map<String, Object>> response = jobs.stream()
                .map(this::jobToMap)
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "activeJobs", response,
                "count", response.size()
            ));

        } catch (Exception e) {
            log.error("Error getting active jobs", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Cancel upload job
     */
    @PostMapping("/cancel/{jobId}")
    public ResponseEntity<?> cancelJob(@PathVariable String jobId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            asyncUploadService.cancelUploadJob(jobId, user);

            log.info("Upload job cancelled: jobId={}, user={}", jobId, user.getUsername());

            return ResponseEntity.ok(Map.of(
                "message", "Upload job cancelled successfully",
                "jobId", jobId
            ));

        } catch (Exception e) {
            log.error("Error cancelling job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    // Helper method to convert UploadJob to Map
    private Map<String, Object> jobToMap(UploadJob job) {
        Map<String, Object> map = new HashMap<>();
        map.put("jobId", job.getJobId());
        map.put("fileName", job.getFileName());
        map.put("fileSize", job.getFileSize());
        map.put("status", job.getStatus());
        map.put("uploadedBytes", job.getUploadedBytes());
        map.put("progressPercentage", job.getProgressPercentage());
        map.put("errorMessage", job.getErrorMessage());
        map.put("createdAt", job.getCreatedAt());
        map.put("startedAt", job.getStartedAt());
        map.put("completedAt", job.getCompletedAt());
        map.put("resourceId", job.getResourceId());
        return map;
    }
}


