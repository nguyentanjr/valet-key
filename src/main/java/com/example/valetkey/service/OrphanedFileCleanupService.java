package com.example.valetkey.service;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.example.valetkey.repository.ResourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class OrphanedFileCleanupService {

    @Autowired
    private BlobServiceClient blobServiceClient;

    @Autowired
    private ResourceRepository resourceRepository;

    /**
     * Scheduled job to cleanup orphaned files in Azure
     * Runs every 6 hours
     */
    @Scheduled(cron = "0 0 */6 * * *") // Every 6 hours
    @Transactional
    public void cleanupOrphanedFiles() {
        log.info("Starting orphaned file cleanup job");
        
        try {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("valet-demo");
            
            // Get all blob paths from Azure
            Set<String> azureBlobPaths = new HashSet<>();
            for (BlobItem blobItem : containerClient.listBlobs(new ListBlobsOptions(), null)) {
                // Only check blobs older than 1 hour (to avoid deleting files currently being uploaded)
                if (blobItem.getProperties().getCreationTime().isBefore(OffsetDateTime.now().minusHours(1))) {
                    azureBlobPaths.add(blobItem.getName());
                }
            }
            
            log.info("Found {} blobs in Azure (older than 1 hour)", azureBlobPaths.size());
            
            // Get all file paths from database
            List<String> dbFilePaths = resourceRepository.findAllFilePaths();
            Set<String> dbFilePathSet = new HashSet<>(dbFilePaths);
            
            log.info("Found {} file records in database", dbFilePathSet.size());
            
            // Find orphaned files (in Azure but not in DB)
            List<String> orphanedFiles = new ArrayList<>();
            for (String azurePath : azureBlobPaths) {
                if (!dbFilePathSet.contains(azurePath)) {
                    orphanedFiles.add(azurePath);
                }
            }
            
            if (orphanedFiles.isEmpty()) {
                log.info("No orphaned files found");
                return;
            }
            
            log.warn("Found {} orphaned files in Azure", orphanedFiles.size());
            
            // Delete orphaned files from Azure
            int deletedCount = 0;
            for (String orphanedPath : orphanedFiles) {
                try {
                    containerClient.getBlobClient(orphanedPath).delete();
                    deletedCount++;
                    log.debug("Deleted orphaned file: {}", orphanedPath);
                } catch (Exception e) {
                    log.error("Failed to delete orphaned file: {}", orphanedPath, e);
                }
            }
            
            log.info("Cleanup completed. Deleted {} out of {} orphaned files", deletedCount, orphanedFiles.size());
            
        } catch (Exception e) {
            log.error("Error during orphaned file cleanup", e);
        }
    }

    /**
     * Manual cleanup trigger (for admin)
     */
    public void manualCleanup() {
        log.info("Manual cleanup triggered");
        cleanupOrphanedFiles();
    }
}


