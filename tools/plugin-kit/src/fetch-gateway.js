import http from 'node:http';
import https from 'node:https';
import { PluginCookieJar } from './cookies.js';
import { NetworkPolicyError, originKey } from './network-policy.js';

/** §11.1 (B2): 8 MB single fetch response cap, 4 concurrent fetches, 16 queued, 16 MB in-flight bytes, 2 MB req body, 32 KB req headers, 8 KB URL. */
export const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
export const MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024;
export const MAX_REQUEST_URL_BYTES = 8 * 1024;
export const MAX_REQUEST_HEADERS_BYTES = 32 * 1024;
export const MAX_FETCH_META_JSON_BYTES = 3 * 1024 * 1024;
export const MAX_CONCURRENT_FETCHES = 4;
export const MAX_QUEUED_FETCHES = 16;
export const MAX_INFLIGHT_BYTES = 16 * 1024 * 1024;
export const MAX_REDIRECTS = 10;

const REDIRECT_CODES = new Set([301, 302, 303, 307, 308]);
const ALLOWED_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']);
const CROSS_ORIGIN_SAFE_HEADERS = new Set(['accept', 'accept-language', 'user-agent', 'range']);

export class FetchResponseTooLargeError extends Error {
  constructor(message) {
    super(message);
    this.name = 'FetchResponseTooLargeError';
  }
}

export class FetchRequestTooLargeError extends Error {
  constructor(message) {
    super(message);
    this.name = 'FetchRequestTooLargeError';
  }
}

export class FetchQueueFullError extends Error {
  constructor(message) {
    super(message);
    this.name = 'FetchQueueFullError';
  }
}

class AsyncSemaphore {
  constructor(permits, maxQueued = MAX_QUEUED_FETCHES) {
    this.permits = permits;
    this.maxQueued = maxQueued;
    this.waiters = [];
  }

  acquire(signal) {
    if (signal?.aborted) {
      return Promise.reject(new Error('请求已取消'));
    }
    if (this.permits > 0) {
      this.permits -= 1;
      return Promise.resolve();
    }
    if (this.waiters.length >= this.maxQueued) {
      return Promise.reject(new FetchQueueFullError(`排队中的请求数超过上限（最多 ${this.maxQueued} 个）`));
    }
    return new Promise((resolve, reject) => {
      const entry = { resolve, reject };
      this.waiters.push(entry);
      if (signal) {
        const onAbort = () => {
          const idx = this.waiters.indexOf(entry);
          if (idx >= 0) this.waiters.splice(idx, 1);
          reject(new Error('请求已取消'));
        };
        signal.addEventListener('abort', onAbort, { once: true });
        entry.cleanup = () => signal.removeEventListener('abort', onAbort);
      }
    });
  }

  release() {
    while (this.waiters.length > 0) {
      const next = this.waiters.shift();
      if (next.cleanup) next.cleanup();
      next.resolve();
      return;
    }
    this.permits += 1;
  }
}

export class InFlightByteBudget {
  constructor(maxBytes = MAX_INFLIGHT_BYTES) {
    this.maxBytes = maxBytes;
    this.used = 0;
  }

  reserve(bytes) {
    if (bytes <= 0) return;
    if (this.used + bytes > this.maxBytes) {
      throw new FetchResponseTooLargeError(
        `并发请求在途字节超过上限（${Math.round(this.maxBytes / (1024 * 1024))} MB）`,
      );
    }
    this.used += bytes;
  }

  release(bytes) {
    if (bytes <= 0) return;
    this.used = Math.max(0, this.used - bytes);
  }
}

