package com.example.valetkey.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.azure.storage.blob.models.BlockListType;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.ResourceRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AzureSasService {

    @Autowired
    private BlobServiceClient blobServiceClient;

    @Autowired
    private ResourceRepository resourceRepository;

    @Retry(name = "azureService")
    @CircuitBreaker(name = "azureService", fallbackMethod = "generateBlobReadSasFallback")
    @Cacheable(value = "sasUrls", key = "#blobName + '_' + #user.id")
    public String generateBlobReadSas(String blobName, int expiryMinutes, User user) {
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

    /**
     * Generate SAS URL with write permission for direct upload to Azure
     * Best Practice: Use for direct client-to-Azure uploads to reduce backend load
     */
    @Retry(name = "azureService")
    @CircuitBreaker(name = "azureService", fallbackMethod = "generateBlobWriteSasFallback")
    public String generateBlobWriteSas(String blobName, int expiryMinutes, User user) {
        log.debug("Generating write SAS URL for blob: {}", blobName);
        
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobName);

        // Create SAS permission with full upload capabilities
        BlobSasPermission permission = new BlobSasPermission();
        permission.setCreatePermission(true);  // Allow creating new blob
        permission.setWritePermission(true);   // Allow writing
        permission.setAddPermission(true);     // Allow adding blocks (for chunked upload)
        permission.setDeletePermission(false); // Don't allow delete

        OffsetDateTime expiryTime = OffsetDateTime.now().plusMinutes(expiryMinutes);
        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiryTime, permission);

        String sas = blobClient.generateSas(sasValues);
        String sasUrl = blobClient.getBlobUrl() + "?" + sas;
        
        log.debug("Generated write SAS URL successfully for blob: {}", blobName);
        return sasUrl;
    }
    
    public String generateBlobWriteSasFallback(String blobName, int expiryMinutes, User user, Exception ex) {
        log.error("Circuit breaker fallback: Failed to generate write SAS for blob: {}, Error: {}", blobName, ex.getMessage());
        throw new RuntimeException("Azure Storage is temporarily unavailable. Please try again later.");
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

    @Retry(name = "azureService")
    @CircuitBreaker(name = "azureService", fallbackMethod = "uploadFileFallback")
    public String uploadFile(MultipartFile file, String blobPath) throws IOException {
        log.debug("Uploading file to Azure: {}", blobPath);
        
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobPath);

        try (InputStream data = file.getInputStream()) {
            blobClient.upload(data, file.getSize(), true);
        }
        
        log.debug("File uploaded successfully to Azure: {}", blobPath);
        return blobClient.getBlobUrl();
    }
    
    public String uploadFileFallback(MultipartFile file, String blobPath, Exception ex) throws IOException {
        log.error("Circuit breaker fallback: Failed to upload file: {}, Error: {}", blobPath, ex.getMessage());
        throw new IOException("Azure Storage is temporarily unavailable. Please try again later.");
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
        // For delete, we might want to queue it for later instead of failing
        throw new RuntimeException(
                "Delete operation temporarily unavailable. File will be removed later.", e);
    }

    public boolean blobExists(String blobPath) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        return blobClient.exists();
    }

    /**
     * Upload a chunk (block) for resume upload
     */
    @Retry(name = "azureService")
    @CircuitBreaker(name = "azureService", fallbackMethod = "uploadChunkFallback")
    public String uploadChunk(String blobPath, int chunkIndex, byte[] chunkData) throws IOException {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlockBlobClient blockBlobClient = containerClient.getBlobClient(blobPath).getBlockBlobClient();

        // Generate block ID (must be base64 encoded, unique for each block)
        String blockId = java.util.Base64.getEncoder().encodeToString(
            String.format("%08d", chunkIndex).getBytes()
        );

        // Upload block
        blockBlobClient.stageBlock(blockId, new java.io.ByteArrayInputStream(chunkData), chunkData.length);

        return blockId;
    }
    
    public String uploadChunkFallback(String blobPath, int chunkIndex, byte[] chunkData, Exception ex) throws IOException {
        log.error("Circuit breaker fallback: Failed to upload chunk {} for {}, Error: {}", chunkIndex, blobPath, ex.getMessage());
        throw new IOException("Azure Storage is temporarily unavailable. Please try again later.");
    }

    /**
     * Commit all blocks to complete the blob
     */
    public void commitBlocks(String blobPath, List<String> blockIds) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlockBlobClient blockBlobClient = containerClient.getBlobClient(blobPath).getBlockBlobClient();

        // Commit blocks
        blockBlobClient.commitBlockList(blockIds);
    }

    /**
     * Get list of uncommitted blocks (for resume)
     */
    public List<String> getUncommittedBlocks(String blobPath) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlockBlobClient blockBlobClient = containerClient.getBlobClient(blobPath).getBlockBlobClient();

        if (!blockBlobClient.exists()) {
            return new ArrayList<>();
        }

        // Get block list
        var blockList = blockBlobClient.listBlocks(BlockListType.UNCOMMITTED);
        List<String> blockIds = new ArrayList<>();
        for (var block : blockList.getUncommittedBlocks()) {
            blockIds.add(block.getName());
        }
        return blockIds;
    }
}
