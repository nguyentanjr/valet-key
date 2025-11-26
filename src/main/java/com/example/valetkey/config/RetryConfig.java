package com.example.valetkey.config;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RetryConfig {

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