export class FetchGateway {
  constructor(policy, cookieJar, opts = {}) {
    this.policy = policy;
    this.cookieJar = cookieJar;
    this.maxResponseBytes = opts.maxResponseBytes ?? MAX_RESPONSE_BYTES;
    this.maxRequestBodyBytes = opts.maxRequestBodyBytes ?? MAX_REQUEST_BODY_BYTES;
    this.maxRequestUrlBytes = opts.maxRequestUrlBytes ?? MAX_REQUEST_URL_BYTES;
    this.maxRequestHeadersBytes = opts.maxRequestHeadersBytes ?? MAX_REQUEST_HEADERS_BYTES;
    this.maxConcurrent = opts.maxConcurrent ?? MAX_CONCURRENT_FETCHES;
    this.maxQueued = opts.maxQueued ?? MAX_QUEUED_FETCHES;
    this.inFlightBudget = opts.inFlightBudget ?? new InFlightByteBudget(opts.maxInFlightBytes ?? MAX_INFLIGHT_BYTES);
    this.maxRedirects = opts.maxRedirects ?? MAX_REDIRECTS;
    this.transportOverride = opts.transportOverride ?? null;
    this.semaphore = new AsyncSemaphore(this.maxConcurrent, this.maxQueued);
    this.activeControllers = new Set();
  }

  validateRequestLimits(url, headers, body) {
    if (Buffer.byteLength(String(url ?? ''), 'utf8') > this.maxRequestUrlBytes) {
      throw new FetchRequestTooLargeError(`请求 URL 超过大小限制（上限 ${Math.round(this.maxRequestUrlBytes / 1024)} KB）`);
    }
    let headerBytes = 0;
    for (const [k, v] of Object.entries(headers || {})) {
      if (v === undefined || v === null) continue;
      headerBytes += Buffer.byteLength(String(k), 'utf8') + Buffer.byteLength(String(v), 'utf8') + 4;
      if (headerBytes > this.maxRequestHeadersBytes) {
        throw new FetchRequestTooLargeError(`请求头总大小超过限制（上限 ${Math.round(this.maxRequestHeadersBytes / 1024)} KB）`);
      }
    }
    if (body !== null && body !== undefined) {
      const len = Buffer.isBuffer(body) || body instanceof Uint8Array
        ? body.byteLength
        : Buffer.byteLength(String(body), 'utf8');
      if (len > this.maxRequestBodyBytes) {
        throw new FetchRequestTooLargeError(`请求体超过大小限制（上限 ${Math.round(this.maxRequestBodyBytes / (1024 * 1024))} MB）`);
      }
    }
  }

  async withSlot(fn) {
    const controller = new AbortController();
    this.activeControllers.add(controller);
    let acquired = false;
    try {
      await this.semaphore.acquire(controller.signal);
      acquired = true;
      return await fn(controller.signal);
    } finally {
      if (acquired) this.semaphore.release();
      this.activeControllers.delete(controller);
    }
  }

  cancelAllActive() {
    for (const ac of [...this.activeControllers]) {
      try { ac.abort(new Error('请求已取消')); } catch {}
    }
    this.activeControllers.clear();
  }

  activeCount() {
    return this.activeControllers.size;
  }

  async fetch(req, defaultTimeoutMs = 15_000) {
    this.validateRequestLimits(req.url, req.headers, req.body);
    return this.withSlot((signal) => this._execute(req, defaultTimeoutMs, signal));
  }

