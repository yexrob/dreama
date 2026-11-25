import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { getQuickJS } from 'quickjs-emscripten';
import { pluginCrypto } from './crypto.js';
import { PluginCookieJar } from './cookies.js';
import { DomLimitExceededError, DomTimeoutError, PluginDomParser } from './dom.js';
import { FetchGateway, FetchRequestTooLargeError, MAX_FETCH_META_JSON_BYTES, MAX_QUEUED_REQUEST_BODY_BYTES } from './fetch-gateway.js';
import { PluginLocale } from './locale.js';
import { MAX_LOG_MESSAGE_BYTES, PluginLogger, truncateUtf8 } from './logger.js';
import { NetworkPolicy, extractUserHosts } from './network-policy.js';
import { SCHEMA_DIR } from './schema.js';
import { PluginStorage } from './storage.js';

const ERR_MARKER = '__D3_PLUGIN_ERR__:';
/** §11.1 (B2): 64 MB QuickJS heap limit, matching Android PluginRuntime.kt. */
export const DEFAULT_MEMORY_LIMIT = 64 * 1024 * 1024;
export const MAX_RETURN_BYTES = 8 * 1024 * 1024;
export const MAX_ACTIVE_TIMERS = 256;
export const DEFAULT_TIMEOUT_MS = 15_000;
export const PLAYBACK_TIMEOUT_MS = 30_000;

/**
 * S9: Single shared bootstrap script loaded from docs/specs/plugin-schema/runtime-bootstrap.js
 * (the exact same file bundled into Android resources by Gradle).
 */
const BOOTSTRAP_JS = fs.readFileSync(path.join(SCHEMA_DIR, 'runtime-bootstrap.js'), 'utf8');

export class PluginCallError extends Error {
  constructor(code, message, retryAfterSec = null) {
    super(message || code);
    this.name = 'PluginCallError';
    this.code = code || 'Internal';
    this.retryAfterSec = retryAfterSec;
  }
}

