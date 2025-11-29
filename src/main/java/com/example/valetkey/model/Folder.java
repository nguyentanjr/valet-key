package com.example.valetkey.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne
    @JoinColumn(name = "parent_folder_id")
    private Folder parentFolder;

    @OneToMany(mappedBy = "parentFolder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Folder> subFolders = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    // Full path from root (e.g., "/Documents/Photos")
    @Column(name = "full_path")
    private String fullPath;

    @PrePersist
    @PreUpdate
    private void updatePath() {
        if (parentFolder == null) {
            fullPath = "/" + name;
        } else {
            fullPath = parentFolder.getFullPath() + "/" + name;
        }
        updatedAt = LocalDateTime.now();
    }

    // Constructors
    public Folder() {
    }

    public Folder(Long id, String name, User owner, Folder parentFolder, List<Folder> subFolders, LocalDateTime createdAt, LocalDateTime updatedAt, String fullPath) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.parentFolder = parentFolder;
        this.subFolders = subFolders;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.fullPath = fullPath;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public User getOwner() {
        return owner;
    }

    public Folder getParentFolder() {
        return parentFolder;
    }

    public List<Folder> getSubFolders() {
        return subFolders;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getFullPath() {
        return fullPath;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public void setParentFolder(Folder parentFolder) {
        this.parentFolder = parentFolder;
    }

    public void setSubFolders(List<Folder> subFolders) {
        this.subFolders = subFolders;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setFullPath(String fullPath) {
        this.fullPath = fullPath;
    }
}

