package com.example.valetkey.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class CompressionService {

    private static final Logger log = LoggerFactory.getLogger(CompressionService.class);

    @Autowired
    private BlobServiceClient blobServiceClient;

    @Autowired
    private AzureSasService azureSasService;

    public byte[] createZipFile(List<Resource> files, User user) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
            
            for (Resource file : files) {
                // Verify ownership
                if (!file.getUploader().getId().equals(user.getId())) {
                    log.warn("Skipping file {} - access denied", file.getFileName());
                    continue;
                }

                try {
                    BlobClient blobClient = containerClient.getBlobClient(file.getFilePath());
                    
                    if (!blobClient.exists()) {
                        log.warn("File not found in Azure: {}", file.getFilePath());
                        continue;
                    }

                    // Add file to ZIP
                    ZipEntry zipEntry = new ZipEntry(file.getFileName());
                    zos.putNextEntry(zipEntry);

                    // Read file from Azure and write to ZIP
                    try (InputStream blobInputStream = blobClient.openInputStream()) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = blobInputStream.read(buffer)) != -1) {
                            zos.write(buffer, 0, len);
                        }
                    }

                    zos.closeEntry();
                    log.debug("Added file to ZIP: {}", file.getFileName());

                } catch (Exception e) {
                    log.error("Error adding file {} to ZIP: {}", file.getFileName(), e.getMessage());
                    // Continue with other files
                }
            }
        }

        byte[] zipBytes = baos.toByteArray();
        log.info("Created ZIP file with {} files, size: {} bytes", files.size(), zipBytes.length);
        
        return zipBytes;
    }


    public String getZipFileName(List<Resource> files) {
        if (files.size() == 1) {
            String fileName = files.get(0).getFileName();
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0) {
                return fileName.substring(0, lastDot) + ".zip";
            }
            return fileName + ".zip";
        }
        return "files_" + System.currentTimeMillis() + ".zip";
    }
}

