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
export const MAX_DISCOVERY_NAME_CHARS = 80;
export const MAX_DISCOVERY_REPLY_BYTES = 512;
export const UDP_RATE_LIMIT_WINDOW_MS = 1000;
export const UDP_RATE_LIMIT_PER_IP = 4;
export const UDP_RATE_LIMIT_GLOBAL = 30;
export const UDP_RATE_LIMIT_MAX_TRACKED_IPS = 256;
export const DISCOVERY_MTIME_CHECK_INTERVAL_MS = 500;

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

/**
 * Bounded fixed-window rate limiter enforcing both per-source-IP and global caps
 * on UDP discovery replies, with bounded IP tracking memory.
 */
export class UdpRateLimiter {
  constructor({
    windowMs = UDP_RATE_LIMIT_WINDOW_MS,
    maxPerIp = UDP_RATE_LIMIT_PER_IP,
    maxGlobal = UDP_RATE_LIMIT_GLOBAL,
    maxTrackedIps = UDP_RATE_LIMIT_MAX_TRACKED_IPS,
  } = {}) {
    this.windowMs = windowMs;
    this.maxPerIp = maxPerIp;
    this.maxGlobal = maxGlobal;
    this.maxTrackedIps = maxTrackedIps;
    this.globalWindowStart = 0;
    this.globalCount = 0;
    this.perIp = new Map();
  }

  allow(sourceIp, nowMs = Date.now()) {
    const key = String(sourceIp || '').trim().toLowerCase();
    if (!key) return false;

    if (nowMs - this.globalWindowStart >= this.windowMs) {
      this.globalWindowStart = nowMs;
      this.globalCount = 0;
      this.perIp.clear();
    }

    if (this.globalCount >= this.maxGlobal) {
      return false;
    }

    let bucket = this.perIp.get(key);
    if (!bucket || nowMs - bucket.windowStart >= this.windowMs) {
      if (!bucket && this.perIp.size >= this.maxTrackedIps) {
        for (const [k, b] of this.perIp.entries()) {
          if (nowMs - b.windowStart >= this.windowMs) {
            this.perIp.delete(k);
          }
        }
        if (this.perIp.size >= this.maxTrackedIps) {
          return false;
        }
      }
      bucket = { windowStart: nowMs, count: 0 };
      this.perIp.set(key, bucket);
    }

    if (bucket.count >= this.maxPerIp) {
      return false;
    }

    bucket.count += 1;
    this.globalCount += 1;
    return true;
  }
}

function truncateChars(str, maxChars) {
  const chars = Array.from(String(str || ''));
  if (chars.length <= maxChars) return chars.join('');
  if (maxChars <= 1) return chars.slice(0, Math.max(0, maxChars)).join('');
  return `${chars.slice(0, maxChars - 1).join('')}…`;
}

export function buildDiscoveryReplyFromManifest(manifest, httpPort, locale = null) {
  const rawName = String(manifest.name || manifest.id);
  const translated = rawName.startsWith('$t:') && locale
    ? locale.translate(rawName.slice(3))
    : rawName;
  const baseName = translated || String(manifest.id);
  let name = truncateChars(baseName, MAX_DISCOVERY_NAME_CHARS);
  const reply = {
    name,
    id: String(manifest.id),
    version: String(manifest.version),
    port: httpPort,
  };

  // Enforce strict UTF-8 byte cap on the entire JSON payload by trimming `name` if needed.
  let encoded = Buffer.from(JSON.stringify(reply), 'utf8');
  while (encoded.length > MAX_DISCOVERY_REPLY_BYTES && name.length > 0) {
    const chars = Array.from(name);
    const nextLen = Math.max(0, Math.floor(chars.length * 0.75) - 1);
    name = nextLen > 0 ? truncateChars(chars.slice(0, nextLen).join(''), nextLen) : '';
    reply.name = name;
    encoded = Buffer.from(JSON.stringify(reply), 'utf8');
    if (name.length === 0) break;
  }

  // Strict cap invariant: if even with empty `name` the payload exceeds MAX_DISCOVERY_REPLY_BYTES
  // (e.g. an excessively long `id` or `version`), return null so the server never sends an oversized reply.
  if (encoded.length > MAX_DISCOVERY_REPLY_BYTES) {
    return null;
  }
  return reply;
}

