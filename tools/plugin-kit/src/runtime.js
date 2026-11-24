import { getQuickJS } from 'quickjs-emscripten';
import { pluginCrypto } from './crypto.js';
import { PluginCookieJar } from './cookies.js';
import { PluginDomParser } from './dom.js';
import { FetchGateway } from './fetch-gateway.js';
import { PluginLocale } from './locale.js';
import { PluginLogger } from './logger.js';
import { NetworkPolicy, extractUserHosts } from './network-policy.js';
import { PluginStorage } from './storage.js';

const ERR_MARKER = '__D3_PLUGIN_ERR__:';
export const DEFAULT_MEMORY_LIMIT = 32 * 1024 * 1024;
export const DEFAULT_TIMEOUT_MS = 15_000;
export const PLAYBACK_TIMEOUT_MS = 30_000;

export class PluginCallError extends Error {
  constructor(code, message, retryAfterSec = null) {
    super(message || code);
    this.name = 'PluginCallError';
    this.code = code || 'Internal';
    this.retryAfterSec = retryAfterSec;
  }
}

const VALID_ERROR_CODES = new Set([
  'NeedsAuth',
  'AuthFailed',
  'NeedsSetup',
  'Unavailable',
  'NotFound',
  'Forbidden',
  'RateLimited',
  'Unsupported',
  'Internal',
]);

function parseWireError(rawMessage) {
  if (!rawMessage) return null;
  const s = String(rawMessage);
  const idx = s.indexOf(ERR_MARKER);
  if (idx < 0) return null;
  const line = s.slice(idx + ERR_MARKER.length).split('\n')[0].trim();
  try {
    const obj = JSON.parse(line);
    const code = VALID_ERROR_CODES.has(obj.code) ? obj.code : 'Internal';
    const retryAfterSec = Number.isFinite(obj.retryAfterSec) ? Math.round(obj.retryAfterSec) : null;
    return new PluginCallError(code, obj.message || '', retryAfterSec);
  } catch {
    return null;
  }
}

let cachedQuickJS = null;
async function loadQuickJS() {
  if (!cachedQuickJS) cachedQuickJS = await getQuickJS();
  return cachedQuickJS;
}

