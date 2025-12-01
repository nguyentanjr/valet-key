package com.example.valetkey.interceptor;

import com.example.valetkey.model.User;
import com.example.valetkey.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    @Autowired
    private RateLimitService rateLimitService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) 
            throws Exception {
        
        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        RateLimitService.RateLimitType limitType = determineRateLimitType(requestUri, method);
        
        if (limitType == null) {
            return true;
        }

        String key = generateRateLimitKey(request, requestUri, limitType);
        
        if (key == null) {
            return true;
        }

        boolean allowed = rateLimitService.tryConsume(key, limitType);
        
        if (!allowed) {
            handleRateLimitExceeded(response, key, limitType);
            return false;
        }

        addRateLimitHeaders(response, key, limitType);
        
        return true;
    }

    /**
     * Determine rate limit type based on request URI and method
     */
    private RateLimitService.RateLimitType determineRateLimitType(String uri, String method) {

        // === SKIP RATE LIMIT FOR ESSENTIAL ENDPOINTS ===
        // These endpoints are required for basic app functionality
        if (uri.equals("/api/folders/tree") && method.equals("GET")) {
            return null; // No rate limit - needed for folder navigation
        }
        
        if (uri.matches("/api/folders/.*/breadcrumb") && method.equals("GET")) {
            return null; // No rate limit - needed for navigation breadcrumb
        }
        
        if (uri.equals("/api/folders/root/breadcrumb") && method.equals("GET")) {
            return null; // No rate limit - needed for root breadcrumb
        }
        
        if (uri.equals("/api/files/storage") && method.equals("GET")) {
            return null; // No rate limit - needed for storage info display
        }
        
        if (uri.equals("/user") && method.equals("GET")) {
            return null; // No rate limit - needed for auth check
        }
        
        if (uri.equals("/api/folders/list") && method.equals("GET")) {
            return null; // No rate limit - needed for folder listing
        }

        // === APPLY RATE LIMIT FOR RESOURCE-INTENSIVE OPERATIONS ===
        
        if (uri.equals("/login") && method.equals("POST")) {
            return RateLimitService.RateLimitType.LOGIN;
        }

        if (uri.equals("/api/files/upload/sas-url") && method.equals("POST")) {
            return RateLimitService.RateLimitType.UPLOAD_SMALL;
        }

        if (uri.equals("/api/files/upload/confirm") && method.equals("POST")) {
            return RateLimitService.RateLimitType.UPLOAD_SMALL;
        }

        if (uri.startsWith("/api/files/bulk-") && method.equals("POST")) {
            return RateLimitService.RateLimitType.BULK_OPERATION;
        }

        if (uri.matches("/api/files/\\d+/download") && method.equals("GET")) {
            return RateLimitService.RateLimitType.DOWNLOAD;
        }

        if (uri.startsWith("/api/public/files/") && method.equals("GET")) {
            return RateLimitService.RateLimitType.PUBLIC_ACCESS_IP;
        }

        if (uri.equals("/api/files/search") && method.equals("GET")) {
            return RateLimitService.RateLimitType.SEARCH;
        }

        if (uri.equals("/api/files/list") && method.equals("GET")) {
            return RateLimitService.RateLimitType.LIST_FILES;
        }

        return null; // No rate limit for other endpoints
    }


    private String generateRateLimitKey(HttpServletRequest request, String uri, 
                                       RateLimitService.RateLimitType limitType) {
        
        if (limitType == RateLimitService.RateLimitType.LOGIN) {
            String ipAddress = getClientIpAddress(request);
            return rateLimitService.generateIpKey(ipAddress, limitType);
        }

        if (limitType == RateLimitService.RateLimitType.PUBLIC_ACCESS_IP) {
            String ipAddress = getClientIpAddress(request);
            return rateLimitService.generateIpKey(ipAddress, limitType);
        }

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


