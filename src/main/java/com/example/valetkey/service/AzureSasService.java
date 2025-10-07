package com.example.valetkey.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import com.example.valetkey.repository.ResourceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class AzureSasService {

    @Autowired
    private BlobServiceClient blobServiceClient;

    @Autowired
    private ResourceRepository resourceRepository;

    public String generateBlobReadSas(String blobName, int expiryMinutes, User user) {
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
        return blobClient.getBlobUrl() + "?" + sas;
    }

    public String generateBlobWriteSas(String blobName, int expiryMinutes, User user) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobName);

        BlobSasPermission permission = new BlobSasPermission();
        permission.setCreatePermission(true);
        permission.setWritePermission(true);
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

    public String proxyUploadFileWithSpeed(MultipartFile file, String fileName, User user) throws IOException {
        String uniqueFileName = generateUniqueFileName(fileName);
        String blobPath = "user-" + user.getId() + "/" + uniqueFileName;

        // Log bắt đầu upload
        double fileSizeMB = file.getSize() / 1024.0 / 1024.0;
        System.out.printf("🚀 Starting upload: %s (%.1f MB)%n", fileName, fileSizeMB);

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobPath);

        final long startTime = System.currentTimeMillis();
        final long[] lastLoggedBytes = {0};

        ParallelTransferOptions transferOptions = new ParallelTransferOptions()
                .setProgressReceiver(bytesTransferred -> {
                    long now = System.currentTimeMillis();
                    double uploadedMB = bytesTransferred / 1024.0 / 1024.0;
                    double totalMB = file.getSize() / 1024.0 / 1024.0;
                    double percentage = (double) bytesTransferred / file.getSize() * 100;

                    long elapsedMs = now - startTime;
                    double speedMBps = elapsedMs > 0 ? (bytesTransferred / 1024.0 / 1024.0) / (elapsedMs / 1000.0) : 0;

                     // Log mỗi 10MB hoặc cuối cùng
                     long tenMB = 10 * 1024 * 1024;
                     if ((bytesTransferred - lastLoggedBytes[0] >= tenMB) || bytesTransferred == file.getSize()) {
                         System.out.printf("📤 Upload: %.1f/%.1f MB (%.1f%%) - %.2f MB/s - %s%n",
                                 uploadedMB, totalMB, percentage, speedMBps, fileName);
                         lastLoggedBytes[0] = bytesTransferred;
                     }
                });

        blobClient.uploadWithResponse(
                file.getInputStream(),
                file.getSize(),
                transferOptions,
                null, // BlobHttpHeaders
                null, // metadata
                null, // accessTier
                null, // requestConditions
                null, // timeout
                null  // context
        );
        
        System.out.printf("✅ Upload completed: %s%n", fileName);

        // Lưu thông tin file vào database
        Resource resource = new Resource();
        resource.setFileName(uniqueFileName);
        resource.setFilePath(blobPath);
        resource.setUploader(user);
        resourceRepository.save(resource);

        return blobPath;
    }


    private String generateUniqueFileName(String originalFileName) {
        return UUID.randomUUID().toString().substring(0, 8) + "_" + originalFileName;
    }

    public void deleteFile(String filePath) {
        try {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
            BlobClient blobClient = containerClient.getBlobClient(filePath);
            
            if (blobClient.exists()) {
                blobClient.delete();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file from Azure Storage: " + e.getMessage(), e);
        }
    }

}
