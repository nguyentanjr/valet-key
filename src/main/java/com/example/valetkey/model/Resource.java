package com.example.valetkey.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
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

    // Constructors
    public Resource() {
    }

    public Resource(Long id, String fileName, String filePath, String originalName, User uploader, Folder folder, LocalDateTime uploadedAt, Long fileSize, String contentType, boolean isPublic, String publicLinkToken, LocalDateTime publicLinkCreatedAt, LocalDateTime lastModified, boolean isDeleted, LocalDateTime deletedAt, Long originalFolderId) {
        this.id = id;
        this.fileName = fileName;
        this.filePath = filePath;
        this.originalName = originalName;
        this.uploader = uploader;
        this.folder = folder;
        this.uploadedAt = uploadedAt;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.isPublic = isPublic;
        this.publicLinkToken = publicLinkToken;
        this.publicLinkCreatedAt = publicLinkCreatedAt;
        this.lastModified = lastModified;
        this.isDeleted = isDeleted;
        this.deletedAt = deletedAt;
        this.originalFolderId = originalFolderId;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getOriginalName() {
        return originalName;
    }

    public User getUploader() {
        return uploader;
    }

    public Folder getFolder() {
        return folder;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public String getPublicLinkToken() {
        return publicLinkToken;
    }

    public LocalDateTime getPublicLinkCreatedAt() {
        return publicLinkCreatedAt;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public Long getOriginalFolderId() {
        return originalFolderId;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public void setUploader(User uploader) {
        this.uploader = uploader;
    }

    public void setFolder(Folder folder) {
        this.folder = folder;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public void setPublicLinkToken(String publicLinkToken) {
        this.publicLinkToken = publicLinkToken;
    }

    public void setPublicLinkCreatedAt(LocalDateTime publicLinkCreatedAt) {
        this.publicLinkCreatedAt = publicLinkCreatedAt;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public void setDeleted(boolean deleted) {
        isDeleted = deleted;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void setOriginalFolderId(Long originalFolderId) {
        this.originalFolderId = originalFolderId;
    }
}
