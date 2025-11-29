package com.example.valetkey.model;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public class CustomUserDetails implements UserDetails, Serializable {

    private static final long serialVersionUID = 1L;
    
    // Chỉ lưu thông tin cần thiết, không lưu toàn bộ User entity
    // Điều này tránh vấn đề serialize JPA entity và LocalDateTime
    private Long userId;
    private String username;
    private String password;
    private User.Role role;

    public CustomUserDetails(User user) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.role = user.getRole();
    }

    // Constructor mặc định để deserialize từ session
    public CustomUserDetails() {
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (role != null) {
            // User.Role enum already has "ROLE_" prefix (ROLE_USER, ROLE_ADMIN)
            // So we use it directly without adding another prefix
            return List.of(new SimpleGrantedAuthority(role.name()));
        }
        // nếu user chưa có role, mặc định là USER
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() { 
        return password; 
    }

    @Override
    public String getUsername() { 
        return username; 
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public User.Role getRole() {
        return role;
    }

    public void setRole(User.Role role) {
        this.role = role;
    }

    @Override
    public boolean isAccountNonExpired() { 
        return true; 
    }

    @Override
    public boolean isAccountNonLocked() { 
        return true; 
    }

    @Override
    public boolean isCredentialsNonExpired() { 
        return true; 
    }

    @Override
    public boolean isEnabled() { 
        return true; 
    }
}