const BOOTSTRAP_JS = `
(function() {
  const host = globalThis.__d3_host;
  delete globalThis.__d3_host;
  const ERR_MARKER = host.getErrorMarker();

  function deepFreeze(obj) {
    if (obj === null || typeof obj !== 'object') return obj;
    Object.freeze(obj);
    for (const key of Object.keys(obj)) deepFreeze(obj[key]);
    return obj;
  }

  function utf8Encode(str) {
    const s = String(str);
    const bytes = [];
    for (let i = 0; i < s.length; i++) {
      let cp = s.charCodeAt(i);
      if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < s.length) {
        const lo = s.charCodeAt(++i);
        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
      }
      if (cp < 0x80) bytes.push(cp);
      else if (cp < 0x800) bytes.push(0xC0 | (cp >> 6), 0x80 | (cp & 0x3F));
      else if (cp < 0x10000) bytes.push(0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
      else bytes.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
    }
    return new Uint8Array(bytes);
  }

  function utf8Decode(bytes) {
    const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    let out = '';
    let i = 0;
    while (i < u8.length) {
      const b0 = u8[i++];
      if (b0 < 0x80) out += String.fromCharCode(b0);
      else if ((b0 & 0xE0) === 0xC0 && i < u8.length) {
        out += String.fromCharCode(((b0 & 0x1F) << 6) | (u8[i++] & 0x3F));
      } else if ((b0 & 0xF0) === 0xE0 && i + 1 < u8.length) {
        const b1 = u8[i++], b2 = u8[i++];
        out += String.fromCharCode(((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F));
      } else if ((b0 & 0xF8) === 0xF0 && i + 2 < u8.length) {
        const b1 = u8[i++], b2 = u8[i++], b3 = u8[i++];
        const cp = (((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)) - 0x10000;
        out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
      } else out += '\\uFFFD';
    }
    return out;
  }

  const B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const B64_LOOKUP = new Int16Array(128).fill(-1);
  for (let i = 0; i < B64_CHARS.length; i++) B64_LOOKUP[B64_CHARS.charCodeAt(i)] = i;
  B64_LOOKUP['-'.charCodeAt(0)] = 62;
  B64_LOOKUP['_'.charCodeAt(0)] = 63;

  function bytesToBase64(u8) {
    let out = '';
    for (let i = 0; i < u8.length; i += 3) {
      const a = u8[i], b = i + 1 < u8.length ? u8[i + 1] : 0, c = i + 2 < u8.length ? u8[i + 2] : 0;
      const n = (a << 16) | (b << 8) | c;
      out += B64_CHARS[(n >> 18) & 63];
      out += B64_CHARS[(n >> 12) & 63];
      out += i + 1 < u8.length ? B64_CHARS[(n >> 6) & 63] : '=';
      out += i + 2 < u8.length ? B64_CHARS[n & 63] : '=';
    }
    return out;
  }

  function base64ToBytes(str) {
    const s = String(str).replace(/[\\r\\n\\s=]+/g, '');
    const out = new Uint8Array(Math.floor(s.length * 3 / 4));
    let p = 0;
    for (let i = 0; i < s.length; i += 4) {
      const a = B64_LOOKUP[s.charCodeAt(i)] ?? 0;
      const b = B64_LOOKUP[s.charCodeAt(i + 1)] ?? 0;
      const c = i + 2 < s.length ? (B64_LOOKUP[s.charCodeAt(i + 2)] ?? 0) : 0;
      const d = i + 3 < s.length ? (B64_LOOKUP[s.charCodeAt(i + 3)] ?? 0) : 0;
      out[p++] = (a << 2) | (b >> 4);
      if (i + 2 < s.length) out[p++] = ((b & 15) << 4) | (c >> 2);
      if (i + 3 < s.length) out[p++] = ((c & 3) << 6) | d;
    }
    return out.subarray(0, p);
  }

  function toInputBase64(input) {
    if (input instanceof Uint8Array) return bytesToBase64(input);
    if (ArrayBuffer.isView(input)) return bytesToBase64(new Uint8Array(input.buffer, input.byteOffset, input.byteLength));
    if (input instanceof ArrayBuffer) return bytesToBase64(new Uint8Array(input));
    return bytesToBase64(utf8Encode(input ?? ''));
  }

  if (typeof globalThis.TextEncoder === 'undefined') {
    globalThis.TextEncoder = class TextEncoder { encode(s) { return utf8Encode(s); } };
  }
  if (typeof globalThis.TextDecoder === 'undefined') {
    globalThis.TextDecoder = class TextDecoder { decode(b) { return utf8Decode(b); } };
  }
  if (typeof globalThis.URLSearchParams === 'undefined') {
    globalThis.URLSearchParams = class URLSearchParams {
      constructor(init) {
        this._pairs = [];
        if (typeof init === 'string') {
          const q = init.startsWith('?') ? init.slice(1) : init;
          if (q) {
            for (const part of q.split('&')) {
              if (!part) continue;
              const idx = part.indexOf('=');
              const k = decodeURIComponent((idx >= 0 ? part.slice(0, idx) : part).replace(/\\+/g, ' '));
              const v = decodeURIComponent((idx >= 0 ? part.slice(idx + 1) : '').replace(/\\+/g, ' '));
              this._pairs.push([k, v]);
            }
          }
        } else if (init && typeof init === 'object') {
          for (const [k, v] of Object.entries(init)) {
            if (v !== undefined && v !== null) this._pairs.push([String(k), String(v)]);
          }
        }
      }
      append(k, v) { this._pairs.push([String(k), String(v)]); }
      set(k, v) {
        const ks = String(k);
        this._pairs = this._pairs.filter(([key]) => key !== ks);
        this._pairs.push([ks, String(v)]);
      }
      get(k) { const f = this._pairs.find(([key]) => key === String(k)); return f ? f[1] : null; }
      delete(k) { this._pairs = this._pairs.filter(([key]) => key !== String(k)); }
      toString() { return this._pairs.map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&'); }
    };
  }

  let currentGen = 0;
  let nextTimerId = 1;
  const cancelledTimers = new Set();

  globalThis.setTimeout = function(fn, delayMs, ...args) {
    if (typeof fn !== 'function') throw new TypeError('setTimeout requires a function');
    const id = nextTimerId++;
    const gen = currentGen;
    const ms = Number(delayMs) || 0;
    host.sleep(ms, gen).then((stillActive) => {
      if (stillActive && gen === currentGen && !cancelledTimers.has(id)) {
        cancelledTimers.delete(id);
        fn(...args);
      }
    }).catch(() => {});
    return id;
  };
  globalThis.clearTimeout = function(id) { cancelledTimers.add(id); };

  function wrapElement(info) {
    if (!info) return null;
    const el = {
      tag: info.tag,
      text: info.text,
      html: info.html,
      outerHtml: info.outerHtml,
      attrs: deepFreeze(Object.assign({}, info.attrs || {})),
      attr(name) {
        const v = info.attrs ? info.attrs[String(name)] : undefined;
        return v === undefined ? null : v;
      },
      querySelector(sel) {
        const raw = host.domSelectFirst(info.id, String(sel));
        return raw ? wrapElement(JSON.parse(raw)) : null;
      },
      selectFirst(sel) { return el.querySelector(sel); },
      querySelectorAll(sel) { return JSON.parse(host.domSelectAll(info.id, String(sel))).map(wrapElement); },
      select(sel) { return el.querySelectorAll(sel); },
      get children() { return JSON.parse(host.domChildren(info.id)).map(wrapElement); },
    };
    return Object.freeze(el);
  }

  class PluginError extends Error {
    constructor(code, message, options) {
      super(message === undefined || message === null ? '' : String(message));
      this.name = 'PluginError';
      this.code = String(code || 'Internal');
      this.__d3Error = true;
      if (options && typeof options === 'object' && options.retryAfterSec !== undefined) {
        this.retryAfterSec = Number(options.retryAfterSec);
      }
    }
  }

  function formatLogArgs(args) {
    return args.map((a) => {
      if (typeof a === 'string') return a;
      try { return JSON.stringify(a); } catch { return String(a); }
    }).join(' ');
  }

  const ctx = Object.freeze({
    Error: PluginError,
    config: deepFreeze(JSON.parse(host.getConfigJson())),
    instanceId: host.getInstanceId(),
    device: deepFreeze(JSON.parse(host.getDeviceJson())),

    async fetch(url, opts) {
      const o = opts || {};
      const req = {
        url: String(url),
        method: o.method ? String(o.method) : 'GET',
        headers: {},
        redirect: o.redirect ? String(o.redirect) : 'follow',
      };
      if (o.timeoutMs !== undefined && o.timeoutMs !== null) req.timeoutMs = Number(o.timeoutMs);
      if (o.headers && typeof o.headers === 'object') {
        for (const [k, v] of Object.entries(o.headers)) {
          if (v !== undefined && v !== null) req.headers[String(k)] = String(v);
        }
      }
      if (o.body !== undefined && o.body !== null) {
        if (o.body instanceof Uint8Array || ArrayBuffer.isView(o.body) || o.body instanceof ArrayBuffer) {
          req.bodyBase64 = toInputBase64(o.body);
        } else if (typeof o.body === 'string') {
          req.bodyText = o.body;
        } else {
          req.bodyText = JSON.stringify(o.body);
          if (!Object.keys(req.headers).some((k) => k.toLowerCase() === 'content-type')) {
            req.headers['Content-Type'] = 'application/json; charset=utf-8';
          }
        }
      }
      const rawResp = await host.fetch(JSON.stringify(req));
      const parsed = JSON.parse(rawResp);
      let cachedBytes = null;
      let cachedText = null;
      const headers = deepFreeze(parsed.headers || {});
      const resp = {
        status: parsed.status,
        ok: parsed.status >= 200 && parsed.status < 300,
        url: parsed.url,
        headers,
        bytes() {
          if (cachedBytes === null) cachedBytes = base64ToBytes(parsed.bodyBase64 || '');
          return new Uint8Array(cachedBytes);
        },
        text() {
          if (cachedText === null) cachedText = utf8Decode(resp.bytes());
          return cachedText;
        },
        json() { return JSON.parse(resp.text()); },
      };
      return Object.freeze(resp);
    },

    storage: Object.freeze({
      get(key) {
        const raw = host.storageGet(String(key));
        return raw === null || raw === undefined ? null : JSON.parse(raw);
      },
      set(key, value) { host.storageSet(String(key), JSON.stringify(value === undefined ? null : value)); },
      remove(key) { host.storageRemove(String(key)); },
      keys() { return JSON.parse(host.storageKeys()); },
    }),

    cookies: Object.freeze({
      get(url) { return host.cookiesGet(String(url)); },
      set(url, cookie) { host.cookiesSet(String(url), String(cookie)); },
      clear() { host.cookiesClear(); },
    }),

    crypto: Object.freeze({
      md5(data) { return host.cryptoHash('md5', toInputBase64(data)); },
      sha1(data) { return host.cryptoHash('sha1', toInputBase64(data)); },
      sha256(data) { return host.cryptoHash('sha256', toInputBase64(data)); },
      hmac(algo, key, data) { return host.cryptoHmac(String(algo), toInputBase64(key), toInputBase64(data)); },
      aes: Object.freeze({
        encrypt(modeOrOpts, key, iv, data, aad) {
          if (modeOrOpts && typeof modeOrOpts === 'object') {
            const o = modeOrOpts;
            return base64ToBytes(host.cryptoAes(true, String(o.mode || 'GCM'), toInputBase64(o.key), toInputBase64(o.iv), toInputBase64(o.data), o.aad !== undefined ? toInputBase64(o.aad) : ''));
          }
          return base64ToBytes(host.cryptoAes(true, String(modeOrOpts), toInputBase64(key), toInputBase64(iv), toInputBase64(data), aad !== undefined ? toInputBase64(aad) : ''));
        },
        decrypt(modeOrOpts, key, iv, data, aad) {
          if (modeOrOpts && typeof modeOrOpts === 'object') {
            const o = modeOrOpts;
            return base64ToBytes(host.cryptoAes(false, String(o.mode || 'GCM'), toInputBase64(o.key), toInputBase64(o.iv), toInputBase64(o.data), o.aad !== undefined ? toInputBase64(o.aad) : ''));
          }
          return base64ToBytes(host.cryptoAes(false, String(modeOrOpts), toInputBase64(key), toInputBase64(iv), toInputBase64(data), aad !== undefined ? toInputBase64(aad) : ''));
        },
      }),
      rsa: Object.freeze({
        encrypt(pubOrOpts, data, padding) {
          if (pubOrOpts && typeof pubOrOpts === 'object') {
            return base64ToBytes(host.cryptoRsaEncrypt(String(pubOrOpts.publicKey), toInputBase64(pubOrOpts.data), String(pubOrOpts.padding || 'PKCS1')));
          }
          return base64ToBytes(host.cryptoRsaEncrypt(String(pubOrOpts), toInputBase64(data), String(padding || 'PKCS1')));
        },
      }),
      randomBytes(n) { return base64ToBytes(host.cryptoRandomBytes(Number(n))); },
      base64: Object.freeze({
        encode(data, urlSafe) { return host.cryptoBase64Encode(toInputBase64(data), Boolean(urlSafe)); },
        decode(str) { return base64ToBytes(host.cryptoBase64Decode(String(str))); },
      }),
      hex: Object.freeze({
        encode(data) { return host.cryptoHexEncode(toInputBase64(data)); },
        decode(str) { return base64ToBytes(host.cryptoHexDecode(String(str))); },
      }),
    }),

    parse: Object.freeze({
      html(text) { return wrapElement(JSON.parse(host.parseHtml(String(text ?? '')))); },
      xml(text) { return wrapElement(JSON.parse(host.parseXml(String(text ?? '')))); },
    }),

    log: Object.freeze({
      debug(...args) { host.log('DEBUG', formatLogArgs(args)); },
      info(...args) { host.log('INFO', formatLogArgs(args)); },
      warn(...args) { host.log('WARN', formatLogArgs(args)); },
      error(...args) { host.log('ERROR', formatLogArgs(args)); },
    }),

    t(key, vars) { return host.translate(String(key), vars ? JSON.stringify(vars) : 'null'); },
  });

  let pluginExport = null;
  globalThis.__d3_register = function(mod) {
    if (!mod || typeof mod !== 'object') throw new Error('Plugin main.js must export a default object');
    pluginExport = mod;
  };
  globalThis.__d3_exports = function() {
    if (!pluginExport) return '[]';
    return JSON.stringify(Object.keys(pluginExport).filter((k) => typeof pluginExport[k] === 'function'));
  };

  async function callPlugin(method, argJson, hasArg, gen, optional) {
    currentGen = gen;
    cancelledTimers.clear();
    if (!pluginExport) throw new Error('Plugin not loaded');
    const fn = pluginExport[method];
    if (typeof fn !== 'function') {
      if (optional) return 'undefined';
      throw new Error('Plugin does not implement method: ' + method);
    }
    try {
      const res = hasArg ? await fn.call(pluginExport, ctx, JSON.parse(argJson)) : await fn.call(pluginExport, ctx);
      return res === undefined ? 'undefined' : JSON.stringify(res);
    } catch (err) {
      if (err && err.__d3Error) {
        const payload = { code: err.code || 'Internal' };
        if (err.message) payload.message = String(err.message);
        if (typeof err.retryAfterSec === 'number' && Number.isFinite(err.retryAfterSec)) {
          payload.retryAfterSec = Math.round(err.retryAfterSec);
        }
        throw new Error(ERR_MARKER + JSON.stringify(payload));
      }
      throw err;
    }
  }

  globalThis.__d3_invoke = function(method, argJson, hasArg, gen) {
    return callPlugin(method, argJson, hasArg, gen, false);
  };
  globalThis.__d3_invoke_optional = function(method, argJson, hasArg, gen) {
    return callPlugin(method, argJson, hasArg, gen, true);
  };
  globalThis.__d3_eval_probe = async function(fnCode, gen) {
    currentGen = gen;
    const fn = new Function('ctx', 'return (async () => { ' + fnCode + ' })();');
    const res = await fn(ctx);
    return res === undefined ? 'undefined' : JSON.stringify(res);
  };
})();
`;

