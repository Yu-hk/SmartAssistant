// 纯 Node 内置模块：静态托管 frontend/dist + 反向代理 /api、/assistant/api 到网关 8081
// 零 npm 依赖，避免 rollup/vite 原生二进制问题。
const http = require('http');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const PORT = process.env.UI_PORT || 5173;
const DIST = path.join(__dirname, 'dist');
const GATEWAY = { host: '127.0.0.1', port: 8081 };

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
};

function isProxy(pathname) {
  return pathname.startsWith('/api') || pathname.startsWith('/assistant/api');
}

function proxy(req, res, pathname, search) {
  const options = {
    host: GATEWAY.host,
    port: GATEWAY.port,
    method: req.method,
    path: pathname + search,
    headers: { ...req.headers, host: `${GATEWAY.host}:${GATEWAY.port}` },
  };
  const p = http.request(options, (proxyRes) => {
    res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
    proxyRes.pipe(res);
  });
  p.on('error', (e) => {
    console.error('[proxy error]', e.message);
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ code: 502, message: 'gateway unreachable: ' + e.message }));
  });
  req.pipe(p);
}

function serveStatic(req, res, pathname) {
  // 默认首页
  let rel = decodeURIComponent(pathname);
  if (rel === '/' || rel === '') rel = '/index.html';
  // 防目录穿越
  const safe = path.normalize(rel).replace(/^(\.\.[/\\])+/, '');
  let filePath = path.join(DIST, safe);
  if (!filePath.startsWith(DIST)) {
    res.writeHead(403); res.end('forbidden'); return;
  }
  fs.stat(filePath, (err, stat) => {
    if (err || !stat.isFile()) {
      // SPA 回退：无扩展名请求返回 index.html
      if (!path.extname(safe)) {
        filePath = path.join(DIST, 'index.html');
      } else {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('404 Not Found'); return;
      }
    }
    fs.readFile(filePath, (e, data) => {
      if (e) { res.writeHead(500); res.end('read error'); return; }
      const ext = path.extname(filePath).toLowerCase();
      res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
      res.end(data);
    });
  });
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = u.pathname;
  const search = u.search || '';
  if (isProxy(pathname)) {
    proxy(req, res, pathname, search);
  } else {
    serveStatic(req, res, pathname);
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[serve-ui] UI  → http://localhost:${PORT}  (静态: ${DIST})`);
  console.log(`[serve-ui] API → 代理 /api,/assistant/api → http://${GATEWAY.host}:${GATEWAY.port}`);
});
