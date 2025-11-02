import React, { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { FaDownload, FaFile } from 'react-icons/fa';
import { publicAPI } from '../services/api';
import './PublicFile.css';

function PublicFile() {
  const { token } = useParams();
  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    loadFile();
  }, [token]);

  const loadFile = async () => {
    try {
      const response = await publicAPI.getFile(token);
      setFile(response.data);
    } catch (err) {
      setError('File not found or link has been revoked');
    } finally {
      setLoading(false);
    }
  };

  const handleDownload = async () => {
    try {
      const response = await publicAPI.getDownloadUrl(token);
      window.open(response.data.downloadUrl, '_blank');
    } catch (err) {
      alert('Failed to download file');
    }
  };

  const formatFileSize = (bytes) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  if (loading) {
    return (
      <div className="public-file-container">
        <div className="loading">Loading...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="public-file-container">
        <div className="public-file-card">
          <div className="error">{error}</div>
        </div>
      </div>
    );
  }

  return (
    <div className="public-file-container">
      <div className="public-file-card">
        <div className="public-file-header">
          <h1>☁️ Shared File</h1>
          <p>Someone shared a file with you</p>
        </div>

        <div className="file-info">
          <FaFile className="file-icon-large" />
          <h2>{file.fileName}</h2>
          <p className="file-meta">
            Size: {formatFileSize(file.fileSize)} • 
            Uploaded by: {file.uploader}
          </p>
          <p className="file-date">
            {new Date(file.uploadedAt).toLocaleDateString()}
          </p>
        </div>

        <button className="btn btn-primary btn-large" onClick={handleDownload}>
          <FaDownload /> Download File
        </button>

        <div className="public-footer">
          <p>This file will be available as long as the owner keeps it shared.</p>
        </div>
      </div>
    </div>
  );
}

export default PublicFile;

