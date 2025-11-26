package com.example.valetkey.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Configure cache manager with different TTL for each cache type.
     * Each cache has its own configuration based on data characteristics.
     * 
     * TTL Strategy:
     * - Short TTL (30s-1min): Frequently changing data (file list, storage)
     * - Medium TTL (2-5min): Moderately changing data (search results)
     * - Long TTL (9-15min): Rarely changing data (SAS URLs, metadata, folders)
     */
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        
        cacheManager.setCaches(Arrays.asList(
            // SAS URLs - 9 minutes (slightly less than SAS expiry of 10 min)
            // Rarely changes, safe to cache long
            buildCache("sasUrls", 500, 9, TimeUnit.MINUTES),
            
            // File metadata - 15 minutes (rarely changes after upload)
            // Individual file properties don't change often
            buildCache("fileMetadata", 1000, 15, TimeUnit.MINUTES),
            
            // File list - 1 minute (changes frequently with uploads/deletes/renames/moves)
            // Users need to see new files immediately after upload
            buildCache("fileList", 200, 1, TimeUnit.MINUTES),
            
            // User storage info - 1 minute (updates on every upload/delete)
            // Critical for quota enforcement, must be accurate
            buildCache("userStorage", 100, 1, TimeUnit.MINUTES),
            
            // Folder tree - 5 minutes (changes when folders created/deleted/renamed)
            // Less frequent than file operations
            buildCache("folderTree", 200, 5, TimeUnit.MINUTES),
            
            // Search results - 2 minutes (can change with new uploads matching query)
            // Balance between performance and freshness
            buildCache("searchResults", 300, 2, TimeUnit.MINUTES)
        ));
        
        return cacheManager;
    }

    /**
     * Build a Caffeine cache with specific configuration
     */
    private Cache buildCache(String name, int maxSize, long ttl, TimeUnit timeUnit) {
        return new CaffeineCache(name, Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttl, timeUnit)
            .recordStats()
            .build());
    }
}


