package com.example.valetkey.service;

import com.example.valetkey.model.Folder;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.FolderRepository;
import com.example.valetkey.repository.ResourceRepository;
import com.example.valetkey.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AzureSasService azureSasService;




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


    @Transactional(readOnly = true)
    public String getDownloadUrl(Long fileId, User user) throws InterruptedException {
        Resource resource = getFile(fileId, user);

        user.setRead(true);
        if (!user.isRead()) {
            throw new RuntimeException("User does not have permission to download files");
        }

        int expiryMinutes = 10;
        return azureSasService.generateBlobReadSas(resource.getFilePath(), expiryMinutes, user);
    }


    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "userStorage", key = "#user.id"),
        @CacheEvict(value = "fileMetadata", key = "#fileId + '_' + #user.id"),
        @CacheEvict(value = "fileList", allEntries = true)
    })
    public void deleteFile(Long fileId, User user) {
        Resource resource = getFile(fileId, user);

        azureSasService.deleteBlob(resource.getFilePath());

        resourceRepository.delete(resource);

        userRepository.decrementStorageUsed(user.getId(), resource.getFileSize());

        log.info("File deleted: {} by user: {}", resource.getFileName(), user.getUsername());
    }


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


    @Transactional(readOnly = true)
    public Page<Resource> searchFiles(User user, String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return resourceRepository.searchByUploaderAndFileName(user, query, pageable);
    }


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


    @Transactional
    public String generatePublicLink(Long fileId, User user) {
        Resource resource = getFile(fileId, user);
        
        if (resource.getPublicLinkToken() == null) {
            resource.generatePublicLinkToken();
            resourceRepository.save(resource);
        }

        return resource.getPublicLinkToken();
    }


    @Transactional
    public void revokePublicLink(Long fileId, User user) {
        Resource resource = getFile(fileId, user);
        resource.revokePublicLink();
        resourceRepository.save(resource);
    }


    public Resource getFileByPublicToken(String token) {
        return resourceRepository.findByPublicLinkToken(token)
            .orElseThrow(() -> new RuntimeException("Invalid or expired public link"));
    }


    public String getPublicDownloadUrl(String token) throws InterruptedException {
        Resource resource = getFileByPublicToken(token);
        
        // Create a temporary user with read permission for SAS generation
        User tempUser = new User();
        tempUser.setRead(true);
        
        int expiryMinutes = 10000000;
        return azureSasService.generateBlobReadSas(resource.getFilePath(), expiryMinutes, tempUser);
    }


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


    @Transactional
    public Resource renameFile(Long fileId, String newName, User user) {
        Resource resource = getFile(fileId, user);

        if (newName == null || newName.trim().isEmpty()) {
            throw new RuntimeException("Invalid file name");
        }

        resource.setFileName(newName.trim());
        return resourceRepository.save(resource);
    }



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
        
        // Atomic update user storage to avoid deadlock
        if (totalSize > 0) {
            userRepository.decrementStorageUsed(user.getId(), totalSize);
        }
        
        log.info("Bulk deleted {} files by user: {}", resources.size(), user.getUsername());
    }


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


    public Resource getFileIncludingDeleted(Long fileId, User user) {
        return resourceRepository.findByIdAndUploader(fileId, user)
            .orElseThrow(() -> new RuntimeException("File not found"));
    }

    // Helper methods


    @Transactional(readOnly = true)
    public Map<String, String> generateUploadSasUrl(String fileName, Long fileSize, User user, Long folderId, int expiryMinutes) {
        // Check permissions
        if (!user.isCreate() || !user.isWrite()) {
            throw new RuntimeException("User does not have permission to upload files");
        }

        // Check storage quota
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(actualStorageUsed);
        
        if (!user.hasStorageSpace(fileSize)) {
            throw new RuntimeException("Storage quota exceeded. Available: " + 
                formatBytes(user.getRemainingStorage()) + ", Required: " + formatBytes(fileSize));
        }

        // Get folder if specified
        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
            
            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to this folder");
            }
        }

        // Generate unique blob path
        String folderPath = (folder != null) ? folder.getFullPath() : "";
        String uniqueFileName = generateUniqueFileName(fileName);
        String blobPath = "user-" + user.getId() + folderPath + "/" + uniqueFileName;

        // Generate SAS URL with write permissions
        String sasUrl = azureSasService.generateBlobWriteSas(blobPath, expiryMinutes, user);

        Map<String, String> result = new HashMap<>();
        result.put("sasUrl", sasUrl);
        result.put("blobPath", blobPath);
        
        log.debug("Generated SAS URL for upload: {} ({} bytes)", blobPath, fileSize);
        return result;
    }


    @Transactional(readOnly = true)
    public Map<String, Object> generateBatchUploadSasUrls(List<Map<String, Object>> fileInfos, User user, Long folderId, int expiryMinutes) {
        // Check permissions
        if (!user.isCreate() || !user.isWrite()) {
            throw new RuntimeException("User does not have permission to upload files");
        }

        // Get folder if specified
        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
            
            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to this folder");
            }
        }

        // Check total storage quota
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(user);
        user.setStorageUsed(actualStorageUsed);
        
        long totalSize = fileInfos.stream()
            .mapToLong(info -> Long.parseLong(info.get("fileSize").toString()))
            .sum();
        
        if (!user.hasStorageSpace(totalSize)) {
            throw new RuntimeException("Storage quota exceeded. Available: " + 
                formatBytes(user.getRemainingStorage()) + ", Required: " + formatBytes(totalSize));
        }

        String folderPath = (folder != null) ? folder.getFullPath() : "";
        List<Map<String, Object>> sasUrls = new ArrayList<>();

        for (Map<String, Object> fileInfo : fileInfos) {
            try {
                String fileName = (String) fileInfo.get("fileName");
                Long fileSize = Long.parseLong(fileInfo.get("fileSize").toString());
                
                String uniqueFileName = generateUniqueFileName(fileName);
                String blobPath = "user-" + user.getId() + folderPath + "/" + uniqueFileName;

                // Generate SAS URL
                String sasUrl = azureSasService.generateBlobWriteSas(blobPath, expiryMinutes, user);

                Map<String, Object> sasInfo = new HashMap<>();
                sasInfo.put("sasUrl", sasUrl);
                sasInfo.put("blobPath", blobPath);
                sasInfo.put("status", "success");
                sasUrls.add(sasInfo);

            } catch (Exception e) {
                Map<String, Object> sasInfo = new HashMap<>();
                sasInfo.put("status", "failed");
                sasInfo.put("error", e.getMessage());
                sasUrls.add(sasInfo);
                log.error("Failed to generate SAS URL for file: {}", fileInfo.get("fileName"), e);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sasUrls", sasUrls);
        result.put("totalFiles", fileInfos.size());
        
        log.info("Generated {} SAS URLs for batch upload by user: {}", fileInfos.size(), user.getUsername());
        return result;
    }


    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "fileList", allEntries = true),
        @CacheEvict(value = "userStorage", key = "#user.id")
    })
    public Resource confirmDirectUpload(String blobPath, String fileName, Long fileSize, String contentType, User user, Long folderId) {
        // Verify blob exists in Azure
        if (!azureSasService.blobExists(blobPath)) {
            throw new RuntimeException("Blob not found in Azure. Upload may have failed.");
        }

        // Check if resource already exists
        Optional<Resource> existingResource = resourceRepository.findByFilePath(blobPath);
        if (existingResource.isPresent()) {
            log.warn("Resource already exists for blob path: {}", blobPath);
            return existingResource.get();
        }

        // Get folder if specified
        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
            
            if (!folder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to this folder");
            }
        }

        // Use pessimistic lock to prevent concurrent updates and check quota atomically
        Optional<User> lockedUserOpt = userRepository.findByIdWithLock(user.getId());
        if (lockedUserOpt.isEmpty()) {
            throw new RuntimeException("User not found");
        }
        User lockedUser = lockedUserOpt.get();
        
        // Recalculate storage with lock held
        Long actualStorageUsed = resourceRepository.getTotalStorageUsedByUser(lockedUser);
        lockedUser.setStorageUsed(actualStorageUsed);
        
        // Final quota check with lock held
        if (!lockedUser.hasStorageSpace(fileSize)) {
            // Delete blob from Azure if quota exceeded
            try {
                azureSasService.deleteBlob(blobPath);
            } catch (Exception e) {
                log.error("Failed to delete blob after quota check: {}", blobPath, e);
            }
            throw new RuntimeException("Storage quota exceeded. Available: " + 
                formatBytes(lockedUser.getRemainingStorage()) + ", Required: " + formatBytes(fileSize));
        }

        // Create resource record
        Resource resource = new Resource();
        resource.setFileName(fileName);
        resource.setFilePath(blobPath);
        resource.setUploader(lockedUser);
        resource.setFolder(folder);
        resource.setFileSize(fileSize);
        resource.setContentType(contentType);

        // Save to database
        resource = resourceRepository.save(resource);

        // Atomic update storage_used to avoid deadlock
        // This uses database-level atomic operation, safe for concurrent updates
        int updated = userRepository.incrementStorageUsed(lockedUser.getId(), fileSize);
        if (updated == 0) {
            log.error("Failed to update storage for user: {}", lockedUser.getId());
            // Rollback: delete resource if storage update failed
            resourceRepository.delete(resource);
            throw new RuntimeException("Failed to update storage");
        }

        log.info("Direct upload confirmed: {} ({} bytes) by user: {}", fileName, fileSize, lockedUser.getUsername());
        return resource;
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

