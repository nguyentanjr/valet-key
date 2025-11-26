package com.example.valetkey.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Map<String, Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        // Log only if it's not a common browser request (like favicon or OPTIONS)
        String method = ex.getMethod();
        if (!"OPTIONS".equals(method) && !ex.getMessage().contains("favicon")) {
            log.warn("Method not supported: {} for URL: {}", method, ex.getMessage());
        }
        return Map.of(
            "error", "Method Not Allowed",
            "message", "The HTTP method " + method + " is not supported for this endpoint"
        );
    }
}


