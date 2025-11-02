package com.example.valetkey.service;

import com.example.valetkey.model.Folder;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.FolderRepository;
import com.example.valetkey.repository.ResourceRepository;
import com.example.valetkey.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

        // Commit blocks in Azure
        azureSasService.commitBlocks(resource.getFilePath(), blockIds);

        // Mark as completed
        resource.setUploadStatus("COMPLETED");
        resource.setUploadProgress(resource.getFileSize());
        resource.setUploadSessionId(null);
        
        resource = resourceRepository.save(resource);
        
        // Recalculate and update user storage from DB to ensure accuracy
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(actualStorageUsed);
        userRepository.save(user);

        log.info("Resume upload completed: {} by user: {}", resource.getFileName(), user.getUsername());

        return resource;
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
     * Upload a file through the backend
     */
    @Transactional(rollbackFor = {RuntimeException.class, Exception.class})
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

        // Get folder if specified
        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
            
            // Verify folder belongs to user
            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to this folder");
            }
        }

        // Create unique blob path
        String folderPath = (folder != null) ? folder.getFullPath() : "";
        String uniqueFileName = generateUniqueFileName(fileName);
        String blobPath = "user-" + user.getId() + folderPath + "/" + uniqueFileName;

        // Final quota check before upload - use pessimistic locking to prevent race conditions
        // Refresh user from DB one more time to get latest storageUsed
        user = userRepository.findById(user.getId())
            .orElseThrow(() -> new RuntimeException("User not found"));
        Long finalStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(finalStorageUsed);
        
        // Check quota one final time before upload
        if (!user.hasStorageSpace(fileSize)) {
            throw new RuntimeException("Storage quota exceeded. Available: " + 
                formatBytes(user.getRemainingStorage()) + ", Required: " + formatBytes(fileSize));
        }

        // Upload to Azure
        String azureUrl = null;
        Resource resource = null;
        try {
            azureUrl = azureSasService.uploadFile(file, blobPath);

            // Create resource entity
            resource = new Resource();
            resource.setFileName(fileName);
            resource.setOriginalName(file.getOriginalFilename());
            resource.setFilePath(blobPath);
            resource.setUploader(user);
            resource.setFolder(folder);
            resource.setFileSize(fileSize);
            resource.setContentType(file.getContentType());

            // Save to database
            resource = resourceRepository.save(resource);

            // Update user storage (use finalStorageUsed + fileSize to ensure accuracy)
            user.setStorageUsed(finalStorageUsed + fileSize);
            userRepository.save(user);

            // Only log success if everything completed without exception
            log.info("File uploaded successfully: {} by user: {}", fileName, user.getUsername());

        } catch (RuntimeException e) {
            // If upload to Azure succeeded but something failed, clean up Azure blob
            if (blobPath != null) {
                try {
                    azureSasService.deleteBlob(blobPath);
                    log.warn("Cleaned up Azure blob {} after error: {}", blobPath, e.getMessage());
                } catch (Exception cleanupEx) {
                    log.error("Failed to cleanup Azure blob after error: {}", blobPath, cleanupEx);
                }
            }
            // Delete resource from DB if it was created
            if (resource != null && resource.getId() != null) {
                try {
                    resourceRepository.delete(resource);
                    log.warn("Deleted resource {} from DB after error", resource.getId());
                } catch (Exception e2) {
                    log.error("Failed to delete resource from DB: {}", resource.getId(), e2);
                }
            }
            log.error("Upload failed for file {}: {}", fileName, e.getMessage());
            throw e; // Re-throw the exception to trigger transaction rollback
        } catch (Exception e) {
            // Clean up on any other exception
            if (blobPath != null) {
                try {
                    azureSasService.deleteBlob(blobPath);
                    log.warn("Cleaned up Azure blob {} after error: {}", blobPath, e.getMessage());
                } catch (Exception cleanupEx) {
                    log.error("Failed to cleanup Azure blob after error: {}", blobPath, cleanupEx);
                }
            }
            log.error("Upload failed for file {}: {}", fileName, e.getMessage());
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }

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
    public Page<Resource> searchFiles(User user, String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return resourceRepository.searchByUploaderAndFileName(user, query, pageable);
    }

    /**
     * Get file metadata
     */
    @Transactional(readOnly = true)
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