export function buildDiscoveryReply(pluginDir, httpPort) {
  const manifestPath = path.join(pluginDir, 'manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const check = validateManifest(manifest);
  if (!check.valid) {
    throw new Error(`Invalid manifest.json: ${check.errors.join('; ')}`);
  }
  const locale = PluginLocale.fromDir(pluginDir, 'zh-CN');
  return buildDiscoveryReplyFromManifest(manifest, httpPort, locale);
}

function encodeDiscoveryReply(reply) {
  if (!reply) return null;
  const buf = Buffer.from(JSON.stringify(reply), 'utf8');
  return buf.length <= MAX_DISCOVERY_REPLY_BYTES ? buf : null;
}

async function readDiscoveryReplyAsync(pluginDir, httpPort) {
  const manifestPath = path.join(pluginDir, 'manifest.json');
  const raw = await fs.promises.readFile(manifestPath, 'utf8');
  const manifest = JSON.parse(raw);
  const check = validateManifest(manifest);
  if (!check.valid) {
    throw new Error(`Invalid manifest.json: ${check.errors.join('; ')}`);
  }
  let locale = null;
  if (String(manifest.name || '').startsWith('$t:')) {
    const localesDir = path.join(pluginDir, 'locales');
    const strings = {};
    for (const fileName of ['en.json', 'zh-CN.json']) {
      try {
        const text = await fs.promises.readFile(path.join(localesDir, fileName), 'utf8');
        const parsed = JSON.parse(text);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          Object.assign(strings, parsed);
        }
      } catch {
        // Optional locale file missing or mid-edit
      }
    }
    locale = new PluginLocale(strings);
  }
  return buildDiscoveryReplyFromManifest(manifest, httpPort, locale);
}

