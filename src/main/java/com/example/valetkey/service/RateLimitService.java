package com.example.valetkey.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final LettuceBasedProxyManager<String> proxyManager;
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public RateLimitService(LettuceBasedProxyManager<String> proxyManager,
                           RedisTemplate<String, Object> redisTemplate) {
        this.proxyManager = proxyManager;
        this.redisTemplate = redisTemplate;
    }


    public enum RateLimitType {
        // === SECURITY CRITICAL (Strict) ===
        // Login - 5 requests per minute per IP
        // Chống brute force: 5 lần thử/phút là đủ cho user thật
        LOGIN(5, Duration.ofMinutes(1)),

        // === RESOURCE INTENSIVE (Balanced) ===
        // Upload - 30 requests per minute per user
        // Cho phép upload nhiều files nhỏ liên tục, UX tốt hơn
        UPLOAD_SMALL(30, Duration.ofMinutes(1)),
        
        // Bulk Operations - 5 requests per minute per user
        // Bulk operations RẤT tốn tài nguyên, giảm xuống 5
        BULK_OPERATION(5, Duration.ofMinutes(1)),

        // Download - 50 requests per minute per user
        // Cân bằng giữa UX và bandwidth protection
        DOWNLOAD(50, Duration.ofMinutes(1)),

        // List Files - 60 requests per minute per user
        // Giữ nguyên, cần cho pagination
        LIST_FILES(60, Duration.ofMinutes(1)),

        // Search - 20 requests per minute per user
        // Giảm xuống 20 vì search tốn DB resources
        SEARCH(20, Duration.ofMinutes(1)),

        // === PUBLIC ACCESS (Strict) ===
        // Public Access by IP - 30 requests per minute per IP
        // Giảm xuống 30 để chặn scraping bots
        PUBLIC_ACCESS_IP(30, Duration.ofMinutes(1)),

        // Public Access by Token - 100 requests per hour per token
        // Giảm xuống 100/hour (~1.6/min) để chặn abuse
        PUBLIC_ACCESS_TOKEN(100, Duration.ofHours(1));

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


    public boolean tryConsume(String key, RateLimitType type) {
        Bucket bucket = resolveBucket(key, type);
        boolean consumed = bucket.tryConsume(1);
        
        if (!consumed) {
            log.warn("Rate limit exceeded for key: {} with type: {}", key, type);
        }
        
        return consumed;
    }


    public long getAvailableTokens(String key, RateLimitType type) {
        Bucket bucket = resolveBucket(key, type);
        return bucket.getAvailableTokens();
    }


    public String generateUserKey(Long userId, RateLimitType type) {
        return String.format("user:%d:%s", userId, type.name());
    }


    public String generateIpKey(String ipAddress, RateLimitType type) {
        return String.format("ip:%s:%s", ipAddress, type.name());
    }


    public String generateTokenKey(String token, RateLimitType type) {
        return String.format("token:%s:%s", token, type.name());
    }


    public void clearBucket(String key, RateLimitType type) {

        try {
            log.info("Bucket clear requested for key: {} (type: {})", key, type);
            // Delete the key from Redis
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Failed to clear bucket for key: {}", key, e);
        }
    }


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


    public Map<String, Long> getBucketStats(String key, RateLimitType type) {
        Bucket bucket = resolveBucket(key, type);
        return Map.of(
            "availableTokens", bucket.getAvailableTokens(),
            "capacity", type.getCapacity()
        );
    }
}