export class QuickJsPluginHost {
  constructor({
    manifest,
    mainJs,
    config = {},
    instanceId = 'dev-instance',
    storage = new PluginStorage(),
    cookieJar = new PluginCookieJar(),
    locale = new PluginLocale(),
    device = {
      appVersion: '1.0.0',
      locale: 'zh-CN',
      model: 'depth3d-kit',
      maxWidth: 3840,
      maxHeight: 2160,
      codecs: ['avc', 'hevc', 'av1'],
      hdr: true,
    },
    dnsResolver = null,
    transportOverride = null,
    logSink = null,
    memoryLimitBytes = DEFAULT_MEMORY_LIMIT,
    defaultTimeoutMs = DEFAULT_TIMEOUT_MS,
    playbackTimeoutMs = PLAYBACK_TIMEOUT_MS,
  }) {
    this.manifest = manifest;
    this.mainJs = mainJs;
    this.config = this._applyDefaults(manifest, config);
    this.instanceId = instanceId;
    this.storage = storage;
    this.cookieJar = cookieJar;
    this.locale = locale;
    this.device = device;
    this.memoryLimitBytes = memoryLimitBytes;
    this.defaultTimeoutMs = defaultTimeoutMs;
    this.playbackTimeoutMs = playbackTimeoutMs;

    const secretKeys = (manifest.config || []).filter((f) => f.type === 'password').map((f) => f.key);
    const knownSecrets = secretKeys.map((k) => this.config[k]).filter((v) => typeof v === 'string');
    this.logger = new PluginLogger(manifest.id, instanceId, knownSecrets, logSink);

    const userHosts = extractUserHosts(manifest, this.config);
    this.networkPolicy = new NetworkPolicy(manifest.permissions || {}, userHosts, dnsResolver);
    this.fetchGateway = new FetchGateway(this.networkPolicy, this.cookieJar, { transportOverride });
    this.domParser = new PluginDomParser();

    this.rt = null;
    this.vm = null;
    this.initialized = false;
    this.creationCount = 0;
    this.callGen = 0;
    this.deadlineMs = Infinity;
    this.activeTimers = new Set();
  }

