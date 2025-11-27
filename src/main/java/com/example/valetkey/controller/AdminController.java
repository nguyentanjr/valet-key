package com.example.valetkey.controller;

import com.example.valetkey.model.User;
import com.example.valetkey.repository.UserRepository;
import com.example.valetkey.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    /**
     * Get all users - Returns consistent format with users array
     */
    @GetMapping("/user-list")
    public ResponseEntity<?> getUserList() {
        List<User> users = userService.getAllUsers();
        
        // Convert User entities to safe Map format (exclude password, handle LocalDateTime)
        List<Map<String, Object>> usersList = users.stream()
            .map(user -> {
                Map<String, Object> userMap = new HashMap<>();
                userMap.put("id", user.getId());
                userMap.put("username", user.getUsername());
                userMap.put("role", user.getRole() != null ? user.getRole().toString() : null);
                userMap.put("create", user.isCreate());
                userMap.put("read", user.isRead());
                userMap.put("write", user.isWrite());
                userMap.put("storageQuota", user.getStorageQuota() != null ? user.getStorageQuota() : 0L);
                userMap.put("storageUsed", user.getStorageUsed() != null ? user.getStorageUsed() : 0L);
                userMap.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
                // ✅ KHÔNG trả về password!
                return userMap;
            })
            .collect(Collectors.toList());
        
        // Return consistent format: { users: [...] }
        return ResponseEntity.ok(Map.of("users", usersList));
    }

    /**
     * Update user permissions - Returns safe Map format
     */
    @PostMapping("/permission/{id}")
    public ResponseEntity<?> updateUserPermission(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> permission) {

        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        user.setCreate(permission.get("create"));
        user.setRead(permission.get("read"));
        user.setWrite(permission.get("write"));

        User updatedUser = userRepository.save(user);
        
        // Return safe Map format (no password)
        Map<String, Object> response = new HashMap<>();
        response.put("id", updatedUser.getId());
        response.put("username", updatedUser.getUsername());
        response.put("role", updatedUser.getRole() != null ? updatedUser.getRole().toString() : null);
        response.put("create", updatedUser.isCreate());
        response.put("read", updatedUser.isRead());
        response.put("write", updatedUser.isWrite());
        response.put("storageQuota", updatedUser.getStorageQuota() != null ? updatedUser.getStorageQuota() : 0L);
        response.put("storageUsed", updatedUser.getStorageUsed() != null ? updatedUser.getStorageUsed() : 0L);
        response.put("message", "Permissions updated successfully");
        
        return ResponseEntity.ok(response);
    }

    /**
     * Update user quota - Returns safe Map format
     */
    @PutMapping("/quota/{id}")
    public ResponseEntity<?> updateUserQuota(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload) {
        try {
            Double quotaGb = ((Number) payload.get("storageQuotaGb")).doubleValue();
            Long quotaBytes = (long) (quotaGb * 1024 * 1024 * 1024);
            
            User updatedUser = userService.updateStorageQuota(id, quotaBytes);
            
            // Return safe Map format (no password)
            Map<String, Object> response = new HashMap<>();
            response.put("id", updatedUser.getId());
            response.put("username", updatedUser.getUsername());
            response.put("role", updatedUser.getRole() != null ? updatedUser.getRole().toString() : null);
            response.put("storageQuota", updatedUser.getStorageQuota() != null ? updatedUser.getStorageQuota() : 0L);
            response.put("storageUsed", updatedUser.getStorageUsed() != null ? updatedUser.getStorageUsed() : 0L);
            response.put("message", "Quota updated successfully");
            
            return ResponseEntity.ok(response);
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
