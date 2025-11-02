package com.example.valetkey.repository;

import com.example.valetkey.model.Folder;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, Long> {
    
    // Find all files by uploader (with pagination) - exclude deleted
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND r.isDeleted = false ORDER BY r.uploadedAt DESC")
    Page<Resource> findByUploaderOrderByUploadedAtDesc(User uploader, Pageable pageable);
    
    // Find all files by uploader (without pagination) - exclude deleted
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND r.isDeleted = false ORDER BY r.uploadedAt DESC")
    List<Resource> findByUploaderOrderByUploadedAtDesc(User uploader);
    
    // Find files in a specific folder - exclude deleted
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND r.folder = :folder AND r.isDeleted = false ORDER BY r.uploadedAt DESC")
    Page<Resource> findByUploaderAndFolderOrderByUploadedAtDesc(User uploader, Folder folder, Pageable pageable);
    
    // Find files in root (no folder) - exclude deleted
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND r.folder IS NULL AND r.isDeleted = false ORDER BY r.uploadedAt DESC")
    Page<Resource> findByUploaderAndFolderIsNullOrderByUploadedAtDesc(User uploader, Pageable pageable);
    
    // Search files by name (case-insensitive, with pagination) - exclude deleted
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND r.isDeleted = false AND LOWER(r.fileName) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<Resource> searchByUploaderAndFileName(User uploader, String query, Pageable pageable);
    
    // Search files by name (case-insensitive, without pagination)
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND LOWER(r.fileName) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Resource> searchByUploaderAndFileName(User uploader, String query);
    
    // Find file by public link token
    Optional<Resource> findByPublicLinkToken(String token);
    
    // Find file by uploader and file path
    Optional<Resource> findByUploaderAndFilePath(User uploader, String filePath);
    
    // Get total storage used by user (excluding deleted)
    @Query("SELECT COALESCE(SUM(r.fileSize), 0) FROM Resource r WHERE r.uploader = :uploader AND r.isDeleted = false")
    Long getTotalStorageUsedByUser(User uploader);
    
    // Count files in folder - exclude deleted
    @Query("SELECT COUNT(r) FROM Resource r WHERE r.uploader = :uploader AND r.folder = :folder AND r.isDeleted = false")
    Long countByUploaderAndFolder(User uploader, Folder folder);
    
    // Count files in root - exclude deleted
    @Query("SELECT COUNT(r) FROM Resource r WHERE r.uploader = :uploader AND r.folder IS NULL AND r.isDeleted = false")
    Long countByUploaderAndFolderIsNull(User uploader);
    
    // Find all files in a folder and its subfolders (for deletion) - exclude deleted
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND r.isDeleted = false AND r.folder.fullPath LIKE CONCAT(:folderPath, '%')")
    List<Resource> findAllInFolderAndSubfolders(User uploader, String folderPath);
    
    // Trash/Recycle Bin queries
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND r.isDeleted = true ORDER BY r.deletedAt DESC")
    Page<Resource> findDeletedFilesByUser(User uploader, Pageable pageable);
    
    @Query("SELECT r FROM Resource r WHERE r.uploader = :uploader AND r.isDeleted = true ORDER BY r.deletedAt DESC")
    List<Resource> findAllDeletedFilesByUser(User uploader);
    
    // Find file by ID and user (including deleted)
    @Query("SELECT r FROM Resource r WHERE r.id = :fileId AND r.uploader = :uploader")
    Optional<Resource> findByIdAndUploader(Long fileId, User uploader);
    
    // Find files by IDs for bulk operations
    @Query("SELECT r FROM Resource r WHERE r.id IN :fileIds AND r.uploader = :uploader AND r.isDeleted = false")
    List<Resource> findByIdsAndUploader(List<Long> fileIds, User uploader);
    
    // Find resource by upload session ID (for resume upload)
    @Query("SELECT r FROM Resource r WHERE r.uploadSessionId = :sessionId AND r.isDeleted = false")
    Optional<Resource> findByUploadSessionId(String sessionId);
}
