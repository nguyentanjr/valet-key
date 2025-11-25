package com.example.valetkey.interceptor;

import com.example.valetkey.model.User;
import com.example.valetkey.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Autowired
    private RateLimitService rateLimitService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) 
            throws Exception {
        
        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        // Determine rate limit type based on endpoint
        RateLimitService.RateLimitType limitType = determineRateLimitType(requestUri, method);
        
        if (limitType == null) {
            // No rate limit for this endpoint
            return true;
        }

        // Generate rate limit key
        String key = generateRateLimitKey(request, requestUri, limitType);
        
        if (key == null) {
            // Cannot determine key, allow request
            return true;
        }

        // Check rate limit
        boolean allowed = rateLimitService.tryConsume(key, limitType);
        
        if (!allowed) {
            handleRateLimitExceeded(response, key, limitType);
            return false;
        }

        // Add rate limit headers
        addRateLimitHeaders(response, key, limitType);
        
        return true;
    }

    /**
     * Determine rate limit type based on request URI and method
     */
    private RateLimitService.RateLimitType determineRateLimitType(String uri, String method) {
        // Authentication
        if (uri.equals("/login") && method.equals("POST")) {
            return RateLimitService.RateLimitType.LOGIN;
        }

        // Direct Azure Upload - Generate SAS URL
        // Rate limit: Prevent abuse of SAS URL generation
        if (uri.equals("/api/files/upload/sas-url") && method.equals("POST")) {
            return RateLimitService.RateLimitType.UPLOAD_SMALL;
        }

        // Direct Azure Upload - Confirm Upload
        // Rate limit: Prevent abuse of confirm endpoint
        if (uri.equals("/api/files/upload/confirm") && method.equals("POST")) {
            return RateLimitService.RateLimitType.UPLOAD_SMALL;
        }

        // Bulk Operations
        if (uri.startsWith("/api/files/bulk-") && method.equals("POST")) {
            return RateLimitService.RateLimitType.BULK_OPERATION;
        }

        // Download
        if (uri.matches("/api/files/\\d+/download") && method.equals("GET")) {
            return RateLimitService.RateLimitType.DOWNLOAD;
        }

        // Public Access
        if (uri.startsWith("/api/public/files/") && method.equals("GET")) {
            return RateLimitService.RateLimitType.PUBLIC_ACCESS_IP;
        }

        // Search
        if (uri.equals("/api/files/search") && method.equals("GET")) {
            return RateLimitService.RateLimitType.SEARCH;
        }

        // List Files
        if (uri.equals("/api/files/list") && method.equals("GET")) {
            return RateLimitService.RateLimitType.LIST_FILES;
        }

        // No rate limit for other endpoints
        return null;
    }

    /**
     * Generate rate limit key based on request context
     */
    private String generateRateLimitKey(HttpServletRequest request, String uri, 
                                       RateLimitService.RateLimitType limitType) {
        
        // For login, use IP address
        if (limitType == RateLimitService.RateLimitType.LOGIN) {
            String ipAddress = getClientIpAddress(request);
            return rateLimitService.generateIpKey(ipAddress, limitType);
        }

        // For public access, use IP address
        if (limitType == RateLimitService.RateLimitType.PUBLIC_ACCESS_IP) {
            String ipAddress = getClientIpAddress(request);
            return rateLimitService.generateIpKey(ipAddress, limitType);
        }

        // For authenticated endpoints, use user ID
        HttpSession session = request.getSession(false);
        if (session != null) {
            User user = (User) session.getAttribute("user");
            if (user != null) {
                return rateLimitService.generateUserKey(user.getId(), limitType);
            }
        }

        // If no user session, use IP as fallback
        String ipAddress = getClientIpAddress(request);
        return rateLimitService.generateIpKey(ipAddress, limitType);
    }

    /**
     * Get client IP address from request
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }

    /**
     * Handle rate limit exceeded
     */
    private void handleRateLimitExceeded(HttpServletResponse response, String key, 
                                        RateLimitService.RateLimitType limitType) throws IOException {
        
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        
        String message = String.format(
            "{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests. Please try again later.\",\"limitType\":\"%s\"}",
            limitType.name()
        );
        
        response.getWriter().write(message);
        
        log.warn("Rate limit exceeded for key: {} with type: {}", key, limitType);
    }

    /**
     * Add rate limit headers to response
     */
    private void addRateLimitHeaders(HttpServletResponse response, String key, 
                                    RateLimitService.RateLimitType limitType) {
        
        long availableTokens = rateLimitService.getAvailableTokens(key, limitType);
        
        response.setHeader("X-RateLimit-Limit", String.valueOf(limitType.getCapacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(availableTokens));
        response.setHeader("X-RateLimit-Reset", String.valueOf(
            System.currentTimeMillis() + limitType.getRefillDuration().toMillis()
        ));
    }
}


