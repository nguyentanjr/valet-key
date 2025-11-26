package com.example.valetkey.service;

import com.example.valetkey.model.Folder;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.FolderRepository;
import com.example.valetkey.repository.ResourceRepository;
import com.example.valetkey.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AsyncUploadService {

    @Autowired
    private UploadJobRepository uploadJobRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private AzureSasService azureSasService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    private static final String TEMP_UPLOAD_DIR = "temp_uploads";

    /**
     * Create upload job and save file temporarily
     */
    @Transactional
    public UploadJob initiateAsyncUpload(MultipartFile file, User user, Long folderId) throws IOException {
        // Check quota
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(actualStorageUsed);
        userRepository.save(user);

        if (!user.hasStorageSpace(file.getSize())) {
            throw new RuntimeException("Storage quota exceeded. Available: " + 
                formatBytes(user.getRemainingStorage()) + ", Required: " + formatBytes(file.getSize()));
        }

        // Create temp directory if not exists
        Path tempDir = Paths.get(TEMP_UPLOAD_DIR);
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        // Save file temporarily
        String tempFileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
        Path tempFilePath = tempDir.resolve(tempFileName);
        Files.copy(file.getInputStream(), tempFilePath, StandardCopyOption.REPLACE_EXISTING);

        // Create upload job
        UploadJob job = new UploadJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setUser(user);
        job.setFileName(file.getOriginalFilename());
        job.setFileSize(file.getSize());
        job.setFolderId(folderId);
        job.setStatus(UploadJob.JobStatus.PENDING);
        job.setTempFilePath(tempFilePath.toString());

        job = uploadJobRepository.save(job);

        log.info("Async upload job created: {} for file: {} by user: {}", 
            job.getJobId(), file.getOriginalFilename(), user.getUsername());

        return job;
    }

    /**
     * Process upload job asynchronously
     * NOTE: No @Transactional here - we manage transactions manually per operation
     */
    @Async("asyncUploadExecutor")
    public void processUploadJob(String jobId) {
        log.info("Starting async upload processing for job: {}", jobId);

        UploadJob job = findJobById(jobId);
        if (job == null) {
            log.error("Job not found: {}", jobId);
            return;
        }

        // Check if already completed or cancelled
        if (job.getStatus() != UploadJob.JobStatus.PENDING) {
            log.warn("Job {} is not in PENDING status, skipping. Current status: {}", jobId, job.getStatus());
            return;
        }

        // Mark as started (separate transaction)
        markJobAsStarted(jobId);
        job.setStatus(UploadJob.JobStatus.UPLOADING);
        notifyProgress(job);

        try {
            // Load user and folder data (short transaction)
            User user = loadUser(job.getUser().getId());
            Folder folder = job.getFolderId() != null ? loadFolder(job.getFolderId()) : null;

            // Generate unique file name and blob path
            String uniqueFileName = generateUniqueFileName(job.getFileName());
            String folderPath = (folder != null) ? folder.getFullPath() : "";
            String blobPath = "user-" + user.getId() + folderPath + "/" + uniqueFileName;

            // Upload to Azure with progress tracking (NO DATABASE CONNECTION NEEDED)
            File tempFile = new File(job.getTempFilePath());
            uploadFileToAzureWithProgress(tempFile, blobPath, job);

            // Save to database (separate transaction)
            Long resourceId = saveUploadedFile(job, blobPath, user, folder);

            // Mark job as completed (separate transaction)
            markJobAsCompleted(jobId, resourceId, blobPath);

            // Delete temp file
            deleteTempFile(tempFile);

            // Refresh job for notification
            job = findJobById(jobId);
            notifyProgress(job);

            log.info("Async upload completed successfully: {} - File: {}", jobId, job.getFileName());

        } catch (Exception e) {
            log.error("Async upload failed for job: {} - Error: {}", jobId, e.getMessage(), e);

            // Mark as failed (separate transaction)
            markJobAsFailed(jobId, e.getMessage());

            // Clean up temp file
            try {
                deleteTempFile(new File(job.getTempFilePath()));
            } catch (Exception cleanupEx) {
                log.error("Failed to cleanup temp file: {}", job.getTempFilePath(), cleanupEx);
            }

            // Refresh job for notification
            job = findJobById(jobId);
            if (job != null) {
                notifyProgress(job);
            }
        }
    }

    /**
     * Upload file to Azure with progress tracking
     * NO DATABASE TRANSACTIONS HERE - only Azure operations
     */
    private void uploadFileToAzureWithProgress(File file, String blobPath, UploadJob job) throws IOException {
        long totalSize = file.length();
        long uploadedBytes = 0;
        int chunkSize = 5 * 1024 * 1024; // 5MB chunks
        int updateProgressEvery = 10; // Update DB every 10 chunks (50MB)

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[chunkSize];
            int chunkIndex = 0;
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                // Check if job was cancelled (quick query, separate transaction)
                if (isJobCancelled(job.getJobId())) {
                    log.info("Upload cancelled by user for job: {}", job.getJobId());
                    throw new RuntimeException("Upload cancelled by user");
                }

                byte[] chunkData = (bytesRead == buffer.length) ? buffer : java.util.Arrays.copyOf(buffer, bytesRead);

                // Upload chunk to Azure (NO DATABASE CONNECTION)
                azureSasService.uploadChunk(blobPath, chunkIndex, chunkData);

                uploadedBytes += bytesRead;
                chunkIndex++;

                // Update progress periodically (not every chunk to reduce DB load)
                if (chunkIndex % updateProgressEvery == 0 || uploadedBytes >= totalSize) {
                    updateJobProgress(job.getJobId(), uploadedBytes, totalSize);
                    log.debug("Progress for job {}: {} / {} bytes ({}%)", 
                        job.getJobId(), uploadedBytes, totalSize, (uploadedBytes * 100 / totalSize));
                }
            }

            // Commit all blocks to Azure (NO DATABASE CONNECTION)
            List<String> blockIds = new java.util.ArrayList<>();
            for (int i = 0; i < chunkIndex; i++) {
                String blockId = java.util.Base64.getEncoder().encodeToString(
                    String.format("%08d", i).getBytes()
                );
                blockIds.add(blockId);
            }
            azureSasService.commitBlocks(blobPath, blockIds);

            // Final progress update
            updateJobProgress(job.getJobId(), totalSize, totalSize);

            log.info("Azure upload completed for job {}: {} chunks committed", job.getJobId(), chunkIndex);
        }
    }

    /**
     * Cancel upload job
     */
    @Transactional
    public void cancelUploadJob(String jobId, User user) {
        UploadJob job = uploadJobRepository.findByJobId(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        if (!job.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied");
        }

        if (job.getStatus() == UploadJob.JobStatus.COMPLETED) {
            throw new RuntimeException("Cannot cancel completed job");
        }

        job.markAsCancelled();
        uploadJobRepository.save(job);

        // Clean up temp file
        try {
            deleteTempFile(new File(job.getTempFilePath()));
        } catch (Exception e) {
            log.error("Failed to cleanup temp file after cancellation: {}", job.getTempFilePath(), e);
        }

        notifyProgress(job);

        log.info("Upload job cancelled: {} by user: {}", jobId, user.getUsername());
    }

    /**
     * Get upload job status
     */
    @Transactional(readOnly = true)
    public UploadJob getJobStatus(String jobId, User user) {
        UploadJob job = uploadJobRepository.findByJobId(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        if (!job.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied");
        }

        return job;
    }

    /**
     * Get all active jobs for user
     */
    @Transactional(readOnly = true)
    public List<UploadJob> getActiveJobs(User user) {
        return uploadJobRepository.findActiveJobsByUser(user);
    }

    /**
     * Notify progress via WebSocket
     */
    private void notifyProgress(UploadJob job) {
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSendToUser(
                    job.getUser().getId().toString(),
                    "/queue/upload-progress",
                    job
                );
            } catch (Exception e) {
                log.debug("WebSocket notification failed (may not be connected): {}", e.getMessage());
            }
        }
    }

    // Helper methods with separate transactions
    
    @Transactional(readOnly = true)
    private UploadJob findJobById(String jobId) {
        return uploadJobRepository.findByJobId(jobId).orElse(null);
    }
    
    @Transactional
    private void markJobAsStarted(String jobId) {
        UploadJob job = uploadJobRepository.findByJobId(jobId).orElse(null);
        if (job != null) {
            job.markAsStarted();
            uploadJobRepository.save(job);
        }
    }
    
    @Transactional(readOnly = true)
    private User loadUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }
    
    @Transactional(readOnly = true)
    private Folder loadFolder(Long folderId) {
        return folderRepository.findById(folderId)
            .orElseThrow(() -> new RuntimeException("Folder not found"));
    }
    
    @Transactional(readOnly = true)
    private boolean isJobCancelled(String jobId) {
        UploadJob job = uploadJobRepository.findByJobId(jobId).orElse(null);
        return job != null && job.getStatus() == UploadJob.JobStatus.CANCELLED;
    }
    
    @Transactional
    private void updateJobProgress(String jobId, long uploadedBytes, long totalSize) {
        UploadJob job = uploadJobRepository.findByJobId(jobId).orElse(null);
        if (job != null) {
            job.updateProgress(uploadedBytes, totalSize);
            uploadJobRepository.save(job);
            
            // Notify via WebSocket
            try {
                notifyProgress(job);
            } catch (Exception e) {
                // Ignore WebSocket errors
            }
        }
    }
    
    @Transactional
    private Long saveUploadedFile(UploadJob job, String blobPath, User user, Folder folder) {
        // Create resource in database
        Resource resource = new Resource();
        resource.setFileName(job.getFileName());
        resource.setOriginalName(job.getFileName());
        resource.setFilePath(blobPath);
        resource.setUploader(user);
        resource.setFolder(folder);
        resource.setFileSize(job.getFileSize());
        resource.setContentType(guessContentType(job.getFileName()));

        resource = resourceRepository.save(resource);

        // Update user storage
        Long finalStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(finalStorageUsed + job.getFileSize());
        userRepository.save(user);
        
        return resource.getId();
    }
    
    @Transactional
    private void markJobAsCompleted(String jobId, Long resourceId, String filePath) {
        UploadJob job = uploadJobRepository.findByJobId(jobId).orElse(null);
        if (job != null) {
            job.markAsCompleted(resourceId, filePath);
            uploadJobRepository.save(job);
        }
    }
    
    @Transactional
    private void markJobAsFailed(String jobId, String errorMessage) {
        UploadJob job = uploadJobRepository.findByJobId(jobId).orElse(null);
        if (job != null) {
            job.markAsFailed(errorMessage);
            uploadJobRepository.save(job);
        }
    }
    
    private void deleteTempFile(File file) {
        try {
            if (file.exists()) {
                Files.delete(file.toPath());
                log.debug("Deleted temp file: {}", file.getPath());
            }
        } catch (IOException e) {
            log.error("Failed to delete temp file: {}", file.getPath(), e);
        }
    }

    private String generateUniqueFileName(String originalName) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String randomStr = UUID.randomUUID().toString().substring(0, 8);
        
        int lastDotIndex = originalName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            String name = originalName.substring(0, lastDotIndex);
            String extension = originalName.substring(lastDotIndex);
            return name + "_" + timestamp + "_" + randomStr + extension;
        } else {
            return originalName + "_" + timestamp + "_" + randomStr;
        }
    }

    private String guessContentType(String fileName) {
        try {
            Path path = Paths.get(fileName);
            return Files.probeContentType(path);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private String formatBytes(Long bytes) {
        if (bytes == null || bytes < 0) return "0 B";
        
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double size = bytes.doubleValue();
        
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        
        return String.format("%.2f %s", size, units[unitIndex]);
    }
}

