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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FolderService {

    private static final Logger log = LoggerFactory.getLogger(FolderService.class);

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AzureSasService azureSasService;


    @Transactional
    public Folder createFolder(String folderName, Long parentFolderId, User user) {
        // Validate folder name
        if (folderName == null || folderName.trim().isEmpty()) {
            throw new RuntimeException("Folder name cannot be empty");
        }

        if (folderName.contains("/") || folderName.contains("\\")) {
            throw new RuntimeException("Folder name cannot contain '/' or '\\'");
        }

        String sanitizedName = folderName.trim();

        // Get parent folder if specified
        Folder parentFolder = null;
        if (parentFolderId != null) {
            parentFolder = folderRepository.findById(parentFolderId)
                .orElseThrow(() -> new RuntimeException("Parent folder not found"));

            // Verify parent folder belongs to user
            if (!parentFolder.getOwner().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied to parent folder");
            }
        }

        // Check if folder with same name already exists in the same location
        if (folderRepository.existsByOwnerAndNameAndParentFolder(user, sanitizedName, parentFolder)) {
            throw new RuntimeException("Folder with this name already exists in this location");
        }

        // Create new folder
        Folder folder = new Folder();
        folder.setName(sanitizedName);
        folder.setOwner(user);
        folder.setParentFolder(parentFolder);

        folder = folderRepository.save(folder);

        log.info("Folder created: {} by user: {}", folder.getFullPath(), user.getUsername());

        return folder;
    }

    /**
     * Get folder by ID
     */
    @Transactional(readOnly = true)
    public Folder getFolder(Long folderId, User user) {
        Folder folder = folderRepository.findById(folderId)
            .orElseThrow(() -> new RuntimeException("Folder not found"));

        // Check ownership
        if (!folder.getOwner().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied to this folder");
        }

        return folder;
    }

    /**
     * List folders in a parent folder or root
     */
    @Transactional(readOnly = true)
    public List<Folder> listFolders(Long parentFolderId, User user) {
        if (parentFolderId == null) {
            // List root folders
            return folderRepository.findByOwnerAndParentFolderIsNullOrderByNameAsc(user);
        } else {
            Folder parentFolder = getFolder(parentFolderId, user);
            return folderRepository.findByParentFolderOrderByNameAsc(parentFolder);
        }
    }

    /**
     * Get all folders for a user (flat list)
     */
    @Transactional(readOnly = true)
    public List<Folder> getAllFolders(User user) {
        return folderRepository.findByOwnerOrderByFullPathAsc(user);
    }

    /**
     * Get folder tree structure
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getFolderTree(User user) {
        List<Folder> rootFolders = folderRepository.findByOwnerAndParentFolderIsNullOrderByNameAsc(user);
        return rootFolders.stream()
            .map(this::buildFolderNode)
            .collect(Collectors.toList());
    }

    /**
     * Search folders by name
     */
    @Transactional(readOnly = true)
    public List<Folder> searchFolders(String query, User user) {
        if (query == null || query.trim().isEmpty()) {
            return getAllFolders(user);
        }
        return folderRepository.searchByOwnerAndName(user, query.trim());
    }

    /**
     * Delete a folder (and optionally its contents)
     */
    @Transactional
    public void deleteFolder(Long folderId, User user, boolean deleteContents) {
        Folder folder = getFolder(folderId, user);

        if (deleteContents) {
            // Delete all files and subfolders recursively
            deleteFolderContentsRecursive(folder, user);
        } else {
            // Check if folder is empty
            List<Folder> subFolders = folderRepository.findByParentFolderOrderByNameAsc(folder);
            Long fileCount = resourceRepository.countByUploaderAndFolder(user, folder);

            if (!subFolders.isEmpty() || fileCount > 0) {
                throw new RuntimeException("Cannot delete non-empty folder. Use deleteContents=true to force delete.");
            }
        }

        // Delete the folder itself
        folderRepository.delete(folder);

        log.info("Folder deleted: {} by user: {}", folder.getFullPath(), user.getUsername());
    }

    /**
     * Rename a folder
     */
    @Transactional
    public Folder renameFolder(Long folderId, String newName, User user) {
        Folder folder = getFolder(folderId, user);

        // Validate new name
        if (newName == null || newName.trim().isEmpty()) {
            throw new RuntimeException("Folder name cannot be empty");
        }

        if (newName.contains("/") || newName.contains("\\")) {
            throw new RuntimeException("Folder name cannot contain '/' or '\\'");
        }

        String sanitizedName = newName.trim();

        // Check if folder with same name already exists in the same location
        if (folderRepository.existsByOwnerAndNameAndParentFolder(user, sanitizedName, folder.getParentFolder())) {
            throw new RuntimeException("Folder with this name already exists in this location");
        }

        folder.setName(sanitizedName);
        folder = folderRepository.save(folder);

        // Update paths of all subfolders
        updateSubfolderPaths(folder);

        log.info("Folder renamed to: {} by user: {}", folder.getFullPath(), user.getUsername());

        return folder;
    }

    /**
     * Move a folder to another parent folder
     */
    @Transactional
    public Folder moveFolder(Long folderId, Long targetParentFolderId, User user) {
        Folder folder = getFolder(folderId, user);

        Folder targetParentFolder = null;
        if (targetParentFolderId != null) {
            targetParentFolder = getFolder(targetParentFolderId, user);

            // Check for circular reference
            if (isDescendantOf(targetParentFolder, folder)) {
                throw new RuntimeException("Cannot move folder into its own subfolder");
            }
        }

        // Check if folder with same name already exists in target location
        if (folderRepository.existsByOwnerAndNameAndParentFolder(user, folder.getName(), targetParentFolder)) {
            throw new RuntimeException("Folder with this name already exists in target location");
        }

        folder.setParentFolder(targetParentFolder);
        folder = folderRepository.save(folder);

        // Update paths of all subfolders
        updateSubfolderPaths(folder);

        log.info("Folder moved to: {} by user: {}", folder.getFullPath(), user.getUsername());

        return folder;
    }

    /**
     * Get folder contents (files and subfolders)
     */
    public Map<String, Object> getFolderContents(Long folderId, User user, int page, int size) {
        List<Folder> subFolders;
        Long fileCount;

        if (folderId == null) {
            // Root level
            subFolders = folderRepository.findByOwnerAndParentFolderIsNullOrderByNameAsc(user);
            fileCount = resourceRepository.countByUploaderAndFolderIsNull(user);
        } else {
            Folder folder = getFolder(folderId, user);
            subFolders = folderRepository.findByParentFolderOrderByNameAsc(folder);
            fileCount = resourceRepository.countByUploaderAndFolder(user, folder);
        }

        Map<String, Object> contents = new HashMap<>();
        contents.put("folders", subFolders.stream()
            .map(this::folderToMap)
            .collect(Collectors.toList()));
        contents.put("fileCount", fileCount);

        return contents;
    }

    /**
     * Get folder metadata
     */
    public Map<String, Object> getFolderMetadata(Long folderId, User user) {
        Folder folder = getFolder(folderId, user);

        List<Folder> subFolders = folderRepository.findByParentFolderOrderByNameAsc(folder);
        Long fileCount = resourceRepository.countByUploaderAndFolder(user, folder);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("id", folder.getId());
        metadata.put("name", folder.getName());
        metadata.put("fullPath", folder.getFullPath());
        metadata.put("createdAt", folder.getCreatedAt());
        metadata.put("updatedAt", folder.getUpdatedAt());
        metadata.put("subFolderCount", subFolders.size());
        metadata.put("fileCount", fileCount);

        if (folder.getParentFolder() != null) {
            metadata.put("parent", Map.of(
                "id", folder.getParentFolder().getId(),
                "name", folder.getParentFolder().getName(),
                "path", folder.getParentFolder().getFullPath()
            ));
        }

        return metadata;
    }

    /**
     * Get breadcrumb path for a folder
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getBreadcrumb(Long folderId, User user) {
        List<Map<String, Object>> breadcrumb = new ArrayList<>();
        
        // Add root
        Map<String, Object> root = new HashMap<>();
        root.put("id", null);
        root.put("name", "My Files");
        root.put("path", "/");
        breadcrumb.add(root);

        if (folderId != null) {
            Folder folder = getFolder(folderId, user);
            List<Folder> ancestors = new ArrayList<>();
            
            Folder current = folder;
            while (current != null) {
                ancestors.add(0, current);
                current = current.getParentFolder();
            }

            for (Folder ancestor : ancestors) {
                Map<String, Object> ancestorMap = new HashMap<>();
                ancestorMap.put("id", ancestor.getId());
                ancestorMap.put("name", ancestor.getName());
                ancestorMap.put("path", ancestor.getFullPath());
                breadcrumb.add(ancestorMap);
            }
        }

        return breadcrumb;
    }

    // Helper methods

    private void deleteFolderContentsRecursive(Folder folder, User user) {
        // Delete all files in this folder
        List<Resource> files = resourceRepository.findByUploaderAndFolderOrderByUploadedAtDesc(
            user, folder, org.springframework.data.domain.Pageable.unpaged()
        ).getContent();

        for (Resource file : files) {
            // Delete from Azure
            azureSasService.deleteBlob(file.getFilePath());
            
            // Update user storage
            user.setStorageUsed(user.getStorageUsed() - file.getFileSize());
            
            // Delete from database
            resourceRepository.delete(file);
        }

        // Recursively delete subfolders
        List<Folder> subFolders = folderRepository.findByParentFolderOrderByNameAsc(folder);
        for (Folder subFolder : subFolders) {
            deleteFolderContentsRecursive(subFolder, user);
            folderRepository.delete(subFolder);
        }

        // Save user storage changes
        userRepository.save(user);
    }

    private void updateSubfolderPaths(Folder folder) {
        List<Folder> subFolders = folderRepository.findByParentFolderOrderByNameAsc(folder);
        for (Folder subFolder : subFolders) {
            folderRepository.save(subFolder); // Triggers @PreUpdate to recalculate path
            updateSubfolderPaths(subFolder);
        }
    }

    private boolean isDescendantOf(Folder potentialDescendant, Folder ancestor) {
        Folder current = potentialDescendant.getParentFolder();
        while (current != null) {
            if (current.getId().equals(ancestor.getId())) {
                return true;
            }
            current = current.getParentFolder();
        }
        return false;
    }

    private Map<String, Object> buildFolderNode(Folder folder) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", folder.getId());
        node.put("name", folder.getName());
        node.put("path", folder.getFullPath());
        node.put("createdAt", folder.getCreatedAt());
        
        List<Folder> children = folderRepository.findByParentFolderOrderByNameAsc(folder);
        if (!children.isEmpty()) {
            node.put("children", children.stream()
                .map(this::buildFolderNode)
                .collect(Collectors.toList()));
        }
        
        return node;
    }

    private Map<String, Object> folderToMap(Folder folder) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", folder.getId());
        map.put("name", folder.getName());
        map.put("fullPath", folder.getFullPath());
        map.put("createdAt", folder.getCreatedAt());
        map.put("updatedAt", folder.getUpdatedAt());
        return map;
    }
}

