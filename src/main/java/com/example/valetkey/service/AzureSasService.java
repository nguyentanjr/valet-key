package com.example.valetkey.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.example.valetkey.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AzureSasService {

    @Autowired
    private BlobServiceClient blobServiceClient;

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
        permission.setCreatePermission(user.isCreate());
        permission.setWritePermission(user.isWrite());
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

}
