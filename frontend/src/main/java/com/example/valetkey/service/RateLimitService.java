package com.example.valetkey.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RateLimitService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Rate limit configurations by endpoint type (BEST PRACTICE)
     * 
     * Strategy:
     * - Security-critical: Low limits (login, registration)
     * - Resource-intensive: Medium limits (upload, bulk operations)
     * - Read operations: High limits (download, list)
     * - Public access: Dual limits (IP + token)
     */
    public enum RateLimitType {
        // === SECURITY-CRITICAL (Low Limits) ===
        
        // Login - 5 requests per 15 minutes per IP
        // Best Practice: Low limit prevents brute-force attacks
        LOGIN(5, Duration.ofMinutes(15)),
        
        // === RESOURCE-INTENSIVE (Medium Limits) ===
        
        // Small File Upload (<10MB) - 20 requests per minute per user
        // Best Practice: Normal usage pattern for small files
        UPLOAD_SMALL(20, Duration.ofMinutes(1)),
        
        // Large File Upload (>10MB) - 5 requests per minute per user
        // Best Practice: Lower limit for resource-intensive operations
        UPLOAD_LARGE(5, Duration.ofMinutes(1)),
        
        // Bulk Operations - 10 requests per minute per user
        // Best Practice: Prevent abuse of batch operations
        BULK_OPERATION(10, Duration.ofMinutes(1)),
        
        // Async Upload Initiation - 10 requests per minute per user
        // Best Practice: Limit concurrent background jobs
        ASYNC_UPLOAD(10, Duration.ofMinutes(1)),
        
        // === READ OPERATIONS (High Limits) ===
        
        // File Download - 100 requests per minute per user
        // Best Practice: High limit, downloads are cheap
        DOWNLOAD(100, Duration.ofMinutes(1)),
        
        // List Files - 60 requests per minute per user
        // Best Practice: Allow frequent refreshes
        LIST_FILES(60, Duration.ofMinutes(1)),
        
        // Search Operations - 30 requests per minute per user
        // Best Practice: Balance between UX and DB load
        SEARCH(30, Duration.ofMinutes(1)),
        
        // === PUBLIC ACCESS (Dual Limits) ===
        
        // Public File Access - 50 requests per minute per IP
        // Best Practice: Prevent IP-based abuse
        PUBLIC_ACCESS_IP(50, Duration.ofMinutes(1)),
        
        // Public File Access by Token - 200 requests per hour
        // Best Practice: Prevent token-based abuse
        PUBLIC_ACCESS_TOKEN(200, Duration.ofHours(1));

        private final long capacity;
        private final Duration refillDuration;

        RateLimitType(long capacity, Duration refillDuration) {
            this.capacity = capacity;
            this.refillDuration = refillDuration;
        }

        public long getCapacity() {
            return capacity;
        }

        public Duration getRefillDuration() {
            return refillDuration;
        }
        
        /**
         * Get user-friendly description for error messages
         */
        public String getDescription() {
            return String.format("%d requests per %s", 
                capacity, 
                formatDuration(refillDuration));
        }
        
        private String formatDuration(Duration duration) {
            if (duration.toHours() > 0) {
                return duration.toHours() + " hour" + (duration.toHours() > 1 ? "s" : "");
            } else if (duration.toMinutes() > 0) {
                return duration.toMinutes() + " minute" + (duration.toMinutes() > 1 ? "s" : "");
            } else {
                return duration.getSeconds() + " second" + (duration.getSeconds() > 1 ? "s" : "");
            }
        }
    }

    /**
     * Get or create bucket for a specific key and rate limit type
     */
    public Bucket resolveBucket(String key, RateLimitType type) {
        return buckets.computeIfAbsent(key, k -> createBucket(type));
    }

    /**
     * Create a new bucket with specified rate limit (BEST PRACTICE)
     * 
     * Uses greedy refill strategy:
     * - Tokens refill at constant rate (not all at once)
     * - Smoother traffic distribution
     * - Better for sustained load
     */
    private Bucket createBucket(RateLimitType type) {
        // Best Practice: Use greedy refill for smoother rate limiting
        Bandwidth limit = Bandwidth.builder()
            .capacity(type.getCapacity())
            .refillGreedy(type.getCapacity(), type.getRefillDuration())
            .build();
            
        return Bucket.builder()
            .addLimit(limit)
            .build();
    }

    /**
     * Try to consume tokens from bucket
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryConsume(String key, RateLimitType type) {
        Bucket bucket = resolveBucket(key, type);
        boolean consumed = bucket.tryConsume(1);
        
        if (!consumed) {
            log.warn("Rate limit exceeded for key: {} with type: {}", key, type);
        }
        
        return consumed;
    }

    /**
     * Get remaining tokens for a key
     */
    public long getAvailableTokens(String key, RateLimitType type) {
        Bucket bucket = resolveBucket(key, type);
        return bucket.getAvailableTokens();
    }

    /**
     * Generate rate limit key based on user ID
     */
    public String generateUserKey(Long userId, RateLimitType type) {
        return String.format("user:%d:%s", userId, type.name());
    }

    /**
     * Generate rate limit key based on IP address
     */
    public String generateIpKey(String ipAddress, RateLimitType type) {
        return String.format("ip:%s:%s", ipAddress, type.name());
    }

    /**
     * Generate rate limit key based on token
     */
    public String generateTokenKey(String token, RateLimitType type) {
        return String.format("token:%s:%s", token, type.name());
    }

    /**
     * Clear all buckets (for testing or admin purposes)
     */
    public void clearAllBuckets() {
        buckets.clear();
        log.info("All rate limit buckets cleared");
    }

    /**
     * Get bucket statistics
     */
    public Map<String, Long> getBucketStats(String key, RateLimitType type) {
        Bucket bucket = resolveBucket(key, type);
        return Map.of(
            "availableTokens", bucket.getAvailableTokens(),
            "capacity", type.getCapacity()
        );
    }
}


