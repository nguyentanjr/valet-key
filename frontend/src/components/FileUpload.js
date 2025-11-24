import React, { useState, useEffect, forwardRef, useImperativeHandle } from 'react';
import { FaCloudUploadAlt } from 'react-icons/fa';
import { fileAPI } from '../services/api';
import './FileUpload.css';

const FileUpload = forwardRef(({ currentFolderId, onUploadSuccess }, ref) => {
  const [selectedFiles, setSelectedFiles] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [uploadResults, setUploadResults] = useState(null);
  const [fileProgress, setFileProgress] = useState({});
  const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB chunks


  const handleFileSelect = async (e) => {
    const files = Array.from(e.target.files);
    if (files.length === 0) return;

    setUploadResults(null);
    setSelectedFiles(files);
  };

  const handleUpload = async () => {
    if (!selectedFiles || selectedFiles.length === 0) return;

    const file = selectedFiles[0];
    const CHUNKED_THRESHOLD = 10 * 1024 * 1024; // 10MB

    // If multiple files, use batch upload
    if (selectedFiles.length > 1) {
      await handleBatchUpload();
    } else if (file.size > CHUNKED_THRESHOLD) {
      // Large file (> 10MB) - use direct Azure upload with chunked upload
      await handleDirectAzureUpload(file, true); // true = use chunked upload
    } else {
      // Small file (< 10MB) - use direct Azure upload (simple)
      await handleDirectAzureUpload(file, false); // false = simple upload
    }
  };


  /**
   * Upload directly to Azure using SAS URL
   * File only saved after 100% completion
   * @param {File} file - File to upload
   * @param {boolean} useChunked - Whether to use chunked upload (for files > 10MB)
   */
  const handleDirectAzureUpload = async (file, useChunked = false) => {
    setUploading(true);
    setProgress(0);

    try {
      // Step 1: Request SAS URL from backend
      const sasResponse = await fileAPI.generateUploadSasUrl(
        file.name,
        file.size,
        currentFolderId,
        60 // 60 minutes expiry
      );
      
      const { sasUrl, blobPath } = sasResponse.data;
      console.log('Got SAS URL for direct upload:', blobPath);

      if (useChunked && file.size > CHUNK_SIZE) {
        // Chunked upload for large files
        await uploadToAzureChunked(file, sasUrl, blobPath);
      } else {
        // Simple direct upload for small files
        await uploadToAzureSimple(file, sasUrl, blobPath);
      }

      // Step 2: Confirm upload with backend (only after 100% completion)
      await fileAPI.confirmUpload(
        blobPath,
        file.name,
        file.size,
        file.type,
        currentFolderId
      );

      setProgress(100);
      setTimeout(() => {
        setSelectedFiles([]);
        setUploading(false);
        setProgress(0);
        onUploadSuccess();
        document.getElementById('file-input').value = '';
      }, 500);

    } catch (err) {
      console.error('Direct Azure upload failed:', err);
      alert(err.response?.data?.message || 'Upload failed');
      setUploading(false);
      setProgress(0);
    }
  };

  /**
   * Simple direct upload to Azure (for small files)
   */
  const uploadToAzureSimple = async (file, sasUrl, blobPath) => {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();

      xhr.upload.addEventListener('progress', (e) => {
        if (e.lengthComputable) {
          const percentComplete = (e.loaded / e.total) * 100;
          setProgress(percentComplete);
        }
      });

      xhr.addEventListener('load', () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve();
        } else {
          reject(new Error(`Upload failed with status ${xhr.status}`));
        }
      });

      xhr.addEventListener('error', () => {
        reject(new Error('Network error during upload'));
      });

      xhr.open('PUT', sasUrl);
      xhr.setRequestHeader('x-ms-blob-type', 'BlockBlob');
      xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
      xhr.send(file);
    });
  };

  /**
   * Chunked upload to Azure (for large files > 10MB)
   * File only saved after 100% completion
   */
  const uploadToAzureChunked = async (file, sasUrl, blobPath) => {
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
    const blockIds = [];
    const baseUrl = sasUrl.split('?')[0]; // Get base URL without SAS token
    const sasToken = sasUrl.split('?')[1]; // Get SAS token

    for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
      const start = chunkIndex * CHUNK_SIZE;
      const end = Math.min(start + CHUNK_SIZE, file.size);
      const chunk = file.slice(start, end);

      // Generate block ID (base64 encoded, must be valid base64)
      // Match backend format: Base64.encodeToString(String.format("%08d", chunkIndex).getBytes())
      const blockId = btoa(String(chunkIndex).padStart(8, '0'));
      
      // Upload block to Azure
      await uploadBlockToAzure(baseUrl, sasToken, blockId, chunk, chunkIndex);
      
      blockIds.push(blockId);
      
      // Update progress
      const chunkProgress = ((chunkIndex + 1) / totalChunks) * 100;
      setProgress(chunkProgress);
    }

    // Commit all blocks
    await commitBlocksToAzure(baseUrl, sasToken, blockIds);
  };

  /**
   * Upload a single block to Azure
   */
  const uploadBlockToAzure = async (baseUrl, sasToken, blockId, chunk, chunkIndex) => {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const blockUrl = `${baseUrl}?comp=block&blockid=${encodeURIComponent(blockId)}&${sasToken}`;

      xhr.addEventListener('load', () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve();
        } else {
          reject(new Error(`Block upload failed with status ${xhr.status}`));
        }
      });

      xhr.addEventListener('error', () => {
        reject(new Error(`Network error uploading block ${chunkIndex}`));
      });

      xhr.open('PUT', blockUrl);
      xhr.setRequestHeader('Content-Length', chunk.size);
      xhr.send(chunk);
    });
  };

  /**
   * Commit all blocks to Azure
   */
  const commitBlocksToAzure = async (baseUrl, sasToken, blockIds) => {
    return new Promise((resolve, reject) => {
      // Create XML block list
      const blockListXml = '<?xml version="1.0" encoding="utf-8"?><BlockList>' +
        blockIds.map(id => `<Latest>${id}</Latest>`).join('') +
        '</BlockList>';

      const commitUrl = `${baseUrl}?comp=blocklist&${sasToken}`;
      const xhr = new XMLHttpRequest();

      xhr.addEventListener('load', () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve();
        } else {
          reject(new Error(`Commit failed with status ${xhr.status}`));
        }
      });

      xhr.addEventListener('error', () => {
        reject(new Error('Network error committing blocks'));
      });

      xhr.open('PUT', commitUrl);
      xhr.setRequestHeader('Content-Type', 'application/xml');
      xhr.setRequestHeader('Content-Length', blockListXml.length);
      xhr.send(blockListXml);
    });
  };

  const handleBatchUpload = async () => {
    setUploading(true);
    setProgress(0);
    setUploadResults(null);

    try {
      const response = await fileAPI.uploadBatch(
        selectedFiles,
        currentFolderId,
        (progressEvent) => {
          const percentCompleted = Math.round(
            (progressEvent.loaded * 100) / progressEvent.total
          );
          setProgress(percentCompleted);
        }
      );

      setProgress(100);
      setUploadResults(response.data);

      setTimeout(() => {
        if (response.data.failureCount === 0) {
          // All succeeded
          setSelectedFiles([]);
          setUploadResults(null);
          document.getElementById('file-input').value = '';
          onUploadSuccess();
        }
        setUploading(false);
        setProgress(0);
      }, 2000);

    } catch (err) {
      alert(err.response?.data?.message || 'Batch upload failed');
      setUploading(false);
      setProgress(0);
    }
  };


  return (
    <div className="file-upload-container">
      <div className="upload-area">
        <FaCloudUploadAlt className="upload-icon" />
        <h3>Upload Files</h3>
        <p>Select one or multiple files to upload to your cloud storage</p>
        
        <input
          id="file-input"
          type="file"
          multiple
          onChange={handleFileSelect}
          disabled={uploading}
          style={{ display: 'none' }}
        />
        
        <label htmlFor="file-input" className="btn btn-primary">
          Choose Files
        </label>

        {selectedFiles.length > 0 && (
          <div className="selected-file">
            {selectedFiles.length === 1 ? (
              <>
                <p><strong>Selected:</strong> {selectedFiles[0].name}</p>
                <p><strong>Size:</strong> {(selectedFiles[0].size / 1024 / 1024).toFixed(2)} MB</p>
              </>
            ) : (
              <>
                <p><strong>Selected:</strong> {selectedFiles.length} files</p>
                <p><strong>Total Size:</strong> {(selectedFiles.reduce((sum, f) => sum + f.size, 0) / 1024 / 1024).toFixed(2)} MB</p>
                <div className="file-list">
                  {selectedFiles.map((file, idx) => (
                    <div key={idx} className="file-item">
                      • {file.name} ({(file.size / 1024 / 1024).toFixed(2)} MB)
                    </div>
                  ))}
                </div>
              </>
            )}
            
            <button
              className="btn btn-success"
              onClick={handleUpload}
              disabled={uploading}
            >
              {uploading ? `Uploading... ${Math.round(progress)}%` : `Upload ${selectedFiles.length > 1 ? selectedFiles.length + ' Files' : ''}`}
            </button>
          </div>
        )}

        {uploading && (
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${progress}%` }}></div>
          </div>
        )}

        {uploadResults && (
          <div className="upload-results">
            <h4>Upload Results</h4>
            <p className="results-summary">
              ✅ {uploadResults.successCount} succeeded, ❌ {uploadResults.failureCount} failed
            </p>
            
            {uploadResults.failedFiles && uploadResults.failedFiles.length > 0 && (
              <div className="failed-files">
                <h5>Failed Files:</h5>
                {uploadResults.failedFiles.map((file, idx) => (
                  <div key={idx} className="failed-file-item">
                    <strong>{file.fileName}</strong>: {file.error}
                  </div>
                ))}
              </div>
            )}
            
            {uploadResults.failureCount === 0 && (
              <p className="success-message">All files uploaded successfully! 🎉</p>
            )}
          </div>
        )}
      </div>
    </div>
  );
});

FileUpload.displayName = 'FileUpload';

export default FileUpload;

