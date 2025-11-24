import axios from 'axios';

const API_BASE_URL = 'http://localhost:8080';

// Create axios instance with default config
const api = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true, // Important for session cookies
  headers: {
    'Content-Type': 'application/json',
  },
});

// Authentication
export const authAPI = {
  login: (username, password) => 
    api.post('/login', { username, password }),
  
  logout: () => 
    api.post('/logout'),
  
  getCurrentUser: () => 
    api.get('/user'),
};

// File Operations
export const fileAPI = {
  upload: (file, folderId, fileName) => {
    const formData = new FormData();
    formData.append('file', file);
    if (folderId) formData.append('folderId', folderId);
    if (fileName) formData.append('fileName', fileName);
    
    return api.post('/api/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  
  uploadBatch: (files, folderId, onProgress) => {
    const formData = new FormData();
    files.forEach(file => {
      formData.append('files', file);
    });
    if (folderId) formData.append('folderId', folderId);
    
    return api.post('/api/files/upload/batch', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: onProgress,
    });
  },
  
  list: (folderId, page = 0, size = 20) => {
    const params = { page, size };
    if (folderId) params.folderId = folderId;
    return api.get('/api/files/list', { params });
  },
  
  getAllIds: (folderId) => {
    const params = {};
    if (folderId) params.folderId = folderId;
    return api.get('/api/files/all-ids', { params });
  },
  
  get: (fileId) => 
    api.get(`/api/files/${fileId}`),
  
  getDownloadUrl: (fileId) => 
    api.get(`/api/files/${fileId}/download`),
  
  delete: (fileId) => 
    api.delete(`/api/files/${fileId}`),
  
  move: (fileId, targetFolderId) => 
    api.put(`/api/files/${fileId}/move`, null, {
      params: { targetFolderId },
    }),
  
  rename: (fileId, newName) => 
    api.put(`/api/files/${fileId}/rename`, { newName }),
  
  search: (query, page = 0, size = 20) => 
    api.get('/api/files/search', { params: { query, page, size } }),
  
  generatePublicLink: (fileId) => 
    api.post(`/api/files/${fileId}/share`),
  
  revokePublicLink: (fileId) => 
    api.delete(`/api/files/${fileId}/share`),
  
  getStorageInfo: () => 
    api.get('/api/files/storage'),

  // Bulk operations
  bulkDelete: (fileIds) =>
    api.post('/api/files/bulk-delete', { fileIds }),

  bulkMove: (fileIds, targetFolderId) =>
    api.post('/api/files/bulk-move', { fileIds, targetFolderId }),

  bulkDownload: (fileIds) =>
    api.post('/api/files/bulk-download', { fileIds }, {
      responseType: 'blob',
    }),

  // Resume upload
  initiateUpload: (fileName, fileSize, folderId) =>
    api.post('/api/files/upload/initiate', { fileName, fileSize, folderId }),

  uploadChunk: (sessionId, chunkIndex, chunk) => {
    const formData = new FormData();
    formData.append('sessionId', sessionId);
    formData.append('chunkIndex', chunkIndex);
    formData.append('chunk', chunk);
    return api.post('/api/files/upload/chunk', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  completeUpload: (sessionId, blockIds) =>
    api.post('/api/files/upload/complete', { sessionId, blockIds }),

  getUploadStatus: (sessionId) =>
    api.get(`/api/files/upload/status/${sessionId}`),

  getUncommittedBlocks: (sessionId) =>
    api.get(`/api/files/upload/uncommitted/${sessionId}`),

  // Direct Azure Upload APIs
  generateUploadSasUrl: (fileName, fileSize, folderId, expiryMinutes = 60) =>
    api.post('/api/files/upload/sas-url', {
      fileName,
      fileSize,
      folderId,
      expiryMinutes
    }),

  confirmUpload: (blobPath, fileName, fileSize, contentType, folderId) =>
    api.post('/api/files/upload/confirm', {
      blobPath,
      fileName,
      fileSize,
      contentType,
      folderId
    }),

};

// Folder Operations
export const folderAPI = {
  create: (folderName, parentFolderId) => 
    api.post('/api/folders/create', { folderName, parentFolderId }),
  
  list: (parentFolderId) => {
    const params = parentFolderId ? { parentFolderId } : {};
    return api.get('/api/folders/list', { params });
  },
  
  get: (folderId) => 
    api.get(`/api/folders/${folderId}`),
  
  getTree: () => 
    api.get('/api/folders/tree'),
  
  getContents: (folderId, page = 0, size = 20) => {
    const url = folderId 
      ? `/api/folders/${folderId}/contents`
      : '/api/folders/root/contents';
    return api.get(url, { params: { page, size } });
  },
  
  delete: (folderId, deleteContents = false) => 
    api.delete(`/api/folders/${folderId}`, {
      params: { deleteContents },
    }),
  
  rename: (folderId, newName) => 
    api.put(`/api/folders/${folderId}/rename`, { newName }),
  
  move: (folderId, targetParentFolderId) => 
    api.put(`/api/folders/${folderId}/move`, null, {
      params: { targetParentFolderId },
    }),
  
  getBreadcrumb: (folderId) => {
    const url = folderId 
      ? `/api/folders/${folderId}/breadcrumb`
      : '/api/folders/root/breadcrumb';
    return api.get(url);
  },
  
  search: (query) => 
    api.get('/api/folders/search', { params: { query } }),
};

// Public File Access (no auth)
export const publicAPI = {
  getFile: (token) => 
    axios.get(`${API_BASE_URL}/api/public/files/${token}`),
  
  getDownloadUrl: (token) => 
    axios.get(`${API_BASE_URL}/api/public/files/${token}/download`),
};

export default api;

