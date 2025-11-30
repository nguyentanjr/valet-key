import React, { useState, useEffect } from 'react';
import { adminAPI } from '../services/api';
import Monitor from './Monitor';
import './AdminPanel.css';

// Import các icon từ FontAwesome
import {
  FaUsers,
  FaHdd,
  FaCloud,
  FaChartPie,
  FaSync,
  FaSave,
  FaSpinner,
  FaTachometerAlt,
} from 'react-icons/fa';

// --- Helper Components --- //

const Badge = ({ type, children }) => (
  <span className={`badge badge-${type.toLowerCase()}`}>{children}</span>
);

const ProgressBar = ({ percentage }) => {
  let colorClass = 'bg-success';
  if (percentage > 90) colorClass = 'bg-danger';
  else if (percentage > 70) colorClass = 'bg-warning';

  return (
    <div className="progress-wrapper">
      <div className="progress-text">
        <span>Usage</span>
        <span>{percentage.toFixed(1)}%</span>
      </div>
      <div className="progress-track">
        <div
          className={`progress-fill ${colorClass}`}
          style={{ width: `${Math.min(percentage, 100)}%` }}
        ></div>
      </div>
    </div>
  );
};

// --- Main Components --- //

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
      <td className="text-muted">#{user.id}</td>
      <td>
        <div className="user-profile">
          <div className="user-name">{user.username}</div>
        </div>
      </td>
      <td>
        <Badge type={user.role === 'ROLE_ADMIN' ? 'admin' : 'user'}>
          {user.role === 'ROLE_ADMIN' ? 'Admin' : 'User'}
        </Badge>
      </td>
      <td className="font-mono">{formatBytes(user.storageUsed || 0)}</td>
      <td>
        <div className="quota-control">
          <input
            type="number"
            step="0.1"
            min="0.1"
            value={quotaInput}
            onChange={(e) => setQuotaInput(parseFloat(e.target.value) || 0)}
            disabled={updating[`quota_${user.id}`]}
          />
          <span className="unit">GB</span>
          <button
            className="btn-icon"
            onClick={() => onUpdateQuota(user.id, quotaInput)}
            disabled={updating[`quota_${user.id}`]}
            title="Save Quota"
          >
            {updating[`quota_${user.id}`] ? (
              <FaSpinner className="icon-spin" />
            ) : (
              <FaSave />
            )}
          </button>
        </div>
      </td>
      <td>
        <ProgressBar percentage={usagePercentage} />
      </td>
      <td>
        <div className="permissions-grid">
          {['create', 'read', 'write'].map(perm => (
            <label key={perm} className="perm-checkbox">
              <input
                type="checkbox"
                checked={user[perm] !== false && (perm !== 'read' || user.read === true)}
                onChange={(e) => onUpdatePermissions(user.id, {
                  ...user,
                  [perm]: e.target.checked
                })}
                disabled={updating[`perm_${user.id}`]}
              />
              <span className="perm-label">{perm}</span>
            </label>
          ))}
        </div>
      </td>
    </tr>
  );
}

