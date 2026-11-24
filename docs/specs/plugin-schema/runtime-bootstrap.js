// Shared QuickJS sandbox bootstrap loaded by BOTH the Android host
// (bundled into resources from docs/specs/plugin-schema/runtime-bootstrap.js)
// and tools/plugin-kit (loaded directly from the same file).
//
// Host bridge isolation:
// The host injects a one-shot capability token via __d3_seal_bootstrap(token)
// (or captures __d3_host and deletes it if configurable). Every host call
// passes that private closure token so even if QuickJS binds the host object
// non-configurably on globalThis, plugin code cannot call any host bridge
// method directly.

(function () {
  const host = globalThis.__d3_host;
  try {
    delete globalThis.__d3_host;
  } catch (_) {
    // Non-configurable binding in some QuickJS bridges; sealed via private token below.
  }

  let BRIDGE_TOKEN = null;
  let initialized = false;
  let pluginExport = null;

  function deepFreeze(obj) {
    if (obj === null || typeof obj !== 'object') return obj;
    Object.freeze(obj);
    for (const key of Object.keys(obj)) {
      deepFreeze(obj[key]);
    }
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
    const u8 = bytes instanceof Uint8Array
      ? bytes
      : (bytes instanceof ArrayBuffer ? new Uint8Array(bytes) : new Uint8Array(bytes));
    let out = '';
    let i = 0;
    while (i < u8.length) {
      const b0 = u8[i++];
      if (b0 < 0x80) out += String.fromCharCode(b0);
      else if ((b0 & 0xE0) === 0xC0 && i < u8.length) {
        out += String.fromCharCode(((b0 & 0x1F) << 6) | (u8[i++] & 0x3F));
      } else if ((b0 & 0xF0) === 0xE0 && i + 1 < u8.length) {
        const b1 = u8[i++];
        const b2 = u8[i++];
        out += String.fromCharCode(((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F));
      } else if ((b0 & 0xF8) === 0xF0 && i + 2 < u8.length) {
        const b1 = u8[i++];
        const b2 = u8[i++];
        const b3 = u8[i++];
        const cp = (((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)) - 0x10000;
        out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
      } else {
        out += '\uFFFD';
      }
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
      const a = u8[i];
      const b = i + 1 < u8.length ? u8[i + 1] : 0;
      const c = i + 2 < u8.length ? u8[i + 2] : 0;
      const n = (a << 16) | (b << 8) | c;
      out += B64_CHARS[(n >> 18) & 63];
      out += B64_CHARS[(n >> 12) & 63];
      out += i + 1 < u8.length ? B64_CHARS[(n >> 6) & 63] : '=';
      out += i + 2 < u8.length ? B64_CHARS[n & 63] : '=';
    }
    return out;
  }

  function base64ToBytes(str) {
    const s = String(str).replace(/[\r\n\s=]+/g, '');
    const out = new Uint8Array(Math.floor((s.length * 3) / 4));
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

  function toArrayBuffer(val) {
    if (val instanceof ArrayBuffer) return val;
    if (ArrayBuffer.isView(val)) {
      if (val.byteOffset === 0 && val.byteLength === val.buffer.byteLength) {
        return val.buffer;
      }
      return val.buffer.slice(val.byteOffset, val.byteOffset + val.byteLength);
    }
    if (typeof val === 'string') return base64ToBytes(val).buffer;
    return new ArrayBuffer(0);
  }

  function toInputBase64(input) {
    if (input instanceof Uint8Array) return bytesToBase64(input);
    if (ArrayBuffer.isView(input)) {
      return bytesToBase64(new Uint8Array(input.buffer, input.byteOffset, input.byteLength));
    }
    if (input instanceof ArrayBuffer) return bytesToBase64(new Uint8Array(input));
    return bytesToBase64(utf8Encode(input ?? ''));
  }

  if (typeof globalThis.TextEncoder === 'undefined') {
    globalThis.TextEncoder = class TextEncoder {
      encode(s) { return utf8Encode(s); }
    };
  }
  if (typeof globalThis.TextDecoder === 'undefined') {
    globalThis.TextDecoder = class TextDecoder {
      decode(b) { return utf8Decode(b); }
    };
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
              const k = decodeURIComponent((idx >= 0 ? part.slice(0, idx) : part).replace(/\+/g, ' '));
              const v = decodeURIComponent((idx >= 0 ? part.slice(idx + 1) : '').replace(/\+/g, ' '));
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
      get(k) {
        const f = this._pairs.find(([key]) => key === String(k));
        return f ? f[1] : null;
      }
      delete(k) { this._pairs = this._pairs.filter(([key]) => key !== String(k)); }
      toString() {
        return this._pairs
          .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v))
          .join('&');
      }
    };
  }

  // Single-call scoped setTimeout / clearTimeout with true host cancellation
  // and strict per-instance active timer limit (256 max, §11.1).
  const MAX_ACTIVE_TIMERS = 256;
  const MAX_RETURN_BYTES = 8 * 1024 * 1024;
  const MAX_FETCH_URL_BYTES = 8 * 1024;
  const MAX_FETCH_HEADERS_BYTES = 32 * 1024;
  const MAX_FETCH_BODY_BYTES = 2 * 1024 * 1024;

  function utf8ByteLength(str) {
    const s = String(str);
    let n = 0;
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c < 0x80) n += 1;
      else if (c < 0x800) n += 2;
      else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
        n += 4;
        i += 1;
      } else n += 3;
    }
    return n;
  }
  let currentGen = 0;
  let nextTimerId = 1;
  const activeTimerIds = new Set();
  const cancelledTimers = new Set();
  let strictAwaitTracking = false;
  let trackedAsyncOps = [];

  globalThis.setTimeout = function (fn, delayMs, ...args) {
    if (typeof fn !== 'function') throw new TypeError('setTimeout requires a function');
    if (activeTimerIds.size >= MAX_ACTIVE_TIMERS) {
      throw new RangeError('同时活跃的定时器数量超过上限（最多 ' + MAX_ACTIVE_TIMERS + ' 个）');
    }
    const id = nextTimerId++;
    const gen = currentGen;
    const ms = Number(delayMs) || 0;
    activeTimerIds.add(id);
    host.sleep(BRIDGE_TOKEN, id, ms, gen).then((stillActive) => {
      activeTimerIds.delete(id);
      if (stillActive && gen === currentGen && !cancelledTimers.has(id)) {
        cancelledTimers.delete(id);
        fn(...args);
      }
    }).catch(() => {
      activeTimerIds.delete(id);
    });
    return id;
  };

  globalThis.clearTimeout = function (id) {
    const numId = Number(id);
    if (!Number.isFinite(numId)) return;
    activeTimerIds.delete(numId);
    cancelledTimers.add(numId);
    try {
      host.cancelTimer(BRIDGE_TOKEN, numId);
    } catch (_) {}
  };

  class TrackedPromise extends Promise {
    constructor(executor, record) {
      super(executor);
      this.__d3Record = record || null;
    }
    then(onFulfilled, onRejected) {
      if (this.__d3Record) this.__d3Record.awaited = true;
      return super.then(onFulfilled, onRejected);
    }
    catch(onRejected) {
      if (this.__d3Record) this.__d3Record.awaited = true;
      return super.catch(onRejected);
    }
    finally(onFinally) {
      if (this.__d3Record) this.__d3Record.awaited = true;
      return super.finally(onFinally);
    }
  }

  function trackAwaitableOp(opName, promise) {
    if (!strictAwaitTracking) return promise;
    const stack = (new Error(opName)).stack || '';
    const record = { op: opName, stack: String(stack), awaited: false };
    trackedAsyncOps.push(record);
    return new TrackedPromise((resolve, reject) => {
      promise.then(resolve, reject);
    }, record);
  }

  // Lazy DOM handle wrapper (§11.1, S4): never eagerly serializes html/outerHtml/text
  function wrapLazyNode(nodeId) {
    if (nodeId === null || nodeId === undefined || nodeId <= 0) return null;
    const el = {
      get tag() { return host.domTag(BRIDGE_TOKEN, nodeId); },
      get text() { return host.domText(BRIDGE_TOKEN, nodeId); },
      get html() { return host.domHtml(BRIDGE_TOKEN, nodeId); },
      get outerHtml() { return host.domOuterHtml(BRIDGE_TOKEN, nodeId); },
      get attrs() { return deepFreeze(JSON.parse(host.domAttrs(BRIDGE_TOKEN, nodeId))); },
      attr(name) {
        const v = host.domAttr(BRIDGE_TOKEN, nodeId, String(name));
        return v === undefined ? null : v;
      },
      querySelector(sel) {
        const childId = host.domSelectFirstId(BRIDGE_TOKEN, nodeId, String(sel));
        return childId > 0 ? wrapLazyNode(childId) : null;
      },
      selectFirst(sel) { return el.querySelector(sel); },
      querySelectorAll(sel) {
        const ids = JSON.parse(host.domSelectAllIds(BRIDGE_TOKEN, nodeId, String(sel)));
        return ids.map(wrapLazyNode);
      },
      select(sel) { return el.querySelectorAll(sel); },
      get children() {
        const ids = JSON.parse(host.domChildrenIds(BRIDGE_TOKEN, nodeId));
        return ids.map(wrapLazyNode);
      },
    };
    return Object.freeze(el);
  }

  // Protocol §6 & §11.1 ctx.Error(code, message?, { retryAfterSec? })
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
      try { return JSON.stringify(a); } catch (_) { return String(a); }
    }).join(' ');
  }

  let ERR_MARKER = '__D3_PLUGIN_ERR__:';
  let ctx = null;

  function buildCtx() {
    ERR_MARKER = host.getErrorMarker(BRIDGE_TOKEN);
    return Object.freeze({
      Error: PluginError,
      config: deepFreeze(JSON.parse(host.getConfigJson(BRIDGE_TOKEN))),
      instanceId: host.getInstanceId(BRIDGE_TOKEN),
      device: deepFreeze(JSON.parse(host.getDeviceJson(BRIDGE_TOKEN))),

      async fetch(url, opts) {
        const o = opts || {};
        const urlStr = String(url);
        if (utf8ByteLength(urlStr) > MAX_FETCH_URL_BYTES) {
          throw new RangeError('请求 URL 超过大小限制（上限 8 KB）');
        }
        const rawMethod = o.method !== undefined && o.method !== null ? String(o.method) : 'GET';
        if (rawMethod.length > 16) {
          throw new RangeError('请求 method 过长');
        }
        const method = rawMethod.toUpperCase();
        if (!['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].includes(method)) {
          throw new RangeError('不支持的 HTTP method：' + method);
        }
        const rawRedirect = o.redirect !== undefined && o.redirect !== null ? String(o.redirect) : 'follow';
        if (rawRedirect.length > 16) {
          throw new RangeError('请求 redirect 参数过长');
        }
        const redirect = rawRedirect.toLowerCase();
        if (!['follow', 'manual', 'error'].includes(redirect)) {
          throw new RangeError('不支持的 redirect 模式：' + redirect);
        }
        const req = {
          url: urlStr,
          method,
          headers: {},
          redirect,
        };
        if (o.timeoutMs !== undefined && o.timeoutMs !== null) {
          const t = Number(o.timeoutMs);
          if (!Number.isFinite(t) || t < 0) throw new RangeError('无效的 timeoutMs 参数');
          req.timeoutMs = t;
        }
        let headersBytes = 0;
        if (o.headers && typeof o.headers === 'object') {
          for (const [k, v] of Object.entries(o.headers)) {
            if (v !== undefined && v !== null) {
              const ks = String(k);
              const vs = String(v);
              headersBytes += utf8ByteLength(ks) + utf8ByteLength(vs) + 4;
              if (headersBytes > MAX_FETCH_HEADERS_BYTES) {
                throw new RangeError('请求头总大小超过限制（上限 32 KB）');
              }
              req.headers[ks] = vs;
            }
          }
        }
        if (o.body !== undefined && o.body !== null) {
          if (o.body instanceof Uint8Array || ArrayBuffer.isView(o.body) || o.body instanceof ArrayBuffer) {
            const byteLen = o.body instanceof ArrayBuffer ? o.body.byteLength : o.body.byteLength;
            if (byteLen > MAX_FETCH_BODY_BYTES) {
              throw new RangeError('请求体超过大小限制（上限 2 MB）');
            }
            req.bodyBase64 = toInputBase64(o.body);
          } else if (typeof o.body === 'string') {
            if (utf8ByteLength(o.body) > MAX_FETCH_BODY_BYTES) {
              throw new RangeError('请求体超过大小限制（上限 2 MB）');
            }
            req.bodyText = o.body;
          } else {
            const serialized = JSON.stringify(o.body);
            if (utf8ByteLength(serialized) > MAX_FETCH_BODY_BYTES) {
              throw new RangeError('请求体超过大小限制（上限 2 MB）');
            }
            req.bodyText = serialized;
            if (!Object.keys(req.headers).some((k) => k.toLowerCase() === 'content-type')) {
              const ctKey = 'Content-Type';
              const ctVal = 'application/json; charset=utf-8';
              headersBytes += utf8ByteLength(ctKey) + utf8ByteLength(ctVal) + 4;
              if (headersBytes > MAX_FETCH_HEADERS_BYTES) {
                throw new RangeError('请求头总大小超过限制（上限 32 KB）');
              }
              req.headers[ctKey] = ctVal;
            }
          }
        }
        const rawMeta = await host.fetchMeta(BRIDGE_TOKEN, JSON.stringify(req), currentGen);
        const meta = JSON.parse(rawMeta);
        const handleId = meta.handleId;
        const headers = deepFreeze(meta.headers || {});
        // Single-consume Response body matching Web Fetch semantics & releasing host
        // in-flight byte budget immediately upon first read (Android & plugin-kit parity).
        let bodyConsumed = false;
        function consumeBody(mode) {
          if (bodyConsumed) {
            throw new TypeError('Response body already consumed');
          }
          bodyConsumed = true;
          if (mode === 'bytes') {
            return toArrayBuffer(host.fetchReadBytes(BRIDGE_TOKEN, handleId));
          }
          return host.fetchReadText(BRIDGE_TOKEN, handleId);
        }
        const resp = {
          status: meta.status,
          ok: meta.status >= 200 && meta.status < 300,
          url: meta.url,
          headers,
          bytes() {
            return consumeBody('bytes');
          },
          text() {
            return consumeBody('text');
          },
          json() {
            return JSON.parse(consumeBody('text'));
          },
        };
        return Object.freeze(resp);
      },

      // §11.1: ctx.storage.* are async and MUST be awaited
      storage: Object.freeze({
        get(key) {
          return trackAwaitableOp('ctx.storage.get', (async () => {
            const raw = await host.storageGet(BRIDGE_TOKEN, String(key));
            return raw === null || raw === undefined ? null : JSON.parse(raw);
          })());
        },
        set(key, value) {
          return trackAwaitableOp('ctx.storage.set', (async () => {
            await host.storageSet(BRIDGE_TOKEN, String(key), JSON.stringify(value === undefined ? null : value));
          })());
        },
        remove(key) {
          return trackAwaitableOp('ctx.storage.remove', (async () => {
            await host.storageRemove(BRIDGE_TOKEN, String(key));
          })());
        },
        keys() {
          return trackAwaitableOp('ctx.storage.keys', (async () => {
            const raw = await host.storageKeys(BRIDGE_TOKEN);
            return JSON.parse(raw);
          })());
        },
      }),

      // §11.1: ctx.cookies.* are async and MUST be awaited
      cookies: Object.freeze({
        get(url) {
          return trackAwaitableOp('ctx.cookies.get', (async () => await host.cookiesGet(BRIDGE_TOKEN, String(url)))());
        },
        set(url, cookie) {
          return trackAwaitableOp('ctx.cookies.set', (async () => {
            await host.cookiesSet(BRIDGE_TOKEN, String(url), String(cookie));
          })());
        },
        clear() {
          return trackAwaitableOp('ctx.cookies.clear', (async () => {
            await host.cookiesClear(BRIDGE_TOKEN);
          })());
        },
      }),

      crypto: Object.freeze({
        md5(data) { return host.cryptoHash(BRIDGE_TOKEN, 'md5', toInputBase64(data)); },
        sha1(data) { return host.cryptoHash(BRIDGE_TOKEN, 'sha1', toInputBase64(data)); },
        sha256(data) { return host.cryptoHash(BRIDGE_TOKEN, 'sha256', toInputBase64(data)); },
        hmac(algo, key, data) {
          return host.cryptoHmac(BRIDGE_TOKEN, String(algo), toInputBase64(key), toInputBase64(data));
        },
        aes: Object.freeze({
          encrypt(modeOrOpts, key, iv, data, aad) {
            if (modeOrOpts && typeof modeOrOpts === 'object') {
              const o = modeOrOpts;
              return base64ToBytes(
                host.cryptoAes(
                  BRIDGE_TOKEN, true, String(o.mode || 'GCM'),
                  toInputBase64(o.key), toInputBase64(o.iv), toInputBase64(o.data),
                  o.aad !== undefined ? toInputBase64(o.aad) : ''
                )
              );
            }
            return base64ToBytes(
              host.cryptoAes(
                BRIDGE_TOKEN, true, String(modeOrOpts),
                toInputBase64(key), toInputBase64(iv), toInputBase64(data),
                aad !== undefined ? toInputBase64(aad) : ''
              )
            );
          },
          decrypt(modeOrOpts, key, iv, data, aad) {
            if (modeOrOpts && typeof modeOrOpts === 'object') {
              const o = modeOrOpts;
              return base64ToBytes(
                host.cryptoAes(
                  BRIDGE_TOKEN, false, String(o.mode || 'GCM'),
                  toInputBase64(o.key), toInputBase64(o.iv), toInputBase64(o.data),
                  o.aad !== undefined ? toInputBase64(o.aad) : ''
                )
              );
            }
            return base64ToBytes(
              host.cryptoAes(
                BRIDGE_TOKEN, false, String(modeOrOpts),
                toInputBase64(key), toInputBase64(iv), toInputBase64(data),
                aad !== undefined ? toInputBase64(aad) : ''
              )
            );
          },
        }),
        rsa: Object.freeze({
          encrypt(pubOrOpts, data, padding) {
            if (pubOrOpts && typeof pubOrOpts === 'object') {
              return base64ToBytes(
                host.cryptoRsaEncrypt(BRIDGE_TOKEN, String(pubOrOpts.publicKey), toInputBase64(pubOrOpts.data), String(pubOrOpts.padding || 'PKCS1'))
              );
            }
            return base64ToBytes(
              host.cryptoRsaEncrypt(BRIDGE_TOKEN, String(pubOrOpts), toInputBase64(data), String(padding || 'PKCS1'))
            );
          },
        }),
        randomBytes(n) {
          return base64ToBytes(host.cryptoRandomBytes(BRIDGE_TOKEN, Number(n)));
        },
        base64: Object.freeze({
          encode(data, urlSafe) {
            return host.cryptoBase64Encode(BRIDGE_TOKEN, toInputBase64(data), Boolean(urlSafe));
          },
          decode(str) {
            return base64ToBytes(host.cryptoBase64Decode(BRIDGE_TOKEN, String(str)));
          },
        }),
        hex: Object.freeze({
          encode(data) {
            return host.cryptoHexEncode(BRIDGE_TOKEN, toInputBase64(data));
          },
          decode(str) {
            return base64ToBytes(host.cryptoHexDecode(BRIDGE_TOKEN, String(str)));
          },
        }),
      }),

      parse: Object.freeze({
        html(text) { return wrapLazyNode(host.parseHtml(BRIDGE_TOKEN, String(text ?? ''))); },
        xml(text) { return wrapLazyNode(host.parseXml(BRIDGE_TOKEN, String(text ?? ''))); },
      }),

      log: Object.freeze({
        debug(...args) { host.log(BRIDGE_TOKEN, 'DEBUG', formatLogArgs(args)); },
        info(...args) { host.log(BRIDGE_TOKEN, 'INFO', formatLogArgs(args)); },
        warn(...args) { host.log(BRIDGE_TOKEN, 'WARN', formatLogArgs(args)); },
        error(...args) { host.log(BRIDGE_TOKEN, 'ERROR', formatLogArgs(args)); },
      }),

      t(key, vars) {
        return host.translate(BRIDGE_TOKEN, String(key), vars ? JSON.stringify(vars) : 'null');
      },
    });
  }

  globalThis.__d3_seal_bootstrap = function (token) {
    if (initialized) throw new Error('Bootstrap already sealed');
    initialized = true;
    BRIDGE_TOKEN = String(token);
    strictAwaitTracking = typeof host.isStrictAwaitTracking === 'function'
      ? Boolean(host.isStrictAwaitTracking(BRIDGE_TOKEN))
      : false;
    ctx = buildCtx();
    try { delete globalThis.__d3_seal_bootstrap; } catch (_) {}
  };

  globalThis.__d3_register = function (mod) {
    if (!mod || typeof mod !== 'object') {
      throw new Error('Plugin main.js must export a default object');
    }
    pluginExport = mod;
    try { delete globalThis.__d3_register; } catch (_) {}
  };

  function defineReadOnlyGlobal(name, fn) {
    Object.defineProperty(globalThis, name, {
      value: fn,
      writable: false,
      configurable: false,
      enumerable: false,
    });
  }

  defineReadOnlyGlobal('__d3_exports', function () {
    if (!pluginExport) return '[]';
    return JSON.stringify(Object.keys(pluginExport).filter((k) => typeof pluginExport[k] === 'function'));
  });

  async function callPlugin(method, argJson, hasArg, gen, optional) {
    currentGen = gen;
    activeTimerIds.clear();
    cancelledTimers.clear();
    trackedAsyncOps = [];
    if (!pluginExport) throw new Error('Plugin not loaded');
    const fn = pluginExport[method];
    if (typeof fn !== 'function') {
      if (optional) return 'undefined';
      throw new Error('Plugin does not implement method: ' + method);
    }
    let succeeded = false;
    try {
      const res = hasArg
        ? await fn.call(pluginExport, ctx, JSON.parse(argJson))
        : await fn.call(pluginExport, ctx);
      if (res === undefined) {
        succeeded = true;
        return 'undefined';
      }
      const serialized = JSON.stringify(res);
      if (serialized && serialized.length > MAX_RETURN_BYTES) {
        throw new Error('Plugin return value exceeds 8 MB size limit');
      }
      succeeded = true;
      return serialized;
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
    } finally {
      if (strictAwaitTracking && succeeded && trackedAsyncOps.length > 0) {
        const unawaited = trackedAsyncOps.filter((op) => !op.awaited);
        if (unawaited.length > 0 && typeof host.reportUnawaitedOps === 'function') {
          try {
            host.reportUnawaitedOps(BRIDGE_TOKEN, JSON.stringify(unawaited));
          } catch (_) {}
        }
      }
      trackedAsyncOps = [];
      // Cancel all remaining timers & in-flight fetches as soon as the call's Promise settles (§11.1)
      currentGen = -1;
      activeTimerIds.clear();
      cancelledTimers.clear();
      try { host.finishCall(BRIDGE_TOKEN, gen); } catch (_) {}
    }
  }

  defineReadOnlyGlobal('__d3_invoke', function (method, argJson, hasArg, gen) {
    return callPlugin(method, argJson, hasArg, gen, false);
  });
  defineReadOnlyGlobal('__d3_invoke_optional', function (method, argJson, hasArg, gen) {
    return callPlugin(method, argJson, hasArg, gen, true);
  });
  defineReadOnlyGlobal('__d3_eval_probe', async function (fnCode, gen) {
    currentGen = gen;
    activeTimerIds.clear();
    cancelledTimers.clear();
    trackedAsyncOps = [];
    try {
      const fn = new Function('ctx', 'return (async () => { ' + fnCode + ' })();');
      const res = await fn(ctx);
      if (res === undefined) return 'undefined';
      const serialized = JSON.stringify(res);
      if (serialized && serialized.length > MAX_RETURN_BYTES) {
        throw new Error('Plugin return value exceeds 8 MB size limit');
      }
      return serialized;
    } finally {
      currentGen = -1;
      activeTimerIds.clear();
      cancelledTimers.clear();
      trackedAsyncOps = [];
      try { host.finishCall(BRIDGE_TOKEN, gen); } catch (_) {}
    }
  });
})();
