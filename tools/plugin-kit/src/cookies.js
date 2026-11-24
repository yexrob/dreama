import fs from 'node:fs';
import path from 'node:path';
import { canonicalizeHost } from './network-policy.js';

/** §11.1 (S5): maximum total serialized cookie jar size is 256 KB. */
export const MAX_COOKIE_JAR_BYTES = 256 * 1024;

export class CookieJarQuotaExceededError extends Error {
  constructor(message) {
    super(message);
    this.name = 'CookieJarQuotaExceededError';
  }
}

export class PluginCookieJar {
  constructor(clockMs = () => Date.now(), opts = {}) {
    this.clockMs = clockMs;
    this.maxBytes = opts.maxBytes ?? MAX_COOKIE_JAR_BYTES;
    this.filePath = opts.filePath ?? null;
    this.store = new Map();
    this.dirty = false;
    this.diskWriteCount = 0;

    if (this.filePath && fs.existsSync(this.filePath)) {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'));
      if (Array.isArray(parsed)) {
        for (const c of parsed) {
          if (c && c.name && c.domain && c.path) {
            this.store.set(`${c.name}\0${c.domain}\0${c.path}`, c);
          }
        }
      }
    }
  }

  setCookie(rawUrl, setCookieHeader) {
    const url = new URL(rawUrl);
    const reqHost = canonicalizeHost(url.hostname);
    const parts = String(setCookieHeader).split(';').map((s) => s.trim()).filter(Boolean);
    if (parts.length === 0) throw new Error('Invalid Set-Cookie header');
    const eq = parts[0].indexOf('=');
    if (eq <= 0) throw new Error('Invalid cookie name=value');
    const name = parts[0].slice(0, eq).trim();
    const value = parts[0].slice(eq + 1).trim();

    let domain = reqHost;
    let hostOnly = true;
    let cookiePath = this._defaultPath(url.pathname);
    let secure = false;
    let expiresAt = Number.MAX_SAFE_INTEGER;

    for (const attr of parts.slice(1)) {
      const aeq = attr.indexOf('=');
      const k = (aeq >= 0 ? attr.slice(0, aeq) : attr).trim().toLowerCase();
      const v = aeq >= 0 ? attr.slice(aeq + 1).trim() : '';
      if (k === 'domain' && v) {
        const d = canonicalizeHost(v.replace(/^\.+/, ''));
        if (!d) continue;
        if (reqHost === d || reqHost.endsWith(`.${d}`)) {
          domain = d;
          hostOnly = false;
        } else {
          // RFC 6265 §5.3: reject cookie whose Domain does not domain-match request host
          return;
        }
      } else if (k === 'path' && v.startsWith('/')) {
        cookiePath = v;
      } else if (k === 'secure') {
        secure = true;
      } else if (k === 'max-age') {
        const sec = Number(v);
        if (Number.isFinite(sec)) expiresAt = sec <= 0 ? 0 : this.clockMs() + sec * 1000;
      } else if (k === 'expires') {
        const ts = Date.parse(v);
        if (Number.isFinite(ts)) expiresAt = ts;
      }
    }

    const now = this.clockMs();
    const key = `${name}\0${domain}\0${cookiePath}`;
    if (expiresAt <= now) {
      if (this.store.delete(key)) {
        this.dirty = true;
      }
      return;
    }

    const prev = this.store.get(key);
    const next = { name, value, domain, hostOnly, path: cookiePath, secure, expiresAt };
    this.store.set(key, next);
    const size = this._serializedByteLength();
    if (size > this.maxBytes) {
      // Prune expired cookies once and re-check
      this._pruneExpired(now);
      if (this._serializedByteLength() > this.maxBytes) {
        if (prev !== undefined) this.store.set(key, prev);
        else this.store.delete(key);
        throw new CookieJarQuotaExceededError(
          `Cookie 存储超过上限（${Math.round(this.maxBytes / 1024)} KB）`,
        );
      }
    }
    this.dirty = true;
  }

  saveFromHeaders(rawUrl, setCookieValues) {
    for (const hdr of setCookieValues || []) {
      try {
        this.setCookie(rawUrl, hdr);
      } catch (e) {
        if (e instanceof CookieJarQuotaExceededError) throw e;
        // ignore malformed cookie from remote server
      }
    }
  }

  getHeaderForUrl(rawUrl) {
    const url = new URL(rawUrl);
    const now = this.clockMs();
    const host = canonicalizeHost(url.hostname);
    const matched = [];
    for (const [key, c] of this.store.entries()) {
      if (c.expiresAt <= now) {
        this.store.delete(key);
        this.dirty = true;
        continue;
      }
      if (c.secure && url.protocol !== 'https:') continue;
      const domainMatch = c.hostOnly
        ? host === c.domain
        : (host === c.domain || host.endsWith(`.${c.domain}`));
      if (!domainMatch) continue;
      const pathMatch = url.pathname === c.path ||
        (url.pathname.startsWith(c.path) && (c.path.endsWith('/') || url.pathname[c.path.length] === '/'));
      if (!pathMatch) continue;
      matched.push(`${c.name}=${c.value}`);
    }
    return matched.join('; ');
  }

  clear() {
    this.store.clear();
    this.dirty = true;
  }

  /** Coalesced persistence (§11.1, S5): flush dirty state once per call end. */
  flush() {
    if (!this.dirty || !this.filePath) return;
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    fs.writeFileSync(this.filePath, JSON.stringify([...this.store.values()]), 'utf8');
    this.dirty = false;
    this.diskWriteCount += 1;
  }

  _pruneExpired(now) {
    for (const [key, c] of this.store.entries()) {
      if (c.expiresAt <= now) {
        this.store.delete(key);
        this.dirty = true;
      }
    }
  }

  _serializedByteLength() {
    let bytes = 2; // []
    for (const c of this.store.values()) {
      bytes += 80 + Buffer.byteLength(c.name, 'utf8') + Buffer.byteLength(c.value, 'utf8') +
        Buffer.byteLength(c.domain, 'utf8') + Buffer.byteLength(c.path, 'utf8');
    }
    return bytes;
  }

  _defaultPath(pathname) {
    if (!pathname || !pathname.startsWith('/')) return '/';
    const idx = pathname.lastIndexOf('/');
    return idx <= 0 ? '/' : pathname.slice(0, idx);
  }

  /**
   * §11.1 (S9): Merges jar cookies with an explicit `Cookie` request header.
   * On name collision, the explicit header's value wins.
   */
  static mergeCookieHeaders(jarHeader, explicitHeader) {
    const merged = new Map();
    const parseInto = (raw) => {
      if (!raw || !String(raw).trim()) return;
      for (const pair of String(raw).split(';')) {
        const trimmed = pair.trim();
        if (!trimmed) continue;
        const eq = trimmed.indexOf('=');
        if (eq <= 0) continue;
        const k = trimmed.slice(0, eq).trim();
        const v = trimmed.slice(eq + 1).trim();
        if (k) merged.set(k, v);
      }
    };
    parseInto(jarHeader);
    parseInto(explicitHeader);
    return [...merged.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  }
}
