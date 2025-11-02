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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AzureSasService {

    @Autowired
    private BlobServiceClient blobServiceClient;

    @Autowired
    private ResourceRepository resourceRepository;

    public String generateBlobReadSas(String blobName, int expiryMinutes, User user) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobName);
        System.out.println(user.isRead());
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

        // Tạo SAS permission với đầy đủ quyền cho upload
        BlobSasPermission permission = new BlobSasPermission();
        permission.setCreatePermission(true);  // Cho phép tạo blob mới
        permission.setWritePermission(true);   // Cho phép ghi
        permission.setAddPermission(true);     // Cho phép thêm block
        permission.setDeletePermission(false); // Không cho phép xóa

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

    public String uploadFile(MultipartFile file, String blobPath) throws IOException {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobPath);

        try (InputStream data = file.getInputStream()) {
            blobClient.upload(data, file.getSize(), true);
        }
        return blobClient.getBlobUrl();
    }

    public void deleteBlob(String blobPath) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobPath);

        if (blobClient.exists()) {
            blobClient.delete();
        }
    }

    public boolean blobExists(String blobPath) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        return blobClient.exists();
    }

    /**
     * Upload a chunk (block) for resume upload
     */
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
