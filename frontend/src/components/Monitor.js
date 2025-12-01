import React, { useState, useEffect, useMemo } from 'react';
// Nhớ export BACKEND_NODES từ file api.js
import { monitoringAPI, BACKEND_NODES } from '../services/api';
import {
    FaSync, FaRedo, FaTrash, FaPlug, FaBroom, FaUser, FaGlobe,
    FaServer, FaCheckCircle, FaTimesCircle,
    FaDatabase, FaHdd, FaCloud, FaMemory
} from 'react-icons/fa';
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js';
import { Doughnut } from 'react-chartjs-2';
import './Monitor.css';

ChartJS.register(ArcElement, Tooltip, Legend);

function Monitor({ onBack }) {
    // --- STATE ---
    const [activeNode, setActiveNode] = useState(BACKEND_NODES[0]);

    const [nodeHealth, setNodeHealth] = useState(null);
    const [circuitBreakers, setCircuitBreakers] = useState([]);
    const [cacheStats, setCacheStats] = useState([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [retries, setRetries] = useState({});

    // Rate Limit State
    const [searchUserId, setSearchUserId] = useState('6');
    const [rateLimitResult, setRateLimitResult] = useState(null);
    const [searchingRateLimit, setSearchingRateLimit] = useState(false);

    // IP Rate Limit State
    const [searchIp, setSearchIp] = useState('0:0:0:0:0:0:0:1');
    const [ipLimitResult, setIpLimitResult] = useState(null);
    const [searchingIpLimit, setSearchingIpLimit] = useState(false);

    useEffect(() => {
        loadMonitoringData();
    }, [activeNode]);

    // --- HELPERS ---
    const safeMapData = (data) => {
        if (!data || data.message || typeof data !== 'object') return [];
        // Nếu data là array thì return luôn
        if (Array.isArray(data)) return data;
        return Object.entries(data).map(([name, info]) => ({ name, ...info }));
    };

    const formatBytes = (bytes, decimals = 2) => {
        if (!+bytes) return '0 Bytes';
        const k = 1024;
        const dm = decimals < 0 ? 0 : decimals;
        const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizes[i]}`;
    };

    // --- MAIN LOAD DATA FUNCTION (QUAN TRỌNG NHẤT) ---
    const loadMonitoringData = async () => {
        setRefreshing(true);
        if (!refreshing) setLoading(true);

        try {
            const results = await Promise.allSettled([
                // 1. SỬA QUAN TRỌNG: Gọi getActuatorHealth thay vì getNodeHealth
                monitoringAPI.getActuatorHealth(activeNode.url),

                // Các API khác giữ nguyên
                monitoringAPI.getCircuitBreakers(activeNode.url),
                monitoringAPI.getCacheStats(activeNode.url),
                monitoringAPI.getRetries(activeNode.url),
            ]);

            // --- XỬ LÝ 1: HEALTH (ACTUATOR) ---
            if (results[0].status === 'fulfilled') {
                const res = results[0].value;
                // Bóc data từ axios response
                const healthData = res.data ? res.data : res;

                // Nếu đúng chuẩn Actuator, nó sẽ có field 'status' = UP
                if (healthData.status) {
                    setNodeHealth({ status: healthData.status, details: healthData });
                } else {
                    // Fallback nếu API lạ
                    setNodeHealth({ status: 'UNKNOWN', details: healthData });
                }
            } else {
                console.error("Health Check Error:", results[0].reason);
                setNodeHealth({ status: 'DOWN', error: results[0].reason?.message });
            }

            // --- XỬ LÝ 2: CIRCUIT BREAKERS ---
            if (results[1].status === 'fulfilled') {
                const res = results[1].value;
                const data = res.data ? res.data : res;
                setCircuitBreakers(safeMapData(data));
            } else {
                setCircuitBreakers([]);
            }

            // --- XỬ LÝ 3: CACHE ---
            if (results[2].status === 'fulfilled') {
                const res = results[2].value;
                const data = res.data ? res.data : res;
                setCacheStats(safeMapData(data));
            } else {
                setCacheStats([]);
            }

            // --- XỬ LÝ 4: RETRIES ---
            if (results[3].status === 'fulfilled') {
                const res = results[3].value;
                const data = res.data ? res.data : res;
                setRetries(data || {});
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

    // --- HANDLERS ---
    const handleResetCircuitBreaker = async (name) => {
        if (!window.confirm(`Reset circuit breaker "${name}" on ${activeNode.name}?`)) return;
        try {
            await monitoringAPI.resetCircuitBreaker(activeNode.url, name);
            await loadMonitoringData();
        } catch (err) { alert('Error: ' + err.message); }
    };

    const handleClearCache = async (name) => {
        await monitoringAPI.clearCacheOnAllNodes(name);
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
        await monitoringAPI.clearAllRateLimits();
        alert('Rate limits reset!');
        setRateLimitResult(null);
        setIpLimitResult(null);
    };

    const handleSearchRateLimit = async (e) => {
        if (e) e.preventDefault();
        if (!searchUserId) return;
        setSearchingRateLimit(true);
        setRateLimitResult(null);
        try {
            const res = await monitoringAPI.getUserRateLimits(searchUserId);
            setRateLimitResult(res.data);
        } catch (err) {
            setRateLimitResult({});
        } finally {
            setSearchingRateLimit(false);
        }
    };

    const handleSearchIpLimit = async (e) => {
        if (e) e.preventDefault();
        if (!searchIp) return;
        setSearchingIpLimit(true);
        setIpLimitResult(null);
        try {
            const res = await monitoringAPI.getIpRateLimits(activeNode.url, searchIp);
            setIpLimitResult(res.data);
        } catch (err) {
            setIpLimitResult({});
        } finally {
            setSearchingIpLimit(false);
        }
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
                            <h1 style={{ margin: 0, fontSize: '20px', padding: 0 }} title={cb.name}>{cb.name} (Circuit breakers)</h1>
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
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                <h5 style={{ margin: 0, fontSize: "20px" }}>{name} (Retries)</h5>
                            </div>
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
                                <small className="text-muted">Success (w/ retry)</small>
                                <div className="fw-bold text-primary" style={{ fontSize: "1.2rem" }}>
                                    {info.numberOfSuccessfulCallsWithRetryAttempt}
                                </div>
                            </div>
                            <div style={{ flex: 1 }}>
                                <small className="text-muted">Failed (w/ retry)</small>
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
                {/* HEADER */}
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
                    </div>
                </div>

                <div className="monitor-content-area">
                    {/* TABS */}
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

                    {/* CONTENT */}
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
                                {/* 1. INFRASTRUCTURE HEALTH */}
                                <h4 className="monitor-section-title">
                                    <FaServer /> Infrastructure Health
                                    <span className="text-muted small"> ({activeNode.name})</span>
                                </h4>

                                <div className="dashboard-grid mb-4">
                                    {(() => {
                                        const components = nodeHealth?.details?.components || {};
                                        const db = components.db;
                                        const redis = components.redis;
                                        const disk = components.diskSpace;
                                        const azure = components.circuitBreakers?.details?.azureService;

                                        return (
                                            <>
                                                <div className="col-span-4">
                                                    <div className={`monitor-card ${db?.status === 'UP' ? 'border-success-subtle' : 'border-danger-subtle'}`}>
                                                        <div className="d-flex align-center justify-between mb-3">
                                                            <div className="d-flex align-center gap-2">
                                                                <FaDatabase className="text-secondary" style={{ fontSize: '1.5rem', marginRight: '0.5rem' }} />
                                                                <span className="fw-bold">Database</span>
                                                                <span style={{ marginLeft: '10px' }} className={`badge ${db?.status === 'UP' ? 'bg-success' : 'bg-danger'}`}>
                                                                    {db?.status || 'UNKNOWN'}
                                                                </span>
                                                            </div>

                                                        </div>
                                                        <div className="small text-muted">
                                                            Type: <strong>{db?.details?.database || 'N/A'}</strong>
                                                            <span style={{ marginLeft: '1rem' }} className="small text-muted">
                                                                Query: {db?.details?.result === 1 ? <span className="text-success">Pass</span> : 'Fail'}
                                                            </span>
                                                        </div>

                                                    </div>
                                                </div>

                                                <div className="col-span-4">
                                                    <div className={`monitor-card ${redis?.status === 'UP' ? 'border-success-subtle' : 'border-danger-subtle'}`}>
                                                        <div className="d-flex align-center justify-between mb-3">
                                                            <div className="d-flex align-center gap-2">
                                                                <FaMemory className="text-danger" style={{ fontSize: '1.5rem', marginRight: '0.5rem', transform: 'translateY(4px)' }} />
                                                                <span className="fw-bold">Redis</span>
                                                                <span style={{ marginLeft: '10px' }} className={`badge ${redis?.status === 'UP' ? 'bg-success' : 'bg-danger'}`}>
                                                                    {redis?.status || 'UNKNOWN'}
                                                                </span>
                                                            </div>
                                                        </div>
                                                        <div className="small text-muted">
                                                            Version: <strong>{redis?.details?.version || 'N/A'}</strong>
                                                        </div>
                                                    </div>
                                                </div>

                                                <div className="col-span-4">
                                                    <div className={`monitor-card ${azure?.status === 'UP' ? 'border-success-subtle' : 'border-danger-subtle'}`}>
                                                        <div className="d-flex align-center justify-between mb-3">
                                                            <div className="d-flex align-center gap-2">
                                                                <FaCloud className="text-primary" style={{ fontSize: '1.5rem', marginRight: '0.5rem', transform: 'translateY(4px)' }} />
                                                                <span className="fw-bold">Azure Blob</span>
                                                                <span style={{ marginLeft: '10px' }} className={`badge ${azure?.status === 'UP' ? 'bg-success' : 'bg-danger'}`}>
                                                                    {azure?.status || 'UNKNOWN'}
                                                                </span>
                                                            </div>
                                                        </div>
                                                        <div className="small text-muted d-flex justify-between">
                                                            <span>Circuit breaker: </span>
                                                            <span className={`fw-bold ${azure?.details?.state === 'CLOSED' ? 'text-success' : 'text-danger'}`}>
                                                                {azure?.details?.state || 'N/A'}
                                                            </span>
                                                        </div>
                                                    </div>
                                                </div>

                                                <div className="col-span-4">
                                                    <div className="monitor-card">
                                                        <div className="d-flex align-center justify-between mb-3">
                                                            <div className="d-flex align-center gap-2">
                                                                <FaHdd className="text-secondary" style={{ fontSize: '1.5rem', marginRight: '0.5rem', transform: 'translateY(3px)' }} />
                                                                <span className="fw-bold">Disk Space</span>
                                                            </div>

                                                        </div>
                                                        {disk?.details ? (
                                                            <div>
                                                                <div className="d-flex justify-between small text-muted mb-1">
                                                                    <span>Free: {formatBytes(disk.details.free)}</span>
                                                                    <span style={{ marginLeft: '10px' }}>Total: {formatBytes(disk.details.total)}</span>
                                                                </div>
                                                                <div className="progress" style={{ height: '6px' }}>
                                                                    <div
                                                                        className="progress-bar bg-info"
                                                                        style={{ width: `${((disk.details.total - disk.details.free) / disk.details.total * 100)}%` }}
                                                                    ></div>
                                                                </div>
                                                            </div>
                                                        ) : <small className="text-muted">No info</small>}
                                                    </div>
                                                </div>
                                            </>
                                        );
                                    })()}
                                </div>

                                {/* 2. CIRCUIT BREAKERS */}
                                <h4 className="monitor-section-title">
                                    <FaPlug /> Circuit Breakers & Retries
                                    <span className="text-muted small"> ({activeNode.name})</span>
                                </h4>
                                <div className="dashboard-grid">
                                    {circuitBreakers.length > 0 ? (
                                        circuitBreakers.map(renderCircuitBreakerCard)
                                    ) : (
                                        <div className="col-span-12">
                                            <div className="alert alert-info">No Circuit Breakers active on this node.</div>
                                        </div>
                                    )}
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


                            </>
                        )}
                    </div>

                    {/* 4. GLOBAL TOOLS */}
                    <div className="monitor-global-section">
                        <h4 className="monitor-section-title"><FaGlobe /> Global Rate Limit Inspector</h4>
                        <div className="dashboard-grid">
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
                                            (rateLimitResult ? <div className="alert alert-secondary py-1">No active limits found for this User.</div> :
                                                <small className="text-muted">Enter User ID to check limits...</small>)
                                        }
                                    </div>
                                </div>
                            </div>
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
                                            onKeyDown={(e) => e.key === 'Enter' && handleSearchIpLimit()}
                                        />
                                        <button className="monitor-btn" onClick={handleSearchIpLimit} disabled={searchingIpLimit}>
                                            {searchingIpLimit ? '...' : 'Check'}
                                        </button>
                                    </div>
                                    <div className="rate-limit-results">
                                        {ipLimitResult && Object.keys(ipLimitResult).length > 0 ?
                                            Object.entries(ipLimitResult).map(([key, val]) => renderRateLimitBar(key, val)) :
                                            (ipLimitResult ? <div className="alert alert-secondary py-1">No active limits found for this IP.</div> :
                                                <small className="text-muted">Enter IP to check limits (e.g., 127.0.0.1)</small>)
                                        }
                                    </div>
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