package com.example.valetkey.controller;

import com.example.valetkey.model.User;
import com.example.valetkey.service.AzureSasService;
import com.example.valetkey.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

}