  _applyDefaults(manifest, provided) {
    const out = {};
    for (const f of manifest.config || []) {
      if (provided[f.key] !== undefined) out[f.key] = provided[f.key];
      else if (f.default !== undefined) out[f.key] = f.default;
    }
    for (const [k, v] of Object.entries(provided || {})) {
      if (!(k in out)) out[k] = v;
    }
    return out;
  }

  async _ensureInitialized(timeoutMs) {
    if (this.rt && this.vm && this.initialized) return;
    this._destroyVm();

    const QuickJS = await loadQuickJS();
    const rt = QuickJS.newRuntime();
    rt.setMemoryLimit(this.memoryLimitBytes);
    rt.setMaxStackSize(512 * 1024);
    rt.setInterruptHandler(() => Date.now() > this.deadlineMs);
    rt.setModuleLoader((moduleName) => {
      if (moduleName === 'plugin/main.js' || moduleName === './main.js' || moduleName === 'main.js') {
        return this.mainJs;
      }
      return { error: new Error(`Unauthorized module import: ${moduleName}`) };
    });

    const vm = rt.newContext();
    this.rt = rt;
    this.vm = vm;
    this.creationCount += 1;

    try {
      this._bindHostObject(vm);
      this.deadlineMs = Date.now() + timeoutMs;
      const bootHandle = this._unwrapOrThrow(vm, vm.evalCode(BOOTSTRAP_JS, '__d3_bootstrap.js'));
      bootHandle.dispose();
      const entryRes = vm.evalCode(
        `import __p from 'plugin/main.js'; globalThis.__d3_register(__p);`,
        '__d3_entry.js',
        { type: 'module' },
      );
      const entryHandle = this._unwrapOrThrow(vm, entryRes);
      entryHandle.dispose();
      this._drainJobs();

      // Run optional init(ctx)
      const gen = ++this.callGen;
      await this._evalAsyncExpr(`__d3_invoke_optional('init', 'null', false, ${gen})`, timeoutMs);
      this.initialized = true;
    } catch (err) {
      this._destroyVm();
      throw this._mapHostError(err);
    } finally {
      this.deadlineMs = Infinity;
    }
  }

