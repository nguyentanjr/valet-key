package com.example.valetkey.controller;

import com.example.valetkey.model.Folder;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.UserRepository;
import com.example.valetkey.service.FolderService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/folders")
public class FolderController {

    @Autowired
    private FolderService folderService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Create a new folder
     */
    @PostMapping("/create")
    public ResponseEntity<?> createFolder(
            @RequestBody Map<String, Object> request,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
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

            User user = userRepository.getUserById(sessionUser.getId());
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
    public ResponseEntity<?> getFolder(@PathVariable Long folderId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
            @RequestParam(value = "parentFolderId", required = false) Long parentFolderId,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
    public ResponseEntity<?> getFolderTree(HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
            @RequestParam("query") String query,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
            @RequestParam(value = "deleteContents", defaultValue = "false") boolean deleteContents,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
            @RequestBody Map<String, String> request,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            String newName = request.get("newName");
            if (newName == null || newName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("message", "New name is required"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
            @RequestParam(value = "targetParentFolderId", required = false) Long targetParentFolderId,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
    public ResponseEntity<?> getBreadcrumb(@PathVariable Long folderId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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
    public ResponseEntity<?> getRootBreadcrumb(HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
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

