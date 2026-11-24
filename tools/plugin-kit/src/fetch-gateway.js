import http from 'node:http';
import https from 'node:https';
import { NetworkPolicyError } from './network-policy.js';

export const MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
export const MAX_REDIRECTS = 10;
const REDIRECT_CODES = new Set([301, 302, 303, 307, 308]);
const ALLOWED_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']);

export class FetchGateway {
  constructor(policy, cookieJar, opts = {}) {
    this.policy = policy;
    this.cookieJar = cookieJar;
    this.maxResponseBytes = opts.maxResponseBytes ?? MAX_RESPONSE_BYTES;
    this.maxRedirects = opts.maxRedirects ?? MAX_REDIRECTS;
    this.transportOverride = opts.transportOverride ?? null;
  }

  async fetch(req, defaultTimeoutMs = 15_000) {
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
    let currentUrl = String(req.url);
    let currentMethod = method;
    let currentHeaders = { ...headers };
    let currentBody = req.body ?? null;
    let hops = 0;

    while (true) {
      const validated = await this.policy.validateFetchUrl(currentUrl);
      const urlObj = validated.url;

      // Attach cookies from instance jar unless plugin explicitly passed Cookie header
      const hasCookieHeader = Object.keys(currentHeaders).some((k) => k.toLowerCase() === 'cookie');
      const reqHeaders = { ...currentHeaders };
      if (!hasCookieHeader && this.cookieJar) {
        const cookieStr = this.cookieJar.getHeaderForUrl(urlObj.toString());
        if (cookieStr) reqHeaders.Cookie = cookieStr;
      }

      const resp = this.transportOverride
        ? await this.transportOverride({
            url: urlObj,
            addresses: validated.addresses,
            method: currentMethod,
            headers: reqHeaders,
            body: currentBody,
            timeoutMs,
          })
        : await this._httpRequest(urlObj, validated.addresses, currentMethod, reqHeaders, currentBody, timeoutMs);

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
        if (resolved.origin !== urlObj.origin) {
          for (const k of Object.keys(currentHeaders)) {
            if (k.toLowerCase() === 'authorization' || k.toLowerCase() === 'cookie') {
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

  _httpRequest(urlObj, addresses, method, headers, body, timeoutMs) {
    return new Promise((resolve, reject) => {
      const mod = urlObj.protocol === 'https:' ? https : http;
      const pinnedIp = addresses[0];
      const family = pinnedIp.includes(':') ? 6 : 4;
      const req = mod.request(
        urlObj,
        {
          method,
          headers,
          timeout: timeoutMs,
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
            reject(new Error(`响应体超过大小限制（上限 ${Math.round(this.maxResponseBytes / (1024 * 1024))} MB）`));
            return;
          }
          const chunks = [];
          let total = 0;
          res.on('data', (chunk) => {
            total += chunk.length;
            if (total > this.maxResponseBytes) {
              res.destroy();
              reject(new Error(`响应体超过大小限制（上限 ${Math.round(this.maxResponseBytes / (1024 * 1024))} MB）`));
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
            resolve({
              status: res.statusCode || 0,
              headers: flatHeaders,
              setCookies,
              body: Buffer.concat(chunks),
            });
          });
          res.on('error', reject);
        },
      );
      req.on('timeout', () => {
        req.destroy(new Error('请求超时'));
      });
      req.on('error', reject);
      if (body && method !== 'GET' && method !== 'HEAD') {
        req.write(body);
      }
      req.end();
    });
  }
}
