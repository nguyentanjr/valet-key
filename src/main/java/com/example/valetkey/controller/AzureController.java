package com.example.valetkey.controller;

import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.ResourceRepository;
import com.example.valetkey.service.AzureSasService;
import com.example.valetkey.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;


import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/user")
public class AzureController {

    @Autowired
    private AzureSasService azureSasService;

    @Autowired
    private UserService userService;

    @Autowired
    private ResourceRepository resourceRepository;



    @PostMapping("/upload-sas")
    public ResponseEntity<?> getUploadSas(@RequestParam String blobName, HttpSession session) throws Exception {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        String userBlobPath = "user-" + user.getId() + "/" + blobName;
        int expiryMinutes = 3;
        String sasUrl = azureSasService.generateBlobWriteSas(userBlobPath, expiryMinutes, user);
        if (sasUrl == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to upload"));
        }
        return ResponseEntity.ok(Map.of(
                "sasUrl", sasUrl,
                "blobPath", userBlobPath,
                "expiresInMinutes", expiryMinutes
        ));
    }


    @GetMapping("/download-sas")
    public ResponseEntity<?> getDownloadSas(@RequestParam String blobName, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        String userBlobPath = "user-" + user.getId() + "/" + blobName;
        int expiryMinutes = 3;
        String sasUrl = azureSasService.generateBlobReadSas(userBlobPath, expiryMinutes, user);
        if (sasUrl == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to download this blob"));
        }
        return ResponseEntity.ok(Map.of(
                "sasUrl", sasUrl,
                "blobPath", userBlobPath,
                "expiresInMinutes", expiryMinutes
        ));
    }

    @GetMapping("/list-blob")
    public ResponseEntity<?> getListBlob(@RequestParam String userId) {
        List<String> listBlob = azureSasService.listBlobs(Long.parseLong(userId));
        return ResponseEntity.ok(listBlob);
    }

