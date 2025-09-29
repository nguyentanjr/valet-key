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

    @PostMapping("/permission")
    public ResponseEntity<?> createUserPermission(@RequestBody Map<String, Boolean> permission, HttpSession httpSession) {
        User user = (User) httpSession.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        user.setCreate(permission.get("create"));
        user.setRead(permission.get("read"));
        user.setWrite(permission.get("write"));
        userRepository.save(user);
        System.out.println(user);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/user-list")
    public ResponseEntity<?> getUserList() {
        return ResponseEntity.ok(userService.getAllUsers());
    }
}
