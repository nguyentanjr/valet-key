package com.example.valetkey.controller;

import com.example.valetkey.model.User;
import com.example.valetkey.repository.UserRepository;
import com.example.valetkey.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @GetMapping("/user-list")
    public ResponseEntity<?> getUserList() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PostMapping("/permission/{id}")
    public ResponseEntity<?> updateUserPermission(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> permission,
            HttpSession session) {

        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body("User not found");
        }

        user.setCreate(permission.get("create"));
        user.setRead(permission.get("read"));
        user.setWrite(permission.get("write"));

        return ResponseEntity.ok(userRepository.save(user));
    }

    @PutMapping("/quota/{id}")
    public ResponseEntity<?> updateUserQuota(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload) {
        try {
            Double quotaGb = ((Number) payload.get("storageQuotaGb")).doubleValue();
            Long quotaBytes = (long) (quotaGb * 1024 * 1024 * 1024);
            
            User updatedUser = userService.updateStorageQuota(id, quotaBytes);
            return ResponseEntity.ok(updatedUser);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/quota")
    public ResponseEntity<?> updateAllUserQuotas(
            @RequestBody Map<String, Object> payload) {
        try {
            Double quotaGb = ((Number) payload.get("storageQuotaGb")).doubleValue();
            Long quotaBytes = (long) (quotaGb * 1024 * 1024 * 1024);
            
            int updatedCount = userService.updateAllUserQuotas(quotaBytes);
            return ResponseEntity.ok(Map.of(
                "message", "Updated quota for " + updatedCount + " users",
                "updatedCount", updatedCount,
                "newQuotaBytes", quotaBytes
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getSystemStats(
            @RequestParam(defaultValue = "5") int top) {
        try {
            return ResponseEntity.ok(userService.getSystemStats(top));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
