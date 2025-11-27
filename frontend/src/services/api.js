import axios from 'axios';

const API_BASE_URL = 'http://localhost'; // luôn gọi vào Nginx

const api = axios.create({
    baseURL: API_BASE_URL,
    withCredentials: true,
    headers: {
        'Content-Type': 'application/json',
    },
});


// Response interceptor to handle errors
api.interceptors.response.use(
  (response) => response,
  (error) => {
    // Check if this is a Circuit Breaker error
    if (error.response) {
      const { status, data } = error.response;
      
      // 503 Service Unavailable or circuitBreakerOpen flag
      if (status === 503 || (data && data.circuitBreakerOpen)) {
        // Add circuit breaker flag to error for easy detection
        error.isCircuitBreakerError = true;
        error.circuitBreakerMessage = data?.message || 'The system is temporarily overloaded. Please try again later.';
        error.retryAfter = data?.retryAfter || 60;
      }
      
      // 401 Unauthorized - session might have expired or invalid
      // This can happen when load balancer routes to a different backend instance
      if (status === 401) {
        error.isUnauthorized = true;
        // Don't auto-redirect here, let components handle it
      }
    }
    
    return Promise.reject(error);
  }
);

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

  // Generate SAS URLs for batch upload
  generateBatchUploadSasUrls: (files, folderId, expiryMinutes = 60) => {
    const fileInfos = files.map(file => ({
      fileName: file.name,
      fileSize: file.size,
      contentType: file.type
    }));
    
    return api.post('/api/files/upload/batch/sas-urls', {
      files: fileInfos,
      folderId,
      expiryMinutes
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

// Admin Operations
export const adminAPI = {
  getUsers: () =>
    api.get('/admin/user-list'),

  updatePermissions: (userId, permissions) =>
    api.post(`/admin/permission/${userId}`, permissions),

  updateQuota: (userId, payload) =>
    api.put(`/admin/quota/${userId}`, payload),

  updateAllQuota: (payload) =>
    api.put('/admin/quota', payload),

  getStats: (top = 5) =>
    api.get('/admin/stats', { params: { top } }),
};

export default api;

