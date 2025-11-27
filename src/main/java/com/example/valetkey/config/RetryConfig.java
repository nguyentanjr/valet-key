package com.example.valetkey.config;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetryConfig {

    private static final Logger log = LoggerFactory.getLogger(RetryConfig.class);

    public RetryConfig(RetryRegistry registry) {
        Retry retry = registry.retry("azureService");
        retry.getEventPublisher()
                .onRetry(event -> {
                    log.warn("[RETRY] Attempt {} failed due to {}",
                            event.getNumberOfRetryAttempts(),
                            event.getLastThrowable().toString());
                });
    }
}