class HostExecutionTimeoutError extends Error {
  constructor(message = 'Host execution timeout') {
    super(message);
    this.name = 'HostExecutionTimeoutError';
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

export class QuickJsPluginHost {
  constructor({
    manifest,
    mainJs,
    config = {},
    instanceId = 'dev-instance',
    storage = new PluginStorage(),
    cookieJar = null,
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
    strictAwait = true,
  }) {
    this.manifest = manifest;
    this.mainJs = mainJs;
    this.config = this._applyDefaults(manifest, config);
    this.instanceId = instanceId;
    this.storage = storage;
    this.locale = locale;
    this.device = device;
    this.memoryLimitBytes = memoryLimitBytes;
    this.defaultTimeoutMs = defaultTimeoutMs;
    this.playbackTimeoutMs = playbackTimeoutMs;
    this.strictAwait = Boolean(strictAwait);

    const secretKeys = (manifest.config || []).filter((f) => f.type === 'password').map((f) => f.key);
    const knownSecrets = secretKeys.map((k) => this.config[k]).filter((v) => typeof v === 'string');
    this.logger = new PluginLogger(manifest.id, instanceId, knownSecrets, logSink);

    if (cookieJar) {
      this.cookieJar = cookieJar;
      const prevWarn = cookieJar.onWarning;
      cookieJar.onWarning = (msg) => {
        this.logger.log('WARN', msg);
        if (typeof prevWarn === 'function') prevWarn(msg);
      };
    } else {
      this.cookieJar = new PluginCookieJar(() => Date.now(), {
        onWarning: (msg) => this.logger.log('WARN', msg),
      });
    }

    const userHosts = extractUserHosts(manifest, this.config);
    this.networkPolicy = new NetworkPolicy(manifest.permissions || {}, userHosts, dnsResolver);
    this.fetchGateway = new FetchGateway(this.networkPolicy, this.cookieJar, { transportOverride });
    this.domParser = new PluginDomParser();

    this.rt = null;
    this.vm = null;
    this.bridgeToken = null;
    this.initialized = false;
    this.creationCount = 0;
    this.callGen = 0;
    this.deadlineMs = Infinity;
    this._lastCallTimedOut = false;
    this._interruptTriggered = false;
    this._bridgeFaultInjectorForTesting = null;
    this._unawaitedOps = [];
    /** Map<timerId, { timer: NodeJS.Timeout, resolve: (val: boolean) => void }> */
    this.activeTimers = new Map();
    /** Set<QuickJSDeferredPromise> tracked so _destroyVm disposes any unsettled handles cleanly */
    this.pendingDeferreds = new Set();
    /** Map<handleId, { buf: Buffer, bytes: number }> */
    this.callResponseBodies = new Map();
    this.nextResponseHandle = 1;
    /** Serial execution chain per instance (§11.1) */
    this._serialQueue = Promise.resolve();
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

  _serialized(task) {
    const next = this._serialQueue.then(task, task);
    this._serialQueue = next.catch(() => {});
    return next;
  }

  async _ensureInitialized(timeoutMs) {
    if (this.rt && this.vm && this.initialized) return;
    this._destroyVm();

    const QuickJS = await loadQuickJS();
    const rt = QuickJS.newRuntime();
    rt.setMemoryLimit(this.memoryLimitBytes);
    rt.setMaxStackSize(512 * 1024);
    rt.setInterruptHandler(() => {
      if (Date.now() > this.deadlineMs) {
        this._interruptTriggered = true;
        return true;
      }
      return false;
    });
    rt.setModuleLoader((moduleName) => {
      if (moduleName === 'plugin/main.js' || moduleName === './main.js' || moduleName === 'main.js') {
        return this.mainJs;
      }
      return { error: new Error(`Unauthorized module import: ${moduleName}`) };
    });

    const vm = rt.newContext();
    this.rt = rt;
    this.vm = vm;
    this.bridgeToken = crypto.randomBytes(16).toString('hex');
    this.creationCount += 1;

    try {
      this._bindHostObject(vm, this.bridgeToken);
      this.deadlineMs = Date.now() + timeoutMs;
      this._interruptTriggered = false;
      this._lastCallTimedOut = false;
      this._unawaitedOps = [];
      const bootHandle = this._unwrapOrThrow(vm, vm.evalCode(BOOTSTRAP_JS, '__d3_bootstrap.js'));
      bootHandle.dispose();
      const sealHandle = this._unwrapOrThrow(
        vm,
        vm.evalCode(`__d3_seal_bootstrap(${JSON.stringify(this.bridgeToken)})`, '__d3_seal.js'),
      );
      sealHandle.dispose();
      const entryRes = vm.evalCode(
        `import __p from 'plugin/main.js'; globalThis.__d3_register(__p);`,
        '__d3_entry.js',
        { type: 'module' },
      );
      const entryHandle = this._unwrapOrThrow(vm, entryRes);
      entryHandle.dispose();
      this._drainJobs();

      // Run optional init(ctx) — if it fails, destroy VM so next call retries init (§11.1)
      const gen = ++this.callGen;
      await this._evalAsyncExpr(`__d3_invoke_optional('init', 'null', false, ${gen})`, timeoutMs);
      this._checkUnawaitedOps('init');
      this.initialized = true;
    } catch (err) {
      this._destroyVm();
      throw this._mapHostError(err);
    } finally {
      this._cleanupAfterCall();
      this.deadlineMs = Infinity;
    }
  }

  async exportedMethods() {
    return this._serialized(async () => {
      await this._ensureInitialized(this.defaultTimeoutMs);
      const res = this._unwrapOrThrow(this.vm, this.vm.evalCode('__d3_exports()', '__d3_exports.js'));
      const raw = this.vm.dump(res);
      res.dispose();
      return JSON.parse(raw);
    });
  }

  async invoke(method, arg = undefined, opts = {}) {
    return this._serialized(async () => {
      const timeoutMs = opts.timeoutMs ?? (method === 'playback' ? this.playbackTimeoutMs : this.defaultTimeoutMs);
      await this._ensureInitialized(timeoutMs);

      const gen = ++this.callGen;
      this.domParser.clear();
      this._releaseResponseBodies();
      this._lastCallTimedOut = false;
      this._interruptTriggered = false;
      this._unawaitedOps = [];
      const hasArg = arg !== undefined;
      const argJson = hasArg ? JSON.stringify(arg) : 'null';
      const expr = `__d3_invoke(${JSON.stringify(method)}, ${JSON.stringify(argJson)}, ${hasArg}, ${gen})`;

      try {
        const raw = await this._evalAsyncExpr(expr, timeoutMs);
        this._checkUnawaitedOps(method);
        if (raw === undefined || raw === null || raw === 'undefined') return undefined;
        if (typeof raw === 'string' &&
            (raw.length > MAX_RETURN_BYTES || Buffer.byteLength(raw, 'utf8') > MAX_RETURN_BYTES)) {
          throw new PluginCallError('Internal', '插件返回值超过 8 MB 大小上限');
        }
        return JSON.parse(raw);
      } catch (err) {
        const mapped = this._mapHostError(err);
        // Only destroy & rebuild VM on genuine host timeout/interrupt or OOM,
        // NEVER when plugin explicitly throws new ctx.Error('Unavailable', ...)
        if (this._lastCallTimedOut || this._isOom(err)) {
          this._destroyVm();
        }
        throw mapped;
      } finally {
        this.callGen += 1;
        this._cleanupAfterCall();
      }
    });
  }

  async runProbe(code, timeoutMs = 5000) {
    return this._serialized(async () => {
      await this._ensureInitialized(timeoutMs);
      const gen = ++this.callGen;
      this.domParser.clear();
      this._releaseResponseBodies();
      this._lastCallTimedOut = false;
      this._interruptTriggered = false;
      this._unawaitedOps = [];
      try {
        const raw = await this._evalAsyncExpr(`__d3_eval_probe(${JSON.stringify(code)}, ${gen})`, timeoutMs);
        if (raw === undefined || raw === 'undefined') return undefined;
        if (typeof raw === 'string' &&
            (raw.length > MAX_RETURN_BYTES || Buffer.byteLength(raw, 'utf8') > MAX_RETURN_BYTES)) {
          throw new PluginCallError('Internal', '插件返回值超过 8 MB 大小上限');
        }
        return JSON.parse(raw);
      } catch (err) {
        if (this._lastCallTimedOut || this._isOom(err)) {
          this._destroyVm();
        }
        throw err;
      } finally {
        this.callGen += 1;
        this._cleanupAfterCall();
      }
    });
  }

  async dispose() {
    return this._serialized(async () => {
      if (this.rt && this.vm && this.initialized) {
        try {
          const gen = ++this.callGen;
          await this._evalAsyncExpr(`__d3_invoke_optional('dispose', 'null', false, ${gen})`, 2000);
        } catch {
          // best effort
        }
      }
      this._destroyVm();
    });
  }

  _checkUnawaitedOps(method) {
    if (!this.strictAwait || this._unawaitedOps.length === 0) return;
    const details = this._unawaitedOps.map((item) => {
      const site = String(item.stack || '')
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l && !l.includes('__d3_bootstrap.js'))
        .slice(0, 3)
        .join(' <- ');
      return `${item.op}${site ? ` (${site})` : ''}`;
    });
    this._unawaitedOps = [];
    throw new PluginCallError(
      'Internal',
      `${method}() 存在未 await 的异步存储/Cookie 调用（§11.1 要求全部 await）：${details.join('; ')}`,
    );
  }

  _cleanupAfterCall() {
    this._clearActiveTimers();
    this.fetchGateway.cancelAllActive();
    this._releaseResponseBodies();
    this.domParser.clear();
    try { this.cookieJar.flush(); } catch {}
  }

  _releaseResponseBodies() {
    for (const entry of this.callResponseBodies.values()) {
      this.fetchGateway.inFlightBudget.release(entry.bytes);
    }
    this.callResponseBodies.clear();
  }

  /**
   * Disposes any orphan QuickJSContext accidentally instantiated inside
   * `quickjs-emscripten`'s `executePendingJobs()` when `WebAssembly.Memory.grow()`
   * detaches its temporary `ctxPtrOut` TypedArray view (prevents
   * `Assertion failed: list_empty(&rt->gc_obj_list)` on `JS_FreeRuntime`).
   */
  _disposeStrayContexts() {
    if (!this.rt?.contextMap) return;
    for (const ctx of [...this.rt.contextMap.values()]) {
      if (ctx !== this.vm && ctx !== this.rt.context) {
        try {
          if (ctx.alive) ctx.dispose();
        } catch {}
      }
    }
  }

  _destroyVm() {
    this._cleanupAfterCall();
    for (const deferred of [...this.pendingDeferreds]) {
      this.pendingDeferreds.delete(deferred);
      try { deferred.dispose(); } catch {}
    }
    this._disposeStrayContexts();
    if (this.vm) {
      try { this.vm.dispose(); } catch {}
      this.vm = null;
    }
    if (this.rt?.contextMap) {
      for (const ctx of [...this.rt.contextMap.values()]) {
        if (ctx !== this.rt.context) {
          try { if (ctx.alive) ctx.dispose(); } catch {}
        }
      }
    }
    if (this.rt) {
      try { this.rt.dispose(); } catch {}
      this.rt = null;
    }
    this.bridgeToken = null;
    this.initialized = false;
  }

  _cancelTimerById(timerId) {
    const entry = this.activeTimers.get(timerId);
    if (!entry) return;
    this.activeTimers.delete(timerId);
    clearTimeout(entry.timer);
    entry.resolve(false);
  }

  _clearActiveTimers() {
    for (const entry of this.activeTimers.values()) {
      clearTimeout(entry.timer);
      entry.resolve(false);
    }
    this.activeTimers.clear();
  }

  _drainJobs() {
    if (!this.rt) return;
    while (true) {
      const r = this.rt.executePendingJobs();
      if (r.error) {
        const errCtx = r.error.context || this.vm;
        const dumped = errCtx.dump(r.error);
        r.error.dispose();
        this._disposeStrayContexts();
        throw dumped;
      }
      this._disposeStrayContexts();
      if (r.value === 0) break;
    }
  }

  async _evalAsyncExpr(expr, timeoutMs) {
    const vm = this.vm;
    this.deadlineMs = Date.now() + timeoutMs;
    this.domParser.setCallDeadlineMs(this.deadlineMs);

    let timer = null;
    let timedOut = false;
    try {
      const evalRes = vm.evalCode(expr, '__d3_call.js');
      const promiseHandle = this._unwrapOrThrow(vm, evalRes);
      const nativePromise = vm.resolvePromise(promiseHandle);
      promiseHandle.dispose();
      // Ensure late resolution after a timeout disposes its QuickJSHandle cleanly
      const guardedNative = nativePromise.then((res) => {
        if (timedOut) {
          try {
            if (res.error) res.error.dispose();
            else if (res.value) res.value.dispose();
          } catch {}
        }
        return res;
      });
      this._drainJobs();

      const timeoutPromise = new Promise((_, reject) => {
        timer = setTimeout(() => {
          timedOut = true;
          this._lastCallTimedOut = true;
          this.fetchGateway.cancelAllActive();
          this._clearActiveTimers();
          reject(new HostExecutionTimeoutError('interrupted: execution timeout'));
        }, timeoutMs);
      });

      const settled = await Promise.race([guardedNative, timeoutPromise]);
      if (settled.error) {
        const errVal = vm.dump(settled.error);
        settled.error.dispose();
        throw errVal;
      }
      const value = vm.dump(settled.value);
      settled.value.dispose();
      return value;
    } catch (err) {
      if (this._interruptTriggered) {
        this._lastCallTimedOut = true;
      }
      throw err;
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
    if (err instanceof HostExecutionTimeoutError || err instanceof DomTimeoutError || this._lastCallTimedOut || this._interruptTriggered) {
      this._lastCallTimedOut = true;
      return new PluginCallError('Unavailable', '插件调用超时，请重试。');
    }
    if (err instanceof DomLimitExceededError) {
      return new PluginCallError('Internal', err.message || 'DOM 资源超限');
    }
    const msg = String(err?.message || err || '');
    const wire = parseWireError(msg);
    if (wire) return wire;
    return new PluginCallError('Internal', msg || '插件内部错误');
  }

  _bindHostObject(vm, expectedToken) {
    const hostObj = vm.newObject();

    const requireToken = (args) => {
      if (args.length === 0 || args[0] !== expectedToken) {
        throw new Error('Unauthorized host bridge call');
      }
      return args.slice(1);
    };

    const toHandle = (myVm, out) => {
      if (out === undefined) return { handle: myVm.undefined, dispose: false };
      if (out === null) return { handle: myVm.null, dispose: false };
      if (Buffer.isBuffer(out) || out instanceof Uint8Array) {
        const ab = out.buffer.slice(out.byteOffset, out.byteOffset + out.byteLength);
        return { handle: myVm.newArrayBuffer(ab), dispose: true };
      }
      if (out instanceof ArrayBuffer) {
        return { handle: myVm.newArrayBuffer(out), dispose: true };
      }
      if (typeof out === 'string') return { handle: myVm.newString(out), dispose: true };
      if (typeof out === 'number') return { handle: myVm.newNumber(out), dispose: true };
      if (typeof out === 'boolean') return { handle: out ? myVm.true : myVm.false, dispose: false };
      return { handle: myVm.newString(JSON.stringify(out)), dispose: true };
    };

    const addSync = (name, fn) => {
      const h = vm.newFunction(name, (...argHandles) => {
        try {
          const rawArgs = argHandles.map((a) => vm.dump(a));
          const args = requireToken(rawArgs);
          if (name !== 'finishCall' && name !== 'cancelTimer' && name !== 'domCloseDoc' &&
              this.deadlineMs !== Infinity && Date.now() > this.deadlineMs) {
            this._lastCallTimedOut = true;
            throw new DomTimeoutError(`宿主调用已超时（${name}）`);
          }
          if (typeof this._bridgeFaultInjectorForTesting === 'function') {
            this._bridgeFaultInjectorForTesting(name);
          }
          const out = fn(...args);
          return toHandle(vm, out).handle;
        } catch (err) {
          if (err instanceof DomTimeoutError) {
            this._lastCallTimedOut = true;
          }
          if (err instanceof RangeError) {
            this.logger.log('ERROR', `Host bridge '${name}' trapped RangeError: ${err.message}`);
            throw new Error(`宿主函数调用栈或范围溢出（${name}）：${err.message}`);
          }
          throw err;
        }
      });
      vm.setProp(hostObj, name, h);
      h.dispose();
    };

    const addAsync = (name, fn) => {
      const h = vm.newFunction(name, (...argHandles) => {
        const rawArgs = argHandles.map((a) => vm.dump(a));
        const args = requireToken(rawArgs);
        const deferred = vm.newPromise();
        this.pendingDeferreds.add(deferred);
        const myVm = vm;
        const myRt = this.rt;
        Promise.resolve()
          .then(() => {
            if (typeof this._bridgeFaultInjectorForTesting === 'function') {
              this._bridgeFaultInjectorForTesting(name);
            }
            return fn(...args);
          })
          .then(
            (res) => {
              this.pendingDeferreds.delete(deferred);
              if (this.vm !== myVm || this.rt !== myRt) return;
              const { handle, dispose } = toHandle(myVm, res);
              deferred.resolve(handle);
              if (dispose) handle.dispose();
              try { this._drainJobs(); } catch {}
            },
            (err) => {
              this.pendingDeferreds.delete(deferred);
              if (this.vm !== myVm || this.rt !== myRt) return;
              if (err instanceof DomTimeoutError) {
                this._lastCallTimedOut = true;
              }
              const errMsg = err instanceof RangeError
                ? `宿主函数调用栈或范围溢出（${name}）：${err.message}`
                : String(err?.message || err);
              const errHandle = myVm.newError(errMsg);
              deferred.reject(errHandle);
              errHandle.dispose();
              try { this._drainJobs(); } catch {}
            },
          );
        deferred.settled.then(() => {
          this.pendingDeferreds.delete(deferred);
          if (this.vm === myVm) {
            try { deferred.dispose(); } catch {}
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
    addSync('isStrictAwaitTracking', () => this.strictAwait);
    addSync('reportUnawaitedOps', (rawJson) => {
      try {
        const list = JSON.parse(String(rawJson || '[]'));
        if (Array.isArray(list)) {
          this._unawaitedOps.push(...list);
        }
      } catch {}
    });

    // §11.1 (S3): true host-side cancellable timer (max 256 active)
    addAsync('sleep', (timerId, ms, gen) => new Promise((resolve, reject) => {
      if (gen !== this.callGen) {
        resolve(false);
        return;
      }
      if (this.activeTimers.size >= MAX_ACTIVE_TIMERS) {
        reject(new RangeError(`同时活跃的定时器数量超过上限（最多 ${MAX_ACTIVE_TIMERS} 个）`));
        return;
      }
      const clamped = Math.min(Math.max(Number(ms) || 0, 0), 60_000);
      const t = setTimeout(() => {
        this.activeTimers.delete(timerId);
        resolve(gen === this.callGen);
      }, clamped);
      this.activeTimers.set(timerId, { timer: t, resolve });
    }));

    addSync('cancelTimer', (timerId) => {
      this._cancelTimerById(Number(timerId));
    });

    addSync('finishCall', (gen) => {
      if (gen === this.callGen) {
        this._cleanupAfterCall();
      }
    });

    // §11.1 (B2): binary fetchMeta + single-consume fetchReadBytes/fetchReadText
    // Enforce raw envelope, URL (8 KB), headers (32 KB), body (2 MB), and queue (16)
    // limits BEFORE decoding body bytes or queuing.
    addAsync('fetchMeta', async (reqJson, gen) => {
      const rawStr = String(reqJson ?? '');
      if (Buffer.byteLength(rawStr, 'utf8') > MAX_FETCH_META_JSON_BYTES) {
        throw new RangeError('请求内容超过大小限制（请求体上限 2 MB）');
      }
      const req = JSON.parse(rawStr);
      this.fetchGateway.validateRequestLimits(req.url, req.headers, null);
      let reservedReqBodyBytes = 0;
      if (req.bodyBase64 !== undefined) {
        const str = String(req.bodyBase64);
        const approxBytes = Math.floor((str.length * 3) / 4);
        if (approxBytes > this.fetchGateway.maxRequestBodyBytes + 3) {
          throw new RangeError('请求体超过大小限制（上限 2 MB）');
        }
        reservedReqBodyBytes = str.length;
      } else if (req.bodyText !== undefined) {
        const byteLen = Buffer.byteLength(String(req.bodyText), 'utf8');
        if (byteLen > this.fetchGateway.maxRequestBodyBytes) {
          throw new RangeError('请求体超过大小限制（上限 2 MB）');
        }
        reservedReqBodyBytes = byteLen;
      }
      if (reservedReqBodyBytes > 0) {
        try {
          this.fetchGateway.requestBodyBudget.reserve(reservedReqBodyBytes);
        } catch {
          throw new FetchRequestTooLargeError(
            `排队及在途请求体总量超过上限（${Math.round(MAX_QUEUED_REQUEST_BODY_BYTES / (1024 * 1024))} MB）`,
          );
        }
      }
      let resp;
      try {
        resp = await this.fetchGateway.withSlot(async (signal) => {
          let body = null;
          if (req.bodyBase64 !== undefined) {
            body = Buffer.from(req.bodyBase64, 'base64');
            this.fetchGateway.validateRequestLimits(req.url, req.headers, body);
          } else if (req.bodyText !== undefined) {
            body = Buffer.from(req.bodyText, 'utf8');
            this.fetchGateway.validateRequestLimits(req.url, req.headers, body);
          }
          return this.fetchGateway._execute({
            url: req.url,
            method: req.method,
            headers: req.headers,
            body,
            timeoutMs: req.timeoutMs,
            redirect: req.redirect,
          }, this.defaultTimeoutMs, signal);
        });
      } finally {
        if (reservedReqBodyBytes > 0) {
          this.fetchGateway.requestBodyBudget.release(reservedReqBodyBytes);
        }
      }
      if (gen !== this.callGen) {
        throw new Error('请求已取消');
      }
      const bytes = resp.body.length;
      this.fetchGateway.inFlightBudget.reserve(bytes);
      const handleId = this.nextResponseHandle++;
      this.callResponseBodies.set(handleId, { buf: resp.body, bytes });
      return JSON.stringify({
        handleId,
        status: resp.status,
        url: resp.url,
        headers: resp.headers,
      });
    });

    addSync('fetchReadBytes', (handleId) => {
      const id = Number(handleId);
      const entry = this.callResponseBodies.get(id);
      if (!entry) return Buffer.alloc(0);
      this.callResponseBodies.delete(id);
      this.fetchGateway.inFlightBudget.release(entry.bytes);
      return entry.buf;
    });

    addSync('fetchReadText', (handleId) => {
      const id = Number(handleId);
      const entry = this.callResponseBodies.get(id);
      if (!entry) return '';
      this.callResponseBodies.delete(id);
      this.fetchGateway.inFlightBudget.release(entry.bytes);
      return entry.buf.toString('utf8');
    });

    // §11.1 (S9): async ctx.storage.* returning Promises (pre-parse byte quota check)
    addAsync('storageGet', async (k) => {
      const v = this.storage.get(k);
      return v === null ? null : JSON.stringify(v);
    });
    addAsync('storageSet', async (k, vJson) => {
      this.storage.setRawJson(k, vJson);
    });
    addAsync('storageRemove', async (k) => {
      this.storage.remove(k);
    });
    addAsync('storageKeys', async () => JSON.stringify(this.storage.keys()));

    // §11.1 (S9): async ctx.cookies.* returning Promises
    addAsync('cookiesGet', async (u) => this.cookieJar.getHeaderForUrl(u));
    addAsync('cookiesSet', async (u, c) => {
      this.cookieJar.setCookie(u, c);
    });
    addAsync('cookiesClear', async () => {
      this.cookieJar.clear();
    });

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

    // §11.1 (S4): lazy DOM handle bridge (deduplicated handles + explicit doc.close())
    addSync('parseHtml', (text) => this.domParser.parseHtml(text));
    addSync('parseXml', (text) => this.domParser.parseXml(text));
    addSync('domCloseDoc', (docId) => this.domParser.closeDoc(Number(docId)));
    addSync('domTag', (id) => this.domParser.tag(Number(id)));
    addSync('domText', (id) => this.domParser.text(Number(id)));
    addSync('domHtml', (id) => this.domParser.html(Number(id)));
    addSync('domOuterHtml', (id) => this.domParser.outerHtml(Number(id)));
    addSync('domAttrs', (id) => this.domParser.attrsJson(Number(id)));
    addSync('domAttr', (id, name) => this.domParser.attr(Number(id), String(name)));
    addSync('domSelectFirstId', (id, sel) => this.domParser.selectFirstId(Number(id), String(sel)));
    addSync('domSelectAllIds', (id, sel) => JSON.stringify(this.domParser.selectAllIds(Number(id), String(sel))));
    addSync('domChildrenIds', (id) => JSON.stringify(this.domParser.childrenIds(Number(id))));

    addSync('log', (level, msg) => {
      this.logger.log(level, truncateUtf8(msg, MAX_LOG_MESSAGE_BYTES));
    });
    addSync('translate', (key, varsJson) => {
      const vars = varsJson && varsJson !== 'null' ? JSON.parse(varsJson) : null;
      return this.locale.translate(key, vars);
    });

    vm.setProp(vm.global, '__d3_host', hostObj);
    hostObj.dispose();
  }
}
