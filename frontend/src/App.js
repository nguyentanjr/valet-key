import React, { useState, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import Login from './components/Login';
import Dashboard from './components/Dashboard';
import PublicFile from './components/PublicFile';
import { authAPI } from './services/api';
import './App.css';

function App() {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Check if user is already logged in
    console.log('🚀 [App] useEffect triggered - checking auth on mount/refresh');
    checkAuth();
  }, []);

  const checkAuth = async () => {
    console.log('🔍 [App] checkAuth() called - checking authentication...');
    try {
      const response = await authAPI.getCurrentUser();
      console.log('✅ [App] getCurrentUser() response:', response.data);
      
      if (response.data) {
        console.log('✅ [App] User authenticated, setting user state:', response.data);
        setUser(response.data);
      } else {
        console.log('⚠️ [App] No user data in response, setting user to null');
        setUser(null);
      }
    } catch (err) {
      // Not logged in or session expired
      // This is normal when user hasn't logged in yet or session expired
      console.log('❌ [App] checkAuth failed (not logged in or session expired):', err.message);
      setUser(null);
    } finally {
      setLoading(false);
      console.log('🏁 [App] checkAuth() completed, loading=false');
    }
  };

  const handleLogin = (userData) => {
    setUser(userData);
  };

  const handleLogout = async () => {
    try {
      await authAPI.logout();
    } catch (err) {
      // Ignore error
    } finally {
      setUser(null);
    }
  };

  if (loading) {
    return (
      <div className="loading">
        <div className="loading-spinner"></div>
        <p>Loading...</p>
      </div>
    );
  }

  return (
    <BrowserRouter>
      <Routes>
        {/* Public file sharing route */}
        <Route path="/public/:token" element={<PublicFile />} />

        {/* Main application routes */}
        <Route
          path="/*"
          element={
            user ? (
              <Dashboard user={user} onLogout={handleLogout} />
            ) : (
              <Login onLoginSuccess={handleLogin} />
            )
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;

