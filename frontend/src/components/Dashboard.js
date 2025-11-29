import React, { useState, useEffect, useRef } from 'react';
import { FaFolder, FaPlus, FaSearch, FaFile, FaUser } from 'react-icons/fa';
import { fileAPI, folderAPI } from '../services/api';
import FileUpload from './FileUpload';
import FileList from './FileList';
import AdminPanel from './AdminPanel';
import { FiCloud } from 'react-icons/fi';
import './Dashboard.css';

function Dashboard({ user, onLogout }) {
  // Check if user is admin
  const isAdmin = user && user.role === 'ROLE_ADMIN';
  const [files, setFiles] = useState([]);
  const [folders, setFolders] = useState([]);
  const [allFolders, setAllFolders] = useState([]); // All folders for dropdown
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
  const [searchSuggestions, setSearchSuggestions] = useState([]);
  const [showSearchDropdown, setShowSearchDropdown] = useState(false);
  const [isDownloading, setIsDownloading] = useState(false);
  const loadDataRef = useRef(false); // Prevent duplicate calls
  const searchBoxRef = useRef(null); // Reference for search box

  useEffect(() => {
    // Skip if already loading
    if (loadDataRef.current) {
      console.log('⏭️ [loadData] Skipped - already loading');
      return;
    }

    loadDataRef.current = true;
    loadData().finally(() => {
      loadDataRef.current = false;
    });
  }, [currentFolderId, currentPage]);

  // Debounced search effect
  useEffect(() => {
    if (!searchQuery.trim()) {
      // If search is cleared, reload normal data and hide dropdown
      setShowSearchDropdown(false);
      setSearchSuggestions([]);
      loadData();
      return;
    }

    // Set up debounce timer
    const debounceTimer = setTimeout(async () => {
      try {
        // Search both files and folders
        const [filesResponse, foldersResponse] = await Promise.all([
          fileAPI.search(searchQuery),
          folderAPI.getTree() // Get all folders from tree
        ]);

        const filesResults = filesResponse.data.files || [];
        const allFolders = flattenFolders(foldersResponse.data.tree || []);

        // Filter folders by search query
        const foldersResults = allFolders.filter(folder =>
          folder.name.toLowerCase().includes(searchQuery.toLowerCase())
        );

        // Combine results with type identifier
        const combinedResults = [
          ...foldersResults.map(f => ({ ...f, type: 'folder' })),
          ...filesResults.map(f => ({ ...f, type: 'file' }))
        ];

        setSearchSuggestions(combinedResults);
        setShowSearchDropdown(true);
      } catch (err) {
        console.error('Search failed:', err);
        setSearchSuggestions([]);
      }
    }, 500);

    // Cleanup function to clear timer
    return () => clearTimeout(debounceTimer);
  }, [searchQuery]);

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event) => {
      if (searchBoxRef.current && !searchBoxRef.current.contains(event.target)) {
        setShowSearchDropdown(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const loadData = async () => {
    const callId = Math.random().toString(36).substring(7);
    console.log(`📦 [loadData] Called with ID: ${callId}`, { currentFolderId, currentPage });
    setLoading(true);
    try {
      // ✅ Gọi 5 API SONG SONG (parallel) thay vì tuần tự (sequential)
      // → Tất cả requests được gửi cùng lúc → nhanh hơn!
      console.log(`📦 [loadData:${callId}] Starting 5 API calls in PARALLEL...`);

      const [filesRes, foldersRes, allFoldersRes, breadcrumbRes, storageRes] = await Promise.all([
        fileAPI.list(currentFolderId, currentPage, pageSize),
        folderAPI.list(currentFolderId),
        folderAPI.getTree(),
        folderAPI.getBreadcrumb(currentFolderId),
        fileAPI.getStorageInfo()
      ]);

      // Process results
      setFiles(filesRes.data.files || []);
      setTotalPages(filesRes.data.totalPages || 0);
      setTotalItems(filesRes.data.totalItems || 0);

      // Use folderList from current directory for display
      // Use tree for dropdown options in move/rename
      const folderList = foldersRes.data.folders || [];
      const allFoldersTree = flattenFolders(allFoldersRes.data.tree || []);

      // Store current directory folders for display
      setFolders(folderList);
      // Store all folders for dropdown in move/rename operations
      setAllFolders(allFoldersTree);

      setBreadcrumb(breadcrumbRes.data.breadcrumb || []);
      setStorageInfo(storageRes.data);

      console.log(`✅ [loadData:${callId}] All 5 API calls completed in parallel`);
    } catch (err) {
      console.error(`❌ [loadData:${callId}] Failed to load data:`, err);
    } finally {
      setLoading(false);
    }
  };

  const flattenFolders = (tree, result = [], parentPath = '') => {
    tree.forEach(folder => {
      // Build full path for this folder
      const currentPath = parentPath ? `${parentPath}/${folder.name}` : folder.name;

      // Add folder with fullPath
      result.push({
        ...folder,
        fullPath: parentPath || null // Parent path (not including current folder name)
      });

      // Recursively flatten children with current path as parent
      if (folder.children && folder.children.length > 0) {
        flattenFolders(folder.children, result, currentPath);
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
      setShowSearchDropdown(false); // Close dropdown after full search
    } catch (err) {
      alert('Search failed');
    }
  };

  const handleSearchResultClick = (item) => {
    // Close dropdown
    setShowSearchDropdown(false);
    setSearchQuery('');

    if (item.type === 'folder') {
      // Navigate to folder
      handleNavigate(item.id);
    } else {
      // For files, navigate to folder and highlight the file
      if (item.folderId) {
        setCurrentFolderId(item.folderId);
        setCurrentPage(0);
        // Optionally select the file
        setTimeout(() => {
          setSelectedFiles([item.id]);
        }, 500);
      }
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

    setIsDownloading(true);
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
    } finally {
      setIsDownloading(false);
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

  // Show Admin Panel for admin users
  if (isAdmin) {
    return (
      <div className="app">
        <header className="header">
          <div className="header-content">
            <h1>☁️ Cloud Storage</h1>
            <div className="header-user">
              <span>{user.username} (Admin)</span>
              <button className="btn btn-secondary btn-small" onClick={onLogout}>
                Logout
              </button>
            </div>
          </div>
        </header>
        <div className="container">
          <AdminPanel />
        </div>
      </div>
    );
  }

  if (loading && !files.length) {
    return <div className="loading">Loading...</div>;
  }

  return (
    <div className="app">
      {/* Header */}
      <header className="header">
        <div className="header-content">
          <h1>☁️ Cloud Storage</h1>
          <span style={{ textAlign: 'center', marginLeft: '180px' }}><FaUser style={{ marginRight: '5px' }} />{user.username}</span>
          <div className="header-user">
            {storageInfo && (
              <div className="storage-info">
                <span className="storage-label">
                  <FiCloud className="storage-cloud-icon" size={22} />
                  <span className="storage-label-text">Bộ nhớ</span>
                </span>
                <span className="storage-values">{storageInfo.storageUsedFormatted} / {storageInfo.storageQuotaFormatted}</span>
                <span className="storage-bar">
                  <span
                    className="storage-fill"
                    style={{ width: `${storageInfo.usagePercentage}%` }}
                  ></span>
                </span>
              </div>
            )}
            <button className="btn btn-secondary btn-small" onClick={onLogout}>
              Logout
            </button>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <div className="container">
        {/* Breadcrumb with Search */}
        <div className="toolbar">
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

          <div className="toolbar-right">
            <div className="search-box" ref={searchBoxRef} style={{ position: 'relative', width: '400px' }}>
              <input
                type="text"
                placeholder="Search files and folders..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onFocus={() => searchSuggestions.length > 0 && setShowSearchDropdown(true)}
              />
              <button onClick={handleSearch} title="Search now">
                <FaSearch />
              </button>

              {/* Search Dropdown */}
              {showSearchDropdown && searchSuggestions.length > 0 && (
                <div className="search-dropdown">
                  <div className="search-dropdown-header">
                    {searchSuggestions.length} result{searchSuggestions.length !== 1 ? 's' : ''} found
                  </div>
                  <div className="search-dropdown-items">
                    {searchSuggestions.slice(0, 8).map((item) => (
                      <div
                        key={`${item.type}-${item.id}`}
                        className="search-dropdown-item"
                        onClick={() => handleSearchResultClick(item)}
                      >
                        {item.type === 'folder' ? (
                          <FaFolder className="search-item-icon" style={{ color: '#667EEA' }} />
                        ) : (
                          <FaFile className="search-item-icon" />
                        )}
                        <div className="search-item-info">
                          <div className="search-item-name">
                            {item.type === 'folder' ? (
                              <>
                                {item.fullPath && <span className="search-item-path">{item.fullPath}/</span>}
                                {item.name}
                              </>
                            ) : (
                              <>
                                {item.folderPath && <span className="search-item-path">{item.folderPath}/</span>}
                                {item.fileName}
                              </>
                            )}
                          </div>
                          {item.type === 'file' && (
                            <div className="search-item-meta">
                              {(item.fileSize / 1024).toFixed(2)} KB • {new Date(item.uploadedAt).toLocaleDateString()}
                            </div>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                  {searchSuggestions.length > 8 && (
                    <div className="search-dropdown-footer">
                      Press Enter to see all {searchSuggestions.length} results
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Upload */}
        <FileUpload
          currentFolderId={currentFolderId}
          onUploadSuccess={loadData}
        />

        {/* Files */}
        <div className="card">
          <div className="card-header">
            <div className="card-header-left">
              <span>Your Storage</span>
              <button
                className="btn btn-primary btn-small"
                onClick={() => setShowCreateFolder(true)}
                style={{ marginLeft: '1rem' }}
              >
                <FaPlus /> New Folder
              </button>
            </div>
            <div className="card-header-right">
              {selectedFiles.length > 0 && (
                <div className="bulk-actions" style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                  <span>{selectedFiles.length} selected</span>
                  <button style={{ backgroundColor: '#667EEA' }} className="btn btn-success btn-small" onClick={handleBulkDownload}>
                    Download
                  </button>
                  <button style={{ backgroundColor: '#667EEA' }} className="btn btn-move btn-small" onClick={() => setShowFolderPicker(true)}>
                    Move
                  </button>
                  <button style={{ backgroundColor: '#667EEA' }} className="btn btn-danger btn-small" onClick={handleBulkDelete} disabled={isDeleting}>
                    {isDeleting ? 'Deleting...' : `Delete`}
                  </button>
                  <button className="btn btn-secondary btn-small" onClick={deselectAll}>
                    Clear
                  </button>
                </div>
              )}

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

              <span className="file-count">({totalItems} total)</span>
            </div>
          </div>

          {/* Scrollable File List Container */}
          <div className="file-list-container">
            <FileList
              files={files}
              folders={folders}
              allFolders={allFolders}
              selectedFiles={selectedFiles}
              onToggleSelection={toggleFileSelection}
              onFileDeleted={loadData}
              onFileUpdated={loadData}
              onFolderDeleted={loadData}
              onNavigateToFolder={handleNavigate}
            />
          </div>
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
      {
        showCreateFolder && (
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
        )
      }

      {/* Folder Picker Modal for Bulk Move */}
      {
        showFolderPicker && (
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
                <button className="btn btn-move" onClick={() => {
                  handleBulkMove(selectedTargetFolder);
                  setShowFolderPicker(false);
                  setSelectedTargetFolder(null);
                }}>
                  Move Here
                </button>
              </div>
            </div>
          </div>
        )
      }

      {/* Downloading Toast Notification */}
      {isDownloading && (
        <div style={{
          position: 'fixed',
          bottom: '2rem',
          right: '2rem',
          background: 'white',
          borderRadius: '8px',
          boxShadow: '0 4px 12px rgba(0, 0, 0, 0.15)',
          padding: '1.25rem 1.5rem',
          display: 'flex',
          alignItems: 'center',
          gap: '1rem',
          zIndex: 1000,
          minWidth: '320px',
          animation: 'slideInRight 0.3s ease-out'
        }}>
          <div style={{
            width: '40px',
            height: '40px',
            border: '3px solid #f3f4f6',
            borderTop: '3px solid #667eea',
            borderRadius: '50%',
            animation: 'spin 1s linear infinite',
            flexShrink: 0
          }}></div>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 600, color: '#1f2937', marginBottom: '0.25rem' }}>
              Preparing Download
            </div>
            <div style={{ fontSize: '0.875rem', color: '#6b7280' }}>
              Compressing {selectedFiles.length} file{selectedFiles.length !== 1 ? 's' : ''}...
            </div>
          </div>
        </div>
      )}
    </div >
  );
}

export default Dashboard;
