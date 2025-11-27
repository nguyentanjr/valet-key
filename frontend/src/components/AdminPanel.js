import React, { useState, useEffect } from 'react';
import { adminAPI } from '../services/api';
import './AdminPanel.css';

// UserRow component for individual user management
function UserRow({ user, onUpdateQuota, onUpdatePermissions, updating }) {
  const [quotaInput, setQuotaInput] = useState((user.storageQuota || 0) / (1024 * 1024 * 1024));
  
  useEffect(() => {
    setQuotaInput((user.storageQuota || 0) / (1024 * 1024 * 1024));
  }, [user.storageQuota]);

  const formatBytes = (bytes) => {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const usagePercentage = user.storageQuota && user.storageQuota > 0
    ? ((user.storageUsed || 0) * 100 / user.storageQuota)
    : 0;

  return (
    <tr>
      <td>{user.id}</td>
      <td>
        <div className="user-info">
          <strong>{user.username}</strong>
        </div>
      </td>
      <td>
        <span className={`role-badge ${user.role === 'ROLE_ADMIN' ? 'role-admin' : 'role-user'}`}>
          {user.role === 'ROLE_ADMIN' ? '👑 Admin' : '👤 User'}
        </span>
      </td>
      <td>{formatBytes(user.storageUsed || 0)}</td>
      <td>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <input
            type="number"
            step="0.1"
            min="0.1"
            value={quotaInput}
            onChange={(e) => setQuotaInput(parseFloat(e.target.value) || 0)}
            className="form-input"
            style={{ width: '80px' }}
            disabled={updating[`quota_${user.id}`]}
          />
          <span>GB</span>
          <button
            className="btn btn-small btn-primary"
            onClick={() => onUpdateQuota(user.id, quotaInput)}
            disabled={updating[`quota_${user.id}`]}
          >
            {updating[`quota_${user.id}`] ? '⏳' : '💾'}
          </button>
        </div>
      </td>
      <td>
        {usagePercentage.toFixed(2)}%
        <div className="mini-bar">
          <div
            className="mini-bar-fill"
            style={{
              width: `${Math.min(usagePercentage, 100)}%`,
              backgroundColor: usagePercentage > 90 ? '#f44336' : usagePercentage > 70 ? '#ff9800' : '#4CAF50'
            }}
          ></div>
        </div>
      </td>
      <td>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
          <label>
            <input
              type="checkbox"
              checked={user.create !== false}
              onChange={(e) => onUpdatePermissions(user.id, {
                create: e.target.checked,
                read: user.read,
                write: user.write
              })}
              disabled={updating[`perm_${user.id}`]}
            />
            Create
          </label>
          <label>
            <input
              type="checkbox"
              checked={user.read === true}
              onChange={(e) => onUpdatePermissions(user.id, {
                create: user.create,
                read: e.target.checked,
                write: user.write
              })}
              disabled={updating[`perm_${user.id}`]}
            />
            Read
          </label>
          <label>
            <input
              type="checkbox"
              checked={user.write !== false}
              onChange={(e) => onUpdatePermissions(user.id, {
                create: user.create,
                read: user.read,
                write: e.target.checked
              })}
              disabled={updating[`perm_${user.id}`]}
            />
            Write
          </label>
        </div>
      </td>
      <td>
        {updating[`perm_${user.id}`] && <span>⏳</span>}
      </td>
    </tr>
  );
}

function AdminPanel() {
  const [users, setUsers] = useState([]);
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [updating, setUpdating] = useState({});
  const [globalQuota, setGlobalQuota] = useState('');
  const [topN] = useState(5);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const [usersRes, statsRes] = await Promise.all([
        adminAPI.getUsers(),
        adminAPI.getStats(topN)
      ]);
      
      // ✅ Backend trả về format: { users: [...] }
      let usersData = usersRes.data;
      
      // Extract users array from response
      if (usersData && Array.isArray(usersData.users)) {
        usersData = usersData.users;
      } else if (Array.isArray(usersData)) {
        // Fallback: nếu response là array trực tiếp
        usersData = usersData;
      } else {
        console.warn('⚠️ [AdminPanel] Unexpected users response format:', usersData);
        usersData = [];
      }
      
      setUsers(usersData);
      setStats(statsRes.data || {});
    } catch (err) {
      console.error('Failed to load admin data:', err);
      console.error('Error response:', err.response?.data);
      setUsers([]); // Set empty array on error
      setStats({});
      alert(err.response?.data?.message || 'Failed to load admin data');
    } finally {
      setLoading(false);
    }
  };

  const formatBytes = (bytes) => {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const handleUpdatePermissions = async (userId, permissions) => {
    setUpdating(prev => ({ ...prev, [`perm_${userId}`]: true }));
    try {
      await adminAPI.updatePermissions(userId, permissions);
      await loadData();
      alert('Permissions updated successfully');
    } catch (err) {
      alert(err.response?.data?.message || 'Failed to update permissions');
    } finally {
      setUpdating(prev => ({ ...prev, [`perm_${userId}`]: false }));
    }
  };

  const handleUpdateQuota = async (userId, quotaGb) => {
    if (!quotaGb || quotaGb <= 0) {
      alert('Quota must be greater than 0');
      return;
    }

    setUpdating(prev => ({ ...prev, [`quota_${userId}`]: true }));
    try {
      await adminAPI.updateQuota(userId, { storageQuotaGb: quotaGb });
      await loadData();
      alert('Quota updated successfully');
    } catch (err) {
      alert(err.response?.data?.message || err.response?.data?.error || 'Failed to update quota');
    } finally {
      setUpdating(prev => ({ ...prev, [`quota_${userId}`]: false }));
    }
  };

  const handleUpdateAllQuotas = async () => {
    const quotaGb = parseFloat(globalQuota);
    if (!quotaGb || quotaGb <= 0) {
      alert('Quota must be greater than 0');
      return;
    }

    if (!window.confirm(`Update storage quota to ${quotaGb} GB for ALL users?`)) {
      return;
    }

    setUpdating(prev => ({ ...prev, globalQuota: true }));
    try {
      await adminAPI.updateAllQuota({ storageQuotaGb: quotaGb });
      setGlobalQuota('');
      await loadData();
      alert('All quotas updated successfully');
    } catch (err) {
      alert(err.response?.data?.message || err.response?.data?.error || 'Failed to update quotas');
    } finally {
      setUpdating(prev => ({ ...prev, globalQuota: false }));
    }
  };

  if (loading) {
    return <div className="loading">Loading admin panel...</div>;
  }

  return (
    <div className="admin-panel">
      <div className="admin-panel-header">
        <h2>🔧 Admin Panel</h2>
        <button className="btn btn-primary" onClick={loadData}>
          🔄 Refresh
        </button>
      </div>

      {/* System Statistics */}
      {stats && (
        <div className="admin-stats">
          <div className="stat-card">
            <span className="stat-label">Total Users</span>
            <span className="stat-value">{stats.totalUsers || 0}</span>
          </div>
          <div className="stat-card">
            <span className="stat-label">Total Storage Used</span>
            <span className="stat-value">{formatBytes(stats.totalStorageUsed)}</span>
          </div>
          <div className="stat-card">
            <span className="stat-label">Total Storage Quota</span>
            <span className="stat-value">{formatBytes(stats.totalStorageQuota)}</span>
          </div>
          <div className="stat-card">
            <span className="stat-label">Usage Percentage</span>
            <span className="stat-value">
              {stats.usagePercentage ? stats.usagePercentage.toFixed(2) : 0}%
            </span>
          </div>
        </div>
      )}

      {/* Global Quota Update */}
      <div className="global-quota">
        <h3>🌐 Update All User Quotas</h3>
        <div className="global-quota-controls">
          <input
            type="number"
            step="0.1"
            min="0.1"
            placeholder="Quota in GB"
            value={globalQuota}
            onChange={(e) => setGlobalQuota(e.target.value)}
            className="form-input"
            style={{ width: '200px' }}
          />
          <button
            className="btn btn-primary"
            onClick={handleUpdateAllQuotas}
            disabled={updating.globalQuota}
          >
            {updating.globalQuota ? '⏳ Updating...' : 'Update All'}
          </button>
        </div>
      </div>

      {/* Top Consumers */}
      {stats && stats.topConsumers && stats.topConsumers.length > 0 && (
        <div className="top-consumers">
          <h3>📊 Top {topN} Storage Consumers</h3>
          <ul>
            {stats.topConsumers.map((consumer) => (
              <li key={consumer.id}>
                <strong>{consumer.username}</strong>: {formatBytes(consumer.storageUsed)} / {formatBytes(consumer.storageQuota)} 
                ({consumer.usagePercentage ? consumer.usagePercentage.toFixed(2) : 0}%)
                <div className="mini-bar">
                  <div
                    className="mini-bar-fill"
                    style={{ width: `${Math.min(consumer.usagePercentage || 0, 100)}%` }}
                  ></div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* User Management Table */}
      <div className="admin-table-wrapper">
        <h3>👥 User Management</h3>
        <table className="admin-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Username</th>
              <th>Role</th>
              <th>Storage Used</th>
              <th>Storage Quota</th>
              <th>Usage %</th>
              <th>Permissions</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <UserRow
                key={user.id}
                user={user}
                onUpdateQuota={handleUpdateQuota}
                onUpdatePermissions={handleUpdatePermissions}
                updating={updating}
              />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default AdminPanel;

