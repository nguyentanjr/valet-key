import React, { useState } from 'react';
import { FaCloudUploadAlt } from 'react-icons/fa';
import { fileAPI } from '../services/api';
import './FileUpload.css';

function FileUpload({ currentFolderId, onUploadSuccess }) {
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [uploadSessionId, setUploadSessionId] = useState(null);
  const [useResumeUpload, setUseResumeUpload] = useState(false);
  const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB chunks

  const handleFileSelect = (e) => {
    const file = e.target.files[0];
    if (file) {
      setSelectedFile(file);
      // Use resume upload for files larger than 10MB
      setUseResumeUpload(file.size > 10 * 1024 * 1024);
    }
  };

  const handleUpload = async () => {
    if (!selectedFile) return;

    // Use resume upload for large files
    if (useResumeUpload && selectedFile.size > 10 * 1024 * 1024) {
      await handleResumeUpload();
    } else {
      await handleSimpleUpload();
    }
  };

  const handleSimpleUpload = async () => {
    setUploading(true);
    setProgress(0);

    try {
      await fileAPI.upload(selectedFile, currentFolderId, null);
      
      setProgress(100);
      setTimeout(() => {
        setSelectedFile(null);
        setUploading(false);
        setProgress(0);
        onUploadSuccess();
        document.getElementById('file-input').value = '';
      }, 500);
    } catch (err) {
      alert(err.response?.data?.message || 'Upload failed');
      setUploading(false);
      setProgress(0);
    }
  };

  const handleResumeUpload = async () => {
    setUploading(true);
    setProgress(0);

    try {
      // Step 1: Initiate upload session
      const initResponse = await fileAPI.initiateUpload(
        selectedFile.name,
        selectedFile.size,
        currentFolderId
      );
      const sessionId = initResponse.data.sessionId;
      setUploadSessionId(sessionId);

      // Step 2: Upload file in chunks
      const totalChunks = Math.ceil(selectedFile.size / CHUNK_SIZE);
      const blockIds = [];

      for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
        const start = chunkIndex * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, selectedFile.size);
        const chunk = selectedFile.slice(start, end);

        // Upload chunk
        await fileAPI.uploadChunk(sessionId, chunkIndex, chunk);
        
        // Update progress
        const chunkProgress = ((chunkIndex + 1) / totalChunks) * 100;
        setProgress(chunkProgress);
        
        // Generate block ID (simplified - should match backend logic)
        const blockId = btoa(String(chunkIndex).padStart(8, '0'));
        blockIds.push(blockId);
      }

      // Step 3: Complete upload
      await fileAPI.completeUpload(sessionId, blockIds);
      
      setProgress(100);
      setTimeout(() => {
        setSelectedFile(null);
        setUploading(false);
        setProgress(0);
        setUploadSessionId(null);
        onUploadSuccess();
        document.getElementById('file-input').value = '';
      }, 500);
    } catch (err) {
      alert(err.response?.data?.message || 'Upload failed. You can retry to resume.');
      setUploading(false);
      // Keep session ID for resume capability
    }
  };

  return (
    <div className="file-upload-container">
      <div className="upload-area">
        <FaCloudUploadAlt className="upload-icon" />
        <h3>Upload Files</h3>
        <p>Select a file to upload to your cloud storage</p>
        
        <input
          id="file-input"
          type="file"
          onChange={handleFileSelect}
          disabled={uploading}
          style={{ display: 'none' }}
        />
        
        <label htmlFor="file-input" className="btn btn-primary">
          Choose File
        </label>

        {selectedFile && (
          <div className="selected-file">
            <p><strong>Selected:</strong> {selectedFile.name}</p>
            <p><strong>Size:</strong> {(selectedFile.size / 1024 / 1024).toFixed(2)} MB</p>
            {useResumeUpload && (
              <p className="upload-info">
                📦 Large file - Using resume upload (chunked)
              </p>
            )}
            
            <button
              className="btn btn-success"
              onClick={handleUpload}
              disabled={uploading}
            >
              {uploading ? `Uploading... ${Math.round(progress)}%` : 'Upload'}
            </button>
            {uploadSessionId && uploading && (
              <p className="resume-info">
                Resume enabled - Upload can be resumed if interrupted
              </p>
            )}
          </div>
        )}

        {uploading && (
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${progress}%` }}></div>
          </div>
        )}
      </div>
    </div>
  );
}

export default FileUpload;