  async _execute(req, defaultTimeoutMs, signal) {
    const redirectMode = String(req.redirect || 'follow').toLowerCase();
    if (!['follow', 'manual', 'error'].includes(redirectMode)) {
      throw new Error(`Unsupported redirect mode: ${req.redirect}`);
    }
    const method = String(req.method || 'GET').toUpperCase();
    if (!ALLOWED_METHODS.has(method)) {
      throw new Error(`Unsupported HTTP method: ${method}`);
    }

    const headers = {};
    for (const [k, v] of Object.entries(req.headers || {})) {
      if (k.toLowerCase() === 'host') {
        throw new NetworkPolicyError('禁止设置 Host 请求头');
      }
      if (v !== undefined && v !== null) headers[k] = String(v);
    }

    const timeoutMs = Math.min(Math.max(Number(req.timeoutMs || defaultTimeoutMs), 100), 30_000);
    const deadlineMs = Date.now() + timeoutMs;
    let currentUrl = String(req.url);
    let prevScheme = null;
    let currentMethod = method;
    let currentHeaders = { ...headers };
    let currentBody = req.body ?? null;
    let hops = 0;

    while (true) {
      if (signal.aborted) throw new Error('请求已取消');
      const remainingMs = deadlineMs - Date.now();
      if (remainingMs <= 0) {
        throw new Error(`请求总耗时超过 ${timeoutMs} ms 上限`);
      }
      const validated = await this.policy.validateFetchUrl(currentUrl, prevScheme);
      const urlObj = validated.url;

      // §11.1 (S9): merge jar cookies with explicit Cookie header (explicit wins on name collision)
      const reqHeaders = {};
      let explicitCookie = null;
      for (const [k, v] of Object.entries(currentHeaders)) {
        if (k.toLowerCase() === 'cookie') {
          explicitCookie = v;
        } else {
          reqHeaders[k] = v;
        }
      }
      const jarCookie = this.cookieJar ? this.cookieJar.getHeaderForUrl(urlObj.toString()) : '';
      const mergedCookie = PluginCookieJar.mergeCookieHeaders(jarCookie, explicitCookie);
      if (mergedCookie) reqHeaders.Cookie = mergedCookie;

      const resp = this.transportOverride
        ? await this._runTransportOverride(urlObj, validated.addresses, currentMethod, reqHeaders, currentBody, remainingMs, deadlineMs, timeoutMs, signal)
        : await this._httpRequest(urlObj, validated.addresses, currentMethod, reqHeaders, currentBody, remainingMs, deadlineMs, timeoutMs, signal);

      if (this.cookieJar && resp.setCookies?.length) {
        this.cookieJar.saveFromHeaders(urlObj.toString(), resp.setCookies);
      }

      const loc = resp.headers.location;
      if (REDIRECT_CODES.has(resp.status) && loc) {
        if (redirectMode === 'error') {
          throw new Error(`请求触发重定向且 redirect=error（状态码 ${resp.status}）`);
        }
        if (redirectMode === 'manual') {
          return { status: resp.status, url: urlObj.toString(), headers: resp.headers, body: resp.body };
        }
        hops += 1;
        if (hops > this.maxRedirects) {
          throw new Error(`重定向次数过多（超过 ${this.maxRedirects} 次）`);
        }
        let resolved;
        try {
          resolved = new URL(loc, urlObj);
        } catch {
          throw new NetworkPolicyError('无效的重定向目标地址');
        }
        if (originKey(resolved) !== originKey(urlObj)) {
          // Keep ONLY safe general headers across cross-origin redirects;
          // strip Authorization, Cookie, Referer, and ALL custom plugin headers.
          for (const k of Object.keys(currentHeaders)) {
            if (!CROSS_ORIGIN_SAFE_HEADERS.has(k.toLowerCase())) {
              delete currentHeaders[k];
            }
          }
        }
        if (resp.status === 303 || ((resp.status === 301 || resp.status === 302) && currentMethod === 'POST')) {
          currentMethod = 'GET';
          currentBody = null;
          for (const k of Object.keys(currentHeaders)) {
            if (k.toLowerCase() === 'content-type' || k.toLowerCase() === 'content-length') {
              delete currentHeaders[k];
            }
          }
        }
        prevScheme = urlObj.protocol;
        currentUrl = resolved.toString();
        continue;
      }

      return {
        status: resp.status,
        url: urlObj.toString(),
        headers: resp.headers,
        body: resp.body,
      };
    }
  }

