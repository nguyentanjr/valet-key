package com.example.valetkey.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "resources")
public class Resource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = false)
    private String filePath; // Path in Azure Blob Storage

    @Column(name = "original_name")
    private String originalName;

    @ManyToOne
    @JoinColumn(name = "uploader_id", nullable = false)
    private User uploader;

    @ManyToOne
    @JoinColumn(name = "folder_id")
    private Folder folder;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt = LocalDateTime.now();

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "is_public")
    private boolean isPublic = false;

    // Token for public link sharing
    @Column(name = "public_link_token", unique = true)
    private String publicLinkToken;

    @Column(name = "public_link_created_at")
    private LocalDateTime publicLinkCreatedAt;

    @Column(name = "last_modified")
    private LocalDateTime lastModified = LocalDateTime.now();

    // Trash/Recycle Bin fields
    @Column(name = "is_deleted")
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "original_folder_id")
    private Long originalFolderId; // Store original folder before moving to trash

    // Resume Upload fields
    @Column(name = "upload_session_id")
    private String uploadSessionId; // For resume upload

    @Column(name = "upload_progress")
    private Long uploadProgress = 0L; // Bytes uploaded so far

    @Column(name = "upload_status")
    private String uploadStatus; // PENDING, UPLOADING, COMPLETED, FAILED

    @PreUpdate
    private void preUpdate() {
        lastModified = LocalDateTime.now();
    }

    // Generate public link token
    public void generatePublicLinkToken() {
        this.publicLinkToken = UUID.randomUUID().toString();
        this.publicLinkCreatedAt = LocalDateTime.now();
        this.isPublic = true;
    }

    // Revoke public link
    public void revokePublicLink() {
        this.publicLinkToken = null;
        this.publicLinkCreatedAt = null;
        this.isPublic = false;
    }

    // Move to trash (soft delete)
    public void moveToTrash(Long originalFolderId) {
        this.isDeleted = true;
        this.deletedAt = LocalDateTime.now();
        this.originalFolderId = originalFolderId;
    }

    // Restore from trash
    public void restoreFromTrash() {
        this.isDeleted = false;
        this.deletedAt = null;
        this.originalFolderId = null;
    }
}
