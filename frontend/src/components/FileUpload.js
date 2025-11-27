import React, { useState, useRef, useEffect } from 'react';
import { FaCloudUploadAlt } from 'react-icons/fa';
import { fileAPI } from '../services/api';
import './FileUpload.css';

// =======================
// Utility Functions
// =======================
function humanFileSize(bytes) {
  if (bytes === 0) return '0 B';
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return (bytes / Math.pow(1024, i)).toFixed(2) + ' ' + sizes[i];
}

function formatETA(seconds) {
  if (!isFinite(seconds) || seconds <= 0) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return [h, m, s].map(v => String(v).padStart(2, '0')).join(':');
}

const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB
const CHUNK_PARALLELISM = 4;
// Retry/backoff config
const MAX_RETRIES = 3;
const BACKOFF_BASE_DELAY = 500; // ms

// =======================================================
// MAIN COMPONENT
// =======================================================
export default function FileUpload({ currentFolderId, onUploadSuccess }) {

  // State cho UI
  const [selectedFiles, setSelectedFiles] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [currentMode, setCurrentMode] = useState(null);
  const [isPaused, setIsPaused] = useState(false);
  const [isCancelled, setIsCancelled] = useState(false);

  const [overallProgress, setOverallProgress] = useState(0);
  const [fileStats, setFileStats] = useState({});
  const [uploadResults, setUploadResults] = useState(null);
  const [elapsedTime, setElapsedTime] = useState(0); // seconds

  // Refs cho Logic (Để tránh lỗi Closure)
  const isPausedRef = useRef(false);
  const isCancelledRef = useRef(false);

  // Speed & progress refs
  const bytesUploadedRef = useRef({});
  const startTimeRef = useRef({});
  const inflightXhrsRef = useRef({});
  const overallStartRef = useRef(null);
  const overallEndRef = useRef(null);

  const resetStats = () => {
    setFileStats({});
    setOverallProgress(0);
    bytesUploadedRef.current = {};
    startTimeRef.current = {};
    // abort any inflight XHRs and clear
    if (inflightXhrsRef.current) {
      Object.values(inflightXhrsRef.current).forEach(arr => {
        arr.forEach(xhr => {
          try { xhr.abort(); } catch (e) { /* ignore */ }
        });
      });
    }
    inflightXhrsRef.current = {};
    overallStartRef.current = null;
    overallEndRef.current = null;
    setElapsedTime(0);
  };

  const handleFileSelect = (e) => {
    const files = Array.from(e.target.files);
    if (files.length === 0) return;

    setUploadResults(null);
    setSelectedFiles(files);
    resetStats();

    // Reset UI State
    setIsPaused(false);
    setIsCancelled(false);

    // Reset Logic Refs
    isPausedRef.current = false;
    isCancelledRef.current = false;
  };

  // ==============================
  // Control Handlers
  // ==============================
  const handlePause = () => {
    isPausedRef.current = true;
    setIsPaused(true);
    // abort any in-flight requests so they stop early; they'll re-try after resume
    if (inflightXhrsRef.current) {
      Object.values(inflightXhrsRef.current).forEach(arr => arr.forEach(xhr => {
        try { xhr.abort(); } catch (e) { /* ignore */ }
      }));
    }
  };

  const handleResume = () => {
    isPausedRef.current = false;
    setIsPaused(false);
  };

  const handleCancel = () => {
    isCancelledRef.current = true;
    isPausedRef.current = false;

    setIsCancelled(true);
    setIsPaused(false);
    setUploading(false);
    setCurrentMode(null);

    setSelectedFiles([]);
    resetStats();

    const input = document.getElementById('file-input');
    if (input) input.value = "";

    console.warn("UPLOAD CANCELLED");
    // abort any in-flight XHRs so they stop immediately
    if (inflightXhrsRef.current) {
      Object.values(inflightXhrsRef.current).forEach(arr => arr.forEach(xhr => {
        try { xhr.abort(); } catch (e) { /* ignore */ }
      }));
    }
    overallStartRef.current = null;
    overallEndRef.current = null;
    setElapsedTime(0);
  };

  // ==============================
  // Logic Control Helpers
  // ==============================
  const waitIfPaused = async () => {
    while (isPausedRef.current) {
      await new Promise(r => setTimeout(r, 200));
    }
  };

  const checkCancel = () => {
    if (isCancelledRef.current) throw new Error("Upload cancelled");
  };

  // ===========================================================
  // LOW-LEVEL UPLOAD HELPERS
  // ===========================================================

  const uploadToAzureSimpleWithProgress = (file, sasUrl, onProgress) => {
    // Retry-friendly single PUT with progress
    return new Promise(async (resolve, reject) => {
      let attempts = 0;
      const doPut = () => new Promise((resolvePut, rejectPut) => {
        const xhr = new XMLHttpRequest();

        xhr.upload.addEventListener('progress', async (e) => {
          if (e.lengthComputable) {
            await waitIfPaused();
            try { checkCancel(); } catch (err) { xhr.abort(); rejectPut(err); return; }
            onProgress(e.loaded, e.total);
          }
        });

        xhr.addEventListener('load', () => {
          if (xhr.status >= 200 && xhr.status < 300) resolvePut();
          else rejectPut(new Error(`Upload failed: ${xhr.status}`));
        });

        xhr.addEventListener('error', () => rejectPut(new Error('Network error')));
        xhr.addEventListener('abort', () => {
          // Distinguish cancel vs pause via refs
          if (isCancelledRef.current) rejectPut(new Error('Upload cancelled'));
          else if (isPausedRef.current) rejectPut(new Error('Upload paused'));
          else rejectPut(new Error('Upload aborted'));
        });

        xhr.open('PUT', sasUrl);
        xhr.setRequestHeader('x-ms-blob-type', 'BlockBlob');

        inflightXhrsRef.current[file.name] = inflightXhrsRef.current[file.name] || [];
        inflightXhrsRef.current[file.name].push(xhr);
        xhr.addEventListener('loadend', () => {
          inflightXhrsRef.current[file.name] = (inflightXhrsRef.current[file.name] || []).filter(x => x !== xhr);
        });

        xhr.send(file);
      });

      while (attempts <= MAX_RETRIES) {
        try {
          await doPut();
          resolve();
          return;
        } catch (err) {
          if (err.message === 'Upload cancelled') { reject(err); return; }
          if (err.message === 'Upload paused') { await waitIfPaused(); continue; }
          attempts += 1;
          if (attempts > MAX_RETRIES) { reject(err); return; }
          await new Promise(r => setTimeout(r, BACKOFF_BASE_DELAY * Math.pow(2, attempts - 1)));
        }
      }
    });
  };

  const uploadBlock = (baseUrl, sasToken, blockId, chunk, fileName) => {
    return new Promise(async (resolve, reject) => {
      const blockUrl = `${baseUrl}?comp=block&blockid=${encodeURIComponent(blockId)}&${sasToken}`;
      let attempts = 0;

      const doUpload = () => new Promise((resolveUpload, rejectUpload) => {
        const xhr = new XMLHttpRequest();

        xhr.addEventListener('load', () => {
          if (xhr.status >= 200 && xhr.status < 300) resolveUpload(chunk.size);
          else rejectUpload(new Error(`Block upload failed: ${xhr.status}`));
        });

        xhr.addEventListener('error', () => rejectUpload(new Error('Network error block')));
        xhr.addEventListener('abort', () => {
          if (isCancelledRef.current) rejectUpload(new Error('Upload cancelled'));
          else if (isPausedRef.current) rejectUpload(new Error('Upload paused'));
          else rejectUpload(new Error('Upload aborted'));
        });

        xhr.open('PUT', blockUrl);
        // DO NOT set Content-Length in browser

        inflightXhrsRef.current[fileName] = inflightXhrsRef.current[fileName] || [];
        inflightXhrsRef.current[fileName].push(xhr);
        xhr.addEventListener('loadend', () => {
          inflightXhrsRef.current[fileName] = (inflightXhrsRef.current[fileName] || []).filter(x => x !== xhr);
        });
        xhr.send(chunk);
      });

      while (attempts <= MAX_RETRIES) {
        try {
          const size = await doUpload();
          resolve(size);
          return;
        } catch (err) {
          if (err.message === 'Upload cancelled') { reject(err); return; }
          if (err.message === 'Upload paused') { await waitIfPaused(); continue; }
          attempts += 1;
          if (attempts > MAX_RETRIES) { reject(err); return; }
          await new Promise(r => setTimeout(r, BACKOFF_BASE_DELAY * Math.pow(2, attempts - 1)));
        }
      }
    });
  };

  const commitBlocksToAzure = (baseUrl, sasToken, blockIds, fileName = 'global') => {
    return new Promise(async (resolve, reject) => {
      const xml =
        '<?xml version="1.0" encoding="utf-8"?>' +
        '<BlockList>' +
        blockIds.map(id => `<Latest>${id}</Latest>`).join('') +
        '</BlockList>';

      const commitUrl = `${baseUrl}?comp=blocklist&${sasToken}`;
      let attempts = 0;
      const doCommit = () => new Promise((resolveCommit, rejectCommit) => {
        const xhr = new XMLHttpRequest();
        // track commit XHR for file
        inflightXhrsRef.current[fileName] = inflightXhrsRef.current[fileName] || [];
        inflightXhrsRef.current[fileName].push(xhr);
        xhr.addEventListener('loadend', () => {
          inflightXhrsRef.current[fileName] = (inflightXhrsRef.current[fileName] || []).filter(x => x !== xhr);
        });
        xhr.addEventListener('load', () => {
          if (xhr.status >= 200 && xhr.status < 300) resolveCommit();
          else rejectCommit(new Error(`Commit failed: ${xhr.status}`));
        });
        xhr.addEventListener('error', () => rejectCommit(new Error('Network error commit')));
        xhr.addEventListener('abort', () => {
          if (isCancelledRef.current) rejectCommit(new Error('Upload cancelled'));
          else if (isPausedRef.current) rejectCommit(new Error('Upload paused'));
          else rejectCommit(new Error('Commit aborted'));
        });
        xhr.open('PUT', commitUrl);
        xhr.setRequestHeader('Content-Type', 'application/xml');
        xhr.send(xml);
      });
      while (attempts <= MAX_RETRIES) {
        try {
          await doCommit();
          resolve();
          return;
        } catch (err) {
          if (err.message === 'Upload paused') { await waitIfPaused(); continue; }
          attempts += 1;
          if (attempts > MAX_RETRIES) { reject(err); return; }
          await new Promise(r => setTimeout(r, BACKOFF_BASE_DELAY * Math.pow(2, attempts - 1)));
        }
      }
    });
  };

  // ===========================================================
  // CHUNK-UPLOAD (SEQUENTIAL)
  // ===========================================================
  const uploadChunkedSequential = async (file, sasUrl, onChunkProgress) => {
    const baseUrl = sasUrl.split('?')[0];
    const sasToken = sasUrl.split('?')[1];

    const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
    const blockIds = [];
    let uploaded = 0;

    for (let i = 0; i < totalChunks; i++) {
      await waitIfPaused();
      checkCancel();

      const start = i * CHUNK_SIZE;
      const end = Math.min(start + CHUNK_SIZE, file.size);
      const chunk = file.slice(start, end);
      const blockId = btoa(String(i).padStart(8, '0'));

      await uploadBlock(baseUrl, sasToken, blockId, chunk, file.name);
      blockIds.push(blockId);

      uploaded += chunk.size;
      onChunkProgress(uploaded, file.size, (uploaded / file.size) * 100);
    }

    await commitBlocksToAzure(baseUrl, sasToken, blockIds, file.name);
  };

  // ===========================================================
  // CHUNK-PARALLEL UPLOAD
  // ===========================================================
  const uploadChunkedParallel = async (file, sasUrl, onChunkProgress, parallelism = CHUNK_PARALLELISM) => {
    const baseUrl = sasUrl.split('?')[0];
    const sasToken = sasUrl.split('?')[1];
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

    const blockIds = new Array(totalChunks);
    const indices = Array.from({ length: totalChunks }, (_, i) => i);

    let uploaded = 0;

    const worker = async () => {
      while (indices.length > 0) {
        await waitIfPaused();
        checkCancel();

        const i = indices.shift();
        if (i === undefined) return;

        const start = i * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, file.size);
        const chunk = file.slice(start, end);
        const blockId = btoa(String(i).padStart(8, '0'));

        await uploadBlock(baseUrl, sasToken, blockId, chunk, file.name);
        blockIds[i] = blockId;

        uploaded += chunk.size;
        onChunkProgress(uploaded, file.size, (uploaded / file.size) * 100);
      }
    };

    await Promise.all(
      Array.from({ length: Math.min(parallelism, totalChunks) }, () => worker())
    );

    await commitBlocksToAzure(baseUrl, sasToken, blockIds, file.name);
  };

  // ===========================================================
  // HIGH-LEVEL UPLOAD FOR EACH FILE
  // ===========================================================
  const uploadSingleFile = async (file, mode = "sequential", onProgressUpdate = () => { }) => {
    const CHUNKED_THRESHOLD = 10 * 1024 * 1024; // 10MB

    const sasResponse = await fileAPI.generateUploadSasUrl(
      file.name,
      file.size,
      currentFolderId,
      60
    );

    const { sasUrl, blobPath } = sasResponse.data;

    bytesUploadedRef.current[file.name] = 0;
    startTimeRef.current[file.name] = Date.now();
    inflightXhrsRef.current[file.name] = inflightXhrsRef.current[file.name] || [];

    if (file.size <= CHUNKED_THRESHOLD) {
      await uploadToAzureSimpleWithProgress(file, sasUrl, (loaded, total) => {
        bytesUploadedRef.current[file.name] = loaded;
        const elapsed = (Date.now() - startTimeRef.current[file.name]) / 1000;
        const speed = loaded / (elapsed || 1);
        const eta = (total - loaded) / (speed || 1);
        onProgressUpdate(loaded, total, (loaded / total) * 100, speed, eta);
      });
    } else {
      const handler = mode === "sequential" ? uploadChunkedSequential : uploadChunkedParallel;

      await handler(file, sasUrl, (uploaded, total, percent) => {
        bytesUploadedRef.current[file.name] = uploaded;
        const elapsed = (Date.now() - startTimeRef.current[file.name]) / 1000;
        const speed = uploaded / (elapsed || 1);
        const eta = (total - uploaded) / (speed || 1);
        onProgressUpdate(uploaded, total, percent, speed, eta);
      });
    }

    await fileAPI.confirmUpload(blobPath, file.name, file.size, file.type, currentFolderId);
  };

  // ===========================================================
  // UPLOAD MODES MANAGER
  // ===========================================================
  const performUpload = async (mode) => {
    if (!selectedFiles.length) return;

    setUploading(true);
    setCurrentMode(mode);
    resetStats();
    setUploadResults(null);
    // Track overall start time for elapsed/total duration
    overallStartRef.current = Date.now();
    overallEndRef.current = null;
    setElapsedTime(0);

    // Reset Flags
    setIsPaused(false);
    setIsCancelled(false);
    isPausedRef.current = false;
    isCancelledRef.current = false;

    const success = [];
    const failed = [];
    const totalBytes = selectedFiles.reduce((s, f) => s + f.size, 0);

    const updateGlobalProgress = () => {
      const uploadedAll = Object.values(bytesUploadedRef.current).reduce((s, v) => s + (v || 0), 0);
      setOverallProgress((uploadedAll / totalBytes) * 100);
    };

    try {
      if (mode !== "parallel") {
        // FILES SEQUENTIALLY
        for (let i = 0; i < selectedFiles.length; i++) {
          checkCancel();
          await waitIfPaused();

          const file = selectedFiles[i];
          const onProgressUpdate = (uploaded, total, percent, speedBytes, etaSeconds) => {
            setFileStats(prev => ({
              ...prev,
              [i]: {
                uploadedBytes: uploaded,
                totalBytes: total,
                percent,
                speed: speedBytes / 1024 / 1024,
                eta: etaSeconds
              }
            }));
            updateGlobalProgress();
          };

          try {
            await uploadSingleFile(
              file,
              mode === "sequential" ? "sequential" : "chunk-parallel",
              onProgressUpdate
            );
            success.push({ index: i, fileName: file.name });
          } catch (err) {
            if (err.message === "Upload cancelled") throw err;
            failed.push({ index: i, fileName: file.name, error: err.message });
            break;
          }
        }
      } else {
        // PARALLEL FILE UPLOADS
        const tasks = selectedFiles.map((file, i) => async () => {
          const onProgressUpdate = (uploaded, total, percent, speedBytes, etaSeconds) => {
            setFileStats(prev => ({
              ...prev,
              [i]: {
                uploadedBytes: uploaded,
                totalBytes: total,
                percent,
                speed: speedBytes / 1024 / 1024,
                eta: etaSeconds
              }
            }));
            updateGlobalProgress();
          };

          try {
            await waitIfPaused();
            checkCancel();
            await uploadSingleFile(file, "chunk-parallel", onProgressUpdate);
            success.push({ index: i, fileName: file.name });
          } catch (err) {
            if (err.message === "Upload cancelled") throw err;
            failed.push({ index: i, fileName: file.name, error: err.message });
          }
        });

        await Promise.all(tasks.map(f => f()));
      }
    } catch (err) {
      if (err.message === "Upload cancelled") {
        console.log("Process halted by user.");
      } else {
        console.error("Unexpected error:", err);
      }
    }

    setUploading(false);
    setCurrentMode(null);
    // capture end time
    overallEndRef.current = Date.now();

    // Format results
    if (!isCancelledRef.current) {
      setUploadResults({
        totalFiles: selectedFiles.length,
        successCount: success.length,
        failureCount: failed.length,
        successfulFiles: success,
        failedFiles: failed
      });

      if (failed.length === 0 && success.length > 0) {
        // Attach total upload time in seconds to results
        const finalTotalSeconds = overallStartRef.current && overallEndRef.current ? Math.round((overallEndRef.current - overallStartRef.current) / 1000) : Math.round(elapsedTime);
        setUploadResults(prev => ({ ...prev, totalTimeSeconds: finalTotalSeconds }));
        setTimeout(() => {
          setSelectedFiles([]);
          resetStats();
          const input = document.getElementById('file-input');
          if (input) input.value = "";
          onUploadSuccess && onUploadSuccess();
        }, 800);
      }
    }
  };

  // Keep refreshing global progress UI if needed
  useEffect(() => {
    if (!uploading) return;
    const interval = setInterval(() => {
      const totalBytes = selectedFiles.reduce((s, f) => s + f.size, 0) || 1;
      const uploadedAll = Object.values(bytesUploadedRef.current).reduce((s, v) => s + (v || 0), 0);
      setOverallProgress((uploadedAll / totalBytes) * 100);
    }, 500);
    return () => clearInterval(interval);
  }, [uploading, selectedFiles]);

  // Update elapsed time for overall upload
  useEffect(() => {
    if (!uploading) return;
    const interval = setInterval(() => {
      if (overallStartRef.current) {
        setElapsedTime(Math.round((Date.now() - overallStartRef.current) / 1000));
      }
    }, 500);
    return () => clearInterval(interval);
  }, [uploading]);

  // ===========================================================
  // UI HELPER: BUTTON STYLES
  // ===========================================================
  const getButtonStyle = (btnMode) => {
    if (!uploading) return { opacity: 1, cursor: 'pointer' };
    const isActive = currentMode === btnMode;
    return {
      opacity: isActive ? 1 : 0.4,
      cursor: 'not-allowed',
      border: isActive ? '2px solid #333' : '1px solid transparent',
      transform: isActive ? 'scale(1.05)' : 'scale(1)',
      transition: 'all 0.3s ease'
    };
  };

  // ===========================================================
  // RENDER UI
  // ===========================================================
  return (
    <div className="file-upload-container">

      <div className="upload-area">
        <FaCloudUploadAlt className="upload-icon" />
        <h3>Upload Files</h3>
        <p>Select files and choose an upload mode to compare speeds.</p>

        <input id="file-input"
          type="file"
          multiple
          onChange={handleFileSelect}
          disabled={uploading}
          style={{ display: 'none' }}
        />

        <label htmlFor="file-input" className="btn btn-primary" style={{ opacity: uploading ? 0.5 : 1 }}>
          Choose Files
        </label>

        {selectedFiles.length > 0 && (
          <div className="selected-file">

            <p><strong>Selected:</strong> {selectedFiles.length} file(s)</p>
            <p><strong>Total Size:</strong> {humanFileSize(selectedFiles.reduce((s, f) => s + f.size, 0))}</p>

            {/* Buttons Controls */}
            <div style={{ marginTop: 15, display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
              <p style={{ paddingTop: 10 }}><strong>Upload method:</strong></p>
              <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                <button
                  className="btn btn-success"
                  onClick={() => performUpload("sequential")}
                  disabled={uploading}
                  style={getButtonStyle("sequential")}
                >
                  {uploading && currentMode === "sequential" && "▶ "} Sequential
                </button>

                <button
                  className="btn btn-info"
                  onClick={() => performUpload("seq-chunk")}
                  disabled={uploading}
                  style={{
                    ...getButtonStyle("seq-chunk"),
                    backgroundColor: '#2196f3',
                    color: 'white'
                  }}
                >
                  {uploading && currentMode === "seq-chunk" && "▶ "} Seq - Chunk Parallel (Recommended)
                </button>

                <button
                  className="btn btn-warning"
                  onClick={() => performUpload("parallel")}
                  disabled={uploading}
                  style={{
                    ...getButtonStyle("parallel"),
                    backgroundColor: '#6B6BD0',
                    color: 'white'
                  }}
                >
                  {uploading && currentMode === "parallel" && "▶ "} Parallel
                </button>
              </div>
            </div>

            {/* Pause / Resume / Cancel Controls */}
            {uploading && (
              <div style={{ marginTop: 15, borderTop: '1px solid #eee', paddingTop: 10 }}>
                {!isPaused && (
                  <button className="btn btn-secondary" onClick={handlePause}>
                    ⏸ Pause
                  </button>
                )}

                {isPaused && (
                  <button className="btn btn-primary" onClick={handleResume}>
                    ▶ Resume
                  </button>
                )}

                <button className="btn btn-danger" style={{ marginLeft: 10 }} onClick={handleCancel}>
                  ✖ Cancel
                </button>
              </div>
            )}

            {/* Progress List */}
            <div className="file-list" style={{ marginTop: 15 }}>
              {selectedFiles.map((file, idx) => {
                const stats = fileStats[idx] || {};
                return (
                  <div key={idx} className="file-item">

                    <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                      <div style={{ fontWeight: 500 }}>
                        • {file.name} ({humanFileSize(file.size)})
                      </div>

                      <div style={{ textAlign: 'right' }}>
                        <div style={{ fontWeight: 'bold', color: '#007bff' }}>
                          {stats.percent ? Math.round(stats.percent) : 0}%
                        </div>
                        <div style={{ fontSize: 12, color: '#666' }}>
                          {stats.speed ? `${stats.speed.toFixed(2)} MB/s` : '—'}
                          {' • '}
                          ETA {formatETA(stats.eta)}
                        </div>
                        <div style={{ fontSize: 12, color: '#888' }}>
                          {humanFileSize(stats.uploadedBytes || 0)} / {humanFileSize(stats.totalBytes || file.size)}
                        </div>
                      </div>
                    </div>

                    <div className="progress-bar small" style={{ marginTop: 6, height: '6px' }}>
                      <div className="progress-fill" style={{ width: `${stats.percent || 0}%`, backgroundColor: '#28a745' }} />
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Overall Progress */}
            <div style={{ marginTop: 20, padding: '10px', background: '#f8f9fa', borderRadius: '8px' }}>
              <div style={{ fontWeight: 'bold', marginBottom: '5px' }}>Overall Progress</div>

              <div className="progress-bar" style={{ height: '20px' }}>
                <div className="progress-fill" style={{ width: `${overallProgress}%`, transition: 'width 0.3s ease' }} />
              </div>

              <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6 }}>
                <div>{Math.round(overallProgress)}%</div>
                <div style={{ fontSize: 13 }}>
                  {humanFileSize(Object.values(bytesUploadedRef.current).reduce((s, v) => s + (v || 0), 0))}
                  {' / '}
                  {humanFileSize(selectedFiles.reduce((s, f) => s + f.size, 0) || 0)}
                </div>
              </div>
            </div>

          </div>
        )}

        {/* Uploading Status Text */}
        {uploading && (
          <div style={{ marginTop: 12, textAlign: 'center', color: isPaused ? 'orange' : 'green' }}>
            <strong>{isPaused ? "⏸ Upload Paused" : "Uploading in progress..."}</strong>
            <div style={{ fontSize: 12, marginTop: 6 }}>
              Elapsed: {formatDuration(elapsedTime)}
            </div>
          </div>
        )}

        {/* Upload Results */}
        {uploadResults && (
          <div className="upload-results" style={{ marginTop: 20, padding: '10px', border: '1px solid #ddd', borderRadius: '5px' }}>
            <h4>Upload Results</h4>
            <p className="results-summary">
              {uploadResults.successCount} succeeded, {uploadResults.failureCount} failed
            </p>

            {typeof uploadResults.totalTimeSeconds !== 'undefined' && (
              <div style={{ marginTop: 8, fontSize: 13, color: '#444' }}>
                <strong>Total time:</strong> {formatDuration(uploadResults.totalTimeSeconds)}
              </div>
            )}

            {uploadResults.failedFiles?.length > 0 && (
              <div className="failed-files">
                <h5>Failed Files:</h5>
                {uploadResults.failedFiles.map((file, idx) => (
                  <div key={idx} className="failed-file-item" style={{ color: 'red' }}>
                    <strong>{file.fileName}</strong>: {file.error}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

      </div>
    </div>
  );
}

function formatDuration(seconds) {
  if (!isFinite(seconds) || seconds < 0) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return [h, m, s].map(v => String(v).padStart(2, '0')).join(':');
}