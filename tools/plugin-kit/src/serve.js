import http from 'node:http';
import path from 'node:path';
import { packPluginToBuffer } from './pack.js';

export function startDevServer(pluginDir, opts = {}) {
  const absDir = path.resolve(pluginDir);
  const host = opts.host || '0.0.0.0';
  const port = opts.port ?? 8787;

  const server = http.createServer(async (req, res) => {
    const reqUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    try {
      if (reqUrl.pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true }));
        return;
      }

      const packed = await packPluginToBuffer(absDir);
      const baseOrigin = `http://${req.headers.host || `127.0.0.1:${server.address().port}`}`;

      if (reqUrl.pathname === '/manifest.json') {
        res.writeHead(200, {
          'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store',
        });
        res.end(JSON.stringify(packed.manifest, null, 2));
        return;
      }

      if (reqUrl.pathname === '/plugin.d3plugin') {
        res.writeHead(200, {
          'Content-Type': 'application/octet-stream',
          'Cache-Control': 'no-store',
          'X-Depth3d-Sha256': packed.sha256,
        });
        res.end(packed.buffer);
        return;
      }

      if (reqUrl.pathname === '/index.json' || reqUrl.pathname === '/') {
        const index = {
          name: `${packed.manifest.name}（本地开发）`,
          plugins: [
            {
              id: packed.manifest.id,
              name: packed.manifest.name,
              version: packed.manifest.version,
              description: packed.manifest.description || '',
              url: `${baseOrigin}/plugin.d3plugin`,
              sha256: packed.sha256,
              ...(packed.manifest.minApp ? { minApp: packed.manifest.minApp } : {}),
            },
          ],
        };
        res.writeHead(200, {
          'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store',
        });
        res.end(JSON.stringify(index, null, 2));
        return;
      }

      res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'Not found' }));
    } catch (err) {
      res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: err.message }));
    }
  });

  return new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(port, host, () => {
      const addr = server.address();
      resolve({
        server,
        port: addr.port,
        host: addr.address,
        close: () => new Promise((r) => server.close(() => r())),
      });
    });
  });
}
