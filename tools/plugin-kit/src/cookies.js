import fs from 'node:fs';
import path from 'node:path';
import { canonicalizeHost, parseAnyIpLiteral } from './network-policy.js';

/** §11.1 (S5): maximum total serialized cookie jar size is 256 KB. */
export const MAX_COOKIE_JAR_BYTES = 256 * 1024;
const MAX_COOKIE_DATE = 253_402_300_799_999;

const MULTI_LABEL_PUBLIC_SUFFIXES = new Set([
  'co.uk', 'org.uk', 'ac.uk', 'gov.uk', 'me.uk', 'ltd.uk', 'net.uk',
  'com.cn', 'net.cn', 'org.cn', 'gov.cn', 'edu.cn', 'ac.cn',
  'com.au', 'net.au', 'org.au', 'edu.au', 'gov.au',
  'co.jp', 'ne.jp', 'or.jp', 'ac.jp', 'go.jp',
  'com.hk', 'org.hk', 'edu.hk', 'gov.hk',
  'com.tw', 'org.tw', 'edu.tw', 'gov.tw',
  'com.sg', 'com.br', 'com.mx', 'co.kr', 'co.nz', 'co.in',
  'github.io', 'pages.dev', 'vercel.app', 'netlify.app', 'herokuapp.com',
]);

export function isPublicSuffix(canonicalDomain) {
  if (!canonicalDomain) return true;
  if (parseAnyIpLiteral(canonicalDomain)) return false;
  if (!canonicalDomain.includes('.')) return true;
  return MULTI_LABEL_PUBLIC_SUFFIXES.has(canonicalDomain);
}