  async exportedMethods() {
    await this._ensureInitialized(this.defaultTimeoutMs);
    const res = this._unwrapOrThrow(this.vm, this.vm.evalCode('__d3_exports()', '__d3_exports.js'));
    const raw = this.vm.dump(res);
    res.dispose();
    return JSON.parse(raw);
  }

  async invoke(method, arg = undefined, opts = {}) {
    const timeoutMs = opts.timeoutMs ?? (method === 'playback' ? this.playbackTimeoutMs : this.defaultTimeoutMs);
    await this._ensureInitialized(timeoutMs);

    const gen = ++this.callGen;
    this.domParser.clear();
    const hasArg = arg !== undefined;
    const argJson = hasArg ? JSON.stringify(arg) : 'null';
    const expr = `__d3_invoke(${JSON.stringify(method)}, ${JSON.stringify(argJson)}, ${hasArg}, ${gen})`;

    try {
      const raw = await this._evalAsyncExpr(expr, timeoutMs);
      if (raw === undefined || raw === null || raw === 'undefined') return undefined;
      return JSON.parse(raw);
    } catch (err) {
      const mapped = this._mapHostError(err);
      if (mapped.code === 'Unavailable' || this._isOom(err)) {
        this._destroyVm();
      }
      throw mapped;
    } finally {
      this.callGen += 1;
      this._clearActiveTimers();
      this.domParser.clear();
    }
  }

