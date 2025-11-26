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


    public enum RateLimitType {

        LOGIN(5, Duration.ofMinutes(15)),

        UPLOAD_SMALL(20, Duration.ofMinutes(1)),
        

        BULK_OPERATION(10, Duration.ofMinutes(1)),

        DOWNLOAD(100, Duration.ofMinutes(1)),

        LIST_FILES(60, Duration.ofMinutes(1)),

        SEARCH(30, Duration.ofMinutes(1)),

        PUBLIC_ACCESS_IP(50, Duration.ofMinutes(1)),

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


    public Bucket resolveBucket(String key, RateLimitType type) {
        return buckets.computeIfAbsent(key, k -> createBucket(type));
    }


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


