import React, { useState, useEffect, useMemo } from 'react';
// Nhớ export BACKEND_NODES từ file api.js như hướng dẫn trước
import { monitoringAPI, BACKEND_NODES } from '../services/api';
import {
    FaSync, FaRedo, FaTrash, FaPlug, FaBroom, FaUser, FaGlobe, FaServer, FaCheckCircle, FaTimesCircle
} from 'react-icons/fa';
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js';
import { Doughnut } from 'react-chartjs-2';
import './Monitor.css';

ChartJS.register(ArcElement, Tooltip, Legend);

function Monitor({ onBack }) {
    // --- STATE ---
    // Default chọn node đầu tiên trong danh sách
    const [activeNode, setActiveNode] = useState(BACKEND_NODES[0]);

    const [nodeHealth, setNodeHealth] = useState(null); // Trạng thái UP/DOWN của node
    const [circuitBreakers, setCircuitBreakers] = useState([]);
    const [cacheStats, setCacheStats] = useState([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [retries, setRetries] = useState({});


    // Rate Limit State (Global or Local tùy thiết kế, ở đây giả sử check qua Load Balancer)
    const [searchUserId, setSearchUserId] = useState('6');
    const [rateLimitResult, setRateLimitResult] = useState(null);
    const [searchingRateLimit, setSearchingRateLimit] = useState(false);
    const [searchIp, setSearchIp] = useState('0:0:0:0:0:0:0:1');

    // Load data khi component mount hoặc khi chuyển Tab (activeNode thay đổi)
    useEffect(() => {
        loadMonitoringData();
    }, [activeNode]);

    // Helper
    const safeMapData = (data) => {
        if (!data || data.message || typeof data !== 'object') return [];
        return Object.entries(data).map(([name, info]) => ({ name, ...info }));
    };

    const loadMonitoringData = async () => {
        setRefreshing(true);
        // Nếu chuyển tab, set loading nhẹ để user biết
        if (!refreshing) setLoading(true);

        try {
            // Gọi API với URL của node đang chọn
            const results = await Promise.allSettled([
                monitoringAPI.getNodeHealth(activeNode.url),      // Check Health
                monitoringAPI.getCircuitBreakers(activeNode.url), // Check CB
                monitoringAPI.getCacheStats(activeNode.url),      // Check Cache
                monitoringAPI.getRetries(activeNode.url),        // Check Retries 
            ]);

            // Xử lý Health
            if (results[0].status === 'fulfilled') {
                setNodeHealth({ status: 'UP', details: results[0].value.data });
            } else {
                setNodeHealth({ status: 'DOWN', error: results[0].reason?.message });
            }

            // Xử lý Circuit Breaker
            if (results[1].status === 'fulfilled') {
                setCircuitBreakers(safeMapData(results[1].value.data));
            } else {
                setCircuitBreakers([]); // Clear nếu lỗi
            }

            // Xử lý Cache
            if (results[2].status === 'fulfilled') {
                setCacheStats(safeMapData(results[2].value.data));
            } else {
                setCacheStats([]);
            }

            // RETRIES
            if (results[3].status === 'fulfilled') {
                setRetries(results[3].value.data || {});
            } else {
                setRetries({});
            }

        } catch (globalError) {
            console.error('Error loading monitoring data:', globalError);
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    };

    // --- HANDLERS (Reset, Clear...) ---
    const handleResetCircuitBreaker = async (name) => {
        if (!window.confirm(`Reset circuit breaker "${name}" on ${activeNode.name}?`)) return;
        try {
            await monitoringAPI.resetCircuitBreaker(activeNode.url, name);
            await loadMonitoringData();
        } catch (err) { alert('Error: ' + err.message); }
    };

    const handleClearCache = async (name) => {
        // Clear cache trên node hiện tại
        await monitoringAPI.clearCacheOnAllNodes(name); // Hoặc dùng hàm clear local nếu muốn
        await loadMonitoringData();
    };

    const handleClearAllCaches = async () => {
        if (!window.confirm(`Clear ALL caches on ALL nodes?`)) return;
        await monitoringAPI.clearAllCachesOnAllNodes();
        await loadMonitoringData();
        alert("Caches cleared on all nodes.");
    };

    const handleClearAllRateLimits = async () => {
        if (!window.confirm('Reset ALL rate limits?')) return;
        await monitoringAPI.clearAllRateLimits(); // Rate limit thường là global (Redis)
        alert('Rate limits reset!');
        setRateLimitResult(null);
    };

    const handleSearchRateLimit = async (e) => {
        if (e) e.preventDefault();
        if (!searchUserId) return;
        setSearchingRateLimit(true);
        setRateLimitResult(null);
        try {
            const res = await monitoringAPI.getUserRateLimits(searchUserId);
            setRateLimitResult(res.data);
        } catch (err) { setRateLimitResult({}); }
        finally { setSearchingRateLimit(false); }
    };

    // --- CHART DATA ---
    const cacheChartData = useMemo(() => {
        let totalHits = 0;
        let totalMisses = 0;
        cacheStats.forEach(c => {
            totalHits += c.hitCount || 0;
            totalMisses += c.missCount || 0;
        });
        const isEmpty = totalHits === 0 && totalMisses === 0;
        return {
            labels: ['Hits', 'Misses'],
            datasets: [{
                data: isEmpty ? [1, 0] : [totalHits, totalMisses],
                backgroundColor: isEmpty ? ['#e9ecef', '#e9ecef'] : ['#198754', '#dc3545'],
                borderWidth: 0,
            }],
        };
    }, [cacheStats]);

    // --- RENDER HELPERS ---
    const renderCircuitBreakerCard = (cb) => {
        let stateClass = 'cb-closed';
        let badgeClass = 'bg-success';

        if (cb.state === 'OPEN') { stateClass = 'cb-open'; badgeClass = 'bg-danger'; }
        else if (cb.state === 'HALF_OPEN') { stateClass = 'cb-half'; badgeClass = 'bg-warning'; }

        const failRate = cb.failureRate !== -1 ? cb.failureRate?.toFixed(1) + '%' : 'Calculating...';

        return (
            <div className="col-span-6" key={cb.name}>
                <div className={`card ${stateClass}`}>
                    <div className="card-body">
                        <div className="d-flex justify-between align-center mb-3">
                            <h5 style={{ margin: 0, fontSize: '1rem' }} title={cb.name}>{cb.name}</h5>
                            <span className={`badge ${badgeClass}`}>{cb.state}</span>
                        </div>
                        <div style={{ borderBottom: '1px solid #eee', margin: '10px 0' }}></div>

                        <div className="d-flex justify-between text-center mb-3">
                            <div style={{ flex: 1 }} className="border-end">
                                <small className="text-muted">Failed</small>
                                <div className="text-danger fw-bold" style={{ fontSize: '1.2rem' }}>{cb.numberOfFailedCalls}</div>
                            </div>
                            <div style={{ flex: 1 }}>
                                <small className="text-muted">Success</small>
                                <div className="text-success fw-bold" style={{ fontSize: '1.2rem' }}>{cb.numberOfSuccessfulCalls}</div>
                            </div>
                        </div>

                        <div className="d-flex justify-between align-center bg-light p-2 rounded">
                            <small>Failure Rate:</small>
                            <span className="fw-bold text-danger">{failRate}</span>
                        </div>

                        {cb.state === 'OPEN' && (
                            <button className="btn btn-outline-danger w-100 mt-2 btn-sm" onClick={() => handleResetCircuitBreaker(cb.name)}>
                                <FaRedo /> Force Reset
                            </button>
                        )}
                    </div>
                </div>
            </div>
        );
    };

    const renderRateLimitBar = (limitType, info) => {
        const tokens = info.availableTokens ?? 0;
        const capacity = info.capacity ?? 10;
        const percent = (tokens / capacity) * 100;
        let colorClass = tokens === 0 ? 'bg-danger' : (percent < 30 ? 'bg-warning' : 'bg-success');

        return (
            <div className="mb-3" key={limitType}>
                <div className="d-flex justify-between mb-1">
                    <span className="fw-bold text-muted" style={{ fontSize: '0.85em' }}>{limitType}</span>
                    <span className={`small ${tokens === 0 ? 'text-danger fw-bold' : ''}`}>{tokens} / {capacity}</span>
                </div>
                <div className="progress">
                    <div className={`progress-bar ${colorClass}`} style={{ width: `${percent}%` }}></div>
                </div>
            </div>
        );
    };

    const renderRetriesCard = (name, info) => {
        return (
            <div className="col-span-6" key={name}>
                <div className="card cb-closed">
                    <div className="card-body">

                        <div className="d-flex justify-between align-center mb-3">
                            <h5 style={{ margin: 0, fontSize: "1rem" }}>{name}</h5>
                            <span className="badge bg-primary">Retries</span>
                        </div>

                        <div style={{ borderBottom: "1px solid #eee", margin: "10px 0" }} />

                        <div className="d-flex justify-between text-center mb-3">
                            <div style={{ flex: 1 }} className="border-end">
                                <small className="text-muted">Success (no retry)</small>
                                <div className="fw-bold text-success" style={{ fontSize: "1.2rem" }}>
                                    {info.numberOfSuccessfulCallsWithoutRetryAttempt}
                                </div>
                            </div>

                            <div style={{ flex: 1 }} className="border-end">
                                <small className="text-muted">Success (with retry)</small>
                                <div className="fw-bold text-primary" style={{ fontSize: "1.2rem" }}>
                                    {info.numberOfSuccessfulCallsWithRetryAttempt}
                                </div>
                            </div>

                            <div style={{ flex: 1 }}>
                                <small className="text-muted">Failed (with retry)</small>
                                <div className="fw-bold text-danger" style={{ fontSize: "1.2rem" }}>
                                    {info.numberOfFailedCallsWithRetryAttempt}
                                </div>
                            </div>
                        </div>

                    </div>
                </div>
            </div>
        );
    };



    return (
        <div className="monitor-full-page">
            <div className="monitor-wrapper">
                {/* HEADER & GLOBAL ACTIONS */}
                <div className="monitor-header">
                    <div>
                        <h2 style={{ margin: 0, color: '#444' }}>System Monitor</h2>
                        <small className="text-muted">Multi-node Dashboard</small>
                    </div>
                    <div className="monitor-header-actions">
                        {onBack && (
                            <button style={{ background: '#6c63ff', color: 'white', border: 'none' }} className="btn btn-outline-secondary me-2" onClick={onBack}>
                                ← Back
                            </button>
                        )}
                        <button style={{ background: '#6c63ff', color: 'white', border: 'none' }} className="btn btn-outline-secondary me-2" onClick={loadMonitoringData} disabled={refreshing}>
                            <FaSync className={refreshing ? 'fa-spin' : ''} /> {refreshing ? 'Refreshing...' : 'Refresh Node'}
                        </button>
                        <button style={{ background: '#6c63ff', color: 'white', border: 'none' }} className="btn btn-outline-warning me-2" onClick={handleClearAllRateLimits}>
                            <FaBroom /> Global Limits Reset
                        </button>
                        <button style={{ background: '#6c63ff', color: 'white', border: 'none' }} className="btn btn-outline-danger" onClick={handleClearAllCaches}>
                            <FaTrash /> Global Cache Clear
                        </button>
                    </div>
                </div>

                <div className="monitor-content-area">
                    {/* === TABS FOR 3 BACKENDS === */}
                    <div className="monitor-tabs">
                        {BACKEND_NODES.map((node) => (
                            <button
                                key={node.id}
                                className={`monitor-tab-btn ${activeNode.id === node.id ? 'active' : ''}`}
                                onClick={() => setActiveNode(node)}
                            >
                                <FaServer className="me-2" />
                                {node.name}
                                {activeNode.id === node.id && nodeHealth && (
                                    nodeHealth.status === 'UP'
                                        ? <span className="node-status-badge badge-success">UP</span>
                                        : <span className="node-status-badge badge-danger">DOWN</span>
                                )}
                            </button>
                        ))}
                    </div>

                    {/* NODE SPECIFIC CONTENT */}
                    <div className="tab-content">
                        {loading ? (
                            <div className="monitor-loading">
                                <div className="spinner-border" role="status"></div>
                                <p className="mt-2 text-muted">Connecting to {activeNode.name}...</p>
                            </div>
                        ) : nodeHealth?.status === 'DOWN' ? (
                            <div className="monitor-error">
                                <FaTimesCircle size={40} className="mb-3" />
                                <h4>Connection Failed</h4>
                                <p>Could not reach <strong>{activeNode.name}</strong> at {activeNode.url}</p>
                                <p className="text-muted small">Error: {nodeHealth.error}</p>
                            </div>
                        ) : (
                            <>
                                <h4 className="monitor-section-title">
                                    <FaPlug /> Circuit Breakers & Retries
                                    <span className="text-muted small">({activeNode.name})</span>
                                </h4>

                                <div className="dashboard-grid">

                                    {/* Circuit Breakers */}
                                    {circuitBreakers.length > 0 ? (
                                        circuitBreakers.map(renderCircuitBreakerCard)
                                    ) : (
                                        <div className="col-span-12">
                                            <div className="alert alert-info">No Circuit Breakers active on this node.</div>
                                        </div>
                                    )}

                                    {/* Retries */}
                                    {retries && Object.keys(retries).length > 0 ? (
                                        Object.entries(retries).map(([name, info]) =>
                                            renderRetriesCard(name, info)
                                        )
                                    ) : (
                                        <div className="col-span-12">
                                            <div className="alert alert-info">No retry metrics found.</div>
                                        </div>
                                    )}
                                </div>



                                {/* 2. CACHE STATISTICS */}
                                <h4 className="monitor-section-title">
                                    <FaSync /> Cache Statistics <span className="text-muted small">({activeNode.name})</span>
                                </h4>
                                <div className="dashboard-grid">
                                    {/* Table */}
                                    <div className="col-span-8">
                                        <div className="monitor-card">
                                            <div className="monitor-table-container">
                                                <table className="monitor-table">
                                                    <thead>
                                                        <tr>
                                                            <th>Name</th>
                                                            <th>Hit Rate</th>
                                                            <th>Items</th>
                                                            <th>Action</th>
                                                        </tr>
                                                    </thead>
                                                    <tbody>
                                                        {cacheStats.map(cache => {
                                                            const hitRate = cache.hitRate ? (cache.hitRate * 100).toFixed(1) : 0;
                                                            return (
                                                                <tr key={cache.name}>
                                                                    <td><strong>{cache.name}</strong></td>
                                                                    <td style={{ width: '40%' }}>
                                                                        <div className="cache-hitrate-display">
                                                                            <span className="hitrate-percent">{hitRate}%</span>
                                                                            <div className="progress-bar-wrapper">
                                                                                <div className={`progress-bar-fill ${hitRate < 50 ? 'bg-warning' : 'bg-success'}`} style={{ width: `${hitRate}%` }}></div>
                                                                            </div>
                                                                        </div>
                                                                    </td>
                                                                    <td>{cache.estimatedSize || 0}</td>
                                                                    <td><button className="btn-link-danger" onClick={() => handleClearCache(cache.name)}>Clear</button></td>
                                                                </tr>
                                                            );
                                                        })}
                                                        {cacheStats.length === 0 && <tr><td colSpan="4" className="text-center text-muted">No caches found.</td></tr>}
                                                    </tbody>
                                                </table>
                                            </div>
                                        </div>
                                    </div>

                                    {/* Chart */}
                                    <div className="col-span-4">
                                        <div className="monitor-card monitor-chart-card">
                                            <div style={{ width: '100%', maxWidth: '250px', margin: '0 auto' }}>
                                                <Doughnut data={cacheChartData} options={{
                                                    responsive: true,
                                                    maintainAspectRatio: false,
                                                    cutout: '70%',
                                                    plugins: { legend: { position: 'bottom' }, title: { display: true, text: 'Cache Efficiency' } }
                                                }} />
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </>
                        )}
                    </div>

                    {/* 3. GLOBAL TOOLS (RATE LIMITS) */}
                    <div className="monitor-global-section">
                        <h4 className="monitor-section-title"><FaGlobe /> Global Rate Limit Inspector</h4>
                        <div className="dashboard-grid">
                            {/* User Check */}
                            <div className="col-span-6">
                                <div className="monitor-card">
                                    <div className="monitor-card-title">
                                        <FaUser /> Check by User ID
                                    </div>
                                    <div className="monitor-input-row">
                                        <input
                                            type="text"
                                            className="monitor-input"
                                            placeholder="User ID"
                                            value={searchUserId}
                                            onChange={(e) => setSearchUserId(e.target.value)}
                                            onKeyDown={(e) => e.key === 'Enter' && handleSearchRateLimit()}
                                        />
                                        <button className="monitor-btn" onClick={handleSearchRateLimit} disabled={searchingRateLimit}>
                                            {searchingRateLimit ? '...' : 'Check'}
                                        </button>
                                    </div>
                                    <div className="rate-limit-results">
                                        {rateLimitResult && Object.keys(rateLimitResult).length > 0 ?
                                            Object.entries(rateLimitResult).map(([key, val]) => renderRateLimitBar(key, val)) :
                                            <small className="text-muted">Enter User ID to check limits...</small>
                                        }
                                    </div>
                                </div>
                            </div>

                            {/* IP Check */}
                            <div className="col-span-6">
                                <div className="monitor-card">
                                    <div className="monitor-card-title">
                                        <FaGlobe /> Check by IP Address
                                    </div>
                                    <div className="monitor-input-row">
                                        <input
                                            type="text"
                                            className="monitor-input"
                                            placeholder="IP Address"
                                            value={searchIp}
                                            onChange={(e) => setSearchIp(e.target.value)}
                                        />
                                        <button className="monitor-btn">Check</button>
                                    </div>
                                    <small className="text-muted">Check limits for anonymous users.</small>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}

export default Monitor;