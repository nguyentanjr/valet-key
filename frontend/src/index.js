import React from 'react';
import ReactDOM from 'react-dom/client';
import './App.css';
import App from './App';

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
  // StrictMode disabled to prevent duplicate API calls in development
  // In development, StrictMode runs effects twice, causing duplicate requests
  // which get distributed across different backends by the load balancer
  <App />
);

