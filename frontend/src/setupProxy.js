const { createProxyMiddleware } = require('http-proxy-middleware');

module.exports = function(app) {
  const proxyOptions = {
    target: 'http://localhost:8080',
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


