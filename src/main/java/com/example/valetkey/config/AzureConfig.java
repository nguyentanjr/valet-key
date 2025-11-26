package com.example.valetkey.config;

import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import com.example.valetkey.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class AzureConfig {

    @Value("${azure.storage.connection-string:}")
    private String connectionString;

    @Bean
    @Primary
    public BlobServiceClient blobServiceClient() {
        RequestRetryOptions noRetryOptions = new RequestRetryOptions(
                RetryPolicyType.FIXED,     // hoặc EXPONENTIAL đều được, không quan trọng
                1,                         // maxTries = 1 → không retry lần nào
                Duration.ofSeconds(3),     // tryTimeout = 2s ← cái bạn cần
                null,                      // retryDelay → để null vì không retry
                null,                      // maxRetryDelay → để null
                null                       // secondaryHost
        );

        // Tùy chọn: thêm low-level timeout để chắc chắn fail trong 2s
        HttpClient httpClient = new NettyAsyncHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(3))
                .writeTimeout(Duration.ofSeconds(3))
                .build();

        return new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .retryOptions(noRetryOptions)    // ← quan trọng: maxTries = 1
                .httpClient(httpClient)          // ← chắc chắn timeout 2s
                .buildClient();
    }

    @Bean
    public CommandLineRunner initData(@Autowired UserService userService) {
        return args -> {
            // Create demo users for testing
            userService.createDemoUsers();

            System.out.println("=".repeat(50));
            System.out.println("Valet Key Demo Application Started");
            System.out.println("=".repeat(50));
            System.out.println("Demo users:");
            System.out.println("- Username: demo, Password: demo123");
            System.out.println("- Username: admin, Password: admin123");
            System.out.println("=".repeat(50));
            System.out.println("Access the application at: http://localhost:8080");
            System.out.println("=".repeat(50));
        };
    }

}
