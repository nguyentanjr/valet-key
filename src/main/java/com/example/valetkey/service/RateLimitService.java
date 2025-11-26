package com.example.valetkey.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Service
public class RateLimitService {

    private final LettuceBasedProxyManager<String> proxyManager;
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public RateLimitService(LettuceBasedProxyManager<String> proxyManager,
                           RedisTemplate<String, Object> redisTemplate) {
        this.proxyManager = proxyManager;
        this.redisTemplate = redisTemplate;
    }


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


    /**
     * Resolve or create bucket from Redis
     * Buckets are stored in Redis and shared across all instances
     */
    public Bucket resolveBucket(String key, RateLimitType type) {
        // Create bucket configuration supplier
        Supplier<BucketConfiguration> configurationSupplier = () -> {
            Bandwidth limit = Bandwidth.builder()
                .capacity(type.getCapacity())
                .refillGreedy(type.getCapacity(), type.getRefillDuration())
                .build();
            
            return BucketConfiguration.builder()
                .addLimit(limit)
                .build();
        };
        
        // Get or create bucket from Redis
        return proxyManager.builder()
            .build(key, configurationSupplier);
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
     * Clear bucket for a specific key (for testing or admin purposes)
     * The key should be the full key (e.g., "user:1:UPLOAD_SMALL")
     */
    public void clearBucket(String key, RateLimitType type) {
        // Key already contains type (e.g., "user:1:UPLOAD_SMALL")
        // So we use it directly
        try {
            log.info("Bucket clear requested for key: {} (type: {})", key, type);
            // Delete the key from Redis
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Failed to clear bucket for key: {}", key, e);
        }
    }

    /**
     * Clear all rate limit buckets (for testing or admin purposes)
     * This will delete all keys matching the bucket pattern from Redis
     * Pattern: user:*, ip:*, token:*
     */
    public void clearAllBuckets() {
        try {
            int deletedCount = 0;
            
            // Delete all keys matching bucket patterns
            Set<String> userKeys = redisTemplate.keys("user:*");
            Set<String> ipKeys = redisTemplate.keys("ip:*");
            Set<String> tokenKeys = redisTemplate.keys("token:*");
            
            if (userKeys != null && !userKeys.isEmpty()) {
                deletedCount += redisTemplate.delete(userKeys).intValue();
            }
            if (ipKeys != null && !ipKeys.isEmpty()) {
                deletedCount += redisTemplate.delete(ipKeys).intValue();
            }
            if (tokenKeys != null && !tokenKeys.isEmpty()) {
                deletedCount += redisTemplate.delete(tokenKeys).intValue();
            }
            
            log.info("Cleared {} rate limit buckets from Redis", deletedCount);
        } catch (Exception e) {
            log.error("Failed to clear all buckets", e);
        }
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


