package com.example.valetkey.controller;

import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.ResourceRepository;
import com.example.valetkey.repository.UserRepository;
import com.example.valetkey.service.CompressionService;
import com.example.valetkey.service.FileService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/files")
public class FileController {

    @Autowired
    private FileService fileService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private CompressionService compressionService;

    /**
     * Upload a file through backend
     * Note: This route must be before /{fileId} to avoid route conflicts
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) Long folderId,
            @RequestParam(value = "fileName", required = false) String fileName,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());

            Resource resource = fileService.uploadFile(file, user, folderId, fileName);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "File uploaded successfully");
            response.put("file", fileToMap(resource));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error uploading file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to upload file: " + e.getMessage()));
        }
    }

    /**
     * Upload multiple files at once (batch upload)
     */
    @PostMapping(value = "/upload/batch", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadMultipleFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "folderId", required = false) Long folderId,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());

            if (files == null || files.length == 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("message", "No files provided"));
            }

            log.info("Batch upload started: {} files by user {}", files.length, user.getUsername());

            List<Map<String, Object>> successList = new ArrayList<>();
            List<Map<String, Object>> failureList = new ArrayList<>();

            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                try {
                    Resource resource = fileService.uploadFile(file, user, folderId, null);
                    
                    Map<String, Object> fileResult = new HashMap<>();
                    fileResult.put("index", i);
                    fileResult.put("fileName", file.getOriginalFilename());
                    fileResult.put("status", "success");
                    fileResult.put("file", fileToMap(resource));
                    successList.add(fileResult);
                    
                    log.debug("Batch upload: File {} uploaded successfully", file.getOriginalFilename());
                    
                } catch (Exception e) {
                    Map<String, Object> fileResult = new HashMap<>();
                    fileResult.put("index", i);
                    fileResult.put("fileName", file.getOriginalFilename());
                    fileResult.put("status", "failed");
                    fileResult.put("error", e.getMessage());
                    failureList.add(fileResult);
                    
                    log.error("Batch upload: File {} failed: {}", file.getOriginalFilename(), e.getMessage());
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("totalFiles", files.length);
            response.put("successCount", successList.size());
            response.put("failureCount", failureList.size());
            response.put("successfulFiles", successList);
            response.put("failedFiles", failureList);
            response.put("message", String.format("Batch upload completed: %d succeeded, %d failed", 
                successList.size(), failureList.size()));

            log.info("Batch upload completed: {} succeeded, {} failed", successList.size(), failureList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error in batch upload", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Batch upload failed: " + e.getMessage()));
        }
    }

    /**
     * Get file metadata
     */
    @GetMapping("/{fileId:\\d+}")
    public ResponseEntity<?> getFile(@PathVariable Long fileId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            Map<String, Object> metadata = fileService.getFileMetadata(fileId, user);

            return ResponseEntity.ok(metadata);

        } catch (Exception e) {
            log.error("Error getting file", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get download URL for a file
     */
    @GetMapping("/{fileId:\\d+}/download")
    public ResponseEntity<?> getDownloadUrl(@PathVariable Long fileId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            String downloadUrl = fileService.getDownloadUrl(fileId, user);

            return ResponseEntity.ok(Map.of(
                "downloadUrl", downloadUrl,
                "expiresInMinutes", 10
            ));

        } catch (Exception e) {
            log.error("Error generating download URL", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Delete a file
     */
    @DeleteMapping("/{fileId:\\d+}")
    public ResponseEntity<?> deleteFile(@PathVariable Long fileId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            fileService.deleteFile(fileId, user);

            return ResponseEntity.ok(Map.of("message", "File deleted successfully"));

        } catch (Exception e) {
            log.error("Error deleting file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get all file IDs for current user (no pagination, for select all)
     */
    @GetMapping("/all-ids")
    public ResponseEntity<?> getAllFileIds(
            @RequestParam(value = "folderId", required = false) Long folderId,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            List<Resource> allFiles = fileService.getAllFiles(user, folderId);
            List<Long> fileIds = allFiles.stream()
                .map(Resource::getId)
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "fileIds", fileIds,
                "count", fileIds.size()
            ));

        } catch (Exception e) {
            log.error("Error getting all file IDs", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * List files in a folder (with pagination)
     */
    @GetMapping("/list")
    public ResponseEntity<?> listFiles(
            @RequestParam(value = "folderId", required = false) Long folderId,
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
            Page<Resource> filesPage = fileService.listFiles(user, folderId, page, size);

            Map<String, Object> response = new HashMap<>();
            response.put("files", filesPage.getContent().stream()
                .map(this::fileToMap)
                .collect(Collectors.toList()));
            response.put("currentPage", filesPage.getNumber());
            response.put("totalPages", filesPage.getTotalPages());
            response.put("totalItems", filesPage.getTotalElements());
            response.put("hasNext", filesPage.hasNext());
            response.put("hasPrevious", filesPage.hasPrevious());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error listing files", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Search files by name
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchFiles(
            @RequestParam("query") String query,
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
            Page<Resource> filesPage = fileService.searchFiles(user, query, page, size);

            Map<String, Object> response = new HashMap<>();
            response.put("files", filesPage.getContent().stream()
                .map(this::fileToMap)
                .collect(Collectors.toList()));
            response.put("currentPage", filesPage.getNumber());
            response.put("totalPages", filesPage.getTotalPages());
            response.put("totalItems", filesPage.getTotalElements());
            response.put("query", query);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error searching files", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Move file to another folder
     */
    @PutMapping("/{fileId:\\d+}/move")
    public ResponseEntity<?> moveFile(
            @PathVariable Long fileId,
            @RequestParam(value = "targetFolderId", required = false) Long targetFolderId,
            HttpSession session) {

        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            Resource resource = fileService.moveFile(fileId, targetFolderId, user);

            return ResponseEntity.ok(Map.of(
                "message", "File moved successfully",
                "file", fileToMap(resource)
            ));

        } catch (Exception e) {
            log.error("Error moving file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Rename a file
     */
    @PutMapping("/{fileId:\\d+}/rename")
    public ResponseEntity<?> renameFile(
            @PathVariable Long fileId,
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
            Resource resource = fileService.renameFile(fileId, newName, user);

            return ResponseEntity.ok(Map.of(
                "message", "File renamed successfully",
                "file", fileToMap(resource)
            ));

        } catch (Exception e) {
            log.error("Error renaming file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Generate public sharing link
     */
    @PostMapping("/{fileId:\\d+}/share")
    public ResponseEntity<?> generatePublicLink(@PathVariable Long fileId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            String token = fileService.generatePublicLink(fileId, user);

            return ResponseEntity.ok(Map.of(
                "message", "Public link generated successfully",
                "publicLinkToken", token,
                "publicUrl", "/api/public/files/" + token
            ));

        } catch (Exception e) {
            log.error("Error generating public link", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Revoke public sharing link
     */
    @DeleteMapping("/{fileId:\\d+}/share")
    public ResponseEntity<?> revokePublicLink(@PathVariable Long fileId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            fileService.revokePublicLink(fileId, user);

            return ResponseEntity.ok(Map.of("message", "Public link revoked successfully"));

        } catch (Exception e) {
            log.error("Error revoking public link", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get user storage info
     */
    @GetMapping("/storage")
    public ResponseEntity<?> getStorageInfo(HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            Map<String, Object> storageInfo = fileService.getUserStorageInfo(user);

            return ResponseEntity.ok(storageInfo);

        } catch (Exception e) {
            log.error("Error getting storage info", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }


    /**
     * Bulk delete files
     */
    @PostMapping("/bulk-delete")
    public ResponseEntity<?> bulkDeleteFiles(
            @RequestBody Map<String, Object> request,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            @SuppressWarnings("unchecked")
            List<Object> fileIdsObj = (List<Object>) request.get("fileIds");
            if (fileIdsObj == null || fileIdsObj.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("message", "fileIds is required"));
            }

            // Convert to List<Long> handling both Integer and Long
            List<Long> fileIds = fileIdsObj.stream()
                .map(id -> {
                    if (id instanceof Long) {
                        return (Long) id;
                    } else if (id instanceof Integer) {
                        return ((Integer) id).longValue();
                    } else if (id instanceof Number) {
                        return ((Number) id).longValue();
                    } else {
                        return Long.valueOf(id.toString());
                    }
                })
                .collect(java.util.stream.Collectors.toList());

            User user = userRepository.getUserById(sessionUser.getId());
            fileService.bulkDeleteFiles(fileIds, user);

            return ResponseEntity.ok(Map.of(
                "message", "Files deleted successfully",
                "count", fileIds.size()
            ));

        } catch (Exception e) {
            log.error("Error bulk deleting files", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Bulk move files
     */
    @PostMapping("/bulk-move")
    public ResponseEntity<?> bulkMoveFiles(
            @RequestBody Map<String, Object> request,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            @SuppressWarnings("unchecked")
            List<Object> fileIdsObj = (List<Object>) request.get("fileIds");
            if (fileIdsObj == null || fileIdsObj.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("message", "fileIds is required"));
            }

            // Convert to List<Long> handling both Integer and Long
            List<Long> fileIds = fileIdsObj.stream()
                .map(id -> {
                    if (id instanceof Long) {
                        return (Long) id;
                    } else if (id instanceof Integer) {
                        return ((Integer) id).longValue();
                    } else if (id instanceof Number) {
                        return ((Number) id).longValue();
                    } else {
                        return Long.valueOf(id.toString());
                    }
                })
                .collect(java.util.stream.Collectors.toList());

            Long targetFolderId = null;
            if (request.get("targetFolderId") != null) {
                Object targetIdObj = request.get("targetFolderId");
                if (targetIdObj instanceof Long) {
                    targetFolderId = (Long) targetIdObj;
                } else if (targetIdObj instanceof Integer) {
                    targetFolderId = ((Integer) targetIdObj).longValue();
                } else if (targetIdObj instanceof Number) {
                    targetFolderId = ((Number) targetIdObj).longValue();
                } else {
                    targetFolderId = Long.valueOf(targetIdObj.toString());
                }
            }

            User user = userRepository.getUserById(sessionUser.getId());
            fileService.bulkMoveFiles(fileIds, targetFolderId, user);

            return ResponseEntity.ok(Map.of(
                "message", "Files moved successfully",
                "count", fileIds.size()
            ));

        } catch (Exception e) {
            log.error("Error bulk moving files", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Download multiple files as ZIP
     */
    @PostMapping("/bulk-download")
    public ResponseEntity<?> bulkDownloadFiles(
            @RequestBody Map<String, Object> request,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            @SuppressWarnings("unchecked")
            List<Object> fileIdsObj = (List<Object>) request.get("fileIds");
            if (fileIdsObj == null || fileIdsObj.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("message", "fileIds is required"));
            }

            // Convert to List<Long> handling both Integer and Long
            List<Long> fileIds = fileIdsObj.stream()
                .map(id -> {
                    if (id instanceof Long) {
                        return (Long) id;
                    } else if (id instanceof Integer) {
                        return ((Integer) id).longValue();
                    } else if (id instanceof Number) {
                        return ((Number) id).longValue();
                    } else {
                        return Long.valueOf(id.toString());
                    }
                })
                .collect(java.util.stream.Collectors.toList());

            User user = userRepository.getUserById(sessionUser.getId());
            
            // Get files
            List<Resource> files = resourceRepository.findByIdsAndUploader(fileIds, user);
            if (files.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("message", "No valid files found"));
            }

            // Create ZIP
            byte[] zipBytes = compressionService.createZipFile(files, user);
            String zipFileName = compressionService.getZipFileName(files);

            // Return as downloadable response
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", zipFileName);
            headers.setContentLength(zipBytes.length);

            return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error creating ZIP file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to create ZIP: " + e.getMessage()));
        }
    }

    /**
     * Initialize resume upload
     */
    @PostMapping("/upload/initiate")
    public ResponseEntity<?> initiateResumeUpload(
            @RequestBody Map<String, Object> request,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            String fileName = (String) request.get("fileName");
            Long fileSize = Long.valueOf(request.get("fileSize").toString());
            Long folderId = request.get("folderId") != null 
                ? Long.valueOf(request.get("folderId").toString()) 
                : null;

            User user = userRepository.getUserById(sessionUser.getId());
            String sessionId = fileService.initiateResumeUpload(fileName, fileSize, user, folderId);

            return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "chunkSize", 5 * 1024 * 1024 // 5MB chunks
            ));

        } catch (Exception e) {
            log.error("Error initiating upload", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Upload chunk
     */
    @PostMapping("/upload/chunk")
    public ResponseEntity<?> uploadChunk(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("chunk") MultipartFile chunk,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            fileService.uploadChunk(sessionId, chunkIndex, chunk.getBytes(), user);

            return ResponseEntity.ok(Map.of("message", "Chunk uploaded successfully"));

        } catch (Exception e) {
            log.error("Error uploading chunk", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Complete resume upload
     */
    @PostMapping("/upload/complete")
    public ResponseEntity<?> completeResumeUpload(
            @RequestBody Map<String, Object> request,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            String sessionId = (String) request.get("sessionId");
            @SuppressWarnings("unchecked")
            List<String> blockIds = (List<String>) request.get("blockIds");

            User user = userRepository.getUserById(sessionUser.getId());
            Resource resource = fileService.completeResumeUpload(sessionId, blockIds, user);

            return ResponseEntity.ok(Map.of(
                "message", "Upload completed successfully",
                "file", fileToMap(resource)
            ));

        } catch (Exception e) {
            log.error("Error completing upload", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get upload status
     */
    @GetMapping("/upload/status/{sessionId}")
    public ResponseEntity<?> getUploadStatus(@PathVariable String sessionId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            Map<String, Object> status = fileService.getUploadStatus(sessionId, user);

            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("Error getting upload status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get uncommitted blocks for resume capability
     */
    @GetMapping("/upload/uncommitted/{sessionId}")
    public ResponseEntity<?> getUncommittedBlocks(@PathVariable String sessionId, HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            User user = userRepository.getUserById(sessionUser.getId());
            Map<String, Object> result = fileService.getUncommittedBlocks(sessionId, user);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error getting uncommitted blocks", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Generate SAS URL for direct upload to Azure
     * Frontend will use this URL to upload directly to Azure, bypassing backend
     */
    @PostMapping("/upload/sas-url")
    public ResponseEntity<?> generateUploadSasUrl(
            @RequestBody Map<String, Object> request,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            String fileName = (String) request.get("fileName");
            Long fileSize = Long.valueOf(request.get("fileSize").toString());
            Long folderId = request.get("folderId") != null 
                ? Long.valueOf(request.get("folderId").toString()) 
                : null;
            Integer expiryMinutes = request.get("expiryMinutes") != null
                ? Integer.valueOf(request.get("expiryMinutes").toString())
                : 60; // Default 60 minutes

            User user = userRepository.getUserById(sessionUser.getId());
            
            // Generate blob path
            String blobPath = fileService.generateBlobPath(fileName, user, folderId);
            
            // Generate SAS URL with write permission
            String sasUrl = fileService.generateUploadSasUrl(blobPath, expiryMinutes, user);

            return ResponseEntity.ok(Map.of(
                "sasUrl", sasUrl,
                "blobPath", blobPath,
                "expiryMinutes", expiryMinutes,
                "expiresAt", System.currentTimeMillis() + (expiryMinutes * 60 * 1000L)
            ));

        } catch (Exception e) {
            log.error("Error generating upload SAS URL", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Confirm upload and save metadata to database
     * Called by frontend after successful direct upload to Azure
     */
    @PostMapping("/upload/confirm")
    public ResponseEntity<?> confirmUpload(
            @RequestBody Map<String, Object> request,
            HttpSession session) {
        try {
            User sessionUser = (User) session.getAttribute("user");
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
            }

            String blobPath = (String) request.get("blobPath");
            String fileName = (String) request.get("fileName");
            Long fileSize = Long.valueOf(request.get("fileSize").toString());
            String contentType = (String) request.get("contentType");
            Long folderId = request.get("folderId") != null 
                ? Long.valueOf(request.get("folderId").toString()) 
                : null;

            User user = userRepository.getUserById(sessionUser.getId());
            
            // Verify blob exists in Azure
            if (!fileService.verifyBlobExists(blobPath)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "File not found in Azure. Upload may have failed."));
            }

            // Save metadata to database
            Resource resource = fileService.confirmDirectUpload(
                blobPath, fileName, fileSize, contentType, user, folderId
            );

            return ResponseEntity.ok(Map.of(
                "message", "Upload confirmed successfully",
                "file", fileToMap(resource)
            ));

        } catch (Exception e) {
            log.error("Error confirming upload", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", e.getMessage()));
        }
    }

    // Helper method
    private Map<String, Object> fileToMap(Resource resource) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", resource.getId());
        map.put("fileName", resource.getFileName());
        map.put("originalName", resource.getOriginalName());
        map.put("fileSize", resource.getFileSize());
        map.put("contentType", resource.getContentType());
        map.put("filePath", resource.getFilePath());
        map.put("uploadedAt", resource.getUploadedAt());
        map.put("lastModified", resource.getLastModified());
        map.put("isPublic", resource.isPublic());
        map.put("publicLinkToken", resource.getPublicLinkToken());

        // Folder information
        if (resource.getFolder() != null) {
            Map<String, Object> folderInfo = new HashMap<>();
            folderInfo.put("id", resource.getFolder().getId());
            folderInfo.put("name", resource.getFolder().getName());
            folderInfo.put("fullPath", resource.getFolder().getFullPath());
            map.put("folder", folderInfo);
            
            // Backward compatibility
            map.put("folderId", resource.getFolder().getId());
            map.put("folderName", resource.getFolder().getName());
            map.put("folderPath", resource.getFolder().getFullPath());
        } else {
            map.put("folder", null);
        }

        // Owner/Uploader information
        if (resource.getUploader() != null) {
            Map<String, Object> ownerInfo = new HashMap<>();
            ownerInfo.put("id", resource.getUploader().getId());
            ownerInfo.put("username", resource.getUploader().getUsername());
            map.put("owner", ownerInfo);
        }

        return map;
    }
}

