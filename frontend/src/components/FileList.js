import React, { useState } from 'react';
import { FaFile, FaTrash, FaDownload, FaShare, FaEdit, FaFolder } from 'react-icons/fa';
import { fileAPI } from '../services/api';
import './FileList.css';

function FileList({ files, onFileDeleted, onFileUpdated, folders, selectedFiles = [], onToggleSelection }) {
  const [shareModal, setShareModal] = useState(null);
  const [renameModal, setRenameModal] = useState(null);
  const [moveModal, setMoveModal] = useState(null);
  const [newName, setNewName] = useState('');
  const [targetFolderId, setTargetFolderId] = useState('');
  const [publicLink, setPublicLink] = useState('');
  const [deletingFiles, setDeletingFiles] = useState(new Set()); // Track files being deleted

  const formatFileSize = (bytes) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const handleDownload = async (fileId) => {
    try {
      const response = await fileAPI.getDownloadUrl(fileId);
      window.open(response.data.downloadUrl, '_blank');
    } catch (err) {
      alert('Failed to generate download URL');
    }
  };

  const handleDelete = async (fileId, fileName) => {
    if (!window.confirm(`Delete "${fileName}"?`)) return;
    
    setDeletingFiles(prev => new Set(prev).add(fileId));
    try {
      await fileAPI.delete(fileId);
      onFileDeleted(fileId);
    } catch (err) {
      alert('Failed to delete file');
    } finally {
      setDeletingFiles(prev => {
        const next = new Set(prev);
        next.delete(fileId);
        return next;
      });
    }
  };

  const handlePermanentDelete = async (fileId, fileName) => {
    if (!window.confirm(`Permanently delete "${fileName}"? This action cannot be undone.`)) return;
    
    setDeletingFiles(prev => new Set(prev).add(fileId));
    try {
      await fileAPI.permanentDelete(fileId);
      onFileDeleted(fileId);
    } catch (err) {
      alert('Failed to permanently delete file');
    } finally {
      setDeletingFiles(prev => {
        const next = new Set(prev);
        next.delete(fileId);
        return next;
      });
    }
  };


  const handleGenerateLink = async (fileId) => {
    try {
      const response = await fileAPI.generatePublicLink(fileId);
      const link = `${window.location.origin}/public/${response.data.publicLinkToken}`;
      setPublicLink(link);
      setShareModal(fileId);
    } catch (err) {
      alert('Failed to generate public link');
    }
  };

  const handleRevokeLink = async (fileId) => {
    if (!window.confirm('Make this file private? The public link will stop working.')) return;
    
    try {
      await fileAPI.revokePublicLink(fileId);
      onFileUpdated();
    } catch (err) {
      alert('Failed to revoke public link');
    }
  };

  const handleRename = async (fileId) => {
    if (!newName.trim()) return;
    
    try {
      await fileAPI.rename(fileId, newName);
      setRenameModal(null);
      setNewName('');
      onFileUpdated();
    } catch (err) {
      alert('Failed to rename file');
    }
  };

  const handleMove = async (fileId) => {
    try {
      await fileAPI.move(fileId, targetFolderId || null);
      setMoveModal(null);
      setTargetFolderId('');
      onFileUpdated();
    } catch (err) {
      alert('Failed to move file');
    }
  };

  if (!files || files.length === 0) {
    return (
      <div className="empty-state">
        <FaFile />
        <p>No files yet. Upload your first file!</p>
      </div>
    );
  }

  return (
    <div className="file-list">
      <table className="file-table">
        <thead>
          <tr>
            <th>
              <input
                type="checkbox"
                checked={selectedFiles.length === files.length && files.length > 0}
                onChange={(e) => {
                  if (e.target.checked) {
                    files.forEach(f => {
                      if (!selectedFiles.includes(f.id)) {
                        onToggleSelection?.(f.id);
                      }
                    });
                  } else {
                    files.forEach(f => {
                      if (selectedFiles.includes(f.id)) {
                        onToggleSelection?.(f.id);
                      }
                    });
                  }
                }}
              />
            </th>
            <th>Name</th>
            <th>Size</th>
            <th>Uploaded</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {files.map((file) => (
            <tr 
              key={file.id} 
              className={`${selectedFiles.includes(file.id) ? 'selected' : ''} ${deletingFiles.has(file.id) ? 'deleting' : ''}`}
            >
              <td>
                <input
                  type="checkbox"
                  checked={selectedFiles.includes(file.id)}
                  onChange={() => onToggleSelection?.(file.id)}
                />
              </td>
              <td>
                <div className="file-name">
                  <FaFile className="file-icon" />
                  <span>{file.fileName}</span>
                  {file.isPublic && (
                    <span className="public-badge" title="This file is public">
                      🌐 Public
                    </span>
                  )}
                </div>
              </td>
              <td>{formatFileSize(file.fileSize)}</td>
              <td>{new Date(file.uploadedAt).toLocaleDateString()}</td>
              <td>
                <div className="file-actions">
                  <button
                    className="btn-icon"
                    onClick={() => handleDownload(file.id)}
                    title="Download"
                  >
                    <FaDownload />
                  </button>
                  <button
                    className="btn-icon"
                    onClick={() => {
                      setRenameModal(file.id);
                      setNewName(file.fileName);
                    }}
                    title="Rename"
                  >
                    <FaEdit />
                  </button>
                  <button
                    className="btn-icon"
                    onClick={() => setMoveModal(file.id)}
                    title="Move"
                  >
                    <FaFolder />
                  </button>
                  <button
                    className={`btn-icon ${file.isPublic ? 'btn-success' : ''}`}
                    onClick={() => handleGenerateLink(file.id)}
                    title={file.isPublic ? 'Public - Click to view link' : 'Make Public'}
                  >
                    <FaShare />
                  </button>
                  {file.isPublic && (
                    <button
                      className="btn-icon btn-warning"
                      onClick={() => handleRevokeLink(file.id)}
                      title="Make Private"
                    >
                      🔒
                    </button>
                  )}
                  <button
                    className="btn-icon btn-danger"
                    onClick={() => handleDelete(file.id, file.fileName)}
                    disabled={deletingFiles.has(file.id)}
                    title={deletingFiles.has(file.id) ? "Deleting..." : "Delete"}
                  >
                    {deletingFiles.has(file.id) ? (
                      <span className="spinner-small">⏳</span>
                    ) : (
                      <FaTrash />
                    )}
                  </button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Share Modal */}
      {shareModal && (
        <div className="modal-overlay" onClick={() => setShareModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3 className="modal-title">🔗 Public Link</h3>
              <button className="modal-close" onClick={() => setShareModal(null)}>×</button>
            </div>
            <div className="form-group">
              <div style={{ 
                padding: '0.75rem', 
                background: '#f0fdf4', 
                borderRadius: '6px', 
                marginBottom: '1rem',
                border: '1px solid #86efac'
              }}>
                <strong>✅ This file is public</strong>
                <p style={{ margin: '0.5rem 0 0 0', fontSize: '0.9rem', color: '#166534' }}>
                  Anyone with this link can view and download this file
                </p>
              </div>
              <label className="form-label">Share this link:</label>
              <input
                type="text"
                className="form-input"
                value={publicLink}
                readOnly
                onClick={(e) => e.target.select()}
              />
            </div>
            <div className="modal-actions">
              <button
                className="btn btn-secondary"
                onClick={() => {
                  handleRevokeLink(shareModal);
                  setShareModal(null);
                }}
              >
                🔒 Make Private
              </button>
              <button
                className="btn btn-primary"
                onClick={() => {
                  navigator.clipboard.writeText(publicLink);
                  alert('Link copied to clipboard!');
                }}
              >
                📋 Copy Link
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Rename Modal */}
      {renameModal && (
        <div className="modal-overlay" onClick={() => setRenameModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3 className="modal-title">Rename File</h3>
              <button className="modal-close" onClick={() => setRenameModal(null)}>×</button>
            </div>
            <div className="form-group">
              <label className="form-label">New name:</label>
              <input
                type="text"
                className="form-input"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                autoFocus
              />
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setRenameModal(null)}>
                Cancel
              </button>
              <button className="btn btn-primary" onClick={() => handleRename(renameModal)}>
                Rename
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Move Modal */}
      {moveModal && (
        <div className="modal-overlay" onClick={() => setMoveModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3 className="modal-title">Move File</h3>
              <button className="modal-close" onClick={() => setMoveModal(null)}>×</button>
            </div>
            <div className="form-group">
              <label className="form-label">Select Destination Folder:</label>
              <select
                className="form-input"
                value={targetFolderId}
                onChange={(e) => setTargetFolderId(e.target.value)}
                autoFocus
              >
                <option value="">📁 Root (My Files)</option>
                {folders && folders.map((folder) => (
                  <option key={folder.id} value={folder.id}>
                    📁 {folder.name} {folder.fullPath ? `(${folder.fullPath})` : ''}
                  </option>
                ))}
              </select>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setMoveModal(null)}>
                Cancel
              </button>
              <button className="btn btn-primary" onClick={() => handleMove(moveModal)}>
                Move
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default FileList;