  async runProbe(code, timeoutMs = 5000) {
    await this._ensureInitialized(timeoutMs);
    const gen = ++this.callGen;
    try {
      const raw = await this._evalAsyncExpr(`__d3_eval_probe(${JSON.stringify(code)}, ${gen})`, timeoutMs);
      return raw === undefined || raw === 'undefined' ? undefined : JSON.parse(raw);
    } finally {
      this.callGen += 1;
      this._clearActiveTimers();
      this.domParser.clear();
    }
  }

  async dispose() {
    if (this.rt && this.vm && this.initialized) {
      try {
        const gen = ++this.callGen;
        await this._evalAsyncExpr(`__d3_invoke_optional('dispose', 'null', false, ${gen})`, 2000);
      } catch {
        // best effort
      }
    }
    this._destroyVm();
  }

  _destroyVm() {
    this._clearActiveTimers();
    this.domParser.clear();
    if (this.vm) {
      try { this.vm.dispose(); } catch {}
      this.vm = null;
    }
    if (this.rt) {
      try { this.rt.dispose(); } catch {}
      this.rt = null;
    }
    this.initialized = false;
  }

  _clearActiveTimers() {
    for (const t of this.activeTimers) clearTimeout(t);
    this.activeTimers.clear();
  }

  _drainJobs() {
    if (!this.rt) return;
    while (true) {
      const r = this.rt.executePendingJobs();
      if (r.error) {
        const dumped = this.vm.dump(r.error);
        r.error.dispose();
        throw dumped;
      }
      if (r.value === 0) break;
    }
  }

