package com.example.valetkey.service;

import com.example.valetkey.model.Folder;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.FolderRepository;
import com.example.valetkey.repository.ResourceRepository;
import com.example.valetkey.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileService {

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AzureSasService azureSasService;

    /**
     * Initialize resume upload session
     */
    @Transactional
    public String initiateResumeUpload(String fileName, Long fileSize, User user, Long folderId) {
        // Recalculate actual storage used from DB before checking quota
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(actualStorageUsed);
        userRepository.save(user);

        // Check storage quota with accurate storageUsed
        if (!user.hasStorageSpace(fileSize)) {
            throw new RuntimeException("Storage quota exceeded. Available: " + 
                formatBytes(user.getRemainingStorage()) + ", Required: " + formatBytes(fileSize));
        }

        String uploadSessionId = UUID.randomUUID().toString();
        String uniqueFileName = generateUniqueFileName(fileName);
        
        String folderPath = "";
        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
            folderPath = folder.getFullPath();
        }

        String blobPath = "user-" + user.getId() + folderPath + "/" + uniqueFileName;

        // Create resource with upload session
        Resource resource = new Resource();
        resource.setFileName(fileName);
        resource.setFilePath(blobPath);
        resource.setUploader(user);
        resource.setFolder(folder);
        resource.setFileSize(fileSize);
        resource.setUploadSessionId(uploadSessionId);
        resource.setUploadStatus("PENDING");
        resource.setUploadProgress(0L);

        resourceRepository.save(resource);

        return uploadSessionId;
    }

    /**
     * Upload file chunk for resume upload
     */
    @Transactional
    public void uploadChunk(String sessionId, int chunkIndex, byte[] chunkData, User user) throws IOException {
        Resource resource = resourceRepository.findByUploadSessionId(sessionId)
            .orElseThrow(() -> new RuntimeException("Upload session not found"));

        if (!resource.getUploader().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied");
        }

        // Upload chunk to Azure (using block blob)
        azureSasService.uploadChunk(resource.getFilePath(), chunkIndex, chunkData);

        // Update progress
        resource.setUploadProgress(resource.getUploadProgress() + chunkData.length);
        resource.setUploadStatus("UPLOADING");
        resourceRepository.save(resource);

        log.debug("Uploaded chunk {} for session {}, progress: {} / {}", 
            chunkIndex, sessionId, resource.getUploadProgress(), resource.getFileSize());
    }

    /**
     * Complete resume upload (commit blocks)
     */
    @Transactional
    public Resource completeResumeUpload(String sessionId, List<String> blockIds, User user) throws IOException {
        Resource resource = resourceRepository.findByUploadSessionId(sessionId)
            .orElseThrow(() -> new RuntimeException("Upload session not found"));

        if (!resource.getUploader().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied");
        }

        // Two-Phase Commit for chunk upload completion
        boolean azureCommitSucceeded = false;
        
        try {
            // Phase 1: Commit blocks in Azure
            log.debug("Phase 1: Committing {} blocks to Azure for session {}", blockIds.size(), sessionId);
        azureSasService.commitBlocks(resource.getFilePath(), blockIds);
            azureCommitSucceeded = true;
            log.debug("Phase 1 completed: Blocks committed to Azure");

            // Phase 2: Update database status
            log.debug("Phase 2: Updating database status to COMPLETED");
        resource.setUploadStatus("COMPLETED");
        resource.setUploadProgress(resource.getFileSize());
        resource.setUploadSessionId(null);
        
        resource = resourceRepository.save(resource);
        
        // Recalculate and update user storage from DB to ensure accuracy
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(actualStorageUsed);
        userRepository.save(user);

            log.info("Resume upload completed (2PC): {} by user: {}", resource.getFileName(), user.getUsername());

        return resource;
            
        } catch (Exception e) {
            log.error("Failed to complete resume upload: {}", e.getMessage());
            
            if (azureCommitSucceeded) {
                // Azure commit succeeded but DB update failed
                // File exists in Azure but DB shows UPLOADING status
                log.error("CRITICAL: Azure commit succeeded but DB update failed. File {} in inconsistent state.", resource.getFilePath());
                // Keep the DB record for manual reconciliation or scheduled cleanup
            }
            
            // Mark as FAILED so it can be retried or cleaned up
            try {
                resource.setUploadStatus("FAILED");
                resourceRepository.save(resource);
            } catch (Exception dbEx) {
                log.error("Failed to mark upload as FAILED in DB", dbEx);
            }
            
            throw new RuntimeException("Failed to complete upload: " + e.getMessage(), e);
        }
    }

    /**
     * Get upload status
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUploadStatus(String sessionId, User user) {
        Resource resource = resourceRepository.findByUploadSessionId(sessionId)
            .orElseThrow(() -> new RuntimeException("Upload session not found"));

        if (!resource.getUploader().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied");
        }

        Map<String, Object> status = new HashMap<>();
        status.put("sessionId", sessionId);
        status.put("status", resource.getUploadStatus());
        status.put("progress", resource.getUploadProgress());
        status.put("totalSize", resource.getFileSize());
        status.put("percentage", resource.getFileSize() > 0 
            ? (resource.getUploadProgress() * 100.0 / resource.getFileSize()) : 0);

        return status;
    }

    /**
     * Get uncommitted blocks for resume capability
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUncommittedBlocks(String sessionId, User user) {
        Resource resource = resourceRepository.findByUploadSessionId(sessionId)
            .orElseThrow(() -> new RuntimeException("Upload session not found"));

        if (!resource.getUploader().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied");
        }

        // Get uncommitted blocks from Azure
        List<String> uncommittedBlockIds = azureSasService.getUncommittedBlocks(resource.getFilePath());
        
        // Calculate uploaded chunks from block IDs
        int uploadedChunks = uncommittedBlockIds.size();
        long uploadedBytes = resource.getUploadProgress();

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("fileName", resource.getFileName());
        result.put("fileSize", resource.getFileSize());
        result.put("uploadedChunks", uploadedChunks);
        result.put("uploadedBytes", uploadedBytes);
        result.put("uncommittedBlockIds", uncommittedBlockIds);
        result.put("status", resource.getUploadStatus());
        result.put("blobPath", resource.getFilePath());

        log.debug("Uncommitted blocks for session {}: {} blocks, {} bytes", 
            sessionId, uploadedChunks, uploadedBytes);

        return result;
    }

    /**
     * Upload a file through the backend
     */
    /**
     * Upload file - optimized to prevent connection leaks
     * Azure upload is done OUTSIDE transaction to avoid holding DB connection during I/O
     */
    @Caching(evict = {
        @CacheEvict(value = "userStorage", key = "#user.id"),
        @CacheEvict(value = "fileList", allEntries = true)
    })
    public Resource uploadFile(MultipartFile file, User user, Long folderId, String customFileName) throws IOException {
        // Check if user has permission
        if (!user.isCreate() || !user.isWrite()) {
            throw new RuntimeException("User does not have permission to upload files");
        }

        // Validate file
        if (file.isEmpty()) {
            throw new RuntimeException("Cannot upload empty file");
        }

        long fileSize = file.getSize();

        // Phase 0: Validate and prepare (short transaction)
        String fileName;
        Folder folder;
        String blobPath;
        Long finalStorageUsed;
        
        try {
            fileName = prepareUpload(file, user, folderId, customFileName, fileSize);
            folder = (folderId != null) ? folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found")) : null;
            
            String folderPath = (folder != null) ? folder.getFullPath() : "";
            String uniqueFileName = generateUniqueFileName(fileName);
            blobPath = "user-" + user.getId() + folderPath + "/" + uniqueFileName;
            
            // Refresh user and check quota one final time
            user = userRepository.findById(user.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));
            finalStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
            user.setStorageUsed(finalStorageUsed);
            
            if (!user.hasStorageSpace(fileSize)) {
                throw new RuntimeException("Storage quota exceeded. Available: " + 
                    formatBytes(user.getRemainingStorage()) + ", Required: " + formatBytes(fileSize));
            }
        } catch (Exception e) {
            log.error("Upload preparation failed: {}", e.getMessage());
            throw e;
        }

        // Phase 1: Upload to Azure (OUTSIDE transaction - no DB connection held)
        String azureUrl = null;
        boolean azureUploadSucceeded = false;
        
        try {
            log.debug("Phase 1: Uploading file to Azure: {} (outside transaction)", blobPath);
            azureUrl = azureSasService.uploadFile(file, blobPath);
            azureUploadSucceeded = true;
            log.debug("Phase 1 completed: File uploaded to Azure successfully");
        } catch (Exception e) {
            log.error("Phase 1 failed (Azure upload): {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to Azure: " + e.getMessage(), e);
        }

        // Phase 2: Save to database (short transaction - only DB operations)
        Resource resource = null;
        try {
            resource = saveFileMetadata(fileName, file, blobPath, user, folder, fileSize, finalStorageUsed);
            log.info("File uploaded successfully (2PC): {} by user: {}", fileName, user.getUsername());
        } catch (Exception e) {
            // Rollback: Phase 2 failed, cleanup Phase 1
            log.error("Phase 2 failed (Database): {}", e.getMessage());
            
            if (azureUploadSucceeded) {
                log.warn("Rolling back: Deleting Azure blob {}", blobPath);
                try {
                    azureSasService.deleteBlob(blobPath);
                    log.info("Rollback successful: Azure blob deleted");
                } catch (Exception cleanupEx) {
                    log.error("CRITICAL: Failed to rollback Azure blob {}. Will be cleaned by scheduled job.", blobPath, cleanupEx);
                }
            }
            
            throw new RuntimeException("Failed to save file metadata: " + e.getMessage(), e);
        }

        return resource;
    }

    /**
     * Prepare upload - validate and check quota (short transaction)
     */
    @Transactional(timeout = 10)
    private String prepareUpload(MultipartFile file, User user, Long folderId, String customFileName, long fileSize) {
        // Recalculate actual storage used from DB before checking quota
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(actualStorageUsed);
        userRepository.save(user);

        // Check storage quota with accurate storageUsed
        if (!user.hasStorageSpace(fileSize)) {
            throw new RuntimeException("Storage quota exceeded. Available: " + 
                formatBytes(user.getRemainingStorage()) + ", Required: " + formatBytes(fileSize));
        }

        // Determine file name
        String fileName = (customFileName != null && !customFileName.trim().isEmpty()) 
            ? customFileName.trim() 
            : file.getOriginalFilename();

        if (fileName == null || fileName.isEmpty()) {
            fileName = "unnamed_" + System.currentTimeMillis();
        }

        // Verify folder belongs to user if specified
        if (folderId != null) {
            Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
            
            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to this folder");
            }
        }

        return fileName;
    }

    /**
     * Save file metadata to database (short transaction - only DB operations)
     */
    @Transactional(timeout = 10, rollbackFor = {RuntimeException.class, Exception.class})
    private Resource saveFileMetadata(String fileName, MultipartFile file, String blobPath, 
                                      User user, Folder folder, long fileSize, Long finalStorageUsed) {
        log.debug("Phase 2: Saving file metadata to database");
        
        Resource resource = new Resource();
        resource.setFileName(fileName);
        resource.setOriginalName(file.getOriginalFilename());
        resource.setFilePath(blobPath);
        resource.setUploader(user);
        resource.setFolder(folder);
        resource.setFileSize(fileSize);
        resource.setContentType(file.getContentType());

        // Save to database
        resource = resourceRepository.save(resource);
        log.debug("Phase 2: Database record created with ID: {}", resource.getId());

        // Update user storage (use finalStorageUsed + fileSize to ensure accuracy)
        user.setStorageUsed(finalStorageUsed + fileSize);
        userRepository.save(user);
        log.debug("Phase 2: User storage updated");
        
        return resource;
    }

    /**
     * Get file by ID
     */
    @Transactional(readOnly = true)
    public Resource getFile(Long fileId, User user) {
        Resource resource = resourceRepository.findById(fileId)
            .orElseThrow(() -> new RuntimeException("File not found"));

        // Check ownership
        if (!resource.getUploader().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied to this file");
        }

        return resource;
    }

    /**
     * Get download URL for a file
     */
    @Transactional(readOnly = true)
    public String getDownloadUrl(Long fileId, User user) {
        Resource resource = getFile(fileId, user);

        user.setRead(true);
        if (!user.isRead()) {
            throw new RuntimeException("User does not have permission to download files");
        }

        int expiryMinutes = 10;
        return azureSasService.generateBlobReadSas(resource.getFilePath(), expiryMinutes, user);
    }

    /**
     * Delete a file (permanent delete)
     */
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "userStorage", key = "#user.id"),
        @CacheEvict(value = "fileMetadata", key = "#fileId + '_' + #user.id"),
        @CacheEvict(value = "fileList", allEntries = true)
    })
    public void deleteFile(Long fileId, User user) {
        Resource resource = getFile(fileId, user);

        // Delete from Azure
        azureSasService.deleteBlob(resource.getFilePath());

        // Update user storage
        user.setStorageUsed(user.getStorageUsed() - resource.getFileSize());
        userRepository.save(user);

        // Delete from database
        resourceRepository.delete(resource);

        log.info("File deleted: {} by user: {}", resource.getFileName(), user.getUsername());
    }

    /**
     * Get all files in a folder (without pagination - for select all)
     * Optimized: Uses repository list methods when possible, pagination only when needed
     */
    @Transactional(readOnly = true, timeout = 20)
    public List<Resource> getAllFiles(User user, Long folderId) {
        if (folderId == null) {
            // Use list method directly - more efficient
            return resourceRepository.findByUploaderOrderByUploadedAtDesc(user);
        } else {
            Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));

            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to this folder");
            }

            // For folders, paginate if there might be many files
            List<Resource> allFiles = new ArrayList<>();
            int page = 0;
            Page<Resource> pageResult;
            final int pageSize = 200; // Larger batch size to reduce round trips
            
            do {
                Pageable pageable = PageRequest.of(page, pageSize);
                pageResult = resourceRepository.findByUploaderAndFolderOrderByUploadedAtDesc(user, folder, pageable);
                allFiles.addAll(pageResult.getContent());
                page++;
            } while (pageResult.hasNext());
            
            return allFiles;
        }
    }

    /**
     * List files in a folder (with pagination)
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "fileList", key = "#user.id + '_' + #folderId + '_' + #page + '_' + #size")
    public Page<Resource> listFiles(User user, Long folderId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        if (folderId == null) {
            // List files in root
            return resourceRepository.findByUploaderAndFolderIsNullOrderByUploadedAtDesc(user, pageable);
        } else {
            Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));

            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to this folder");
            }

            return resourceRepository.findByUploaderAndFolderOrderByUploadedAtDesc(user, folder, pageable);
        }
    }

    /**
     * Search files by name
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "searchResults", key = "#user.id + '_' + #query + '_' + #page + '_' + #size")
    public Page<Resource> searchFiles(User user, String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return resourceRepository.searchByUploaderAndFileName(user, query, pageable);
    }

    /**
     * Get file metadata
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "fileMetadata", key = "#fileId + '_' + #user.id")
    public Map<String, Object> getFileMetadata(Long fileId, User user) {
        Resource resource = getFile(fileId, user);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("id", resource.getId());
        metadata.put("fileName", resource.getFileName());
        metadata.put("originalName", resource.getOriginalName());
        metadata.put("fileSize", resource.getFileSize());
        metadata.put("fileSizeFormatted", formatBytes(resource.getFileSize()));
        metadata.put("contentType", resource.getContentType());
        metadata.put("uploadedAt", resource.getUploadedAt());
        metadata.put("lastModified", resource.getLastModified());
        metadata.put("isPublic", resource.isPublic());
        
        if (resource.getFolder() != null) {
            metadata.put("folder", Map.of(
                "id", resource.getFolder().getId(),
                "name", resource.getFolder().getName(),
                "path", resource.getFolder().getFullPath()
            ));
        }

        if (resource.isPublic() && resource.getPublicLinkToken() != null) {
            metadata.put("publicLinkToken", resource.getPublicLinkToken());
            metadata.put("publicLinkCreatedAt", resource.getPublicLinkCreatedAt());
        }

        return metadata;
    }

    /**
     * Generate public sharing link
     */
    @Transactional
    public String generatePublicLink(Long fileId, User user) {
        Resource resource = getFile(fileId, user);
        
        if (resource.getPublicLinkToken() == null) {
            resource.generatePublicLinkToken();
            resourceRepository.save(resource);
        }

        return resource.getPublicLinkToken();
    }

    /**
     * Revoke public sharing link
     */
    @Transactional
    public void revokePublicLink(Long fileId, User user) {
        Resource resource = getFile(fileId, user);
        resource.revokePublicLink();
        resourceRepository.save(resource);
    }

    /**
     * Get file by public link token (no authentication required)
     */
    public Resource getFileByPublicToken(String token) {
        return resourceRepository.findByPublicLinkToken(token)
            .orElseThrow(() -> new RuntimeException("Invalid or expired public link"));
    }

    /**
     * Get download URL for public file
     */
    public String getPublicDownloadUrl(String token) {
        Resource resource = getFileByPublicToken(token);
        
        // Create a temporary user with read permission for SAS generation
        User tempUser = new User();
        tempUser.setRead(true);
        
        int expiryMinutes = 60; // Longer expiry for public links
        return azureSasService.generateBlobReadSas(resource.getFilePath(), expiryMinutes, tempUser);
    }

    /**
     * Get user storage info
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "userStorage", key = "#user.id")
    public Map<String, Object> getUserStorageInfo(User user) {
        // Recalculate actual storage used
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        
        // Update if different
        if (!actualStorageUsed.equals(user.getStorageUsed())) {
            user.setStorageUsed(actualStorageUsed);
            userRepository.save(user);
        }

        Map<String, Object> storageInfo = new HashMap<>();
        storageInfo.put("storageUsed", user.getStorageUsed());
        storageInfo.put("storageQuota", user.getStorageQuota());
        storageInfo.put("storageRemaining", user.getRemainingStorage());
        storageInfo.put("storageUsedFormatted", formatBytes(user.getStorageUsed()));
        storageInfo.put("storageQuotaFormatted", formatBytes(user.getStorageQuota()));
        storageInfo.put("storageRemainingFormatted", formatBytes(user.getRemainingStorage()));
        storageInfo.put("usagePercentage", String.format("%.2f", user.getStorageUsagePercentage()));

        return storageInfo;
    }

    /**
     * Move file to another folder
     */
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "fileMetadata", key = "#fileId"),
        @CacheEvict(value = "fileList", allEntries = true)
    })
    public Resource moveFile(Long fileId, Long targetFolderId, User user) {
        Resource resource = getFile(fileId, user);

        Folder targetFolder = null;
        if (targetFolderId != null) {
            targetFolder = folderRepository.findById(targetFolderId)
                .orElseThrow(() -> new RuntimeException("Target folder not found"));

            if (!targetFolder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to target folder");
            }
        }

        resource.setFolder(targetFolder);
        return resourceRepository.save(resource);
    }

    /**
     * Rename a file
     */
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "fileMetadata", key = "#fileId"),
        @CacheEvict(value = "fileList", allEntries = true)
    })
    public Resource renameFile(Long fileId, String newName, User user) {
        Resource resource = getFile(fileId, user);

        if (newName == null || newName.trim().isEmpty()) {
            throw new RuntimeException("Invalid file name");
        }

        resource.setFileName(newName.trim());
        return resourceRepository.save(resource);
    }


    /**
     * Bulk delete files
     */
    @Transactional
    public void bulkDeleteFiles(List<Long> fileIds, User user) {
        List<Resource> resources = resourceRepository.findByIdsAndUploader(fileIds, user);
        
        long totalSize = 0;
        for (Resource resource : resources) {
            // Delete from Azure
            azureSasService.deleteBlob(resource.getFilePath());
            
            // Update total size for storage calculation
            totalSize += resource.getFileSize();
            
            // Delete from database
            resourceRepository.delete(resource);
        }
        
        // Update user storage
        user.setStorageUsed(user.getStorageUsed() - totalSize);
        userRepository.save(user);
        
        log.info("Bulk deleted {} files by user: {}", resources.size(), user.getUsername());
    }

    /**
     * Bulk move files
     */
    @Transactional
    public void bulkMoveFiles(List<Long> fileIds, Long targetFolderId, User user) {
        Folder targetFolder = null;
        if (targetFolderId != null) {
            targetFolder = folderRepository.findById(targetFolderId)
                .orElseThrow(() -> new RuntimeException("Target folder not found"));

            if (!targetFolder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to target folder");
            }
        }

        List<Resource> resources = resourceRepository.findByIdsAndUploader(fileIds, user);
        
        for (Resource resource : resources) {
            resource.setFolder(targetFolder);
            resourceRepository.save(resource);
        }

        log.info("Bulk moved {} files by user: {}", resources.size(), user.getUsername());
    }

    /**
     * Get file by ID (including deleted files for trash operations)
     */
    public Resource getFileIncludingDeleted(Long fileId, User user) {
        return resourceRepository.findByIdAndUploader(fileId, user)
            .orElseThrow(() -> new RuntimeException("File not found"));
    }

    /**
     * Generate blob path for a file
     */
    public String generateBlobPath(String fileName, User user, Long folderId) {
        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
            
            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to folder");
            }
        }

        String folderPath = (folder != null) ? folder.getFullPath() : "";
        String uniqueFileName = generateUniqueFileName(fileName);
        return "user-" + user.getId() + folderPath + "/" + uniqueFileName;
    }

    /**
     * Generate SAS URL for direct upload to Azure
     */
    public String generateUploadSasUrl(String blobPath, int expiryMinutes, User user) {
        return azureSasService.generateBlobWriteSas(blobPath, expiryMinutes, user);
    }

    /**
     * Verify blob exists in Azure
     */
    public boolean verifyBlobExists(String blobPath) {
        return azureSasService.blobExists(blobPath);
    }

    /**
     * Confirm direct upload and save metadata to database
     * Two-Phase Commit: Azure upload already done, now commit to DB
     */
    @Transactional(rollbackFor = {RuntimeException.class, Exception.class})
    @Caching(evict = {
        @CacheEvict(value = "userStorage", key = "#user.id"),
        @CacheEvict(value = "fileList", allEntries = true)
    })
    public Resource confirmDirectUpload(String blobPath, String fileName, Long fileSize, 
                                       String contentType, User user, Long folderId) {
        // Phase 1: Verify Azure upload succeeded (already done by frontend)
        if (!azureSasService.blobExists(blobPath)) {
            throw new RuntimeException("File not found in Azure. Upload may have failed.");
        }

        // Phase 2: Save to database
        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
            
            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to folder");
            }
        }

        // Check storage quota
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(actualStorageUsed);
        userRepository.save(user);

        if (!user.hasStorageSpace(fileSize)) {
            // Rollback: Delete blob from Azure if quota exceeded
            try {
                azureSasService.deleteBlob(blobPath);
            } catch (Exception e) {
                log.error("Failed to cleanup blob after quota check: {}", blobPath, e);
            }
            throw new RuntimeException("Storage quota exceeded. Available: " + 
                formatBytes(user.getRemainingStorage()) + ", Required: " + formatBytes(fileSize));
        }

        // Create resource record
        Resource resource = new Resource();
        resource.setFileName(fileName);
        resource.setOriginalName(fileName);
        resource.setFilePath(blobPath);
        resource.setUploader(user);
        resource.setFolder(folder);
        resource.setFileSize(fileSize);
        resource.setContentType(contentType != null ? contentType : "application/octet-stream");

        resource = resourceRepository.save(resource);

        // Update user storage
        user.setStorageUsed(actualStorageUsed + fileSize);
        userRepository.save(user);

        log.info("Direct upload confirmed (2PC): {} by user: {}", fileName, user.getUsername());
        return resource;
    }

    // Helper methods

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

