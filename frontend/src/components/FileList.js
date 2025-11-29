import React, { useState, useMemo } from 'react';
import {
  FaFile, FaTrash, FaDownload, FaShare, FaEdit, FaFolder, FaLock,
  FaSort, FaSortUp, FaSortDown
} from 'react-icons/fa';
import { fileAPI, folderAPI } from '../services/api';
import './FileList.css';

function FileList({ files, onFileDeleted, onFileUpdated, folders = [], allFolders = [], selectedFiles = [], onToggleSelection, onFolderDeleted, onNavigateToFolder }) {
  const [shareModal, setShareModal] = useState(null);
  const [renameModal, setRenameModal] = useState(null);
  const [moveModal, setMoveModal] = useState(null);
  const [newName, setNewName] = useState('');
  const [targetFolderId, setTargetFolderId] = useState('');
  const [publicLink, setPublicLink] = useState('');
  const [deletingFiles, setDeletingFiles] = useState(new Set());
  const [deletingFolders, setDeletingFolders] = useState(new Set());
  const [itemType, setItemType] = useState('file');
  const [sortConfig, setSortConfig] = useState({ key: null, direction: 'ascending' });

  const formatFileSize = (bytes) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const requestSort = (key) => {
    let direction = 'ascending';
    if (sortConfig.key === key && sortConfig.direction === 'ascending') {
      direction = 'descending';
    }
    setSortConfig({ key, direction });
  };

  const sortedFiles = useMemo(() => {
    let sortableFiles = [...files];
    if (sortConfig.key !== null) {
      sortableFiles.sort((a, b) => {
        let aValue, bValue;

        if (sortConfig.key === 'name') {
          aValue = a.fileName.toLowerCase();
          bValue = b.fileName.toLowerCase();
        } else if (sortConfig.key === 'size') {
          aValue = a.fileSize;
          bValue = b.fileSize;
        } else if (sortConfig.key === 'uploaded') {
          aValue = new Date(a.uploadedAt);
          bValue = new Date(b.uploadedAt);
        }

        if (aValue < bValue) {
          return sortConfig.direction === 'ascending' ? -1 : 1;
        }
        if (aValue > bValue) {
          return sortConfig.direction === 'ascending' ? 1 : -1;
        }
        return 0;
      });
    }
    return sortableFiles;
  }, [files, sortConfig]);

  const getSortIcon = (name) => {
    if (sortConfig.key !== name) return <FaSort style={{ color: '#ccc', marginLeft: '5px', fontSize: '0.8em' }} />;
    if (sortConfig.direction === 'ascending') {
      return <FaSortUp style={{ color: '#667EEA', marginLeft: '5px' }} />;
    }
    return <FaSortDown style={{ color: '#667EEA', marginLeft: '5px' }} />;
  };

  const allItems = [
    ...folders.map(f => ({ ...f, type: 'folder' })),
    ...sortedFiles.map(f => ({ ...f, type: 'file' }))
  ];

  const handleDownload = async (fileId) => {
    try {
      const response = await fileAPI.getDownloadUrl(fileId);
      window.open(response.data.downloadUrl, '_blank');
    } catch (err) {
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to generate download URL. Please try again.');
      }
    }
  };

  const handleDelete = async (fileId, fileName) => {
    if (!window.confirm(`Delete "${fileName}"?`)) return;

    setDeletingFiles(prev => new Set(prev).add(fileId));
    try {
      await fileAPI.delete(fileId);
      onFileDeleted(fileId);
    } catch (err) {
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to delete file. Please try again.');
      }
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
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to permanently delete file. Please try again.');
      }
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
      if (onFileUpdated) onFileUpdated();
    } catch (err) {
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to generate public link. Please try again.');
      }
    }
  };

  const handleRevokeLink = async (fileId) => {
    if (!window.confirm('Make this file private? The public link will stop working.')) return;

    try {
      await fileAPI.revokePublicLink(fileId);
      onFileUpdated();
    } catch (err) {
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to revoke public link. Please try again.');
      }
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
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to rename file. Please try again.');
      }
    }
  };

  const handleMove = async (fileId) => {
    try {
      await fileAPI.move(fileId, targetFolderId || null);
      setMoveModal(null);
      setTargetFolderId('');
      onFileUpdated();
    } catch (err) {
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to move file. Please try again.');
      }
    }
  };

  // Folder operation handlers
  const handleFolderDelete = async (folderId, folderName) => {
    if (!window.confirm(`Delete folder "${folderName}"? All contents will be deleted.`)) return;

    setDeletingFolders(prev => new Set(prev).add(folderId));
    try {
      await folderAPI.delete(folderId, true);
      if (onFolderDeleted) await onFolderDeleted();
    } catch (err) {
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to delete folder. Please try again.');
      }
    } finally {
      setDeletingFolders(prev => {
        const next = new Set(prev);
        next.delete(folderId);
        return next;
      });
    }
  };

  const handleFolderRename = async (folderId) => {
    if (!newName.trim()) return;

    try {
      await folderAPI.rename(folderId, newName);
      setRenameModal(null);
      setNewName('');
      if (onFolderDeleted) await onFolderDeleted();
    } catch (err) {
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to rename folder. Please try again.');
      }
    }
  };

  const handleFolderMove = async (folderId) => {
    try {
      await folderAPI.move(folderId, targetFolderId || null);
      setMoveModal(null);
      setTargetFolderId('');
      if (onFolderDeleted) await onFolderDeleted();
    } catch (err) {
      if (err.isCircuitBreakerError) {
        alert(err.circuitBreakerMessage || 'The system is temporarily overloaded. Please try again later.');
      } else {
        alert(err.response?.data?.message || err.message || 'Failed to move folder. Please try again.');
      }
    }
  };

  if (!allItems || allItems.length === 0) {
    return (
      <div className="empty-state">
        <FaFile />
        <p>No files or folders yet. Upload your first file or create a folder!</p>
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
            {/* THÊM: Sự kiện onClick và icon cho cột Name */}
            <th
              onClick={() => requestSort('name')}
              style={{ cursor: 'pointer', userSelect: 'none' }}
            >
              <div style={{ display: 'flex', alignItems: 'center' }}>
                Name {getSortIcon('name')}
              </div>
            </th>

            {/* THÊM: Sự kiện onClick và icon cho cột Size */}
            <th
              onClick={() => requestSort('size')}
              style={{ cursor: 'pointer', userSelect: 'none' }}
            >
              <div style={{ display: 'flex', alignItems: 'center' }}>
                Size {getSortIcon('size')}
              </div>
            </th>

            {/* THÊM: Sự kiện onClick và icon cho cột Uploaded */}
            <th
              onClick={() => requestSort('uploaded')}
              style={{ cursor: 'pointer', userSelect: 'none' }}
            >
              <div style={{ display: 'flex', alignItems: 'center' }}>
                Uploaded {getSortIcon('uploaded')}
              </div>
            </th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {allItems.map((item) => (
            <tr
              key={`${item.type}-${item.id}`}
              className={`${selectedFiles.includes(item.id) && item.type === 'file' ? 'selected' : ''} ${deletingFiles.has(item.id) || deletingFolders.has(item.id) ? 'deleting' : ''}`}
            >
              <td>
                {item.type === 'file' && (
                  <input
                    type="checkbox"
                    checked={selectedFiles.includes(item.id)}
                    onChange={() => onToggleSelection?.(item.id)}
                  />
                )}
              </td>
              <td>
                <div
                  className="file-name"
                  style={{ cursor: item.type === 'folder' ? 'pointer' : 'default' }}
                  onClick={() => item.type === 'folder' && onNavigateToFolder && onNavigateToFolder(item.id)}
                >
                  {item.type === 'folder' ? (
                    <FaFolder className="folder-icon" style={{ color: '#667EEA', fontSize: '1.5rem', marginTop: '0.5rem' }} />
                  ) : (
                    <FaFile className="file-icon" />
                  )}
                  <span>
                    {item.type === 'folder' ? item.name : (
                      <>
                        {item.folderPath && <span style={{ color: '#888', fontSize: '0.9em' }}>{item.folderPath}/</span>}
                        {item.fileName}
                      </>
                    )}
                  </span>
                  {item.type === 'file' && item.isPublic && (
                    <span className="public-badge" title="This file is public">
                      Public
                    </span>
                  )}
                </div>
              </td>
              <td>{item.type === 'file' ? formatFileSize(item.fileSize) : '—'}</td>
              <td>{item.type === 'file' ? new Date(item.uploadedAt).toLocaleDateString() : '—'}</td>
              <td>
                <div className="file-actions">
                  {item.type === 'file' ? (
                    <>
                      <button
                        className="btn-icon"
                        onClick={() => handleDownload(item.id)}
                        title="Download"
                      >
                        <FaDownload />
                      </button>
                      <button
                        className="btn-icon"
                        onClick={() => {
                          setItemType('file');
                          setRenameModal(item.id);
                          setNewName(item.fileName);
                        }}
                        title="Rename"
                      >
                        <FaEdit />
                      </button>
                      <button
                        className="btn-icon"
                        onClick={() => {
                          setItemType('file');
                          setMoveModal(item.id);
                        }}
                        title="Move"
                      >
                        <FaFolder />
                      </button>
                      <button
                        className={`btn-icon ${item.isPublic ? 'btn-success' : ''}`}
                        onClick={() => handleGenerateLink(item.id)}
                        title={item.isPublic ? 'Public - Click to view link' : 'Make Public'}
                      >
                        <FaShare />
                      </button>
                      {item.isPublic && (
                        <button
                          className="btn-icon btn-warning"
                          onClick={() => handleRevokeLink(item.id)}
                          title="Make Private"
                        >
                          <FaLock />
                        </button>
                      )}
                      <button
                        className="btn-icon btn-danger"
                        onClick={() => handleDelete(item.id, item.fileName)}
                        disabled={deletingFiles.has(item.id)}
                        title={deletingFiles.has(item.id) ? "Deleting..." : "Delete"}
                      >
                        {deletingFiles.has(item.id) ? (
                          <span className="spinner-small">⏳</span>
                        ) : (
                          <FaTrash />
                        )}
                      </button>
                    </>
                  ) : (
                    <>
                      <button
                        className="btn-icon"
                        onClick={() => {
                          setItemType('folder');
                          setRenameModal(item.id);
                          setNewName(item.name);
                        }}
                        title="Rename Folder"
                      >
                        <FaEdit />
                      </button>
                      <button
                        className="btn-icon"
                        onClick={() => {
                          setItemType('folder');
                          setMoveModal(item.id);
                        }}
                        title="Move Folder"
                      >
                        <FaFolder />
                      </button>
                      <button
                        className="btn-icon btn-danger"
                        onClick={() => handleFolderDelete(item.id, item.name)}
                        disabled={deletingFolders.has(item.id)}
                        title={deletingFolders.has(item.id) ? "Deleting..." : "Delete Folder"}
                      >
                        {deletingFolders.has(item.id) ? (
                          <span className="spinner-small">⏳</span>
                        ) : (
                          <FaTrash />
                        )}
                      </button>
                    </>
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Share Modal */}
      {
        shareModal && (
          <div className="modal-overlay" onClick={() => setShareModal(null)}>
            <div className="modal" onClick={(e) => e.stopPropagation()}>
              <div className="modal-header">
                <h3 className="modal-title" style={{ color: '#6B6BD0' }}>
                  Public Link
                </h3>
                <button className="modal-close" onClick={() => setShareModal(null)}>×</button>
              </div>
              <div className="form-group">
                <label className="form-label">Share this link:</label>
                <input
                  type="text"
                  className="form-input"
                  value={publicLink}
                  readOnly
                  onClick={(e) => e.target.select()}
                />
                <p
                  style={{
                    margin: '0.5rem 0 0 0',
                    paddingLeft: '0.5rem',
                    fontSize: '0.9rem',
                    color: '#444746',
                    textAlign: 'left'
                  }}
                >
                  Anyone with this link can view and download this file
                </p>

              </div>
              <div className="modal-actions">
                <button
                  className="btn btn-secondary"
                  onClick={() => {
                    handleRevokeLink(shareModal);
                    setShareModal(null);
                  }}
                >
                  Make Private
                </button>
                <button
                  className="btn btn-primary"
                  onClick={() => {
                    navigator.clipboard.writeText(publicLink);
                    alert('Link copied to clipboard!');
                  }}
                >
                  Copy Link
                </button>
              </div>
            </div>
          </div>
        )
      }

      {/* Rename Modal */}
      {
        renameModal && (
          <div className="modal-overlay" onClick={() => setRenameModal(null)}>
            <div className="modal" onClick={(e) => e.stopPropagation()}>
              <div className="modal-header">
                <h3 className="modal-title">Rename {itemType === 'file' ? 'File' : 'Folder'}</h3>
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
                <button
                  className="btn btn-primary"
                  onClick={() => itemType === 'file' ? handleRename(renameModal) : handleFolderRename(renameModal)}
                >
                  Rename
                </button>
              </div>
            </div>
          </div>
        )
      }

      {/* Move Modal */}
      {
        moveModal && (
          <div className="modal-overlay" onClick={() => setMoveModal(null)}>
            <div className="modal" onClick={(e) => e.stopPropagation()}>
              <div className="modal-header">
                <h3 className="modal-title">Move {itemType === 'file' ? 'File' : 'Folder'}</h3>
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
                  {allFolders && allFolders
                    .filter(folder => itemType === 'folder' ? folder.id !== moveModal : true)
                    .map((folder) => (
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
                <button
                  className="btn btn-primary"
                  onClick={() => itemType === 'file' ? handleMove(moveModal) : handleFolderMove(moveModal)}
                >
                  Move
                </button>
              </div>
            </div>
          </div>
        )
      }
    </div >
  );
}

export default FileList;