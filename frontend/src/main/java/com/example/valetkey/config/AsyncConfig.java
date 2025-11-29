package com.example.valetkey.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("Upload-");
        executor.initialize();
        return executor;
    }

    /**
     * Custom executor for async file uploads
     * Allows concurrent file uploads while limiting resource usage
     */
    @Bean(name = "asyncUploadExecutor")
    public Executor asyncUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);  // 3 concurrent uploads
        executor.setMaxPoolSize(5);   // Max 5 concurrent uploads
        executor.setQueueCapacity(50); // Queue up to 50 pending uploads
        executor.setThreadNamePrefix("upload-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}