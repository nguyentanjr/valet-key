package com.example.valetkey.controller;

import com.example.valetkey.model.User;
import com.example.valetkey.service.AzureSasService;
import com.example.valetkey.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class AzureController {

    @Autowired
    private AzureSasService azureSasService;

    @Autowired
    private UserService userService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials, HttpSession httpSession) {
        String username = credentials.get("username");
        String password = credentials.get("password");
        Optional<User> user = userService.login(username, password);
        if (userService.login(username, password).isPresent()) {
            httpSession.setAttribute("user", user.get());
            List<User> userList = userService.getAllUsers();
            return ResponseEntity.ok(userList);
        }
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid credentials"));

    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @PostMapping("/permission")
    public ResponseEntity<?> createUserPermission(@RequestBody Map<String, Boolean> permission, HttpSession httpSession) {
        User user = (User) httpSession.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        user.setCreate(permission.get("create"));
        user.setRead(permission.get("read"));
        user.setWrite(permission.get("write"));

        return ResponseEntity.ok(user);

    }

    @GetMapping("/upload-sas")
    public ResponseEntity<?> getUploadSas(@RequestParam String blobName, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        String userBlobPath = "user-" + user.getId() + "/" + blobName;
        int expiryMinutes = 3;
        String sasUrl = azureSasService.generateBlobWriteSas(userBlobPath, expiryMinutes, user);
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
        return ResponseEntity.ok(Map.of(
                "sasUrl", sasUrl,
                "blobPath", userBlobPath,
                "expiresInMinutes", expiryMinutes
        ));
    }


}
