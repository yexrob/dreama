export class PluginCookieJar {
  constructor(clockMs = () => Date.now()) {
    this.clockMs = clockMs;
    this.store = new Map();
  }

  setCookie(rawUrl, setCookieHeader) {
    const url = new URL(rawUrl);
    const parts = String(setCookieHeader).split(';').map((s) => s.trim()).filter(Boolean);
    if (parts.length === 0) throw new Error('Invalid Set-Cookie header');
    const eq = parts[0].indexOf('=');
    if (eq <= 0) throw new Error('Invalid cookie name=value');
    const name = parts[0].slice(0, eq).trim();
    const value = parts[0].slice(eq + 1).trim();

    let domain = url.hostname.toLowerCase();
    let hostOnly = true;
    let path = this._defaultPath(url.pathname);
    let secure = false;
    let expiresAt = Number.MAX_SAFE_INTEGER;

    for (const attr of parts.slice(1)) {
      const aeq = attr.indexOf('=');
      const k = (aeq >= 0 ? attr.slice(0, aeq) : attr).trim().toLowerCase();
      const v = aeq >= 0 ? attr.slice(aeq + 1).trim() : '';
      if (k === 'domain' && v) {
        const d = v.replace(/^\./, '').toLowerCase();
        if (url.hostname.toLowerCase() === d || url.hostname.toLowerCase().endsWith(`.${d}`)) {
          domain = d;
          hostOnly = false;
        }
      } else if (k === 'path' && v.startsWith('/')) {
        path = v;
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

    const key = `${name}\0${domain}\0${path}`;
    if (expiresAt <= this.clockMs()) {
      this.store.delete(key);
    } else {
      this.store.set(key, { name, value, domain, hostOnly, path, secure, expiresAt });
    }
  }

  saveFromHeaders(rawUrl, setCookieValues) {
    for (const hdr of setCookieValues || []) {
      try {
        this.setCookie(rawUrl, hdr);
      } catch {
        // ignore malformed cookie from remote server
      }
    }
  }

  getHeaderForUrl(rawUrl) {
    const url = new URL(rawUrl);
    const now = this.clockMs();
    const host = url.hostname.toLowerCase();
    const matched = [];
    for (const [key, c] of this.store.entries()) {
      if (c.expiresAt <= now) {
        this.store.delete(key);
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
  }

  _defaultPath(pathname) {
    if (!pathname || !pathname.startsWith('/')) return '/';
    const idx = pathname.lastIndexOf('/');
    return idx <= 0 ? '/' : pathname.slice(0, idx);
  }
}