  async _runTransportOverride(urlObj, addresses, method, headers, body, remainingMs, deadlineMs, totalTimeoutMs, signal) {
    let timer = null;
    const timeoutPromise = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`请求总耗时超过 ${totalTimeoutMs} ms 上限`)), Math.max(1, remainingMs));
    });
    let resp;
    try {
      resp = await Promise.race([
        this.transportOverride({
          url: urlObj,
          addresses,
          method,
          headers,
          body,
          timeoutMs: remainingMs,
          signal,
        }),
        timeoutPromise,
      ]);
    } finally {
      if (timer) clearTimeout(timer);
    }
    if (Date.now() > deadlineMs) {
      throw new Error(`请求总耗时超过 ${totalTimeoutMs} ms 上限`);
    }
    const buf = Buffer.isBuffer(resp.body) ? resp.body : Buffer.from(resp.body || '');
    if (buf.length > this.maxResponseBytes) {
      throw new FetchResponseTooLargeError(
        `响应体超过大小限制（上限 ${Math.round(this.maxResponseBytes / (1024 * 1024))} MB）`,
      );
    }
    this.inFlightBudget.reserve(buf.length);
    this.inFlightBudget.release(buf.length);
    return { ...resp, body: buf };
  }

  _httpRequest(urlObj, addresses, method, headers, body, remainingMs, deadlineMs, totalTimeoutMs, signal) {
    return new Promise((resolve, reject) => {
      if (signal.aborted) {
        reject(new Error('请求已取消'));
        return;
      }
      if (remainingMs <= 0) {
        reject(new Error(`请求总耗时超过 ${totalTimeoutMs} ms 上限`));
        return;
      }
      const mod = urlObj.protocol === 'https:' ? https : http;
      const pinnedIp = addresses[0];
      const family = pinnedIp.includes(':') ? 6 : 4;
      let reserved = 0;
      let settled = false;
      let deadlineTimer = null;
      const finish = (err, val) => {
        if (settled) return;
        settled = true;
        if (deadlineTimer) clearTimeout(deadlineTimer);
        signal.removeEventListener('abort', onAbort);
        if (reserved > 0) {
          this.inFlightBudget.release(reserved);
          reserved = 0;
        }
        if (err) reject(err);
        else resolve(val);
      };

      const req = mod.request(
        urlObj,
        {
          method,
          headers,
          timeout: remainingMs,
          lookup: (_hostname, _opts, cb) => {
            if (typeof _opts === 'function') {
              _opts(null, pinnedIp, family);
            } else if (_opts && _opts.all) {
              cb(null, addresses.map((a) => ({ address: a, family: a.includes(':') ? 6 : 4 })));
            } else {
              cb(null, pinnedIp, family);
            }
          },
        },
        (res) => {
          const contentLen = Number(res.headers['content-length']);
          if (Number.isFinite(contentLen) && contentLen > this.maxResponseBytes) {
            res.destroy();
            finish(new FetchResponseTooLargeError(
              `响应体超过大小限制（上限 ${Math.round(this.maxResponseBytes / (1024 * 1024))} MB）`,
            ));
            return;
          }
          const chunks = [];
          let total = 0;
          res.on('data', (chunk) => {
            if (Date.now() > deadlineMs) {
              res.destroy();
              finish(new Error(`请求总耗时超过 ${totalTimeoutMs} ms 上限`));
              return;
            }
            total += chunk.length;
            if (total > this.maxResponseBytes) {
              res.destroy();
              finish(new FetchResponseTooLargeError(
                `响应体超过大小限制（上限 ${Math.round(this.maxResponseBytes / (1024 * 1024))} MB）`,
              ));
              return;
            }
            try {
              this.inFlightBudget.reserve(chunk.length);
              reserved += chunk.length;
            } catch (e) {
              res.destroy();
              finish(e);
              return;
            }
            chunks.push(chunk);
          });
          res.on('end', () => {
            const flatHeaders = {};
            const setCookies = [];
            for (const [k, v] of Object.entries(res.headers)) {
              const lk = k.toLowerCase();
              if (lk === 'set-cookie') {
                const list = Array.isArray(v) ? v : [v];
                setCookies.push(...list);
                flatHeaders[lk] = list.join(', ');
              } else if (Array.isArray(v)) {
                flatHeaders[lk] = v.join(', ');
              } else if (v !== undefined) {
                flatHeaders[lk] = String(v);
              }
            }
            finish(null, {
              status: res.statusCode || 0,
              headers: flatHeaders,
              setCookies,
              body: Buffer.concat(chunks),
            });
          });
          res.on('error', (err) => finish(err));
        },
      );

      deadlineTimer = setTimeout(() => {
        req.destroy(new Error(`请求总耗时超过 ${totalTimeoutMs} ms 上限`));
        finish(new Error(`请求总耗时超过 ${totalTimeoutMs} ms 上限`));
      }, Math.max(1, remainingMs));

      const onAbort = () => {
        req.destroy(new Error('请求已取消'));
        finish(new Error('请求已取消'));
      };
      signal.addEventListener('abort', onAbort, { once: true });

      req.on('timeout', () => {
        req.destroy(new Error(`请求总耗时超过 ${totalTimeoutMs} ms 上限`));
      });
      req.on('error', (err) => finish(err));
      if (body && method !== 'GET' && method !== 'HEAD') {
        req.write(body);
      }
      req.end();
    });
  }
}
