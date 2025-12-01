import axios from 'axios';

// ✅ Trong development: dùng relative URL để đi qua React dev server proxy
// ✅ Trong production: có thể dùng absolute URL nếu cần
// React dev server proxy sẽ forward requests đến Nginx (port 80)
const API_BASE_URL = process.env.NODE_ENV === 'production'
  ? 'http://localhost'  // Production: gọi trực tiếp đến Nginx
  : '';                  // Development: dùng relative URL → đi qua setupProxy.js → Nginx

const api = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    'Pragma': 'no-cache',
    'Expires': '0',
  },
});


// Request interceptor to log all API calls
api.interceptors.request.use(
  (config) => {
    const timestamp = new Date().toISOString();
    const method = config.method?.toUpperCase() || 'GET';
    const url = config.url || '';
    console.log(`🚀 [API REQUEST] ${timestamp} ${method} ${url}`, {
      baseURL: config.baseURL,
      params: config.params,
    });
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor to handle errors
api.interceptors.response.use(
  (response) => {
    const timestamp = new Date().toISOString();
    const method = response.config?.method?.toUpperCase() || 'GET';
    const url = response.config?.url || '';
    console.log(`✅ [API RESPONSE] ${timestamp} ${method} ${url} - Status: ${response.status}`);
    return response;
  },
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
    api.get(`/api/public/files/${token}`),

  getDownloadUrl: (token) =>
    api.get(`/api/public/files/${token}/download`),
};

// Admin Operations
export const adminAPI = {
  getUsers: () =>
    api.get('/admin/user-list', {
      params: { _t: Date.now() } // ✅ Add timestamp to prevent caching
    }),

  updatePermissions: (userId, permissions) =>
    api.post(`/admin/permission/${userId}`, permissions),

  updateQuota: (userId, payload) =>
    api.put(`/admin/quota/${userId}`, payload),

  updateAllQuota: (payload) =>
    api.put('/admin/quota', payload),

  getStats: (top = 5) =>
    api.get('/admin/stats', { params: { top } }),
};

export const BACKEND_NODES = [
  { id: 'be1', name: 'Backend 01', url: 'http://localhost:8081' },
  { id: 'be2', name: 'Backend 02', url: 'http://localhost:8082' },
  { id: 'be3', name: 'Backend 03', url: 'http://localhost:8083' },
];

// Helper function: Gọi API cho một node cụ thể
// Nó sẽ ghi đè baseURL mặc định của instance 'api'
const requestNode = (nodeUrl, method, endpoint, data = null, params = {}) => {
  return api({
    method,
    url: endpoint,
    baseURL: nodeUrl, // 👈 Quan trọng: Ghi đè baseURL chỉ cho request này
    data,
    params,
  });
};

export const monitoringAPI = {

  getActuatorHealth: (nodeUrl) =>
    requestNode(nodeUrl, 'GET', '/actuator/health'),
  // 1. HEALTH
  // Lấy health của 1 node cụ thể (dùng cho hàm loadMonitoringData bên Monitor.js)
  getNodeHealth: (nodeUrl) =>
    requestNode(nodeUrl, 'GET', '/api/admin/monitoring/health-summary'),

  getRetries: (nodeUrl) =>
    requestNode(nodeUrl, 'GET', '/api/admin/monitoring/retries'),

  // 2. CIRCUIT BREAKERS
  // Quan trọng: Phải nhận tham số nodeUrl để biết gọi vào backend nào
  getCircuitBreakers: (nodeUrl) =>
    requestNode(nodeUrl, 'GET', '/api/admin/monitoring/circuit-breakers'),

  resetCircuitBreaker: (nodeUrl, name) =>
    requestNode(nodeUrl, 'POST', `/api/admin/monitoring/circuit-breakers/${name}/reset`),

  // 3. CACHE
  getCacheStats: (nodeUrl) =>
    requestNode(nodeUrl, 'GET', '/api/admin/monitoring/cache'),

  // Clear cache trên TẤT CẢ các node (dùng Promise.all)
  clearCacheOnAllNodes: (cacheName) => {
    const promises = BACKEND_NODES.map(node =>
      requestNode(node.url, 'POST', `/api/admin/monitoring/cache/clear/${cacheName}`)
    );
    return Promise.all(promises);
  },

  clearAllCachesOnAllNodes: () => {
    const promises = BACKEND_NODES.map(node =>
      requestNode(node.url, 'POST', '/api/admin/monitoring/cache/clear-all')
    );
    return Promise.all(promises);
  },

  // 4. RATE LIMITS (Thường là Global nếu dùng Redis, gọi node nào cũng được)
  // Nhưng để chắc chắn, ta cứ gọi api mặc định (qua Load Balancer)
  getUserRateLimits: (userId) =>
    api.get(`/api/admin/monitoring/rate-limits/user/${userId}`),

  getIpRateLimits: (nodeUrl, ip) =>
    requestNode(nodeUrl, 'GET', '/api/admin/monitoring/rate-limits/ip', null, { ip }),

  clearAllRateLimits: () =>
    api.post('/api/admin/monitoring/rate-limits/clear-all'),
};

export default api;