function AdminPanel() {
  const [activeTab, setActiveTab] = useState('users'); // 'users' or 'monitor'
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

      let usersData = usersRes.data;
      if (usersData && Array.isArray(usersData.users)) usersData = usersData.users;
      else if (!Array.isArray(usersData)) usersData = [];

      setUsers(usersData);
      setStats(statsRes.data || {});
    } catch (err) {
      console.error(err);
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

    if (!window.confirm(`Are you sure you want to update storage quota to ${quotaGb} GB for ALL users?`)) {
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
    return (
      <div className="loading-screen">
        {/* Thay thế CSS spinner bằng Icon FaSpinner */}
        <FaSpinner className="spinner icon-spin" size={40} />
        <p>Initializing Admin Dashboard...</p>
      </div>
    );
  }

  // Nếu đang xem Monitor, render full page mà không cần admin wrapper
  if (activeTab === 'monitor') {
    return <Monitor onBack={() => setActiveTab('users')} />;
  }

  return (
    <div className="admin-scope-wrapper">
      <div className="admin-container">
        {/* Header Section */}
        <header className="admin-header">
          <div>
            <h1>System Administration</h1>
            <p className="subtitle">Manage users, storage quotas, permissions and system monitoring</p>
          </div>
          <div style={{ display: 'flex', gap: '10px' }}>
            <button className="btn btn-primary btn-refresh" onClick={loadData}>
              <FaSync className={loading ? 'icon-spin' : ''} />
              Refresh Data
            </button>
            <button className="btn btn-primary" onClick={() => setActiveTab('monitor')}>
              <FaTachometerAlt /> System Monitor
            </button>
          </div>
        </header>

        {/* Tab Content */}
        {activeTab === 'users' && (
          <>
            {/* Stats Grid */}
            {stats && (
              <div className="stats-grid">
                <div className="stat-card">
                  <div className="stat-icon bg-blue-light">
                    <FaUsers />
                  </div>
                  <div className="stat-content">
                    <h3>Total Users</h3>
                    <p className="stat-number">{stats.totalUsers || 0}</p>
                  </div>
                </div>
                <div className="stat-card">
                  <div className="stat-icon bg-purple-light">
                    <FaHdd />
                  </div>
                  <div className="stat-content">
                    <h3>Storage Used</h3>
                    <p className="stat-number">{formatBytes(stats.totalStorageUsed)}</p>
                  </div>
                </div>
                <div className="stat-card">
                  <div className="stat-icon bg-green-light">
                    <FaCloud />
                  </div>
                  <div className="stat-content">
                    <h3>Total Quota</h3>
                    <p className="stat-number">{formatBytes(stats.totalStorageQuota)}</p>
                  </div>
                </div>
                <div className="stat-card">
                  <div className="stat-icon bg-orange-light">
                    <FaChartPie />
                  </div>
                  <div className="stat-content">
                    <h3>Overall Usage</h3>
                    <p className="stat-number">
                      {stats.usagePercentage ? stats.usagePercentage.toFixed(1) : 0}%
                    </p>
                  </div>
                </div>
              </div>
            )}

            <div className="dashboard-grid">
              {/* Main Table Section */}
              <div className="card table-section">
                <div className="card-header">
                  <h2>User Management</h2>
                  <div style={{ fontSize: '14px' }} className="badge badge-neutral">{users.length} users</div>
                </div>
                <div className="table-responsive">
                  <table className="modern-table">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>User</th>
                        <th>Role</th>
                        <th>Used</th>
                        <th>Set Quota</th>
                        <th style={{ width: '15%' }}>Usage</th>
                        <th>Permissions</th>
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

              {/* Sidebar Section */}
              <div className="sidebar-section">
                {/* Global Actions */}
                <div className="card action-card">
                  <h3>
                    Global Actions
                  </h3>
                  <p className="text-small text-muted">Update storage quota for all users at once.</p>
                  <div className="input-group">
                    <input
                      type="number"
                      placeholder="GB Amount"
                      value={globalQuota}
                      onChange={(e) => setGlobalQuota(e.target.value)}
                    />
                    <button
                      className="btn btn-primary"
                      onClick={handleUpdateAllQuotas}
                      disabled={updating.globalQuota}
                    >
                      {updating.globalQuota}
                      Apply All
                    </button>
                  </div>
                </div>

                {/* Top Consumers List */}
                {stats && stats.topConsumers && stats.topConsumers.length > 0 && (
                  <div className="card consumers-card">
                    <h3>Top Consumers</h3>
                    <ul className="consumer-list">
                      {stats.topConsumers.map((consumer, idx) => (
                        <li key={consumer.id} className="consumer-item">
                          <div className="consumer-rank">{idx + 1}</div>
                          <div className="consumer-info">
                            <strong>{consumer.username}</strong>
                            <div className="consumer-meta">
                              {formatBytes(consumer.storageUsed)} used
                            </div>
                          </div>
                          <div className="mini-pie" style={{
                            background: `conic-gradient(#4f46e5 ${consumer.usagePercentage}%, #e5e7eb 0)`
                          }}></div>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
            </div>
          </>
        )}

      </div>
    </div>
  );
}

export default AdminPanel;