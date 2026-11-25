import dgram from 'node:dgram';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { PluginLocale } from './locale.js';
import { isAlwaysBlockedBytes, parseAnyIpLiteral } from './network-policy.js';
import { packPluginToBuffer } from './pack.js';
import { validateManifest } from './schema.js';

export const DEV_DISCOVERY_PORT = 18787;
export const DEV_DISCOVERY_PROBE = 'depth3d-plugin-dev-discover-v1';

/**
 * Returns true strictly for RFC1918 IPv4 (10/8, 172.16/12, 192.168/16) and
 * IPv6 ULA (fc00::/7). Loopback, link-local, public, and reserved addresses
 * are rejected (loopback may be explicitly opted in for local unit tests only).
 */
export function isPrivateDiscoveryAddress(rawAddress, allowLoopbackForTesting = false) {
  let addr = String(rawAddress || '').trim().toLowerCase();
  if (!addr) return false;
  if (addr.startsWith('[') && addr.endsWith(']')) {
    addr = addr.slice(1, -1);
  }
  const zoneIdx = addr.indexOf('%');
  if (zoneIdx >= 0) addr = addr.slice(0, zoneIdx);
  // Node dual-stack UDP sockets may report IPv4 senders as ::ffff:a.b.c.d
  if (addr.startsWith('::ffff:') && addr.slice(7).includes('.')) {
    addr = addr.slice(7);
  }
  const bytes = parseAnyIpLiteral(addr);
  if (!bytes) return false;
  if (bytes.length === 4) {
    const [b0, b1] = bytes;
    if (b0 === 127) return Boolean(allowLoopbackForTesting);
    if (isAlwaysBlockedBytes(bytes)) return false;
    return b0 === 10 || (b0 === 172 && b1 >= 16 && b1 <= 31) || (b0 === 192 && b1 === 168);
  }
  if (bytes.length === 16) {
    let first15Zero = true;
    for (let i = 0; i < 15; i++) {
      if (bytes[i] !== 0) {
        first15Zero = false;
        break;
      }
    }
    if (first15Zero && bytes[15] === 1) return Boolean(allowLoopbackForTesting);
    if (isAlwaysBlockedBytes(bytes)) return false;
    return (bytes[0] & 0xFE) === 0xFC;
  }
  return false;
}

export function listLanIpv4Addresses() {
  const ifaces = os.networkInterfaces();
  const found = [];
  for (const entries of Object.values(ifaces)) {
    for (const info of entries || []) {
      if (!info || info.internal || info.family !== 'IPv4') continue;
      if (isPrivateDiscoveryAddress(info.address, false)) {
        found.push(info.address);
      }
    }
  }
  return [...new Set(found)];
}

export function buildDiscoveryReply(pluginDir, httpPort) {
  const manifestPath = path.join(pluginDir, 'manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const check = validateManifest(manifest);
  if (!check.valid) {
    throw new Error(`Invalid manifest.json: ${check.errors.join('; ')}`);
  }
  const locale = PluginLocale.fromDir(pluginDir, 'zh-CN');
  const rawName = String(manifest.name || manifest.id);
  const displayName = rawName.startsWith('$t:')
    ? locale.translate(rawName.slice(3))
    : rawName;
  return {
    name: displayName || manifest.id,
    id: manifest.id,
    version: manifest.version,
    port: httpPort,
  };
}

function startUdpDiscoveryResponder(absDir, httpPort, opts) {
  const requestedPort = opts.discoverPort ?? DEV_DISCOVERY_PORT;
  const allowLoopback = Boolean(opts.allowLoopbackDiscoveryForTesting);
  const bindHost = opts.discoverHost || '0.0.0.0';

  return new Promise((resolve) => {
    const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    let settled = false;

    udp.on('error', (err) => {
      if (!settled) {
        settled = true;
        try { udp.close(); } catch {}
        resolve({ socket: null, port: null, error: err });
      }
    });

    udp.on('message', (msg, rinfo) => {
      const text = msg.toString('utf8').trim();
      if (text !== DEV_DISCOVERY_PROBE) return;
      if (!isPrivateDiscoveryAddress(rinfo.address, allowLoopback)) return;
      try {
        const reply = buildDiscoveryReply(absDir, httpPort);
        const payload = Buffer.from(JSON.stringify(reply), 'utf8');
        udp.send(payload, rinfo.port, rinfo.address, () => {});
      } catch {
        // Manifest may be mid-edit; skip replying rather than crashing the dev server
      }
    });

    udp.bind(requestedPort, bindHost, () => {
      if (!settled) {
        settled = true;
        const addr = udp.address();
        resolve({ socket: udp, port: addr.port, error: null });
      }
    });
  });
}

export async function startDevServer(pluginDir, opts = {}) {
  const absDir = path.resolve(pluginDir);
  const host = opts.host || '0.0.0.0';
  const port = opts.port ?? 8787;
  const discoverEnabled = opts.discover !== false;

  const server = http.createServer(async (req, res) => {
    const reqUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    try {
      if (reqUrl.pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true }));
        return;
      }

      if (
        reqUrl.pathname !== '/' &&
        reqUrl.pathname !== '/index.json' &&
        reqUrl.pathname !== '/manifest.json' &&
        reqUrl.pathname !== '/plugin.d3plugin'
      ) {
        res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'Not found' }));
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
    } catch (err) {
      res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: err.message }));
    }
  });

  const httpInfo = await new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(port, host, () => {
      const addr = server.address();
      resolve({ port: addr.port, host: addr.address });
    });
  });

  let udpSocket = null;
  let actualDiscoverPort = null;
  let discoverError = null;
  if (discoverEnabled) {
    const udpRes = await startUdpDiscoveryResponder(absDir, httpInfo.port, opts);
    udpSocket = udpRes.socket;
    actualDiscoverPort = udpRes.port;
    discoverError = udpRes.error;
  }

  return {
    server,
    port: httpInfo.port,
    host: httpInfo.host,
    discoverEnabled: Boolean(udpSocket),
    discoverPort: actualDiscoverPort,
    discoverError,
    close: async () => {
      if (udpSocket) {
        await new Promise((r) => {
          try {
            udpSocket.close(() => r());
          } catch {
            r();
          }
        });
        udpSocket = null;
      }
      await new Promise((r) => server.close(() => r()));
    },
  };
}
