import React, { useState, useEffect } from 'react';
import { FaFolder, FaPlus, FaSearch } from 'react-icons/fa';
import { fileAPI, folderAPI } from '../services/api';
import FileUpload from './FileUpload';
import FileList from './FileList';
import './Dashboard.css';

function Dashboard({ user, onLogout }) {
  const [files, setFiles] = useState([]);
  const [folders, setFolders] = useState([]);
  const [currentFolderId, setCurrentFolderId] = useState(null);
  const [breadcrumb, setBreadcrumb] = useState([]);
  const [storageInfo, setStorageInfo] = useState(null);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [showCreateFolder, setShowCreateFolder] = useState(false);
  const [newFolderName, setNewFolderName] = useState('');
  const [selectedFiles, setSelectedFiles] = useState([]);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalItems, setTotalItems] = useState(0);
  const [pageSize] = useState(20);
  const [showFolderPicker, setShowFolderPicker] = useState(false);
  const [selectedTargetFolder, setSelectedTargetFolder] = useState(null);
  const [isDeleting, setIsDeleting] = useState(false);

  useEffect(() => {
    loadData();
  }, [currentFolderId, currentPage]);

  const loadData = async () => {
    setLoading(true);
    try {
      // Load files with pagination
      const filesRes = await fileAPI.list(currentFolderId, currentPage, pageSize);
      setFiles(filesRes.data.files || []);
      setTotalPages(filesRes.data.totalPages || 0);
      setTotalItems(filesRes.data.totalItems || 0);

      // Load folders
      const foldersRes = await folderAPI.list(currentFolderId);
      const folderList = foldersRes.data.folders || [];

      // Load all folders for move operation
      const allFoldersRes = await folderAPI.getTree();
      const flatFolders = flattenFolders(allFoldersRes.data.tree || []);
      setFolders(flatFolders.length > 0 ? flatFolders : folderList);

      // Load breadcrumb
      const breadcrumbRes = await folderAPI.getBreadcrumb(currentFolderId);
      setBreadcrumb(breadcrumbRes.data.breadcrumb || []);

      // Load storage info
      const storageRes = await fileAPI.getStorageInfo();
      setStorageInfo(storageRes.data);
    } catch (err) {
      console.error('Failed to load data:', err);
    } finally {
      setLoading(false);
    }
  };

  const flattenFolders = (tree, result = []) => {
    tree.forEach(folder => {
      result.push(folder);
      if (folder.children && folder.children.length > 0) {
        flattenFolders(folder.children, result);
      }
    });
    return result;
  };

  const handleCreateFolder = async () => {
    if (!newFolderName.trim()) return;

    try {
      await folderAPI.create(newFolderName, currentFolderId);
      setShowCreateFolder(false);
      setNewFolderName('');
      loadData();
    } catch (err) {
      alert(err.response?.data?.message || 'Failed to create folder');
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      loadData();
      return;
    }

    try {
      const response = await fileAPI.search(searchQuery);
      setFiles(response.data.files || []);
    } catch (err) {
      alert('Search failed');
    }
  };

  const handleNavigate = (folderId) => {
    setCurrentFolderId(folderId);
    setSearchQuery('');
    setCurrentPage(0); // Reset to first page when navigating
    setSelectedFiles([]); // Clear selection
  };

  const handleGoBack = () => {
    if (breadcrumb.length > 1) {
      // Go to parent folder
      const parentIndex = breadcrumb.length - 2;
      const parentFolder = breadcrumb[parentIndex];
      setCurrentFolderId(parentFolder.id);
      setCurrentPage(0);
    } else {
      // Go to root
      setCurrentFolderId(null);
      setCurrentPage(0);
    }
    setSelectedFiles([]);
  };

  const handleBulkDelete = async () => {
    if (selectedFiles.length === 0) return;
    if (!window.confirm(`Delete ${selectedFiles.length} file(s)?`)) return;

    setIsDeleting(true);
    try {
      await fileAPI.bulkDelete(selectedFiles);
      setSelectedFiles([]);
      loadData();
    } catch (err) {
      alert('Failed to delete files');
    } finally {
      setIsDeleting(false);
    }
  };

  const handleBulkDownload = async () => {
    if (selectedFiles.length === 0) return;

    try {
      const response = await fileAPI.bulkDownload(selectedFiles);
      // Create download link
      const blob = new Blob([response.data], { type: 'application/zip' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `files_${Date.now()}.zip`;
      a.click();
      window.URL.revokeObjectURL(url);
      setSelectedFiles([]);
    } catch (err) {
      alert('Failed to download files');
    }
  };

  const handleBulkMove = async (targetFolderId) => {
    if (selectedFiles.length === 0) return;

    try {
      await fileAPI.bulkMove(selectedFiles, targetFolderId);
      setSelectedFiles([]);
      loadData();
    } catch (err) {
      alert('Failed to move files');
    }
  };


  const toggleFileSelection = (fileId) => {
    setSelectedFiles(prev => 
      prev.includes(fileId) 
        ? prev.filter(id => id !== fileId)
        : [...prev, fileId]
    );
  };

  const selectAll = () => {
    setSelectedFiles(files.map(f => f.id));
  };

  const selectAllRecords = async () => {
    try {
      const response = await fileAPI.getAllIds(currentFolderId);
      const allFileIds = response.data.fileIds || [];
      setSelectedFiles(allFileIds);
    } catch (err) {
      console.error('Failed to get all file IDs:', err);
      alert('Failed to select all records');
    }
  };

  const deselectAll = () => {
    setSelectedFiles([]);
  };

  if (loading && !files.length) {
    return <div className="loading">Loading...</div>;
  }

  return (
    <div className="app">
      {/* Header */}
      <header className="header">
        <div className="header-content">
          <h1>☁️ Cloud Storage</h1>
          <div className="header-user">
            {storageInfo && (
              <div className="storage-info">
                {storageInfo.storageUsedFormatted} / {storageInfo.storageQuotaFormatted}
                <span className="storage-bar">
                  <span 
                    className="storage-fill" 
                    style={{ width: `${storageInfo.usagePercentage}%` }}
                  ></span>
                </span>
              </div>
            )}
            <span>{user.username}</span>
            <button className="btn btn-secondary btn-small" onClick={onLogout}>
              Logout
            </button>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <div className="container">
        {/* Breadcrumb with Back Button */}
        <div className="breadcrumb">
          <button className="btn btn-secondary btn-small" onClick={handleGoBack}>
            ← Back
          </button>
          {breadcrumb.map((item, index) => (
            <React.Fragment key={index}>
              <button
                className="breadcrumb-item"
                onClick={() => handleNavigate(item.id)}
              >
                {item.name}
              </button>
              {index < breadcrumb.length - 1 && <span> / </span>}
            </React.Fragment>
          ))}
        </div>

        {/* Toolbar */}
        <div className="toolbar">
          <div className="toolbar-left">
            <button
              className="btn btn-primary"
              onClick={() => setShowCreateFolder(true)}
            >
              <FaPlus /> New Folder
            </button>
          </div>
          
          {selectedFiles.length > 0 && (
            <div className="bulk-actions">
              <span>{selectedFiles.length} selected</span>
              <button className="btn btn-success btn-small" onClick={handleBulkDownload}>
                ⬇️ Download ({selectedFiles.length})
              </button>
              <button className="btn btn-secondary btn-small" onClick={() => setShowFolderPicker(true)}>
                📁 Move
              </button>
              <button 
                className="btn btn-danger btn-small" 
                onClick={handleBulkDelete}
                disabled={isDeleting}
              >
                {isDeleting ? '⏳ Deleting...' : `🗑️ Delete (${selectedFiles.length})`}
              </button>
              <button className="btn btn-secondary btn-small" onClick={deselectAll}>
                ✖️ Clear
              </button>
            </div>
          )}
          
          <div className="toolbar-right">
            <div className="search-box">
              <input
                type="text"
                placeholder="Search files..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onKeyPress={(e) => e.key === 'Enter' && handleSearch()}
              />
              <button onClick={handleSearch}>
                <FaSearch />
              </button>
            </div>
          </div>
        </div>

        {/* Folders */}
        {folders.length > 0 && !searchQuery && (
          <div className="folders-grid">
            {folders.map((folder) => (
              <div
                key={folder.id}
                className="folder-card"
                onClick={() => handleNavigate(folder.id)}
              >
                <FaFolder className="folder-icon" />
                <span>{folder.name}</span>
              </div>
            ))}
          </div>
        )}

        {/* Upload */}
        <FileUpload 
          currentFolderId={currentFolderId} 
          onUploadSuccess={loadData}
        />

        {/* Files */}
        <div className="card">
          <div className="card-header">
            Files
            {files.length > 0 && (
              <>
                <button className="btn btn-secondary btn-small" onClick={selectAll}>
                  Select All (Page)
                </button>
                {totalItems > files.length && (
                  <button className="btn btn-primary btn-small" onClick={selectAllRecords}>
                    Select All Records ({totalItems})
                  </button>
                )}
              </>
            )}
            <span className="file-count">
              ({totalItems} total)
            </span>
          </div>
          <FileList
            files={files}
            folders={folders}
            selectedFiles={selectedFiles}
            onToggleSelection={toggleFileSelection}
            onFileDeleted={loadData}
            onFileUpdated={loadData}
          />
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="pagination">
            <button
              className="btn btn-secondary"
              onClick={() => setCurrentPage(Math.max(0, currentPage - 1))}
              disabled={currentPage === 0}
            >
              ← Previous
            </button>
            <span>
              Page {currentPage + 1} of {totalPages} ({totalItems} items)
            </span>
            <button
              className="btn btn-secondary"
              onClick={() => setCurrentPage(Math.min(totalPages - 1, currentPage + 1))}
              disabled={currentPage >= totalPages - 1}
            >
              Next →
            </button>
          </div>
        )}
      </div>

      {/* Create Folder Modal */}
      {showCreateFolder && (
        <div className="modal-overlay" onClick={() => setShowCreateFolder(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3 className="modal-title">Create New Folder</h3>
              <button className="modal-close" onClick={() => setShowCreateFolder(false)}>×</button>
            </div>
            <div className="form-group">
              <label className="form-label">Folder Name:</label>
              <input
                type="text"
                className="form-input"
                value={newFolderName}
                onChange={(e) => setNewFolderName(e.target.value)}
                placeholder="Enter folder name"
                autoFocus
                onKeyPress={(e) => e.key === 'Enter' && handleCreateFolder()}
              />
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setShowCreateFolder(false)}>
                Cancel
              </button>
              <button className="btn btn-primary" onClick={handleCreateFolder}>
                Create
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Folder Picker Modal for Bulk Move */}
      {showFolderPicker && (
        <div className="modal-overlay" onClick={() => setShowFolderPicker(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3 className="modal-title">Select Destination Folder</h3>
              <button className="modal-close" onClick={() => setShowFolderPicker(false)}>×</button>
            </div>
            <div className="form-group">
              <label className="form-label">Choose folder:</label>
              <select
                className="form-input"
                value={selectedTargetFolder || ''}
                onChange={(e) => setSelectedTargetFolder(e.target.value || null)}
              >
                <option value="">📁 Root (My Files)</option>
                {folders.map((folder) => (
                  <option key={folder.id} value={folder.id}>
                    📁 {folder.name} {folder.fullPath ? `(${folder.fullPath})` : ''}
                  </option>
                ))}
              </select>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => {
                setShowFolderPicker(false);
                setSelectedTargetFolder(null);
              }}>
                Cancel
              </button>
              <button className="btn btn-primary" onClick={() => {
                handleBulkMove(selectedTargetFolder);
                setShowFolderPicker(false);
                setSelectedTargetFolder(null);
              }}>
                Move Here
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default Dashboard;

