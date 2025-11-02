package com.example.valetkey.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "\"user\"") // Quoted to avoid PostgreSQL reserved keyword "user"
@Accessors(chain = true)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    private boolean create = true;
    private boolean write = true;
    private boolean read = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    private Role role;

    // Storage quota in bytes (default 1GB = 1,073,741,824 bytes)
    @Column(name = "storage_quota")
    private Long storageQuota = 1073741824L; // 1GB

    // Used storage in bytes
    @Column(name = "storage_used")
    private Long storageUsed = 0L;

    public enum Role {
        ROLE_USER,
        ROLE_ADMIN
    }

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getPassword() {
        return this.password;
    }

    // Check if user has enough storage space
    public boolean hasStorageSpace(Long fileSize) {
        return (storageUsed + fileSize) <= storageQuota;
    }

    // Get remaining storage space in bytes
    public Long getRemainingStorage() {
        return storageQuota - storageUsed;
    }

    // Get storage usage percentage
    public double getStorageUsagePercentage() {
        if (storageQuota == 0) return 0.0;
        return (storageUsed * 100.0) / storageQuota;
    }
}
