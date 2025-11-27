package com.example.valetkey.controller;

import com.example.valetkey.model.CustomUserDetails;
import com.example.valetkey.model.User;
import com.example.valetkey.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
public class AuthController {

    @Autowired
    private UserService userService;

    @Autowired
    private AuthenticationManager authenticationManager;

    @GetMapping("/whoami")
    public String whoami(HttpSession session) {
        session.setAttribute("check", "alive"); // tạo 1 key để xem có lưu không
        return "FROM BACKEND → " + System.getenv("HOSTNAME") +
                " | SESSION_ID = " + session.getId();
    }


    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> loginRequest, 
                                   HttpServletRequest request,
                                   jakarta.servlet.http.HttpServletResponse response) {
        try {
            String username = loginRequest.get("username");
            String password = loginRequest.get("password");

            // Authenticate user
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );


            SecurityContext securityContext = SecurityContextHolder.getContext();
            securityContext.setAuthentication(authentication);
            

            HttpSession session = request.getSession(true);

            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);

            Optional<User> userOpt = userService.findByUsername(username);
            if (userOpt.isPresent()) {
                User user = userOpt.get();

                Map<String, Object> responses = new HashMap<>();
                responses.put("message", "Login successful");
                responses.put("user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "role", user.getRole().toString(),
                        "create", user.isCreate(),
                        "read", user.isRead(),
                        "write", user.isWrite()
                ));

                return ResponseEntity.ok(responses);
            } else {
                return ResponseEntity.status(401).body(Map.of("message", "User not found"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid credentials: " + e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/user")
    public ResponseEntity<?> getCurrentUser() {
        // Lấy user từ SecurityContext thay vì từ session attribute
        // Điều này đảm bảo session được share giữa các backend instances
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        // Lấy username từ authentication
        String username = authentication.getName();
        
        // Lấy user từ database (hoặc từ CustomUserDetails nếu cần)
        Optional<User> userOpt = userService.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", "User not found"));
        }

        User user = userOpt.get();
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("role", user.getRole().toString());
        userInfo.put("create", user.isCreate());
        userInfo.put("read", user.isRead());
        userInfo.put("write", user.isWrite());

        return ResponseEntity.ok(userInfo);
    }
    
    @GetMapping("/debug/session")
    public ResponseEntity<?> debugSession(HttpServletRequest request) {
        Map<String, Object> debugInfo = new HashMap<>();
        
        HttpSession session = request.getSession(false);
        if (session != null) {
            debugInfo.put("sessionId", session.getId());
            debugInfo.put("sessionCreated", session.getCreationTime());
            debugInfo.put("lastAccessed", session.getLastAccessedTime());
            debugInfo.put("maxInactiveInterval", session.getMaxInactiveInterval());
            
            SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            debugInfo.put("hasSecurityContext", context != null);
            if (context != null && context.getAuthentication() != null) {
                debugInfo.put("username", context.getAuthentication().getName());
                debugInfo.put("authenticated", context.getAuthentication().isAuthenticated());
            }
        } else {
            debugInfo.put("sessionId", "NO_SESSION");
        }
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        debugInfo.put("securityContextAuth", auth != null ? auth.getName() : "null");
        
        return ResponseEntity.ok(debugInfo);
    }
}
