package com.example.valetkey.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Map<String, Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String method = ex.getMethod();
        if (!"OPTIONS".equals(method) && !ex.getMessage().contains("favicon")) {
            log.warn("Method not supported: {} for URL: {}", method, ex.getMessage());
        }
        return Map.of(
            "error", "Method Not Allowed",
            "message", "The HTTP method " + method + " is not supported for this endpoint"
        );
    }


    @ExceptionHandler(CallNotPermittedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> handleCircuitBreakerOpen(CallNotPermittedException ex) {
        log.warn("Circuit Breaker is OPEN. Request rejected: {}", ex.getMessage());
        return Map.of(
            "error", "Service Temporarily Unavailable",
            "message", "The system is temporarily overloaded. Please try again later.",
            "circuitBreakerOpen", true,
            "retryAfter", 60 // seconds
        );
    }


    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleRuntimeException(RuntimeException ex) {
        String message = ex.getMessage();
        
        if (message != null && (
            message.contains("temporarily unavailable") ||
            message.contains("tạm thời quá tải") ||
            message.contains("Circuit breaker")
        )) {
            log.warn("Circuit Breaker related error: {}", message);
            return Map.of(
                "error", "Service Temporarily Unavailable",
                "message", "The system is temporarily overloaded. Please try again later.",
                "circuitBreakerOpen", true,
                "retryAfter", 60
            );
        }
        
        // Generic runtime exception
        log.error("Runtime exception: {}", message, ex);
        return Map.of(
            "error", "Internal Server Error",
            "message", message != null ? message : "An unexpected error occurred"
        );
    }
}