  async _evalAsyncExpr(expr, timeoutMs) {
    const vm = this.vm;
    const rt = this.rt;
    this.deadlineMs = Date.now() + timeoutMs;

    let timer = null;
    try {
      const evalRes = vm.evalCode(expr, '__d3_call.js');
      const promiseHandle = this._unwrapOrThrow(vm, evalRes);
      const nativePromise = vm.resolvePromise(promiseHandle);
      promiseHandle.dispose();
      this._drainJobs();

      const timeoutPromise = new Promise((_, reject) => {
        timer = setTimeout(() => {
          reject(new Error('interrupted: execution timeout'));
        }, timeoutMs);
      });

      const settled = await Promise.race([nativePromise, timeoutPromise]);
      if (settled.error) {
        const errVal = vm.dump(settled.error);
        settled.error.dispose();
        throw errVal;
      }
      const value = vm.dump(settled.value);
      settled.value.dispose();
      return value;
    } finally {
      if (timer) clearTimeout(timer);
      this.deadlineMs = Infinity;
    }
  }

  _unwrapOrThrow(vm, result) {
    if (result.error) {
      const dumped = vm.dump(result.error);
      result.error.dispose();
      throw dumped;
    }
    return result.value;
  }

  _isOom(err) {
    const msg = String(err?.message || err || '').toLowerCase();
    return msg.includes('out of memory') || msg.includes('memory limit');
  }

  _mapHostError(err) {
    if (err instanceof PluginCallError) return err;
    const msg = String(err?.message || err || '');
    const wire = parseWireError(msg);
    if (wire) return wire;
    if (msg.toLowerCase().includes('interrupted') || msg.toLowerCase().includes('timeout')) {
      return new PluginCallError('Unavailable', '插件调用超时，请重试。');
    }
    return new PluginCallError('Internal', msg || '插件内部错误');
  }