    @PostMapping("/proxy-upload")
    public ResponseEntity<?> proxyUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fileName", required = false) String fileName,
            HttpSession session) {

        long t0 = System.nanoTime();

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double cpuBefore = osBean.getProcessCpuLoad(); // 0..1 (có thể -1 nếu chưa có mẫu)
        long memBeforeBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        System.out.println("🚀 Proxy upload request received");
        System.out.println("📁 File: " + (file != null ? file.getOriginalFilename() : "null"));
        System.out.println("📁 File size: " + (file != null ? file.getSize() : "null"));
        System.out.println("🔑 Session ID: " + session.getId());

        User user = (User) session.getAttribute("user");
        System.out.println("👤 User from session: " + (user != null ? user.getUsername() : "null"));

        if (user == null) {
            System.out.println("❌ User not authenticated - session expired?");
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        System.out.println("🔍 User permissions - Create: " + user.isCreate() + ", Write: " + user.isWrite());
        if (!user.isCreate() || !user.isWrite()) {
            System.out.println("❌ User does not have required permissions");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "No permission"));
        }

        if (file.isEmpty()) {
            System.out.println("❌ File is empty");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Empty file"));
        }

        try {
            String finalFileName = (fileName != null && !fileName.trim().isEmpty())
                    ? fileName.trim() : file.getOriginalFilename();

            System.out.println("📤 Starting upload for: " + finalFileName);

            String filePath = azureSasService.proxyUploadFileWithSpeed(file, finalFileName, user);

            // Đo sau khi upload
            long t1 = System.nanoTime();
            double cpuAfter = osBean.getProcessCpuLoad(); // 0..1
            long memAfterBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            double elapsedSec = (t1 - t0) / 1_000_000_000.0;
            double cpuAvgPct = avgCpuPercent(cpuBefore, cpuAfter);
            double memAvgMB = ((memBeforeBytes + memAfterBytes) / 2.0) / (1024.0 * 1024.0);

            System.out.println(String.format("⏱️  Server time: %.2fs", elapsedSec));
            System.out.println(String.format("🧠 Server CPU(avg): %.1f%%", cpuAvgPct));
            System.out.println(String.format("💾 Server Memory(avg): %.1f MB", memAvgMB));

            return ResponseEntity.ok(Map.of(
                    "message", "Upload completed successfully",
                    "fileName", finalFileName,
                    "filePath", filePath,
                    "serverTime_s", String.format("%.2f", elapsedSec),
                    "serverCPU_pct", String.format("%.1f", cpuAvgPct),
                    "serverMemory_MB", String.format("%.1f", memAvgMB)
            ));

        } catch (Exception e) {
            long t1 = System.nanoTime();
            double cpuAfter = osBean.getProcessCpuLoad();
            long memAfterBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            double elapsedSec = (t1 - t0) / 1_000_000_000.0;
            double cpuAvgPct = avgCpuPercent(cpuBefore, cpuAfter);
            double memAvgMB = ((memBeforeBytes + memAfterBytes) / 2.0) / (1024.0 * 1024.0);

            System.out.println("❌ Upload failed with exception: " + e.getMessage());
            e.printStackTrace();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "message", "Failed to upload: " + e.getMessage(),
                            "serverTime_s", String.format("%.2f", elapsedSec),
                            "serverCPU_pct", String.format("%.1f", cpuAvgPct),
                            "serverMemory_MB", String.format("%.1f", memAvgMB)
                    ));
        }
    }

    private double avgCpuPercent(double before, double after) {
        // getProcessCpuLoad() trả 0..1 hoặc -1 nếu chưa sẵn sàng; chuẩn hoá sang %
        double b = before >= 0 ? before * 100.0 : -1;
        double a = after >= 0 ? after * 100.0 : -1;
        if (b < 0 && a < 0) return 0.0;
        if (b < 0) return a;
        if (a < 0) return b;
        return (b + a) / 2.0;
    }

    @GetMapping("/my-files")
    public ResponseEntity<?> getMyFiles(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        try {
            List<Resource> userFiles = resourceRepository.findByUploaderOrderByUploadedAtDesc(user);
            
            // Tạo response với thông tin cần thiết
            List<Map<String, Object>> fileInfos = userFiles.stream()
                    .map(resource -> {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("id", resource.getId());
                        fileInfo.put("fileName", resource.getFileName());
                        fileInfo.put("originalName", resource.getOriginalName());
                        fileInfo.put("filePath", resource.getFilePath());
                        fileInfo.put("uploadedAt", resource.getUploadedAt().toString());
                        fileInfo.put("uploader", resource.getUploader().getUsername());
                        fileInfo.put("fileSize", resource.getFileSize());
                        fileInfo.put("fileSizeMB", resource.getFileSize() != null ? 
                                String.format("%.2f MB", resource.getFileSize() / 1024.0 / 1024.0) : "Unknown");
                        return fileInfo;
                    })
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "files", fileInfos,
                    "totalFiles", fileInfos.size()
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve files: " + e.getMessage()));
        }
    }

    @DeleteMapping("/delete-file/{fileId}")
    public ResponseEntity<?> deleteFile(@PathVariable Long fileId, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        try {
            Optional<Resource> resourceOpt = resourceRepository.findById(fileId);
            if (resourceOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "File not found"));
            }

            Resource resource = resourceOpt.get();
            
            // Kiểm tra quyền sở hữu file
            if (!resource.getUploader().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "You don't have permission to delete this file"));
            }

            // Xóa file từ Azure Blob Storage
            azureSasService.deleteFile(resource.getFilePath());
            
            // Xóa record từ database
            resourceRepository.delete(resource);

            return ResponseEntity.ok(Map.of(
                    "message", "File deleted successfully",
                    "fileName", resource.getFileName()
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to delete file: " + e.getMessage()));
        }
    }

    @GetMapping("/upload-info")
    public ResponseEntity<?> getUploadInfo() {
        long maxFileSize = 1_073_741_824L; // 1GB
        return ResponseEntity.ok(Map.of(
                "maxFileSize", maxFileSize,
                "maxFileSizeMB", "1024 MB",
                "maxFileSizeGB", "1 GB",
                "supportedFormats", "All file types supported",
                "uploadTimeout", "60 minutes",
                "parallelUpload", true,
                "progressTracking", true,
                "blockSize", "8MB",
                "maxConcurrency", 8
        ));
    }

}
