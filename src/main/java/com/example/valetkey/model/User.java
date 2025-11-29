package com.example.valetkey.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "\"user\"") // Quoted to avoid PostgreSQL reserved keyword "user"
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

    // Constructors
    public User() {
    }

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public User(Long id, String username, String password, boolean create, boolean write, boolean read, LocalDateTime createdAt, Role role, Long storageQuota, Long storageUsed) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.create = create;
        this.write = write;
        this.read = read;
        this.createdAt = createdAt;
        this.role = role;
        this.storageQuota = storageQuota;
        this.storageUsed = storageUsed;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isCreate() {
        return create;
    }

    public boolean isWrite() {
        return write;
    }

    public boolean isRead() {
        return read;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Role getRole() {
        return role;
    }

    public Long getStorageQuota() {
        return storageQuota;
    }

    public Long getStorageUsed() {
        return storageUsed;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setCreate(boolean create) {
        this.create = create;
    }

    public void setWrite(boolean write) {
        this.write = write;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void setStorageQuota(Long storageQuota) {
        this.storageQuota = storageQuota;
    }

    public void setStorageUsed(Long storageUsed) {
        this.storageUsed = storageUsed;
    }

    // Chain setters (for fluent API)
    public User id(Long id) {
        this.id = id;
        return this;
    }

    public User username(String username) {
        this.username = username;
        return this;
    }

    public User password(String password) {
        this.password = password;
        return this;
    }

    public User create(boolean create) {
        this.create = create;
        return this;
    }

    public User write(boolean write) {
        this.write = write;
        return this;
    }

    public User read(boolean read) {
        this.read = read;
        return this;
    }

    public User createdAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public User role(Role role) {
        this.role = role;
        return this;
    }

    public User storageQuota(Long storageQuota) {
        this.storageQuota = storageQuota;
        return this;
    }

    public User storageUsed(Long storageUsed) {
        this.storageUsed = storageUsed;
        return this;
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
