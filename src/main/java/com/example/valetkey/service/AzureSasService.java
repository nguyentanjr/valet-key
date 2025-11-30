package com.example.valetkey.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.ResourceRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AzureSasService {

    private static final Logger log = LoggerFactory.getLogger(AzureSasService.class);

    @Autowired
    private BlobServiceClient blobServiceClient;

    @Autowired
    private ResourceRepository resourceRepository;

    @CircuitBreaker(name = "azureService", fallbackMethod = "generateBlobReadSasFallback")
    @Retry(name = "azureService")
    // Removed @Cacheable to prevent conflicts with Circuit Breaker and Retry
    // Cache should be handled at a higher level (e.g., in FileService)
    public String generateBlobReadSas(String blobName, int expiryMinutes, User user) throws InterruptedException {
        log.warn(">>> Retry calling Azure now...");
        log.debug("Generating SAS URL for blob: {}", blobName);

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobName);

        if (!blobClient.exists()) {
            throw new RuntimeException("Blob not found: " + blobName);
        }

        BlobSasPermission permission = new BlobSasPermission();
        permission.setReadPermission(user.isRead());

        OffsetDateTime expiryTime = OffsetDateTime.now().plusMinutes(expiryMinutes);
        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiryTime, permission);

        String sas = blobClient.generateSas(sasValues);
        String sasUrl = blobClient.getBlobUrl() + "?" + sas;
        
        log.debug("Generated SAS URL successfully for blob: {}", blobName);
        return sasUrl;
    }
    
    // Fallback method for circuit breaker
    public String generateBlobReadSasFallback(String blobName, int expiryMinutes, User user, Exception ex) {
        log.error("Circuit breaker fallback: Failed to generate SAS for blob: {}, Error: {}", blobName, ex.getMessage());
        throw new RuntimeException("Azure Storage is temporarily unavailable. Please try again later.");
    }

    public String generateBlobWriteSas(String blobName, int expiryMinutes, User user) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobName);

        BlobSasPermission permission = new BlobSasPermission();
        permission.setCreatePermission(true);
        permission.setWritePermission(true);
        permission.setAddPermission(true);
        permission.setDeletePermission(false);

        OffsetDateTime expiryTime = OffsetDateTime.now().plusMinutes(expiryMinutes);
        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiryTime, permission);

        String sas = blobClient.generateSas(sasValues);
        return blobClient.getBlobUrl() + "?" + sas;
    }

    public List<String> listBlobs(Long userId) {
        String prefix = "user-" + userId + "/";
        BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient("valet-demo");

        List<String> result = new ArrayList<>();

        for (BlobItem blobItem : blobContainerClient.listBlobs(new ListBlobsOptions().setPrefix(prefix), null)) {
            result.add(blobItem.getName());
        }

        return result;
    }

    @CircuitBreaker(name = "azureService", fallbackMethod = "deleteBlobFallback")
    public void deleteBlob(String blobPath) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobPath);

        if (blobClient.exists()) {
            blobClient.delete();
        }
    }

    private void deleteBlobFallback(String blobName, Exception e) {
        log.error("Circuit breaker OPEN for deleteBlob. Azure is unavailable. Error: {}",
                e.getMessage());
        throw new RuntimeException(
                "Delete operation temporarily unavailable. File will be removed later.", e);
    }

    public boolean blobExists(String blobPath) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        return blobClient.exists();
    }

}
