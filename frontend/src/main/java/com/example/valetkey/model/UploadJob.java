package com.example.valetkey.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "upload_jobs")
@Data
public class UploadJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String jobId; // UUID for tracking

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private Long fileSize;

    @Column(name = "folder_id")
    private Long folderId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    @Column(nullable = false)
    private Long uploadedBytes = 0L;

    @Column
    private Integer progressPercentage = 0;

    @Column(length = 1000)
    private String errorMessage;

    @Column
    private String filePath; // Azure blob path

    @Column
    private Long resourceId; // Reference to Resource entity after completion

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "temp_file_path")
    private String tempFilePath; // Temporary file path on server

    public enum JobStatus {
        PENDING,      // Job created, waiting to start
        UPLOADING,    // Currently uploading to Azure
        COMPLETED,    // Successfully completed
        FAILED,       // Failed with error
        CANCELLED     // Cancelled by user
    }

    public void updateProgress(Long uploadedBytes, Long totalBytes) {
        this.uploadedBytes = uploadedBytes;
        if (totalBytes > 0) {
            this.progressPercentage = (int) ((uploadedBytes * 100) / totalBytes);
        }
    }

    public void markAsStarted() {
        this.status = JobStatus.UPLOADING;
        this.startedAt = LocalDateTime.now();
    }

    public void markAsCompleted(Long resourceId, String filePath) {
        this.status = JobStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.resourceId = resourceId;
        this.filePath = filePath;
        this.progressPercentage = 100;
    }

    public void markAsFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = errorMessage;
    }

    public void markAsCancelled() {
        this.status = JobStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }
}


