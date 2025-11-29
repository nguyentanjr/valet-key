package com.example.valetkey.controller;

import com.example.valetkey.model.User;
import com.example.valetkey.repository.UserRepository;
import com.example.valetkey.service.AzureSasService;
import com.example.valetkey.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;


import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/user")
public class AzureController {

    @Autowired
    private AzureSasService azureSasService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;




    @PostMapping("/upload-sas")
    public ResponseEntity<?> getUploadSas(@RequestParam String blobName, HttpSession session) throws Exception {
        User sessionUser = (User) session.getAttribute("user");
        if (sessionUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        User user = userRepository.getUserById(sessionUser.getId());
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("message", "User not found"));
        }

        if (blobName == null || blobName.trim().isEmpty() || blobName.contains("..")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Invalid blob name"));
        }

        if (!user.isCreate() && !user.isWrite()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to upload"));
        }

        String userBlobPath = "user-" + user.getId() + "/" + blobName;
        int expiryMinutes = 15;
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
        log.info("Download");
        User sessionUser = (User) session.getAttribute("user");
        if (sessionUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        User user = userRepository.getUserById(sessionUser.getId());
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("message", "User not found"));
        }

        if (!user.isRead()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to download"));
        }
        String userBlobPath = "user-" + user.getId() + "/" + blobName;
        int expiryMinutes = 10;
        String sasUrl = azureSasService.generateBlobReadSas(userBlobPath, expiryMinutes, user);
        System.out.println(sasUrl);
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


        User user = (User) session.getAttribute("user");

        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        if (!user.isCreate() || !user.isWrite()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "No permission"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Empty file"));
        }

        try {
            String finalFileName = (fileName != null && !fileName.trim().isEmpty())
                    ? fileName.trim() : file.getOriginalFilename();


            String uniqueFileName = finalFileName;
            String blobPath = "user-" + user.getId() + "/" + uniqueFileName;
            String filePath = azureSasService.uploadFile(file, blobPath);

            // Đo sau khi upload
            long t1 = System.nanoTime();
            double cpuAfter = osBean.getProcessCpuLoad(); // 0..1
            long memAfterBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            double elapsedSec = (t1 - t0) / 1_000_000_000.0;
            double cpuAvgPct = avgCpuPercent(cpuBefore, cpuAfter);
            double memAvgMB = ((memBeforeBytes + memAfterBytes) / 2.0) / (1024.0 * 1024.0);


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



}
