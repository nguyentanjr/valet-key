package com.example.valetkey.service;

import com.example.valetkey.model.User;
import com.example.valetkey.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public void createDemoUsers() {
        if (userRepository.findUserByUsername("demo").isEmpty()) {
            User demoUser = new User("demo", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan1").isEmpty()) {
            User demoUser = new User("tan1", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan2").isEmpty()) {
            User demoUser = new User("tan2", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan3").isEmpty()) {
            User demoUser = new User("tan3", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan4").isEmpty()) {
            User demoUser = new User("tan4", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }

        if (userRepository.findUserByUsername("admin").isEmpty()) {
            User adminUser = new User("admin", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            adminUser.setRole(User.Role.ROLE_ADMIN);
            userRepository.save(adminUser);
        }
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findUserByUsername(username);
    }

    /**
     * Update storage quota for a single user
     */
    @Transactional
    public User updateStorageQuota(Long userId, Long newQuotaBytes) {
        if (newQuotaBytes == null || newQuotaBytes <= 0) {
            throw new RuntimeException("Storage quota must be greater than 0");
        }
        
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (user.getStorageUsed() != null && user.getStorageUsed() > newQuotaBytes) {
            throw new RuntimeException("New quota is smaller than current storage usage");
        }
        
        user.setStorageQuota(newQuotaBytes);
        return userRepository.save(user);
    }

    /**
     * Update storage quota for all users
     */
    @Transactional
    public int updateAllUserQuotas(Long newQuotaBytes) {
        if (newQuotaBytes == null || newQuotaBytes <= 0) {
            throw new RuntimeException("Storage quota must be greater than 0");
        }
        
        List<User> users = userRepository.findAll();
        
        // Check if any user has storage used greater than new quota
        for (User user : users) {
            if (user.getStorageUsed() != null && user.getStorageUsed() > newQuotaBytes) {
                throw new RuntimeException("Cannot set quota smaller than current usage for user " + user.getUsername());
            }
        }
        
        // Update all users
        users.forEach(user -> user.setStorageQuota(newQuotaBytes));
        userRepository.saveAll(users);
        
        return users.size();
    }

    /**
     * Get system statistics
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSystemStats(int topN) {
        Map<String, Object> stats = new HashMap<>();
        
        long totalUsers = userRepository.count();
        Long totalStorageUsed = userRepository.getTotalStorageUsed();
        Long totalStorageQuota = userRepository.getTotalStorageQuota();
        
        stats.put("totalUsers", totalUsers);
        stats.put("totalStorageUsed", totalStorageUsed != null ? totalStorageUsed : 0L);
        stats.put("totalStorageQuota", totalStorageQuota != null ? totalStorageQuota : 0L);
        stats.put("usagePercentage", totalStorageQuota != null && totalStorageQuota > 0
            ? (double) totalStorageUsed * 100 / totalStorageQuota
            : 0.0);
        
        // Top consumers by storage usage
        List<Map<String, Object>> topConsumers = userRepository.findAll().stream()
            .sorted(Comparator.comparing(User::getStorageUsed, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(topN)
            .map(user -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", user.getId());
                map.put("username", user.getUsername());
                map.put("storageUsed", user.getStorageUsed() != null ? user.getStorageUsed() : 0L);
                map.put("storageQuota", user.getStorageQuota() != null ? user.getStorageQuota() : 0L);
                map.put("usagePercentage", user.getStorageQuota() != null && user.getStorageQuota() > 0
                    ? (double) (user.getStorageUsed() != null ? user.getStorageUsed() : 0L) * 100 / user.getStorageQuota()
                    : 0.0);
                return map;
            })
            .collect(Collectors.toList());
        
        stats.put("topConsumers", topConsumers);
        
        return stats;
    }
}
