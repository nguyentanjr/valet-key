const { createProxyMiddleware } = require('http-proxy-middleware');

module.exports = function(app) {
  const proxyOptions = {
    target: 'http://localhost:80', // ✅ Proxy đến Nginx (load balancer) thay vì backend trực tiếp
    changeOrigin: true,
    cookieDomainRewrite: 'localhost',
    cookiePathRewrite: '/',
    onProxyRes: function(proxyRes, req, res) {
      // Forward Set-Cookie headers from backend
      if (proxyRes.headers['set-cookie']) {
        proxyRes.headers['set-cookie'] = proxyRes.headers['set-cookie'].map(cookie => {
          // Ensure cookie domain is set correctly
          return cookie.replace(/Domain=[^;]+/gi, 'Domain=localhost');
        });
      }
      
      // ✅ Disable caching for API requests
      res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
      res.setHeader('Pragma', 'no-cache');
      res.setHeader('Expires', '0');
    },
    onProxyReq: function(proxyReq, req, res) {
      // Forward cookies from client to backend
      if (req.headers.cookie) {
        proxyReq.setHeader('Cookie', req.headers.cookie);
      }
    }
  };

  app.use('/api', createProxyMiddleware(proxyOptions));
  app.use('/login', createProxyMiddleware(proxyOptions));
  app.use('/logout', createProxyMiddleware(proxyOptions));
  app.use('/user', createProxyMiddleware(proxyOptions));
  app.use('/admin', createProxyMiddleware(proxyOptions));
  app.use('/debug', createProxyMiddleware(proxyOptions));
};


