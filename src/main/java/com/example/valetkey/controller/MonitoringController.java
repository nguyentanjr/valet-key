package com.example.valetkey.controller;

import com.example.valetkey.service.RateLimitService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/admin/monitoring")
public class MonitoringController {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired(required = false)
    private RetryRegistry retryRegistry;

    @Autowired
    private CacheManager cacheManager;

    /**
     * Get circuit breaker status
     */
    @GetMapping("/circuit-breakers")
    public ResponseEntity<?> getCircuitBreakerStatus() {
        if (circuitBreakerRegistry == null) {
            return ResponseEntity.ok(Map.of("message", "Circuit breaker not configured"));
        }

        Map<String, Object> status = new HashMap<>();
        
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            Map<String, Object> cbInfo = new HashMap<>();
            cbInfo.put("state", cb.getState().name());
            cbInfo.put("failureRate", cb.getMetrics().getFailureRate());
            cbInfo.put("numberOfFailedCalls", cb.getMetrics().getNumberOfFailedCalls());
            cbInfo.put("numberOfSuccessfulCalls", cb.getMetrics().getNumberOfSuccessfulCalls());
            cbInfo.put("numberOfBufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
            cbInfo.put("numberOfNotPermittedCalls", cb.getMetrics().getNumberOfNotPermittedCalls());
            
            status.put(cb.getName(), cbInfo);
        });

        return ResponseEntity.ok(status);
    }

    /**
     * Get retry metrics
     */
    @GetMapping("/retries")
    public ResponseEntity<?> getRetryMetrics() {
        if (retryRegistry == null) {
            return ResponseEntity.ok(Map.of("message", "Retry not configured"));
        }

        Map<String, Object> metrics = new HashMap<>();
        
        retryRegistry.getAllRetries().forEach(retry -> {
            Map<String, Object> retryInfo = new HashMap<>();
            retryInfo.put("numberOfSuccessfulCallsWithoutRetryAttempt", 
                retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt());
            retryInfo.put("numberOfSuccessfulCallsWithRetryAttempt", 
                retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt());
            retryInfo.put("numberOfFailedCallsWithRetryAttempt", 
                retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt());
            retryInfo.put("numberOfFailedCallsWithoutRetryAttempt", 
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt());
            
            metrics.put(retry.getName(), retryInfo);
        });

        return ResponseEntity.ok(metrics);
    }

    /**
     * Get cache statistics
     */
    @GetMapping("/cache")
    public ResponseEntity<?> getCacheStats() {
        Map<String, Object> cacheStats = new HashMap<>();
        
        cacheManager.getCacheNames().forEach(cacheName -> {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                Map<String, Object> stats = new HashMap<>();
                stats.put("name", cacheName);
                stats.put("nativeCache", cache.getNativeCache().getClass().getSimpleName());
                
                // Try to get Caffeine stats if available
                Object nativeCache = cache.getNativeCache();
                if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache) {
                    com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache = 
                        (com.github.benmanes.caffeine.cache.Cache<?, ?>) nativeCache;
                    
                    var cacheStats2 = caffeineCache.stats();
                    stats.put("hitCount", cacheStats2.hitCount());
                    stats.put("missCount", cacheStats2.missCount());
                    stats.put("hitRate", cacheStats2.hitRate());
                    stats.put("evictionCount", cacheStats2.evictionCount());
                    stats.put("estimatedSize", caffeineCache.estimatedSize());
                }
                
                cacheStats.put(cacheName, stats);
            }
        });

        return ResponseEntity.ok(cacheStats);
    }

    /**
     * Clear specific cache
     */
    @PostMapping("/cache/clear/{cacheName}")
    public ResponseEntity<?> clearCache(@PathVariable String cacheName) {
        var cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
            log.info("Cache '{}' cleared by admin", cacheName);
            return ResponseEntity.ok(Map.of("message", "Cache '" + cacheName + "' cleared successfully"));
        }
        return ResponseEntity.badRequest().body(Map.of("message", "Cache not found: " + cacheName));
    }

    /**
     * Clear all caches
     */
    @PostMapping("/cache/clear-all")
    public ResponseEntity<?> clearAllCaches() {
        cacheManager.getCacheNames().forEach(cacheName -> {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        });
        log.info("All caches cleared by admin");
        return ResponseEntity.ok(Map.of("message", "All caches cleared successfully"));
    }

    /**
     * Get rate limit bucket statistics for a user
     */
    @GetMapping("/rate-limits/user/{userId}")
    public ResponseEntity<?> getRateLimitStats(@PathVariable Long userId) {
        Map<String, Object> stats = new HashMap<>();
        
        for (RateLimitService.RateLimitType type : RateLimitService.RateLimitType.values()) {
            String key = rateLimitService.generateUserKey(userId, type);
            Map<String, Long> bucketStats = rateLimitService.getBucketStats(key, type);
            stats.put(type.name(), bucketStats);
        }

        return ResponseEntity.ok(stats);
    }

    /**
     * Clear all rate limit buckets (useful for testing)
     */
    @PostMapping("/rate-limits/clear-all")
    public ResponseEntity<?> clearAllRateLimits() {
        rateLimitService.clearAllBuckets();
        log.warn("All rate limit buckets cleared by admin");
        return ResponseEntity.ok(Map.of("message", "All rate limit buckets cleared successfully"));
    }

    /**
     * Get overall system health summary
     */
    @GetMapping("/health-summary")
    public ResponseEntity<?> getHealthSummary() {
        Map<String, Object> health = new HashMap<>();

        // Circuit breaker health
        if (circuitBreakerRegistry != null) {
            long openCircuits = circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .filter(cb -> cb.getState().name().equals("OPEN"))
                .count();
            health.put("circuitBreakersOpen", openCircuits);
            health.put("circuitBreakersTotal", circuitBreakerRegistry.getAllCircuitBreakers().size());
        }

        // Cache health
        long totalCaches = cacheManager.getCacheNames().size();
        health.put("caches", totalCaches);

        // Overall status
        boolean allHealthy = circuitBreakerRegistry == null || 
            circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .noneMatch(cb -> cb.getState().name().equals("OPEN"));

        health.put("status", allHealthy ? "HEALTHY" : "DEGRADED");
        health.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(health);
    }

    /**
     * Reset circuit breaker to closed state (emergency use)
     */
    @PostMapping("/circuit-breakers/{name}/reset")
    public ResponseEntity<?> resetCircuitBreaker(@PathVariable String name) {
        if (circuitBreakerRegistry == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Circuit breaker not configured"));
        }

        try {
            var cb = circuitBreakerRegistry.circuitBreaker(name);
            cb.reset();
            log.warn("Circuit breaker '{}' reset by admin", name);
            return ResponseEntity.ok(Map.of("message", "Circuit breaker '" + name + "' reset successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Circuit breaker not found: " + name));
        }
    }
}