function startUdpDiscoveryResponder(getCachedPayload, onProbeAccepted, opts) {
  const requestedPort = opts.discoverPort ?? DEV_DISCOVERY_PORT;
  const allowLoopback = Boolean(opts.allowLoopbackDiscoveryForTesting);
  const bindHost = opts.discoverHost || '0.0.0.0';
  const rateLimiter = new UdpRateLimiter({
    windowMs: opts.discoverRateWindowMs ?? UDP_RATE_LIMIT_WINDOW_MS,
    maxPerIp: opts.discoverMaxPerIp ?? UDP_RATE_LIMIT_PER_IP,
    maxGlobal: opts.discoverMaxGlobal ?? UDP_RATE_LIMIT_GLOBAL,
  });

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

    // Zero synchronous disk I/O per packet: strictly serves pre-cached Buffer under rate limits
    // and triggers throttled async mtime cache validation in the background.
    udp.on('message', (msg, rinfo) => {
      if (msg.length > 128) return;
      const text = msg.toString('utf8').trim();
      if (text !== DEV_DISCOVERY_PROBE) return;
      if (!isPrivateDiscoveryAddress(rinfo.address, allowLoopback)) return;
      if (!rateLimiter.allow(rinfo.address)) return;
      if (onProbeAccepted) onProbeAccepted();
      const payload = getCachedPayload();
      if (!payload || payload.length === 0 || payload.length > MAX_DISCOVERY_REPLY_BYTES) return;
      udp.send(payload, rinfo.port, rinfo.address, () => {});
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
  const discoverRefreshIntervalMs = opts.discoverRefreshIntervalMs ?? DISCOVERY_MTIME_CHECK_INTERVAL_MS;
  const discoverWatch = opts.discoverWatch !== false;

  const manifestPath = path.join(absDir, 'manifest.json');
  const localesDir = path.join(absDir, 'locales');

  let cachedDiscoveryPayload = null;
  let lastDiskSignature = '';
  let lastMtimeCheckMs = 0;
  let refreshInFlight = false;
  let closed = false;

  const computeDiskSignatureSync = () => {
    try {
      const mStat = fs.statSync(manifestPath);
      let locSig = '';
      try {
        const lStat = fs.statSync(localesDir);
        locSig = `${lStat.mtimeMs}`;
      } catch {
        // locales dir is optional
      }
      return `${mStat.mtimeMs}:${mStat.size}:${locSig}`;
    } catch {
      return '';
    }
  };

  const computeDiskSignatureAsync = async () => {
    const mStat = await fs.promises.stat(manifestPath);
    let locSig = '';
    try {
      const lStat = await fs.promises.stat(localesDir);
      locSig = `${lStat.mtimeMs}`;
    } catch {
      // locales dir is optional
    }
    return `${mStat.mtimeMs}:${mStat.size}:${locSig}`;
  };

  const updateCachedDiscoveryFromDisk = (httpPort) => {
    try {
      const sig = computeDiskSignatureSync();
      const reply = buildDiscoveryReply(absDir, httpPort);
      cachedDiscoveryPayload = encodeDiscoveryReply(reply);
      if (sig) lastDiskSignature = sig;
    } catch {
      // Leave previous valid cache intact if manifest is missing or mid-edit
    }
  };
  const updateCachedDiscoveryFromManifest = (manifest, httpPort) => {
    try {
      const locale = String(manifest.name || '').startsWith('$t:')
        ? PluginLocale.fromDir(absDir, 'zh-CN')
        : null;
      const reply = buildDiscoveryReplyFromManifest(manifest, httpPort, locale);
      cachedDiscoveryPayload = encodeDiscoveryReply(reply);
      const sig = computeDiskSignatureSync();
      if (sig) lastDiskSignature = sig;
    } catch {
      // Ignore cache refresh errors
    }
  };

  const maybeRefreshDiscoveryCacheAsync = (force = false) => {
    if (closed || refreshInFlight) return;
    const now = Date.now();
    if (!force && now - lastMtimeCheckMs < discoverRefreshIntervalMs) return;
    lastMtimeCheckMs = now;
    refreshInFlight = true;
    (async () => {
      try {
        const sig = await computeDiskSignatureAsync();
        if (!sig || (!force && sig === lastDiskSignature)) return;
        const reply = await readDiscoveryReplyAsync(absDir, boundHttpPort);
        if (!closed) {
          cachedDiscoveryPayload = encodeDiscoveryReply(reply);
          lastDiskSignature = sig;
        }
      } catch {
        // Preserve previous valid cache if manifest was temporarily missing or mid-edit
      } finally {
        refreshInFlight = false;
      }
    })();
  };

  let boundHttpPort = port;

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
      updateCachedDiscoveryFromManifest(packed.manifest, boundHttpPort);
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
  boundHttpPort = httpInfo.port;

  let udpSocket = null;
  let actualDiscoverPort = null;
  let discoverError = null;
  let dirWatcher = null;
  let watchDebounceTimer = null;
  if (discoverEnabled) {
    // Precompute discovery reply ONCE at startup so UDP probes never perform sync disk reads.
    updateCachedDiscoveryFromDisk(httpInfo.port);
    const udpRes = await startUdpDiscoveryResponder(
      () => cachedDiscoveryPayload,
      () => maybeRefreshDiscoveryCacheAsync(false),
      opts,
    );
    udpSocket = udpRes.socket;
    actualDiscoverPort = udpRes.port;
    discoverError = udpRes.error;
    if (udpSocket && discoverWatch) {
      try {
        dirWatcher = fs.watch(absDir, (_eventType, filename) => {
          if (closed) return;
          if (filename && filename !== 'manifest.json' && !String(filename).startsWith('locales')) {
            return;
          }
          if (watchDebounceTimer) clearTimeout(watchDebounceTimer);
          watchDebounceTimer = setTimeout(() => {
            watchDebounceTimer = null;
            maybeRefreshDiscoveryCacheAsync(true);
          }, 50);
          if (typeof watchDebounceTimer.unref === 'function') {
            watchDebounceTimer.unref();
          }
        });
        if (typeof dirWatcher.unref === 'function') {
          dirWatcher.unref();
        }
        dirWatcher.on('error', () => {});
      } catch {
        dirWatcher = null;
      }
    }
  }

  return {
    server,
    port: httpInfo.port,
    host: httpInfo.host,
    discoverEnabled: Boolean(udpSocket),
    discoverPort: actualDiscoverPort,
    discoverError,
    close: async () => {
      closed = true;
      if (watchDebounceTimer) {
        clearTimeout(watchDebounceTimer);
        watchDebounceTimer = null;
      }
      if (dirWatcher) {
        try { dirWatcher.close(); } catch {}
        dirWatcher = null;
      }
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