  _bindHostObject(vm) {
    const hostObj = vm.newObject();

    const addSync = (name, fn) => {
      const h = vm.newFunction(name, (...argHandles) => {
        const args = argHandles.map((a) => vm.dump(a));
        const out = fn(...args);
        if (out === undefined) return vm.undefined;
        if (out === null) return vm.null;
        if (typeof out === 'string') return vm.newString(out);
        if (typeof out === 'number') return vm.newNumber(out);
        if (typeof out === 'boolean') return out ? vm.true : vm.false;
        return vm.newString(JSON.stringify(out));
      });
      vm.setProp(hostObj, name, h);
      h.dispose();
    };

    const addAsync = (name, fn) => {
      const h = vm.newFunction(name, (...argHandles) => {
        const args = argHandles.map((a) => vm.dump(a));
        const deferred = vm.newPromise();
        const myVm = vm;
        const myRt = this.rt;
        Promise.resolve()
          .then(() => fn(...args))
          .then(
            (res) => {
              if (this.vm !== myVm || this.rt !== myRt) return;
              const valHandle =
                res === undefined ? myVm.undefined
                : res === null ? myVm.null
                : typeof res === 'string' ? myVm.newString(res)
                : typeof res === 'boolean' ? (res ? myVm.true : myVm.false)
                : typeof res === 'number' ? myVm.newNumber(res)
                : myVm.newString(JSON.stringify(res));
              deferred.resolve(valHandle);
              if (typeof res === 'string' || typeof res === 'number' || (res !== null && typeof res === 'object')) {
                valHandle.dispose();
              }
              try { this._drainJobs(); } catch {}
            },
            (err) => {
              if (this.vm !== myVm || this.rt !== myRt) return;
              const errHandle = myVm.newError(String(err?.message || err));
              deferred.reject(errHandle);
              errHandle.dispose();
              try { this._drainJobs(); } catch {}
            },
          );
        deferred.settled.then(() => {
          if (this.vm === myVm) {
            try { this._drainJobs(); } catch {}
          }
        });
        return deferred.handle;
      });
      vm.setProp(hostObj, name, h);
      h.dispose();
    };

    addSync('getConfigJson', () => JSON.stringify(this.config));
    addSync('getInstanceId', () => this.instanceId);
    addSync('getDeviceJson', () => JSON.stringify(this.device));
    addSync('getErrorMarker', () => ERR_MARKER);

    addAsync('sleep', (ms, gen) => new Promise((resolve) => {
      if (gen !== this.callGen) {
        resolve(false);
        return;
      }
      const clamped = Math.min(Math.max(Number(ms) || 0, 0), 60_000);
      const t = setTimeout(() => {
        this.activeTimers.delete(t);
        resolve(gen === this.callGen);
      }, clamped);
      this.activeTimers.add(t);
    }));

    addAsync('fetch', async (reqJson) => {
      const req = JSON.parse(reqJson);
      let body = null;
      if (req.bodyBase64 !== undefined) body = Buffer.from(req.bodyBase64, 'base64');
      else if (req.bodyText !== undefined) body = Buffer.from(req.bodyText, 'utf8');
      const resp = await this.fetchGateway.fetch({
        url: req.url,
        method: req.method,
        headers: req.headers,
        body,
        timeoutMs: req.timeoutMs,
        redirect: req.redirect,
      });
      return JSON.stringify({
        status: resp.status,
        url: resp.url,
        headers: resp.headers,
        bodyBase64: resp.body.toString('base64'),
      });
    });

    addSync('storageGet', (k) => {
      const v = this.storage.get(k);
      return v === null ? null : JSON.stringify(v);
    });
    addSync('storageSet', (k, vJson) => {
      this.storage.set(k, JSON.parse(vJson));
    });
    addSync('storageRemove', (k) => { this.storage.remove(k); });
    addSync('storageKeys', () => JSON.stringify(this.storage.keys()));

    addSync('cookiesGet', (u) => this.cookieJar.getHeaderForUrl(u));
    addSync('cookiesSet', (u, c) => { this.cookieJar.setCookie(u, c); });
    addSync('cookiesClear', () => { this.cookieJar.clear(); });

    addSync('cryptoHash', (algo, b64) => {
      const buf = Buffer.from(b64, 'base64');
      if (algo === 'md5') return pluginCrypto.md5(buf);
      if (algo === 'sha1') return pluginCrypto.sha1(buf);
      if (algo === 'sha256') return pluginCrypto.sha256(buf);
      throw new Error(`Unsupported hash: ${algo}`);
    });
    addSync('cryptoHmac', (algo, keyB64, dataB64) =>
      pluginCrypto.hmac(algo, Buffer.from(keyB64, 'base64'), Buffer.from(dataB64, 'base64')));
    addSync('cryptoAes', (encrypt, mode, keyB64, ivB64, dataB64, aadB64) => {
      const key = Buffer.from(keyB64, 'base64');
      const iv = Buffer.from(ivB64, 'base64');
      const data = Buffer.from(dataB64, 'base64');
      const aad = aadB64 ? Buffer.from(aadB64, 'base64') : null;
      const out = encrypt
        ? pluginCrypto.aesEncrypt(mode, key, iv, data, aad)
        : pluginCrypto.aesDecrypt(mode, key, iv, data, aad);
      return out.toString('base64');
    });
    addSync('cryptoRsaEncrypt', (pem, dataB64, padding) =>
      pluginCrypto.rsaEncrypt(pem, Buffer.from(dataB64, 'base64'), padding).toString('base64'));
    addSync('cryptoRandomBytes', (n) => pluginCrypto.randomBytes(n).toString('base64'));
    addSync('cryptoBase64Encode', (dataB64, urlSafe) =>
      pluginCrypto.base64Encode(Buffer.from(dataB64, 'base64'), urlSafe));
    addSync('cryptoBase64Decode', (str) => pluginCrypto.base64Decode(str).toString('base64'));
    addSync('cryptoHexEncode', (dataB64) => pluginCrypto.hexEncode(Buffer.from(dataB64, 'base64')));
    addSync('cryptoHexDecode', (hex) => pluginCrypto.hexDecode(hex).toString('base64'));

    addSync('parseHtml', (text) => JSON.stringify(this.domParser.parseHtml(text)));
    addSync('parseXml', (text) => JSON.stringify(this.domParser.parseXml(text)));
    addSync('domSelectFirst', (id, sel) => {
      const r = this.domParser.selectFirst(id, sel);
      return r ? JSON.stringify(r) : null;
    });
    addSync('domSelectAll', (id, sel) => JSON.stringify(this.domParser.selectAll(id, sel)));
    addSync('domChildren', (id) => JSON.stringify(this.domParser.children(id)));

    addSync('log', (level, msg) => { this.logger.log(level, msg); });
    addSync('translate', (key, varsJson) => {
      const vars = varsJson && varsJson !== 'null' ? JSON.parse(varsJson) : null;
      return this.locale.translate(key, vars);
    });

    vm.setProp(vm.global, '__d3_host', hostObj);
    hostObj.dispose();
  }
}
