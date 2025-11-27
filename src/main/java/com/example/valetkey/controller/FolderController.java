package com.example.valetkey.controller;

import com.example.valetkey.model.Folder;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.UserRepository;
import com.example.valetkey.service.FolderService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private static final Logger log = LoggerFactory.getLogger(FolderController.class);

    @Autowired
    private FolderService folderService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Helper method to get current user from SecurityContext
     * Thay thế cho session.getAttribute("user") vì không còn lưu User entity vào session
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        
        // Lấy username từ authentication
        String username = authentication.getName();
        
        // Lấy user từ database
        Optional<User> userOpt = userRepository.findUserByUsername(username);
        return userOpt.orElse(null);
    }

    /**
     * Create a new folder
     */
    @PostMapping("/create")
    public ResponseEntity<?> createFolder(
            @RequestBody Map<String, Object> request) {

        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            String folderName = (String) request.get("folderName");
            Long parentFolderId = request.get("parentFolderId") != null 
                ? Long.valueOf(request.get("parentFolderId").toString()) 
                : null;

            if (folderName == null || folderName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("message", "Folder name is required"));
            }
            Folder folder = folderService.createFolder(folderName, parentFolderId, user);

            return ResponseEntity.ok(Map.of(
                "message", "Folder created successfully",
                "folder", folderToMap(folder)
            ));

        } catch (Exception e) {
            log.error("Error creating folder", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get folder metadata
     */
    @GetMapping("/{folderId}")
    public ResponseEntity<?> getFolder(@PathVariable Long folderId) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            Map<String, Object> metadata = folderService.getFolderMetadata(folderId, user);

            return ResponseEntity.ok(metadata);

        } catch (Exception e) {
            log.error("Error getting folder", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * List folders in a parent folder or root
     */
    @GetMapping("/list")
    public ResponseEntity<?> listFolders(
            @RequestParam(value = "parentFolderId", required = false) Long parentFolderId) {

        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            List<Folder> folders = folderService.listFolders(parentFolderId, user);

            return ResponseEntity.ok(Map.of(
                "folders", folders.stream()
                    .map(this::folderToMap)
                    .collect(Collectors.toList())
            ));

        } catch (Exception e) {
            log.error("Error listing folders", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get folder tree structure
     */
    @GetMapping("/tree")
    public ResponseEntity<?> getFolderTree() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            List<Map<String, Object>> tree = folderService.getFolderTree(user);

            return ResponseEntity.ok(Map.of("tree", tree));

        } catch (Exception e) {
            log.error("Error getting folder tree", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get folder contents (subfolders and file count)
     */
    @GetMapping("/{folderId}/contents")
    public ResponseEntity<?> getFolderContents(
            @PathVariable(required = false) Long folderId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            Map<String, Object> contents = folderService.getFolderContents(folderId, user, page, size);

            return ResponseEntity.ok(contents);

        } catch (Exception e) {
            log.error("Error getting folder contents", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get root folder contents
     */
    @GetMapping("/root/contents")
    public ResponseEntity<?> getRootContents(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            Map<String, Object> contents = folderService.getFolderContents(null, user, page, size);

            return ResponseEntity.ok(contents);

        } catch (Exception e) {
            log.error("Error getting root contents", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Search folders by name
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchFolders(
            @RequestParam("query") String query) {

        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            List<Folder> folders = folderService.searchFolders(query, user);

            return ResponseEntity.ok(Map.of(
                "folders", folders.stream()
                    .map(this::folderToMap)
                    .collect(Collectors.toList()),
                "query", query
            ));

        } catch (Exception e) {
            log.error("Error searching folders", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Delete a folder
     */
    @DeleteMapping("/{folderId}")
    public ResponseEntity<?> deleteFolder(
            @PathVariable Long folderId,
            @RequestParam(value = "deleteContents", defaultValue = "false") boolean deleteContents) {

        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            folderService.deleteFolder(folderId, user, deleteContents);

            return ResponseEntity.ok(Map.of("message", "Folder deleted successfully"));

        } catch (Exception e) {
            log.error("Error deleting folder", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Rename a folder
     */
    @PutMapping("/{folderId}/rename")
    public ResponseEntity<?> renameFolder(
            @PathVariable Long folderId,
            @RequestBody Map<String, String> request) {

        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            String newName = request.get("newName");
            if (newName == null || newName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("message", "New name is required"));
            }
            Folder folder = folderService.renameFolder(folderId, newName, user);

            return ResponseEntity.ok(Map.of(
                "message", "Folder renamed successfully",
                "folder", folderToMap(folder)
            ));

        } catch (Exception e) {
            log.error("Error renaming folder", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Move folder to another parent folder
     */
    @PutMapping("/{folderId}/move")
    public ResponseEntity<?> moveFolder(
            @PathVariable Long folderId,
            @RequestParam(value = "targetParentFolderId", required = false) Long targetParentFolderId) {

        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            Folder folder = folderService.moveFolder(folderId, targetParentFolderId, user);

            return ResponseEntity.ok(Map.of(
                "message", "Folder moved successfully",
                "folder", folderToMap(folder)
            ));

        } catch (Exception e) {
            log.error("Error moving folder", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get breadcrumb path for a folder
     */
    @GetMapping("/{folderId}/breadcrumb")
    public ResponseEntity<?> getBreadcrumb(@PathVariable Long folderId) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            List<Map<String, Object>> breadcrumb = folderService.getBreadcrumb(folderId, user);

            return ResponseEntity.ok(Map.of("breadcrumb", breadcrumb));

        } catch (Exception e) {
            log.error("Error getting breadcrumb", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get root breadcrumb
     */
    @GetMapping("/root/breadcrumb")
    public ResponseEntity<?> getRootBreadcrumb() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }
            List<Map<String, Object>> breadcrumb = folderService.getBreadcrumb(null, user);

            return ResponseEntity.ok(Map.of("breadcrumb", breadcrumb));

        } catch (Exception e) {
            log.error("Error getting root breadcrumb", e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", errorMessage));
        }
    }

    // Helper method
    private Map<String, Object> folderToMap(Folder folder) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", folder.getId());
        map.put("name", folder.getName());
        map.put("fullPath", folder.getFullPath());
        map.put("createdAt", folder.getCreatedAt());
        map.put("updatedAt", folder.getUpdatedAt());

        if (folder.getParentFolder() != null) {
            map.put("parentFolderId", folder.getParentFolder().getId());
            map.put("parentFolderName", folder.getParentFolder().getName());
        }

        return map;
    }
}

