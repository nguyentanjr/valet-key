package com.example.valetkey.config;

import com.example.valetkey.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureConfig {

    @Value("${azure.storage.connection-string:}")
    private String connectionString;

    @Bean
    public BlobServiceClient blobServiceCient() {
        return new BlobServiceClientBuilder().connectionString(connectionString)
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