export function domainMatches(canonicalHost, canonicalDomain) {
  if (canonicalHost === canonicalDomain) return true;
  if (parseAnyIpLiteral(canonicalHost)) return false;
  return canonicalHost.endsWith(`.${canonicalDomain}`);
}

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
    this.onWarning = opts.onWarning ?? (() => {});
    this.store = new Map();
    this.dirty = false;
    this.diskWriteCount = 0;
    this.estimatedBytes = 2; // "[]"

    if (this.filePath && fs.existsSync(this.filePath)) {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'));
      if (Array.isArray(parsed)) {
        for (const c of parsed) {
          if (c && c.name && c.domain && c.path) {
            const norm = { ...c, domain: canonicalizeHost(c.domain) };
            this.store.set(`${norm.name}\0${norm.domain}\0${norm.path}`, norm);
          }
        }
        this.estimatedBytes = this._exactSerializedBytes();
      }
    }
  }

  setCookie(rawUrl, setCookieHeader, strictSyntax = true) {
    const url = new URL(rawUrl);
    const reqHost = canonicalizeHost(url.hostname);
    const reqIsIp = Boolean(parseAnyIpLiteral(reqHost));
    const parts = String(setCookieHeader).trim().split(';').map((s) => s.trim()).filter(Boolean);
    if (parts.length === 0) {
      if (strictSyntax) throw new Error('Invalid Set-Cookie header');
      return;
    }
    const eq = parts[0].indexOf('=');
    if (eq <= 0) {
      if (strictSyntax) throw new Error('Invalid cookie name=value');
      return;
    }
    const name = parts[0].slice(0, eq).trim();
    const value = parts[0].slice(eq + 1).trim();
    if (!name) {
      if (strictSyntax) throw new Error('Invalid cookie name=value');
      return;
    }

    let domain = reqHost;
    let hostOnly = true;
    let cookiePath = this._defaultPath(url.pathname);
    let secure = false;
    let httpOnly = false;
    let hasMaxAge = false;
    let maxAgeExpiresAt = null;
    let parsedExpiresAt = null;

    for (const attr of parts.slice(1)) {
      const aeq = attr.indexOf('=');
      const k = (aeq >= 0 ? attr.slice(0, aeq) : attr).trim().toLowerCase();
      const v = aeq >= 0 ? attr.slice(aeq + 1).trim() : '';
      if (k === 'domain' && v) {
        const d = canonicalizeHost(v.replace(/^\.+/, ''));
        if (!d) continue;
        if (isPublicSuffix(d)) {
          this.onWarning(`Cookie '${name}' 的 Domain='${v}' 属于公共后缀，已忽略`);
          return;
        }
        if (reqIsIp || parseAnyIpLiteral(d)) {
          if (reqHost === d) {
            domain = reqHost;
            hostOnly = true;
          } else {
            this.onWarning(`Cookie '${name}' 的 Domain='${v}' 与请求 IP 主机 '${reqHost}' 不匹配，已忽略`);
            return;
          }
        } else if (domainMatches(reqHost, d)) {
          domain = d;
          hostOnly = false;
        } else {
          // RFC 6265 §5.3: ignore cookie whose Domain does not domain-match request host and log WARN
          this.onWarning(`Cookie '${name}' 的 Domain='${v}' 与请求主机 '${reqHost}' 不匹配，已忽略`);
          return;
        }
      } else if (k === 'path' && v.startsWith('/')) {
        cookiePath = v;
      } else if (k === 'secure') {
        secure = true;
      } else if (k === 'httponly') {
        httpOnly = true;
      } else if (k === 'max-age') {
        // RFC 6265 §5.2.2: Max-Age ALWAYS takes precedence over Expires
        if (/^-?\d+$/.test(v)) {
          const sec = Number.parseInt(v, 10);
          hasMaxAge = true;
          maxAgeExpiresAt = sec <= 0 ? 0 : Math.min(this.clockMs() + sec * 1000, MAX_COOKIE_DATE);
        }
      } else if (k === 'expires') {
        const ts = Date.parse(v);
        if (Number.isFinite(ts)) parsedExpiresAt = ts;
      }
    }

    const expiresAt = hasMaxAge && maxAgeExpiresAt !== null
      ? maxAgeExpiresAt
      : (parsedExpiresAt !== null ? parsedExpiresAt : MAX_COOKIE_DATE);

    const now = this.clockMs();
    const key = `${name}\0${domain}\0${cookiePath}`;
    const old = this.store.get(key);
    const oldCost = old ? this._estimateEntryBytes(old) : 0;

    if (expiresAt <= now) {
      if (this.store.delete(key)) {
        this.estimatedBytes = Math.max(2, this.estimatedBytes - oldCost);
        this.dirty = true;
      }
      return;
    }

    const next = { name, value, domain, hostOnly, path: cookiePath, secure, httpOnly, expiresAt };
    const newCost = this._estimateEntryBytes(next);
    const projected = this.estimatedBytes - oldCost + newCost;
    if (projected > this.maxBytes) {
      this._pruneExpired(now);
      this.store.set(key, next);
      const exactSize = this._exactSerializedBytes();
      if (exactSize > this.maxBytes) {
        if (old !== undefined) this.store.set(key, old);
        else this.store.delete(key);
        throw new CookieJarQuotaExceededError(
          `插件 Cookie 罐超过大小上限（${Math.round(this.maxBytes / 1024)} KB）`,
        );
      }
      this.estimatedBytes = exactSize;
    } else {
      this.store.set(key, next);
      this.estimatedBytes = projected;
    }
    this.dirty = true;
  }

  saveFromHeaders(rawUrl, setCookieValues) {
    for (const hdr of setCookieValues || []) {
      try {
        this.setCookie(rawUrl, hdr, false);
      } catch (e) {
        if (e instanceof CookieJarQuotaExceededError) throw e;
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
        this.estimatedBytes = Math.max(2, this.estimatedBytes - this._estimateEntryBytes(c));
        this.dirty = true;
        continue;
      }
      if (c.secure && url.protocol !== 'https:') continue;
      const dMatch = c.hostOnly ? host === c.domain : domainMatches(host, c.domain);
      if (!dMatch) continue;
      const pathMatch = url.pathname === c.path ||
        (url.pathname.startsWith(c.path) && (c.path.endsWith('/') || url.pathname[c.path.length] === '/'));
      if (!pathMatch) continue;
      matched.push(`${c.name}=${c.value}`);
    }
    return matched.join('; ');
  }

  clear() {
    this.store.clear();
    this.estimatedBytes = 2;
    this.dirty = true;
  }

  /** Coalesced persistence (§11.1, S5): flush dirty state once per call end. */
  flush() {
    if (!this.dirty || !this.filePath) return;
    const raw = Buffer.from(JSON.stringify([...this.store.values()]), 'utf8');
    if (raw.length > this.maxBytes) {
      throw new CookieJarQuotaExceededError(
        `插件 Cookie 罐超过大小上限（${Math.round(this.maxBytes / 1024)} KB）`,
      );
    }
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    fs.writeFileSync(this.filePath, raw);
    this.estimatedBytes = raw.length;
    this.dirty = false;
    this.diskWriteCount += 1;
  }

  _pruneExpired(now) {
    for (const [key, c] of this.store.entries()) {
      if (c.expiresAt <= now) {
        this.store.delete(key);
        this.estimatedBytes = Math.max(2, this.estimatedBytes - this._estimateEntryBytes(c));
        this.dirty = true;
      }
    }
  }

  /** Matches Android PluginCookieJar.estimateEntryBytes exactly. */
  _estimateEntryBytes(c) {
    return 140 +
      Buffer.byteLength(c.name, 'utf8') * 2 +
      Buffer.byteLength(c.value, 'utf8') * 2 +
      Buffer.byteLength(c.domain, 'utf8') +
      Buffer.byteLength(c.path, 'utf8');
  }

  _exactSerializedBytes() {
    return Buffer.byteLength(JSON.stringify([...this.store.values()]), 'utf8');
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
