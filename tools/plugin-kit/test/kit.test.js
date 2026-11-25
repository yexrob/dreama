import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { checkPlugin, loadPluginSource } from '../src/check.js';
import { main as cliMain } from '../src/cli.js';
import { CookieJarQuotaExceededError, PluginCookieJar } from '../src/cookies.js';
import { pluginCrypto } from '../src/crypto.js';
import { FetchGateway, FetchResponseTooLargeError } from '../src/fetch-gateway.js';
import { redactSecrets } from '../src/logger.js';
import { NetworkPolicy, NetworkPolicyError } from '../src/network-policy.js';
import { inspectArchiveBuffer, packPluginToBuffer, packPluginToFile, sha256Hex } from '../src/pack.js';
import { PluginCallError, QuickJsPluginHost } from '../src/runtime.js';
import { SCHEMA_DIR, validateManifest, validateProtocolType, validateRepository } from '../src/schema.js';
import { startDevServer } from '../src/serve.js';
import { PluginStorage } from '../src/storage.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../../..');
const REF_PLUGIN_DIR = path.join(REPO_ROOT, 'plugins/reference');
const JF_PLUGIN_DIR = path.join(REPO_ROOT, 'plugins/jellyfin');
const JF_FIXTURES = JSON.parse(
  fs.readFileSync(path.join(JF_PLUGIN_DIR, 'fixtures/recorded-responses.json'), 'utf8'),
);
const BILI_PLUGIN_DIR = path.join(REPO_ROOT, 'plugins/bilibili');
const BILI_FIXTURES = JSON.parse(
  fs.readFileSync(path.join(BILI_PLUGIN_DIR, 'fixtures/recorded-responses.json'), 'utf8'),
);
const POLICY_VECTORS = JSON.parse(
  fs.readFileSync(path.join(SCHEMA_DIR, 'network-policy-vectors.json'), 'utf8'),
);

test('manifest validation enforces §11.2 optional author, §11.4 full-string regex (S2), and semantics', () => {
  const valid = JSON.parse(fs.readFileSync(path.join(REF_PLUGIN_DIR, 'manifest.json'), 'utf8'));
  assert.equal(validateManifest(valid).valid, true);

  // S8 / §11.2: author is optional, optional fields accept null
  const withoutAuthor = { ...valid };
  delete withoutAuthor.author;
  withoutAuthor.description = null;
  withoutAuthor.homepage = null;
  assert.equal(validateManifest(withoutAuthor).valid, true);

  // S2 / §11.4: trailing newline in id or version MUST be rejected
  assert.equal(validateManifest({ ...valid, id: 'com.example.jellyfin\n' }).valid, false);
  assert.equal(validateManifest({ ...valid, version: '1.0.0\n' }).valid, false);

  assert.equal(validateManifest({ ...valid, api: 2 }).valid, false);
  assert.equal(validateManifest({ ...valid, id: 'NotReverseDomain' }).valid, false);
  assert.equal(validateManifest({ ...valid, version: '1.0' }).valid, false);
  assert.equal(validateManifest({ ...valid, permissions: { hosts: ['localhost'] } }).valid, false);
  assert.equal(validateManifest({ ...valid, permissions: { hosts: ['localhost.'] } }).valid, false);
  assert.equal(validateManifest({ ...valid, permissions: { hosts: ['127.1'] } }).valid, false);
  assert.equal(validateManifest({ ...valid, permissions: { hosts: ['0x7f.0.0.1'] } }).valid, false);
  assert.equal(
    validateManifest({
      ...valid,
      config: [
        { key: 'dup', type: 'text', label: 'A' },
        { key: 'dup', type: 'text', label: 'B' },
      ],
    }).valid,
    false,
  );
  assert.equal(
    validateManifest({
      ...valid,
      config: [{ key: 'n', type: 'number', label: 'N', default: 'not-a-number' }],
    }).valid,
    false,
  );
});

test('shared network-policy-vectors.json passes 100% on Node NetworkPolicy (B1, S9, §11.3)', async () => {
  const dnsMap = POLICY_VECTORS.dns;
  const mockDns = async (host) => {
    const ips = dnsMap[host];
    if (!ips) throw new Error(`Unknown test host: ${host}`);
    return ips;
  };

  for (const tc of POLICY_VECTORS.fetchCases) {
    const policy = new NetworkPolicy(tc.policy, new Set(tc.userHosts), mockDns);
    if (tc.allow) {
      await policy.validateFetchUrl(tc.url);
    } else {
      await assert.rejects(
        () => policy.validateFetchUrl(tc.url),
        NetworkPolicyError,
        `fetchCase "${tc.name}" (${tc.url}) should have been rejected`,
      );
    }
  }

  for (const tc of POLICY_VECTORS.resourceCases) {
    const policy = new NetworkPolicy(tc.policy, new Set(tc.userHosts), mockDns);
    if (tc.allow) {
      await policy.validateResourceUrl(tc.url);
    } else {
      await assert.rejects(
        () => policy.validateResourceUrl(tc.url),
        NetworkPolicyError,
        `resourceCase "${tc.name}" (${tc.url}) should have been rejected`,
      );
    }
  }
});

test('FetchGateway blocks HTTPS downgrade, redirect escape, Host override, and merges explicit+jar Cookie (B1, S9)', async () => {
  const fakeDns = async (host) => {
    if (host === 'api.example.com' || host === 'a.cdn.example.com' || host === 'evil.com') return ['93.184.216.34'];
    return ['93.184.216.34'];
  };
  const policy = new NetworkPolicy(
    { hosts: ['api.example.com', '*.cdn.example.com'], userHosts: true, lan: false },
    new Set(['api.example.com']),
    fakeDns,
  );
  const jar = new PluginCookieJar();
  jar.setCookie('https://api.example.com/', 'jarOnly=1; Path=/; Secure');
  jar.setCookie('https://api.example.com/', 'shared=fromJar; Path=/; Secure');

  let seenCookieHeader = null;
  const gw = new FetchGateway(policy, jar, {
    transportOverride: async ({ url, headers }) => {
      if (url.pathname === '/escape') {
        return { status: 302, headers: { location: 'https://evil.com/steal' }, setCookies: [], body: Buffer.alloc(0) };
      }
      if (url.pathname === '/downgrade') {
        return { status: 302, headers: { location: 'http://api.example.com/plain' }, setCookies: [], body: Buffer.alloc(0) };
      }
      if (url.pathname === '/echo-cookie') {
        seenCookieHeader = headers.Cookie || '';
        return { status: 200, headers: {}, setCookies: [], body: Buffer.from(seenCookieHeader) };
      }
      return { status: 200, headers: {}, setCookies: ['sid=abc; Path=/; Secure'], body: Buffer.from('ok') };
    },
  });

  await assert.rejects(() => gw.fetch({ url: 'https://api.example.com/escape' }), NetworkPolicyError);
  // HTTPS -> HTTP downgrade redirect MUST be blocked even if host is in userHosts
  await assert.rejects(() => gw.fetch({ url: 'https://api.example.com/downgrade' }), NetworkPolicyError);
  await assert.rejects(
    () => gw.fetch({ url: 'https://api.example.com/ok', headers: { Host: 'spoof.com' } }),
    NetworkPolicyError,
  );

  // Explicit Cookie merges with jar; explicit wins on name collision (§11.1 / S9)
  const mergedResp = await gw.fetch({
    url: 'https://api.example.com/echo-cookie',
    headers: { Cookie: 'shared=fromExplicit; extra=2' },
  });
  assert.equal(mergedResp.status, 200);
  assert.equal(seenCookieHeader, 'jarOnly=1; shared=fromExplicit; extra=2');
});

test('B2: 8 MB fetch succeeds via ArrayBuffer inside 64 MB heap; > 8 MB rejected; 12 concurrent bounded at 4 & 16 MB', async () => {
  const eightMb = Buffer.alloc(8 * 1024 * 1024, 0x42);
  const manifest = {
    api: 1,
    id: 'dev.depth3d.memtest',
    name: 'MemTest',
    version: '1.0.0',
    permissions: { hosts: ['api.example.com'] },
  };
  const mainJs = `
    export default {
      async home(ctx) {
        // 1. 8 MB fetch succeeds and bytes() returns an ArrayBuffer (no base64-in-JSON blowup)
        const r = await ctx.fetch('https://api.example.com/8mb');
        const ab = r.bytes();
        if (!(ab instanceof ArrayBuffer)) throw new Error('bytes() must return ArrayBuffer');
        const view = new Uint8Array(ab);
        if (ab.byteLength !== 8 * 1024 * 1024 || view[0] !== 0x42 || view[view.length - 1] !== 0x42) {
          throw new Error('8 MB binary payload corrupted: ' + ab.byteLength);
        }

        // 2. > 8 MB fetch must be rejected
        let tooBigRejected = false;
        try {
          await ctx.fetch('https://api.example.com/9mb');
        } catch (e) {
          tooBigRejected = true;
        }
        return [{ id: 'ok', title: '8mb-ok-' + ab.byteLength + '-' + tooBigRejected, items: [] }];
      },
      async browse(ctx) {
        // 3. 12 concurrent 1 MB fetches: concurrency must never exceed 4
        const tasks = [];
        for (let i = 0; i < 12; i++) {
          tasks.push(ctx.fetch('https://api.example.com/chunk?i=' + i).then((r) => r.bytes().byteLength));
        }
        const lens = await Promise.all(tasks);
        return { items: [{ id: 'c', kind: 'video', title: String(lens.reduce((a, b) => a + b, 0)) }] };
      },
      detail() { return { id: '1', kind: 'video', title: '1' }; },
      playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
    };
  `;

  let activeNow = 0;
  let maxConcurrentSeen = 0;
  const oneMb = Buffer.alloc(1024 * 1024, 0x33);
  const host = new QuickJsPluginHost({
    manifest,
    mainJs,
    dnsResolver: async () => ['93.184.216.34'],
    transportOverride: async ({ url }) => {
      if (url.pathname === '/8mb') {
        return { status: 200, headers: {}, setCookies: [], body: eightMb };
      }
      if (url.pathname === '/9mb') {
        return { status: 200, headers: {}, setCookies: [], body: Buffer.alloc(8 * 1024 * 1024 + 4096) };
      }
      activeNow += 1;
      maxConcurrentSeen = Math.max(maxConcurrentSeen, activeNow);
      await new Promise((r) => setTimeout(r, 20));
      activeNow -= 1;
      return { status: 200, headers: {}, setCookies: [], body: oneMb };
    },
    logSink: () => {},
  });

  try {
    const h = await host.invoke('home');
    assert.equal(h[0].title, `8mb-ok-${8 * 1024 * 1024}-true`);

    const b = await host.invoke('browse', { categoryId: '' });
    assert.equal(b.items[0].title, String(12 * 1024 * 1024));
    assert.ok(maxConcurrentSeen <= 4, `Expected max concurrency <= 4, saw ${maxConcurrentSeen}`);
    assert.equal(host.fetchGateway.inFlightBudget.used, 0);
  } finally {
    await host.dispose();
  }
});

test('S3, S4, S5: clearTimeout cancels host wait, Promise.race aborts fetch, 450 KB lazy DOM works, 3000 cookies coalesce & cap at 256 KB', async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'd3-s345-'));
  const cookieFile = path.join(tmpDir, 'cookies.json');
  const cookieJar = new PluginCookieJar(() => Date.now(), { filePath: cookieFile });

  const bigHtml = '<html><body>' +
    Array.from({ length: 4200 }, (_, i) => `<div class="row" data-idx="${i}"><span>item-${i}-${'x'.repeat(60)}</span></div>`).join('') +
    '</body></html>';
  assert.ok(Buffer.byteLength(bigHtml, 'utf8') > 420 * 1024);

  let slowFetchAborted = false;
  const manifest = {
    api: 1,
    id: 'dev.depth3d.s345',
    name: 'S345',
    version: '1.0.0',
    permissions: { hosts: ['api.example.com'] },
  };
  const mainJs = `
    export default {
      async home(ctx) {
        // Verify async storage & cookies return Promises (S9 / §11.1)
        const p1 = ctx.storage.get('k');
        const p2 = ctx.cookies.get('https://api.example.com/');
        if (!(p1 instanceof Promise) || !(p2 instanceof Promise)) {
          throw new Error('storage/cookies must return Promises');
        }
        await p1;
        await p2;

        // Verify __d3_host is inaccessible
        if (typeof globalThis.__d3_host !== 'undefined') {
          throw new Error('__d3_host should be deleted');
        }

        // S3a: clearTimeout cancels host timer immediately
        const t = setTimeout(() => {}, 2500);
        clearTimeout(t);

        // S3b: Promise.race([slowFetch, shortTimeout]) settles fast and cancels in-flight fetch
        const winner = await Promise.race([
          ctx.fetch('https://api.example.com/slow').then(() => 'fetch'),
          new Promise((resolve) => setTimeout(() => resolve('timeout'), 40)),
        ]);
        return [{ id: 'ok', title: winner, items: [] }];
      },
      async browse(ctx, { categoryId }) {
        if (categoryId === 'dom') {
          const doc = ctx.parse.html(ctx.config.bigHtml);
          const divs = doc.querySelectorAll('div.row');
          // Lazy handle check: read only 2 nodes out of 4200
          const first = divs[0].attr('data-idx') + ':' + divs[0].querySelector('span').text.slice(0, 6);
          const last = divs[divs.length - 1].attr('data-idx');
          // 9th doc in one call must fail
          let ninthFailed = false;
          try {
            for (let i = 0; i < 8; i++) ctx.parse.html('<p>x</p>');
          } catch { ninthFailed = true; }
          return { items: [{ id: 'dom', kind: 'video', title: divs.length + '|' + first + '|' + last + '|' + ninthFailed }] };
        }
        if (categoryId === 'cookies') {
          for (let i = 0; i < 3000; i++) {
            await ctx.cookies.set('https://api.example.com/', 'c' + (i % 500) + '=v' + i + '; Path=/; Secure');
          }
          let quotaHit = false;
          try {
            for (let i = 0; i < 200; i++) {
              await ctx.cookies.set('https://api.example.com/', 'big' + i + '=' + 'A'.repeat(2048) + '; Path=/; Secure');
            }
          } catch { quotaHit = true; }
          return { items: [{ id: 'ck', kind: 'video', title: 'quota-' + quotaHit }] };
        }
        return { items: [] };
      },
      detail() { return { id: '1', kind: 'video', title: '1' }; },
      playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
    };
  `;

  const host = new QuickJsPluginHost({
    manifest,
    mainJs,
    config: { bigHtml },
    cookieJar,
    dnsResolver: async () => ['93.184.216.34'],
    transportOverride: ({ signal }) => new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        resolve({ status: 200, headers: {}, setCookies: [], body: Buffer.from('late') });
      }, 5000);
      signal?.addEventListener('abort', () => {
        slowFetchAborted = true;
        clearTimeout(timer);
        reject(new Error('aborted'));
      }, { once: true });
    }),
    logSink: () => {},
  });

  try {
    const t0 = Date.now();
    const res = await host.invoke('home');
    const elapsed = Date.now() - t0;
    assert.equal(res[0].title, 'timeout');
    assert.ok(elapsed < 800, `clearTimeout + Promise.race should return fast, took ${elapsed}ms`);
    assert.equal(slowFetchAborted, true, 'In-flight fetch should be aborted on call settle');

    const domRes = await host.invoke('browse', { categoryId: 'dom' });
    assert.equal(domRes.items[0].title, '4200|0:item-0|4199|true');

    const ckT0 = Date.now();
    const ckRes = await host.invoke('browse', { categoryId: 'cookies' });
    const ckElapsed = Date.now() - ckT0;
    assert.equal(ckRes.items[0].title, 'quota-true');
    assert.ok(ckElapsed < 5000, `3000 cookie sets should complete quickly, took ${ckElapsed}ms`);
    assert.equal(cookieJar.diskWriteCount, 1, `Expected coalesced single disk flush, got ${cookieJar.diskWriteCount}`);
  } finally {
    await host.dispose();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('ctx APIs (async storage/cookies, crypto, parse HTML/XML, log redaction, corrupted storage preserved)', async () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  });

  // Verify corrupted storage file is preserved untouched instead of silently wiped
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'd3-corrupt-'));
  const corruptFile = path.join(tmpDir, 'bad-storage.json');
  fs.writeFileSync(corruptFile, '{not valid json', 'utf8');
  assert.throws(() => new PluginStorage(corruptFile));
  assert.equal(fs.readFileSync(corruptFile, 'utf8'), '{not valid json');
  fs.rmSync(tmpDir, { recursive: true, force: true });

  const manifest = {
    api: 1,
    id: 'dev.depth3d.ctxtest',
    name: 'CtxTest',
    version: '1.0.0',
    author: 'depth3d',
    permissions: { hosts: ['api.example.com'] },
    config: [{ key: 'secretPass', type: 'password', label: 'P' }],
  };
  const mainJs = `
    export default {
      async home(ctx) {
        let configFrozen = false;
        try { ctx.config.secretPass = 'mutated'; } catch { configFrozen = true; }

        await ctx.storage.set('obj', { a: 1, b: '中文' });
        const stored = await ctx.storage.get('obj');
        const keys = await ctx.storage.keys();
        await ctx.storage.remove('obj');
        let quotaHit = false;
        try { await ctx.storage.set('huge', 'x'.repeat(1100 * 1024)); } catch { quotaHit = true; }

        await ctx.cookies.set('https://api.example.com/', 'k1=v1; Path=/; Secure');
        const cookieVal = await ctx.cookies.get('https://api.example.com/path');

        const md5 = ctx.crypto.md5('hello');
        const sha1 = ctx.crypto.sha1('hello');
        const sha256 = ctx.crypto.sha256('hello');
        const hmac = ctx.crypto.hmac('sha256', 'key', 'msg');
        const key16 = new Uint8Array(16).fill(7);
        const iv16 = new Uint8Array(16).fill(3);
        const iv12 = new Uint8Array(12).fill(5);
        const cbcEnc = ctx.crypto.aes.encrypt('CBC', key16, iv16, 'secret-cbc');
        const cbcDec = new TextDecoder().decode(ctx.crypto.aes.decrypt('CBC', key16, iv16, cbcEnc));
        const gcmEnc = ctx.crypto.aes.encrypt({ mode: 'GCM', key: key16, iv: iv12, data: 'secret-gcm', aad: 'hdr' });
        const gcmDec = new TextDecoder().decode(ctx.crypto.aes.decrypt({ mode: 'GCM', key: key16, iv: iv12, data: gcmEnc, aad: 'hdr' }));
        const b64 = ctx.crypto.base64.encode('depth3d');
        const b64Back = new TextDecoder().decode(ctx.crypto.base64.decode(b64));
        const hex = ctx.crypto.hex.encode('Hi');
        const hexBack = new TextDecoder().decode(ctx.crypto.hex.decode(hex));
        const randLen = ctx.crypto.randomBytes(16).length;
        const rsaEnc = ctx.crypto.base64.encode(ctx.crypto.rsa.encrypt(ctx.config.rsaPub, 'rsa-payload', 'OAEP-SHA256'));

        const hdoc = ctx.parse.html('<div class="card" data-id="42"><span>电影名</span></div>');
        const hEl = hdoc.querySelector('.card');
        const xdoc = ctx.parse.xml('<root><item id="9"><title>剧集A</title></item></root>');
        const xTitle = xdoc.querySelector('item title').text;

        const delayed = await new Promise((resolve) => setTimeout(() => resolve('tick'), 15));
        ctx.log.info('Login with password=' + ctx.config.secretPass + ' Authorization: Bearer tok12345');

        await ctx.storage.set('probeResult', {
          configFrozen, stored, keys, quotaHit, cookieVal,
          md5, sha1, sha256, hmac, cbcDec, gcmDec, b64Back, hex, hexBack, randLen, rsaEnc,
          htmlId: hEl.attr('data-id'), htmlText: hEl.querySelector('span').text, xTitle, delayed,
        });
        return [];
      },
      browse() { return { items: [] }; },
      detail() { return { id: '1', kind: 'video', title: '1' }; },
      playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
    };
  `;

  const logs = [];
  const storage = new PluginStorage();
  const host = new QuickJsPluginHost({
    manifest,
    mainJs,
    config: { secretPass: 'SuperSecretPw!', rsaPub: publicKey },
    storage,
    logSink: (_lvl, line) => logs.push(line),
  });
  try {
    await host.invoke('home');
    const r = storage.get('probeResult');
    assert.equal(r.configFrozen, true);
    assert.deepEqual(r.stored, { a: 1, b: '中文' });
    assert.deepEqual(r.keys, ['obj']);
    assert.equal(r.quotaHit, true);
    assert.equal(r.cookieVal, 'k1=v1');
    assert.equal(r.md5, '5d41402abc4b2a76b9719d911017c592');
    assert.equal(r.sha1, 'aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d');
    assert.equal(r.sha256, '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824');
    assert.equal(r.hmac, pluginCrypto.hmac('sha256', Buffer.from('key'), Buffer.from('msg')));
    assert.equal(r.cbcDec, 'secret-cbc');
    assert.equal(r.gcmDec, 'secret-gcm');
    assert.equal(r.b64Back, 'depth3d');
    assert.equal(r.hex, '4869');
    assert.equal(r.hexBack, 'Hi');
    assert.equal(r.randLen, 16);
    const rsaPlain = crypto.privateDecrypt(
      { key: privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' },
      Buffer.from(r.rsaEnc, 'base64'),
    );
    assert.equal(rsaPlain.toString('utf8'), 'rsa-payload');
    assert.equal(r.htmlId, '42');
    assert.equal(r.htmlText, '电影名');
    assert.equal(r.xTitle, '剧集A');
    assert.equal(r.delayed, 'tick');

    assert.ok(logs.some((l) => l.startsWith('depth3d: plugin dev.depth3d.ctxtest')));
    for (const line of logs) {
      assert.equal(line.includes('SuperSecretPw!'), false);
      assert.equal(line.includes('tok12345'), false);
    }
    assert.equal(redactSecrets('https://alice:pw999@host/x').includes('pw999'), false);
  } finally {
    await host.dispose();
  }
});

test('QuickJS runtime recovers cleanly after timeout and OOM', async () => {
  const manifest = {
    api: 1,
    id: 'dev.depth3d.limits',
    name: 'Limits',
    version: '1.0.0',
  };
  const mainJs = `
    export default {
      async init(ctx) {
        const n = ((await ctx.storage.get('inits')) || 0) + 1;
        await ctx.storage.set('inits', n);
      },
      home() { return []; },
      async browse(ctx, { categoryId }) {
        if (categoryId === 'hang') {
          while (true) {}
        }
        if (categoryId === 'oom') {
          const buf = new Uint8Array(6 * 1024 * 1024);
          return { items: [{ id: String(buf.length), kind: 'video', title: 'never' }] };
        }
        return { items: [{ id: 'ok', kind: 'video', title: 'recovered-' + (await ctx.storage.get('inits')) }] };
      },
      detail() { return { id: '1', kind: 'video', title: '1' }; },
      playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
    };
  `;

  const storage = new PluginStorage();
  const host = new QuickJsPluginHost({
    manifest,
    mainJs,
    storage,
    memoryLimitBytes: 4 * 1024 * 1024,
    defaultTimeoutMs: 250,
    logSink: () => {},
  });
  try {
    const ok1 = await host.invoke('browse', { categoryId: 'ok' });
    assert.equal(ok1.items[0].title, 'recovered-1');
    assert.equal(host.creationCount, 1);

    await assert.rejects(
      () => host.invoke('browse', { categoryId: 'hang' }),
      (err) => err instanceof PluginCallError && err.code === 'Unavailable',
    );
    const ok2 = await host.invoke('browse', { categoryId: 'ok' });
    assert.equal(ok2.items[0].title, 'recovered-2');
    assert.equal(host.creationCount, 2);

    await assert.rejects(
      () => host.invoke('browse', { categoryId: 'oom' }),
      (err) => err instanceof PluginCallError && err.code === 'Internal',
    );
    const ok3 = await host.invoke('browse', { categoryId: 'ok' });
    assert.equal(ok3.items[0].title, 'recovered-3');
    assert.equal(host.creationCount, 3);
  } finally {
    await host.dispose();
  }
});

test('S9: d3plugin run fails non-zero on invalid schema and pack rejects dual-view zip mismatch', async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'd3-s9-'));
  try {
    // 1. Plugin returning invalid home() schema -> d3plugin run MUST exit 1 (not just warn)
    fs.writeFileSync(
      path.join(tmpDir, 'manifest.json'),
      JSON.stringify({ api: 1, id: 'com.example.badret', name: 'BadRet', version: '1.0.0' }),
    );
    fs.writeFileSync(
      path.join(tmpDir, 'main.js'),
      `export default {
        home() { return [{ notAShelf: true }]; },
        browse() { return { items: [] }; },
        detail() { return { id: '1', kind: 'video', title: '1' }; },
        playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
      };`,
    );
    const code = await cliMain(['run', 'home', tmpDir]);
    assert.equal(code, 1, 'd3plugin run MUST fail with exit code 1 on schema violation');

    // 2. Dual-view zip: tamper filename byte in Central Directory vs Local File Header -> MUST be rejected
    const { buffer: goodZip } = await packPluginToBuffer(REF_PLUGIN_DIR);
    await inspectArchiveBuffer(goodZip); // baseline passes

    const tampered = Buffer.from(goodZip);
    // Find Central Directory Header signature 0x02014b50 and corrupt the first char of its filename
    const cdSig = Buffer.from([0x50, 0x4b, 0x01, 0x02]);
    const cdIdx = tampered.indexOf(cdSig);
    assert.ok(cdIdx > 0);
    tampered[cdIdx + 46] = tampered[cdIdx + 46] === 0x6d ? 0x78 : 0x6d; // flip 'm' <-> 'x'
    await assert.rejects(
      () => inspectArchiveBuffer(tampered),
      /differs from Local File Header|mismatch/i,
    );
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('reference plugin passes full conformance check and covers §11 forward-compat & danmaku int colors', async () => {
  const report = await checkPlugin(REF_PLUGIN_DIR);
  assert.equal(report.ok, true, `checkPlugin failed: ${report.errors.join('; ')}`);

  const loaded = await loadPluginSource(REF_PLUGIN_DIR);
  const host = new QuickJsPluginHost({
    manifest: loaded.manifest,
    mainJs: loaded.mainJs,
    locale: loaded.locale,
    logSink: () => {},
  });

  try {
    const qrStart = await host.invoke('loginStart');
    assert.equal(validateProtocolType('QrLoginStartResult', qrStart).valid, true);
    assert.equal((await host.invoke('loginPoll', { state: qrStart.state })).status, 'pending');
    assert.equal((await host.invoke('loginPoll', { state: qrStart.state })).status, 'scanned');
    const done = await host.invoke('loginPoll', { state: qrStart.state });
    assert.equal(done.status, 'done');
    assert.equal(done.user.name, '扫码用户');
    assert.equal((await host.invoke('loginPoll', { state: 'expired-state' })).status, 'expired');

    const mp = await host.invoke('detail', { itemId: 'multipart-video' });
    assert.equal(validateProtocolType('Item', mp).valid, true);
    assert.equal(mp.episodes.length, 3);

    const pb1 = await host.invoke('playback', { itemId: 'multipart-p1' });
    assert.equal(validateProtocolType('PlaybackInfo', pb1).valid, true);
    assert.equal(pb1.durationMs, 900_000);
    assert.ok(pb1.dash.video.length >= 2);
    assert.ok(pb1.headers.Cookie.includes('ref_sess=qr_'));
    assert.equal(pb1.qualities.find((q) => q.id === '4k-vip').locked, '需要会员');
    assert.ok(pb1.dash.video[0].url.includes('token=v1'));

    const pb2 = await host.invoke('playback', { itemId: 'multipart-p1' });
    assert.ok(pb2.dash.video[0].url.includes('token=v2'));

    // Segmented danmaku with BOTH hex string and integer color (§11.2 / S8)
    const dm1 = await host.invoke('danmaku', { itemId: 'multipart-p1', fromMs: 0, toMs: 60_000 });
    assert.equal(validateProtocolType('DanmakuResult', dm1).valid, true);
    assert.equal(dm1.length, 3);
    assert.equal(dm1[1].color, 0xFF6699);

    // Forward-compat browse with null optional fields, unknown kind, out-of-range year/rating
    const compatPage = await host.invoke('browse', { categoryId: 'compat-forward' });
    assert.equal(validateProtocolType('ItemPage', compatPage).valid, true);

    for (const code of ['NeedsAuth', 'AuthFailed', 'NeedsSetup', 'Unavailable', 'NotFound', 'Forbidden', 'RateLimited', 'Unsupported', 'Internal']) {
      await assert.rejects(
        () => host.invoke('detail', { itemId: `err-${code}` }),
        (err) => err instanceof PluginCallError && err.code === code && (code !== 'RateLimited' || err.retryAfterSec === 42),
      );
    }
    await assert.rejects(
      () => host.invoke('detail', { itemId: 'err-Uncaught' }),
      (err) => err instanceof PluginCallError && err.code === 'Internal',
    );
  } finally {
    await host.dispose();
  }
});

test('jellyfin plugin passes static check and full real-recorded Jellyfin 12.4 REST API flow (S10, S11)', async () => {
  const staticCheck = await checkPlugin(JF_PLUGIN_DIR);
  assert.equal(staticCheck.ok, true, `jellyfin check failed: ${staticCheck.errors.join('; ')}`);

  const loaded = await loadPluginSource(JF_PLUGIN_DIR);
  const ids = JF_FIXTURES._meta.ids;
  const expectedToken = JF_FIXTURES.authSuccess.AccessToken;
  const recordedCalls = [];
  const recordedAuthHeaders = [];
  const playbackBodies = [];
  const progressBodies = [];
  let forceExpired = false;

  const mockTransport = async ({ url, method, headers, body }) => {
    const p = url.pathname;
    const qs = url.searchParams;
    recordedCalls.push({ method, path: p, query: Object.fromEntries(qs.entries()) });
    const jsonResp = (status, data) => ({
      status,
      headers: { 'content-type': 'application/json' },
      setCookies: [],
      body: Buffer.from(JSON.stringify(data), 'utf8'),
    });

    if (p === '/System/Info/Public') return jsonResp(200, JF_FIXTURES.systemInfoPublic);
    if (p === '/Users/AuthenticateByName' && method === 'POST') {
      const reqBody = JSON.parse(body.toString('utf8'));
      if (reqBody.Pw === 'bad-pass') return jsonResp(401, { message: 'Unauthorized' });
      return jsonResp(200, JF_FIXTURES.authSuccess);
    }
    recordedAuthHeaders.push(String(headers.Authorization || ''));
    if (forceExpired || !String(headers.Authorization || '').includes(`Token="${expectedToken}"`)) {
      return jsonResp(401, { message: 'Invalid token' });
    }
    if (p === '/UserItems/Resume') return jsonResp(200, JF_FIXTURES.resume);
    if (p === '/Shows/NextUp') return jsonResp(200, JF_FIXTURES.nextUp);
    if (p === '/UserViews') return jsonResp(200, JF_FIXTURES.views);
    if (p === '/Items/Latest') return jsonResp(200, JF_FIXTURES.latestByParent[qs.get('ParentId')] || []);
    if (p === '/Items' && qs.get('SearchTerm')) return jsonResp(200, JF_FIXTURES.searchResults);
    if (p === '/Items') return jsonResp(200, JF_FIXTURES.browseByParent[qs.get('ParentId')] || { Items: [], TotalRecordCount: 0 });
    if (p.endsWith('/Similar')) return jsonResp(200, JF_FIXTURES.similar);
    if (p.endsWith('/PlaybackInfo')) {
      const reqObj = JSON.parse(body.toString('utf8'));
      playbackBodies.push(reqObj);
      const itemId = p.split('/')[2];
      const isTranscode = Array.isArray(reqObj?.DeviceProfile?.DirectPlayProfiles) && reqObj.DeviceProfile.DirectPlayProfiles.length === 0;
      const key = `${itemId}:${isTranscode ? 'transcode' : 'direct'}`;
      return jsonResp(200, JF_FIXTURES.playbackInfo[key]);
    }
    if (/^\/Items\/[^/]+$/.test(p)) {
      const itemId = p.split('/')[2];
      return jsonResp(200, JF_FIXTURES.itemsById[itemId]);
    }
    if (p === `/Shows/${ids.seriesId}/Seasons`) return jsonResp(200, JF_FIXTURES.seriesSeasons);
    if (p === `/Shows/${ids.seriesId}/Episodes`) return jsonResp(200, JF_FIXTURES.seasonEpisodes);
    if (p === '/Search/Hints') return jsonResp(200, JF_FIXTURES.searchHints);
    if (p.startsWith('/Sessions/Playing')) {
      progressBodies.push({ path: p, body: JSON.parse(body.toString('utf8')) });
      return { status: 204, headers: {}, setCookies: [], body: Buffer.alloc(0) };
    }
    if (p.startsWith('/UserFavoriteItems/') || p.startsWith('/UserPlayedItems/') || p === '/Sessions/Logout') {
      return { status: 204, headers: {}, setCookies: [], body: Buffer.alloc(0) };
    }
    return jsonResp(404, { message: `Unhandled mock path: ${p}` });
  };

  const storage = new PluginStorage();
  const host = new QuickJsPluginHost({
    manifest: loaded.manifest,
    mainJs: loaded.mainJs,
    locale: loaded.locale,
    storage,
    config: { server: 'http://192.168.1.50:18096', username: 'd3tester', password: 'secret', quality: 'direct' },
    dnsResolver: async () => ['192.168.1.50'],
    transportOverride: mockTransport,
    logSink: () => {},
  });

  try {
    await assert.rejects(() => host.invoke('home'), (e) => e instanceof PluginCallError && e.code === 'NeedsAuth');

    const loginRes = await host.invoke('login');
    assert.equal(validateProtocolType('FormLoginResult', loginRes).valid, true);
    assert.equal(loginRes.user.name, 'd3tester');

    const shelves = await host.invoke('home');
    assert.equal(validateProtocolType('HomeResult', shelves).valid, true);
    assert.ok(shelves.some((s) => s.style === 'continue'));
    assert.equal(/api_?key=/i.test(JSON.stringify(shelves)), false, 'S10: image URLs must never contain api_key');

    const cats = await host.invoke('categories');
    assert.equal(validateProtocolType('CategoriesResult', cats).valid, true);
    assert.equal(cats.length, 2);

    const movies = await host.invoke('browse', { categoryId: ids.moviesViewId });
    assert.equal(validateProtocolType('ItemPage', movies).valid, true);
    assert.equal(movies.items.length, 2);

    const ser = await host.invoke('detail', { itemId: ids.seriesId });
    assert.equal(ser.seasons.length, 1);
    const sea = await host.invoke('detail', { itemId: ids.seasonId });
    assert.equal(sea.episodes.length, 1);

    // Direct Play H.264: S10 check VideoCodec sends 'h264' (NOT 'avc') and NO api_key in URLs
    const pbH264 = await host.invoke('playback', { itemId: ids.h264MovieId });
    assert.equal(validateProtocolType('PlaybackInfo', pbH264).valid, true);
    assert.ok(pbH264.url.includes('/stream?'));
    assert.equal(/api_?key=/i.test(JSON.stringify(pbH264)), false);
    assert.equal(pbH264.subtitles.length, 1);
    assert.equal(pbH264.subtitles[0].format, 'srt');
    assert.ok(pbH264.headers.Authorization.includes(`Token="${expectedToken}"`));
    const directProfileCodecs = playbackBodies[0].DeviceProfile.DirectPlayProfiles[0].VideoCodec;
    assert.ok(directProfileCodecs.split(',').includes('h264'), `Expected h264 in ${directProfileCodecs}`);
    assert.equal(directProfileCodecs.split(',').includes('avc'), false, 'S10: avc must be mapped to h264');

    // Direct Play HEVC
    const pbHevc = await host.invoke('playback', { itemId: ids.hevcMovieId });
    assert.ok(pbHevc.url.includes('/stream?'));
    assert.equal(/api_?key=/i.test(pbHevc.url), false);

    // Transcode 480p -> real Jellyfin TranscodingUrl with ApiKey stripped
    const pb480 = await host.invoke('playback', { itemId: ids.hevcMovieId, quality: '480p' });
    assert.equal(validateProtocolType('PlaybackInfo', pb480).valid, true);
    assert.equal(pb480.mime, 'application/x-mpegURL');
    assert.ok(pb480.url.includes('/master.m3u8'));
    assert.equal(/api_?key=/i.test(pb480.url), false);

    // Search + suggest + progress + favorite + played + logout using modern routes
    const sr = await host.invoke('search', { query: 'Test' });
    assert.ok(sr.items.length >= 2);
    const hints = await host.invoke('suggest', { query: 'Test' });
    assert.ok(hints.length >= 1);

    await host.invoke('reportProgress', { itemId: ids.h264MovieId, positionMs: 1500, durationMs: 4000, event: 'progress', session: pbH264.session });
    await host.invoke('reportProgress', { itemId: ids.hevcMovieId, positionMs: 2500, durationMs: 4000, event: 'stop', session: pb480.session });
    await host.invoke('setFavorite', { itemId: ids.h264MovieId, favorite: true });
    await host.invoke('setPlayed', { itemId: ids.h264MovieId, played: true });
    // Verify full MediaBrowser Authorization header format & PlayMethod in progress bodies
    const authRe = new RegExp(`^MediaBrowser Client="depth3d", Device="[^"]+", DeviceId="depth3d-[0-9a-f]{16}", Version="[^"]+", Token="${expectedToken}"$`);
    assert.ok(recordedAuthHeaders.every((h) => authRe.test(h)));
    assert.equal(progressBodies[0].body.PlayMethod, 'DirectPlay');
    assert.equal(progressBodies[1].body.PlayMethod, 'Transcode');
    // S10: ZERO ms_* keys written to ctx.storage
    assert.deepEqual(storage.keys().sort(), ['deviceId', 'session']);

    // 401 on expired token -> NeedsAuth and session cleared
    forceExpired = true;
    await assert.rejects(() => host.invoke('categories'), (e) => e instanceof PluginCallError && e.code === 'NeedsAuth');
    assert.equal(storage.get('session'), null);
    forceExpired = false;
    await host.invoke('login');

    await host.invoke('logout');
    assert.ok(recordedCalls.some((c) => c.path === '/System/Info/Public'));
    assert.ok(recordedCalls.some((c) => c.path === '/UserViews'));
    assert.ok(recordedCalls.some((c) => c.path === '/UserItems/Resume'));
    assert.ok(recordedCalls.some((c) => c.path === `/UserFavoriteItems/${ids.h264MovieId}`));
    assert.ok(recordedCalls.some((c) => c.path === `/UserPlayedItems/${ids.h264MovieId}`));
  } finally {
    await host.dispose();
  }
});

test('bilibili plugin passes static check and recorded anonymous + QR login flow (WBI, DASH, token privacy)', async () => {
  const ids = BILI_FIXTURES._meta.ids;
  const tokenPattern = /SESSDATA|fakecsrf|fake%2Csession/;
  const calls = [];
  const logs = [];
  const state = { qrStep: 0, forceExpired: false, bodies: [] };

  const makeTransport = (sink) => async ({ url, method, headers, body }) => {
    const p = url.pathname;
    const qs = url.searchParams;
    const cookie = String(headers.Cookie || '');
    if (sink) {
      sink.push({ method, path: p, query: Object.fromEntries(qs.entries()), cookie });
    }
    const json = (data, status = 200) => ({
      status,
      headers: { 'content-type': 'application/json' },
      setCookies: [],
      body: Buffer.from(JSON.stringify(data), 'utf8'),
    });
    const authed =
      /SESSDATA=fake%2Csession%2Cdata\*11/.test(cookie) && !state.forceExpired;

    if (p === '/x/web-interface/nav') {
      return json(authed ? BILI_FIXTURES.navLoggedIn : BILI_FIXTURES.navAnonymous);
    }
    if (p === '/x/web-interface/index/top/feed/rcmd') return json(BILI_FIXTURES.recommend);
    if (p === '/x/web-interface/popular') return json(BILI_FIXTURES.popular);
    if (p === '/x/web-interface/ranking/v2') return json(BILI_FIXTURES.ranking);
    if (p === '/x/web-interface/newlist') return json(BILI_FIXTURES.newlist);
    if (p === '/x/web-interface/view') {
      const view = BILI_FIXTURES.views[qs.get('bvid')];
      return json(view || BILI_FIXTURES.notFound);
    }
    if (p === '/x/web-interface/archive/related') return json(BILI_FIXTURES.related);
    if (p === '/x/player/wbi/playurl') {
      if (!qs.get('w_rid') || !qs.get('wts')) return json({ code: -400, message: 'missing wbi' });
      return json(qs.get('qn') === '16' ? BILI_FIXTURES.ugcPlayurl360 : BILI_FIXTURES.ugcPlayurl);
    }
    if (p === '/x/player/wbi/v2') {
      return json({ code: 0, data: { subtitle: { subtitles: [] } } });
    }
    if (p === '/pgc/web/rank/list') return json(BILI_FIXTURES.pgcRank);
    if (p === '/pgc/view/web/season') {
      if (qs.get('ep_id') && Number(qs.get('ep_id')) === ids.missingEpId) {
        return json(BILI_FIXTURES.notFound);
      }
      return json(BILI_FIXTURES.season);
    }
    if (p === '/pgc/player/web/playurl') {
      const epId = Number(qs.get('ep_id'));
      if (epId === ids.previewEpId) return json(BILI_FIXTURES.pgcPlayurlPreview);
      if (epId === ids.drmEpId) return json(BILI_FIXTURES.pgcPlayurlDrm);
      if (epId === ids.missingEpId) return json(BILI_FIXTURES.notFound);
      return json(BILI_FIXTURES.pgcPlayurl);
    }
    if (p === '/x/web-interface/wbi/search/type') {
      if (!qs.get('w_rid') || !qs.get('wts')) return json({ code: -400, message: 'missing wbi' });
      return json(
        qs.get('search_type') === 'media_bangumi'
          ? BILI_FIXTURES.bangumiSearch
          : BILI_FIXTURES.search,
      );
    }
    if (p === '/main/suggest') return json(BILI_FIXTURES.suggest);
    if (p === '/x/passport-login/web/qrcode/generate') return json(BILI_FIXTURES.qrGenerate);
    if (p === '/x/passport-login/web/qrcode/poll') {
      const step = state.qrStep;
      state.qrStep += 1;
      if (step === 0) return json(BILI_FIXTURES.qrPollPending);
      if (step === 1) return json(BILI_FIXTURES.qrPollScanned);
      return json(BILI_FIXTURES.qrPollSuccess);
    }
    if (p === '/x/web-interface/history/cursor') {
      return authed ? json(BILI_FIXTURES.history) : json({ code: -101, message: '账号未登录' });
    }
    if (p === '/x/v3/fav/folder/created/list-all') {
      return authed ? json(BILI_FIXTURES.favFolders) : json({ code: -101, message: '账号未登录' });
    }
    if (p === '/x/v3/fav/resource/list') {
      return authed ? json(BILI_FIXTURES.favList) : json({ code: -101, message: '账号未登录' });
    }
    if (p === '/x/v3/fav/resource/deal') {
      state.bodies.push({ path: p, body: String(body || '') });
      return authed ? json(BILI_FIXTURES.favDeal) : json({ code: -101, message: '账号未登录' });
    }
    if (p === '/x/v2/history/report') {
      state.bodies.push({ path: p, body: String(body || '') });
      return authed ? json(BILI_FIXTURES.historyReport) : json({ code: -101, message: '账号未登录' });
    }
    return json({ code: -404, message: `unhandled mock path: ${p}` });
  };

  const staticCheck = await checkPlugin(BILI_PLUGIN_DIR, {
    config: { quality: 'auto' },
    dnsResolver: async () => ['104.16.0.1'],
    transportOverride: makeTransport(null),
  });
  assert.equal(staticCheck.ok, true, `bilibili check failed: ${staticCheck.errors.join('; ')}`);

  state.qrStep = 0;
  state.bodies.length = 0;

  const loaded = await loadPluginSource(BILI_PLUGIN_DIR);
  const storage = new PluginStorage();
  const host = new QuickJsPluginHost({
    manifest: loaded.manifest,
    mainJs: loaded.mainJs,
    locale: loaded.locale,
    storage,
    config: { quality: 'auto' },
    device: {
      appVersion: '1.0.0',
      locale: 'zh-CN',
      model: 'depth3d-kit',
      maxWidth: 3840,
      maxHeight: 2160,
      codecs: ['avc', 'hevc'],
      hdr: true,
    },
    dnsResolver: async () => ['104.16.0.1'],
    transportOverride: makeTransport(calls),
    logSink: (line) => logs.push(line),
  });

  try {
    // ---- anonymous browsing ------------------------------------------------
    const home = await host.invoke('home');
    assert.equal(validateProtocolType('HomeResult', home).valid, true);
    assert.deepEqual(
      home.map((shelf) => shelf.id),
      ['bili-recommend', 'bili-popular', 'bili-ranking', 'bili-bangumi', 'bili-regions'],
    );
    assert.equal(home[0].items.length, 1, 'live recommendation must be filtered out');
    assert.equal(home[0].items[0].id, `v:${ids.singleBvid}`);
    assert.equal(tokenPattern.test(JSON.stringify(home)), false);

    const root = await host.invoke('browse', { categoryId: '' });
    assert.equal(validateProtocolType('ItemPage', root).valid, true);
    assert.equal(root.items.length, 18);

    const popular = await host.invoke('browse', { categoryId: 'popular' });
    assert.equal(popular.items.length, 2);
    assert.equal(popular.next, null);

    const region = await host.invoke('browse', { categoryId: 'region:1', cursor: '2' });
    assert.equal(region.items.length, 1);
    assert.equal(region.next, null);

    const bangumi = await host.invoke('browse', { categoryId: 'bangumi' });
    assert.equal(bangumi.items.length, 2);
    assert.ok(bangumi.items.every((it) => it.kind === 'series' && it.id.startsWith('ss:')));

    // ---- details ----------------------------------------------------------
    const multi = await host.invoke('detail', { itemId: `v:${ids.multiBvid}` });
    assert.equal(validateProtocolType('Item', multi).valid, true);
    assert.deepEqual(
      multi.episodes.map((e) => e.id),
      [`v:${ids.multiBvid}:1`, `v:${ids.multiBvid}:2`],
    );
    const page2 = await host.invoke('detail', { itemId: `v:${ids.multiBvid}:2` });
    assert.equal(page2.kind, 'episode');
    assert.equal(page2.parentId, `v:${ids.multiBvid}`);
    assert.equal(page2.durationMs, 600000);
    const ugc = await host.invoke('detail', { itemId: `v:${ids.seasonBvid}` });
    assert.equal(ugc.episodes.length, 2);
    assert.ok(ugc.badges.includes('合集'));

    const series = await host.invoke('detail', { itemId: `ss:${ids.seasonId}` });
    assert.equal(series.kind, 'series');
    assert.equal(series.seasons.length, 2);
    assert.equal(series.seasons[0].id, `sg:${ids.seasonId}`);
    const season = await host.invoke('detail', { itemId: `sg:${ids.seasonId}` });
    assert.equal(season.kind, 'season');
    assert.deepEqual(
      season.episodes.map((e) => e.id),
      [ids.epId, ids.epId2, ids.previewEpId, ids.drmEpId].map((n) => `ep:${n}`),
    );
    assert.equal(season.episodes[0].title, '第 1 话 · 启程');
    const episode = await host.invoke('detail', { itemId: `ep:${ids.epId}` });
    assert.equal(episode.kind, 'episode');
    assert.equal(episode.parentId, `sg:${ids.seasonId}`);

    // ---- playback: separated DASH, codec filtering, quality lock ----------
    const pb = await host.invoke('playback', { itemId: `v:${ids.singleBvid}` });
    assert.equal(validateProtocolType('PlaybackInfo', pb).valid, true);
    assert.equal(pb.durationMs, 600000);
    assert.equal(pb.dash.video.length, 3, 'av1 must be filtered out for avc+hevc device');
    assert.equal(
      pb.dash.video.some((v) => v.codecs.startsWith('av01')),
      false,
    );
    assert.equal(pb.dash.audio.length, 2);
    assert.equal(pb.qualities.find((q) => q.selected).id, '32');
    assert.equal(pb.qualities.find((q) => q.id === '80').locked, '登录后可看');
    assert.equal(pb.headers.Referer, 'https://www.bilibili.com/');
    assert.equal('Cookie' in pb.headers, false, 'stream headers must not carry the login cookie');
    assert.equal(tokenPattern.test(JSON.stringify(pb)), false);
    const v32 = pb.dash.video.find((v) => v.quality === '32' && v.id.endsWith('avc'));
    assert.equal(v32.initRange, '0-935');
    assert.equal(v32.indexRange, '936-1251');
    assert.deepEqual(v32.backupUrls, ['https://upos-fake2.bilivideo.com/ugc/v32-avc.m4s']);

    const pb360 = await host.invoke('playback', { itemId: `v:${ids.singleBvid}`, quality: '16' });
    assert.equal(pb360.qualities.find((q) => q.selected).id, '16');
    assert.equal(pb360.qualities.find((q) => q.id === '32').locked, '登录后可看');
    assert.equal(pb360.dash.video.length, 1);

    const pgc = await host.invoke('playback', { itemId: `ep:${ids.epId}` });
    assert.equal(pgc.durationMs, 1440000);
    assert.equal(pgc.next, `ep:${ids.epId2}`);
    assert.equal(pgc.dash.video.length, 1);

    await assert.rejects(
      () => host.invoke('playback', { itemId: `ep:${ids.previewEpId}` }),
      (e) => e instanceof PluginCallError && e.code === 'Forbidden',
    );
    await assert.rejects(
      () => host.invoke('playback', { itemId: `ep:${ids.drmEpId}` }),
      (e) => e instanceof PluginCallError && e.code === 'Unsupported',
    );
    await assert.rejects(
      () => host.invoke('playback', { itemId: `ep:${ids.missingEpId}` }),
      (e) => e instanceof PluginCallError && e.code === 'NotFound',
    );

    // ---- search / suggest -------------------------------------------------
    const search = await host.invoke('search', { query: '深度' });
    assert.equal(search.items.length, 1);
    assert.equal(search.items[0].title.includes('<em'), false, 'search titles must be cleaned');
    assert.equal(search.next, '2');
    const seriesSearch = await host.invoke('search', { query: '深度', kind: 'series' });
    assert.equal(seriesSearch.items[0].id, `ss:${ids.seasonId}`);
    assert.deepEqual(await host.invoke('suggest', { query: '深' }), ['深度测试', '深度估计']);

    // ---- WBI signature was actually applied -------------------------------
    const wbiCalls = calls.filter(
      (c) =>
        c.path === '/x/player/wbi/playurl' ||
        c.path === '/x/web-interface/wbi/search/type',
    );
    assert.ok(wbiCalls.length >= 4);
    for (const c of wbiCalls) {
      assert.ok(c.query.w_rid && c.query.wts, `${c.path} must be WBI-signed`);
    }

    // ---- QR login ---------------------------------------------------------
    const qr = await host.invoke('loginStart');
    assert.equal(validateProtocolType('QrLoginStartResult', qr).valid, true);
    assert.equal(qr.state, 'FAKEQRKEY');
    assert.equal(qr.qr.includes('FAKEQRKEY'), true);
    assert.equal((await host.invoke('loginPoll', { state: qr.state })).status, 'pending');
    assert.equal((await host.invoke('loginPoll', { state: qr.state })).status, 'scanned');
    const done = await host.invoke('loginPoll', { state: qr.state });
    assert.equal(validateProtocolType('LoginPollResult', done).valid, true);
    assert.equal(done.status, 'done');
    assert.equal(done.user.name, '深度测试员');
    assert.deepEqual(storage.keys(), ['session']);
    const session = storage.get('session');
    assert.equal(session.sessdata, 'fake%2Csession%2Cdata*11');
    assert.equal(session.biliJct, 'fakecsrf');
    assert.equal(session.mid, '123456789');

    // ---- authenticated shelves -------------------------------------------
    const home2 = await host.invoke('home');
    assert.equal(home2[0].id, 'bili-history');
    assert.equal(home2[0].style, 'continue');
    assert.equal(home2[0].items[0].progressMs, 120000);
    assert.equal(tokenPattern.test(JSON.stringify(home2)), false);
    const history = await host.invoke('browse', { categoryId: 'history' });
    assert.equal(history.items.length, 1);
    const favs = await host.invoke('browse', { categoryId: 'favs' });
    assert.equal(favs.items.length, 1);
    assert.equal(favs.items[0].id, `fav:${ids.favFolderId}`);
    const favItems = await host.invoke('browse', { categoryId: `fav:${ids.favFolderId}` });
    assert.equal(favItems.items.length, 1);
    assert.equal(favItems.items[0].id, `v:${ids.singleBvid}`);

    // ---- progress + favorites --------------------------------------------
    await host.invoke('reportProgress', {
      itemId: `v:${ids.multiBvid}`,
      positionMs: 60000,
      durationMs: 600000,
      event: 'progress',
    });
    const report = state.bodies.find((b) => b.path === '/x/v2/history/report');
    assert.ok(report, 'history report must be sent for a logged-in user');
    assert.ok(report.body.includes('aid=880002'));
    assert.ok(report.body.includes('cid=770002'));
    assert.ok(report.body.includes('progress=60'));
    assert.ok(report.body.includes('csrf=fakecsrf'));

    await host.invoke('setFavorite', { itemId: `v:${ids.singleBvid}`, favorite: true });
    await host.invoke('setFavorite', { itemId: `v:${ids.singleBvid}`, favorite: false });
    const deals = state.bodies.filter((b) => b.path === '/x/v3/fav/resource/deal');
    assert.equal(deals.length, 2);
    assert.ok(deals[0].body.includes('rid=880001'));
    assert.ok(deals[0].body.includes('add_media_ids=555001'));
    assert.ok(deals[0].body.includes('csrf=fakecsrf'));
    assert.ok(deals[1].body.includes('del_media_ids=555001'));
    await assert.rejects(
      () => host.invoke('setFavorite', { itemId: `ep:${ids.epId}`, favorite: true }),
      (e) => e instanceof PluginCallError && e.code === 'Unsupported',
    );

    // ---- expired session --------------------------------------------------
    state.forceExpired = true;
    await assert.rejects(
      () => host.invoke('browse', { categoryId: 'history' }),
      (e) => e instanceof PluginCallError && e.code === 'NeedsAuth',
    );
    await assert.rejects(
      () => host.invoke('home'),
      (e) => e instanceof PluginCallError && e.code === 'NeedsAuth',
    );
    state.forceExpired = false;

    // ---- logout keeps anonymous browsing working -------------------------
    await host.invoke('logout');
    assert.equal(storage.get('session'), null);
    const home3 = await host.invoke('home');
    assert.equal(home3[0].id, 'bili-recommend');

    // ---- no token may ever reach a title/description/log -----------------
    for (const line of logs) {
      const text = typeof line === 'string' ? line : JSON.stringify(line);
      assert.equal(tokenPattern.test(text), false, `log leaked a token: ${text}`);
    }
  } finally {
    await host.dispose();
  }
});

test('Round 3: shared zip-vectors.json (incl. dual.zip PoC) and shared dom-vectors.json pass 100% on Node', async () => {
  const zipVectors = JSON.parse(fs.readFileSync(path.join(SCHEMA_DIR, 'zip-vectors.json'), 'utf8'));
  assert.ok(zipVectors.cases.length >= 8);
  for (const tc of zipVectors.cases) {
    const buf = Buffer.from(tc.base64, 'base64');
    if (tc.valid) {
      const inspected = await inspectArchiveBuffer(buf);
      if (tc.expectedMainJsPrefix) {
        assert.ok(inspected.mainJs.startsWith(tc.expectedMainJsPrefix), `Zip vector ${tc.name} mainJs mismatch`);
      }
    } else {
      await assert.rejects(
        () => inspectArchiveBuffer(buf),
        Error,
        `Malicious zip vector "${tc.name}" MUST be rejected`,
      );
    }
  }

  const domVectors = JSON.parse(fs.readFileSync(path.join(SCHEMA_DIR, 'dom-vectors.json'), 'utf8'));
  assert.ok(domVectors.cases.length >= 4);
  const host = new QuickJsPluginHost({
    manifest: { api: 1, id: 'dev.depth3d.domvec', name: 'DomVec', version: '1.0.0' },
    mainJs: `export default {
      home() { return []; },
      browse(ctx, arg) {
        const doc = arg.mode === 'xml' ? ctx.parse.xml(arg.input) : ctx.parse.html(arg.input);
        return arg.assertions.map((a) => {
          if (a.op === 'rootTag') return doc.tag;
          if (a.op === 'rootText') return doc.text;
          if (a.op === 'rootChildrenTags') return doc.children.map((c) => c.tag);
          if (a.op === 'firstTag') return doc.querySelector(a.selector)?.tag ?? null;
          if (a.op === 'firstText') return doc.querySelector(a.selector)?.text ?? null;
          if (a.op === 'firstAttr') return doc.querySelector(a.selector)?.attr(a.attr);
          if (a.op === 'allTexts') return doc.querySelectorAll(a.selector).map((n) => n.text);
          if (a.op === 'allTags') return doc.querySelectorAll(a.selector).map((n) => n.tag);
          if (a.op === 'allAttrs') return doc.querySelectorAll(a.selector).map((n) => n.attr(a.attr));
          if (a.op === 'childrenTags') return doc.querySelector(a.selector).children.map((c) => c.tag);
          if (a.op === 'subFirstAttr') {
            const p = doc.querySelector(a.parent);
            return p ? (p.querySelector(a.selector)?.attr(a.attr) ?? null) : null;
          }
          if (a.op === 'selectorThrows') {
            try {
              doc.querySelector(a.selector);
              return false;
            } catch {
              return true;
            }
          }
          return null;
        });
      },
      detail() { return { id: '1', kind: 'video', title: '1' }; },
      playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
    };`,
    logSink: () => {},
  });
  try {
    for (const tc of domVectors.cases) {
      const actual = await host.invoke('browse', {
        mode: tc.mode,
        input: tc.input,
        assertions: tc.assertions,
      });
      for (let i = 0; i < tc.assertions.length; i++) {
        assert.deepEqual(actual[i], tc.assertions[i].expected, `DOM vector ${tc.name} assertion #${i}`);
      }
    }
  } finally {
    await host.dispose();
  }

  // Round 5 & 6 Items #1, #3, #4, #6, #7, #9: ALL matrixCases (incl. knownDifference & :nth-* overflow),
  // O(N*S) memoized selectors (< 1s), step & memory budget errors, deadline timeout, adoption agency recount
  const { PluginDomParser, DomLimitExceededError, DomTimeoutError } = await import('../src/dom.js');
  assert.ok(domVectors.matrixCases.length >= 96);
  let knownDiffCount = 0;
  for (const mc of domVectors.matrixCases) {
    if (mc.knownDifference) knownDiffCount += 1;
    const p = new PluginDomParser();
    let threw = false;
    let actual = null;
    try {
      const root = mc.mode === 'xml' ? p.parseXml(mc.input) : p.parseHtml(mc.input);
      const id = mc.target === '#root' ? root : p.selectFirstId(root, mc.target);
      if (id <= 0) actual = null;
      else if (mc.op === 'tag') actual = p.tag(id);
      else if (mc.op === 'text') actual = p.text(id);
      else if (mc.op === 'html') actual = p.html(id);
      else if (mc.op === 'outer') actual = p.outerHtml(id);
      else if (mc.op === 'attrs') actual = p.attrsJson(id);
      else if (mc.op === 'children') actual = p.childrenIds(id).map((x) => p.tag(x)).join(',');
      else if (mc.op.startsWith('attr:')) actual = p.attr(id, mc.op.slice(5));
      else if (mc.op.startsWith('all:')) actual = p.selectAllIds(id, mc.op.slice(4)).map((x) => `${p.tag(x)}=${p.text(x)}`).join('|');
    } catch {
      threw = true;
    } finally {
      p.clear();
    }
    if (mc.throws) {
      assert.equal(threw, true, `matrixCase #${mc.id} must throw`);
    } else {
      assert.equal(threw, false, `matrixCase #${mc.id} must not throw`);
      const expected = mc.knownDifference ? mc.expectedKit : mc.expected;
      assert.equal(actual, expected, `matrixCase #${mc.id} mismatch`);
    }
  }
  assert.ok(knownDiffCount >= 6, 'Expected at least 6 knownDifference vectors (select, ws, template)');

  // Round 9 & 10: Run ALL shared estimatedBytesVectors from dom-vectors.json (±5% parity with Android)
  assert.ok(Array.isArray(domVectors.estimatedBytesVectors) && domVectors.estimatedBytesVectors.length >= 24);
  for (const ev of domVectors.estimatedBytesVectors) {
    const inp = ev.input ?? ((ev.prefix || '') + (ev.unit || '').repeat(ev.repeat || 1) + (ev.suffix || ''));
    const tol = ev.tolerancePct ?? 5;
    const p = new PluginDomParser();
    try {
      if (ev.mode === 'xml') p.parseXml(inp);
      else p.parseHtml(inp);
      const actual = p.currentEstimatedDomBytes;
      const diffPct = Math.abs(actual - ev.expectedBytes) * 100 / ev.expectedBytes;
      assert.ok(
        diffPct <= tol,
        `estimatedBytesVector '${ev.name}': actual=${actual} expected=${ev.expectedBytes} diff=${diffPct.toFixed(2)}% > ${tol}%`,
      );
    } finally {
      p.clear();
    }
  }

  // Pathological selector complexity tests (< 1s each)
  {
    const p = new PluginDomParser();
    try {
      const r = p.parseHtml('<div>'.repeat(500));
      const t0 = Date.now();
      assert.equal(p.selectAllIds(r, 'span div div div div').length, 0);
      assert.ok(Date.now() - t0 < 1000, '500 nested divs selector must complete in < 1s');
    } finally { p.clear(); }
  }
  {
    const p = new PluginDomParser();
    try {
      const r = p.parseHtml('<ul>' + '<li>x</li>'.repeat(2000) + '</ul>');
      const t1 = Date.now();
      assert.equal(p.selectAllIds(r, 'b ~ li ~ li').length, 0);
      assert.ok(Date.now() - t1 < 1000, '2000 li b ~ li ~ li must complete in < 1s');
      const t2 = Date.now();
      assert.equal(p.selectAllIds(r, 'b ~ li ~ li ~ li').length, 0);
      assert.ok(Date.now() - t2 < 1000, '2000 li b ~ li ~ li ~ li must complete in < 1s');
    } finally { p.clear(); }
  }
  {
    const p = new PluginDomParser();
    try {
      const r = p.parseHtml('<ul>' + '<li>x</li>'.repeat(80_000) + '</ul>');
      for (const [sel, count] of [['li:nth-child(2n+1)', 40_000], ['li:last-child', 1], ['li:nth-last-of-type(3n+1)', 26_667]]) {
        const t = Date.now();
        assert.equal(p.selectAllIds(r, sel).length, count);
        assert.ok(Date.now() - t < 1000, `80k li ${sel} must complete in < 1s`);
      }
    } finally { p.clear(); }
  }
  // Step budget, deadline timeout, adoption agency 95k recount, unified element count excluding #document
  {
    const tight = new PluginDomParser(8, 2 * 1024 * 1024, 32 * 1024 * 1024, 500);
    try {
      const r = tight.parseHtml('<ul>' + '<li>x</li>'.repeat(2000) + '</ul>');
      assert.throws(() => tight.selectAllIds(r, 'li'), DomLimitExceededError);
    } finally { tight.clear(); }

    const exp = new PluginDomParser();
    try {
      const rx = exp.parseHtml('<ul>' + '<li>x</li>'.repeat(3000) + '</ul>');
      exp.setCallDeadlineMs(Date.now() - 1);
      assert.throws(() => exp.selectAllIds(rx, 'li'), DomTimeoutError);
    } finally { exp.clear(); }

    const amp = new PluginDomParser();
    try {
      assert.throws(() => amp.parseHtml('<b><p>x</b>y</p>'.repeat(130_000)), DomLimitExceededError);
      assert.equal(amp.totalNodes, 0);
    } finally { amp.clear(); }

    const cnt = new PluginDomParser();
    try {
      cnt.parseHtml('<p>x</p>');
      assert.equal(cnt.totalNodes, 4);
      cnt.parseXml('<r><a/></r>');
      assert.equal(cnt.totalNodes, 6);
    } finally { cnt.clear(); }
  }
});

test('Round 6: selector hard caps, 16 MB memo budget, budgeted attr/class, dedup handles + doc.close(), :nth-* overflow, bridge RangeError trap', async () => {
  const { PluginDomParser, DomLimitExceededError } = await import('../src/dom.js');
  const html190k = '<div>' + '<i></i>'.repeat(190_000) + '</div>';

  // 1. Blocking #1: 190k <i> + 6 KB / 3000-compound selector rejected in < 1s; lazy memo + 16 MB budget
  {
    const p = new PluginDomParser();
    const root = p.parseHtml(html190k);

    let t = Date.now();
    assert.throws(() => p.selectAllIds(root, 'x' + ' x'.repeat(2999)), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000);

    t = Date.now();
    assert.equal(p.selectAllIds(root, 'x' + ' x'.repeat(31)).length, 0);
    assert.ok(Date.now() - t < 1000, '32-compound non-matching selector must complete in < 1s');

    t = Date.now();
    assert.equal(p.selectAllIds(root, 'x' + ' i'.repeat(31)).length, 0);
    assert.ok(Date.now() - t < 1000, '32-compound matching-tail descendant selector must complete in < 1s');

    // 190k siblings x 32 compounds trips 16 MB query memory budget -> DomLimitExceededError in < 1s
    t = Date.now();
    assert.throws(() => p.selectAllIds(root, 'x' + ' ~ i'.repeat(31)), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000, '16 MB memo memory budget must trip in < 1s');
    p.clear();
  }

  // 2. Blocking #2: 2000 x `~ li` rejected + nesting > 4 rejected + max legal complexity (depth 4 x 32) no RangeError
  {
    const p = new PluginDomParser();
    const root = p.parseHtml('<ul>' + '<li></li>'.repeat(2010) + '</ul>');
    let t = Date.now();
    assert.throws(() => p.selectAllIds(root, 'li' + ' ~ li'.repeat(1999) + ':last-child'), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000);

    assert.throws(() => p.selectAllIds(root, ':not(:not(:not(:not(:not(li)))))'), DomLimitExceededError);

    t = Date.now();
    const chain32 = 'li' + ' ~ li'.repeat(31);
    assert.equal(p.selectAllIds(root, `li:is(:not(:is(:not(span))), ${chain32})`).length, 1979);
    assert.ok(Date.now() - t < 1000);
    p.clear();
  }

  // 3. Required #3: 2 MB attr + [x*="a...(20k)b"] / [x*="a...(250)b"] / 32x[x*=b i], 1M-token class + .z
  {
    const p = new PluginDomParser();
    const rAttr = p.parseHtml('<p x="' + 'a'.repeat(2_000_000) + '"></p>');

    let t = Date.now();
    assert.throws(() => p.selectAllIds(rAttr, '[x*="' + 'a'.repeat(20_000) + 'b"]'), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000);

    p.setCallDeadlineMs(Date.now() + 1000);
    t = Date.now();
    assert.throws(() => p.selectAllIds(rAttr, '[x*="' + 'a'.repeat(250) + 'b"]'), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000, '2 MB attr budgeted KMP scan must abort in < 1s');

    t = Date.now();
    assert.throws(() => p.selectAllIds(rAttr, Array(32).fill('p[x*=b i]').join(',')), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000, '2 MB attr x 32 [x*=b i] must abort in < 1s');
    p.clear();

    const rCls = p.parseHtml('<p class="' + 'a '.repeat(1_000_000) + '"></p>');
    t = Date.now();
    assert.throws(() => p.selectAllIds(rCls, Array(3000).fill('.z').join(',')), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000);

    t = Date.now();
    try {
      assert.equal(p.selectAllIds(rCls, Array(32).fill('.z').join(',')).length, 0);
    } catch (err) {
      assert.ok(err instanceof DomLimitExceededError);
    }
    assert.ok(Date.now() - t < 1000, '1M-token class + 32 groups .z must complete/abort in < 1s');
    p.clear();

    const rCls500k = p.parseHtml('<p class="' + 'a '.repeat(500_000) + '"></p>');
    t = Date.now();
    assert.equal(p.selectAllIds(rCls500k, Array(32).fill('.z').join(',')).length, 0);
    assert.ok(Date.now() - t < 1000, '500k-token cached class + 32 groups .z must complete in < 1s');
    p.clear();
  }

  // 4. Required #4: 110k <i>x + select('*') looped 40x -> deduplicated handles; 8 live docs / 48 MB VM cap / doc.close()
  {
    const p = new PluginDomParser();
    try {
      const html110k = '<div>' + '<i>x</i>'.repeat(110_000) + '</div>'; // ~13.0 MiB host model
      const root = p.parseHtml(html110k);
      const first = p.selectAllIds(root, '*');
      const expectedHandles = p.handleCount;
      assert.equal(expectedHandles, 110_005);
      for (let i = 0; i < 40; i++) {
        const t = Date.now();
        const ids = p.selectAllIds(root, '*');
        assert.ok(Date.now() - t < 1000, `select('*') iter #${i} exceeded 1s`);
        assert.equal(ids.length, first.length);
        assert.equal(p.handleCount, expectedHandles, `handle count leaked on iter #${i}`);
      }

      // ~13.0 MiB doc1 + ~19.3 MiB handles + ~13.0 MiB doc2 = ~45.3 MiB <= 48 MiB
      const root2 = p.parseHtml(html110k);
      assert.equal(p.docCount, 2);
      assert.equal(p.totalNodes, 220_008);
      const html50k = '<div>' + '<i>x</i>'.repeat(50_000) + '</div>'; // ~5.9 MiB -> ~51.2 MiB > 48 MiB VM cap
      assert.throws(() => p.parseHtml(html50k), DomLimitExceededError);

      assert.equal(p.closeDoc(root), true);
      assert.equal(p.docCount, 1);
      assert.equal(p.totalNodes, 110_004);
      const root3 = p.parseHtml(html50k);
      assert.equal(p.docCount, 2);
      assert.equal(p.totalNodes, 160_008);
      p.closeDoc(root2);
      p.closeDoc(root3);
      assert.equal(p.docCount, 0);
      assert.equal(p.totalNodes, 0);
      assert.equal(p.handleCount, 0);
      assert.equal(p.currentVmMemoryBytes, 0);
    } finally {
      p.clear();
    }
  }

  // 5. Small #7 & #9: :nth-* overflow rejection + identical DomLimitExceededError class for step & memory budgets
  {
    const p = new PluginDomParser();
    const r = p.parseHtml('<ul><li>1</li><li>2</li></ul>');
    for (const sel of [
      'li:nth-child(n-2147483648)',
      'li:nth-child(2n-2147483647)',
      'li:nth-child(-2147483648n+3)',
      'li:nth-child(2147483648)',
      'li:nth-child(1000001)',
      'li:nth-child(-1000001)',
      'li:nth-child(99999999999999999999n+1)',
    ]) {
      assert.throws(() => p.selectAllIds(r, sel), Error, `Expected rejection for ${sel}`);
    }

    const stepP = new PluginDomParser(8, 2 * 1024 * 1024, 24 * 1024 * 1024, 10);
    const rs = stepP.parseHtml('<ul>' + '<li></li>'.repeat(50) + '</ul>');
    let stepErr = null;
    try { stepP.selectAllIds(rs, 'li'); } catch (e) { stepErr = e; }
    const memP = new PluginDomParser(8, 2 * 1024 * 1024, 24 * 1024 * 1024, 2_000_000, 48 * 1024 * 1024, 1024);
    const rm = memP.parseHtml('<ul>' + '<li></li>'.repeat(200) + '</ul>');
    let memErr = null;
    try { memP.selectAllIds(rm, 'li ~ li'); } catch (e) { memErr = e; }
    assert.ok(stepErr instanceof DomLimitExceededError);
    assert.ok(memErr instanceof DomLimitExceededError);
    assert.equal(stepErr.constructor, memErr.constructor);
    p.clear();
    stepP.clear();
    memP.clear();
  }

  // 6. Real QuickJS host: doc.close() loop (> 8 sequential docs), 9 unclosed docs fails, bridge RangeError trapped
  const host = new QuickJsPluginHost({
    manifest: { api: 1, id: 'dev.depth3d.r6js', name: 'R6JS', version: '1.0.0' },
    mainJs: `export default {
      home() { return []; },
      browse(ctx, arg) {
        if (arg.categoryId === 'close-loop') {
          for (let i = 0; i < 20; i++) {
            const d = ctx.parse.html('<p>v' + i + '</p>');
            const p = d.querySelector('p');
            if (p.text !== 'v' + i) throw new Error('mismatch');
            d.close();
            if (d.tag !== '' || p.text !== '') throw new Error('not cleared');
          }
          return { ok: true };
        }
        if (arg.categoryId === 'unclosed-9') {
          for (let i = 0; i < 9; i++) ctx.parse.html('<p>' + i + '</p>');
          return { ok: true };
        }
        if (arg.categoryId === 'bridge-fault') {
          const d = ctx.parse.html('<p>ok</p>');
          return { tag: d.querySelector('p').tag };
        }
        return { ok: true };
      },
      detail() { return { id: '1', kind: 'video', title: '1' }; },
      playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
    };`,
    logSink: () => {},
  });
  try {
    const r = await host.invoke('browse', { categoryId: 'close-loop' });
    assert.equal(r.ok, true);
    await assert.rejects(
      () => host.invoke('browse', { categoryId: 'unclosed-9' }),
      (e) => e instanceof PluginCallError && e.code === 'Internal',
    );
    host._bridgeFaultInjectorForTesting = (op) => {
      if (op === 'domSelectFirstId') throw new RangeError('Maximum call stack size exceeded');
    };
    await assert.rejects(
      () => host.invoke('browse', { categoryId: 'bridge-fault' }),
      (e) => e instanceof PluginCallError && e.code === 'Internal',
    );
    host._bridgeFaultInjectorForTesting = null;
    const recovered = await host.invoke('browse', { categoryId: 'bridge-fault' });
    assert.equal(recovered.tag, 'p');
  } finally {
    await host.dispose();
  }
});

test('Round 7: byte-budgeted DOM (2 MB/24 MB/48 MB), :empty caching & loop ticks, 8 MB serialization cap, 200k classed selectors, monotonic nextId', async () => {
  const { PluginDomParser, DomLimitExceededError, DomTimeoutError } = await import('../src/dom.js');

  // 1. Input > 2 MB rejected, plus 3 OOM payloads (PI, text+comment, <i>x * 300k) aborted mid-parse
  {
    const p = new PluginDomParser();
    try {
      assert.throws(() => p.parseHtml('<p>' + 'a'.repeat(2 * 1024 * 1024 + 16) + '</p>'), DomLimitExceededError);

      const pi2Mb = '<?>'.repeat(Math.floor((2 * 1024 * 1024) / 3) - 10);
      const textCmt2Mb = 'x<!>'.repeat(Math.floor((2 * 1024 * 1024) / 4) - 10);
      const elemText300k = '<i>x'.repeat(300_000); // 1.2 MB input, ~35.5 MB host model > 32 MB cap
      const elemText180k = '<i>x'.repeat(180_000); // 720 KB input, ~21.3 MB host model each

      for (const bad of [pi2Mb, textCmt2Mb, elemText300k]) {
        const t = Date.now();
        assert.throws(() => p.parseHtml(bad), DomLimitExceededError);
        assert.ok(Date.now() - t < 1500);
        assert.equal(p.docCount, 0);
        assert.equal(p.currentVmMemoryBytes, 0);
      }

      // 2 x (<i>x * 180k) (~21.3 MB each = ~42.6 MB <= 48 MB) succeeds, then select('*') hits 48 MB VM budget
      const d1 = p.parseHtml(elemText180k);
      const d2 = p.parseHtml(elemText180k);
      assert.throws(() => p.selectAllIds(d1, '*'), DomLimitExceededError);
      p.closeDoc(d1);
      p.closeDoc(d2);
      assert.equal(p.currentVmMemoryBytes, 0);

      // 8 simultaneous live docs near the 48 MB VM cap + select('*') + outerHtml
      const nearCapDocs = [
        '<div>' + '<i>x</i>'.repeat(30_000) + '</div>',
        '<div>' + '<i class="a b"></i>'.repeat(35_000) + '</div>',
        '<div>' + '<!--c-->'.repeat(60_000) + '<p>ok</p></div>',
        '<div>' + '<i></i>'.repeat(50_000) + '</div>',
      ];
      const roots = [];
      for (let i = 0; i < 8; i++) roots.push(p.parseHtml(nearCapDocs[i % 4]));
      assert.equal(p.docCount, 8);
      assert.throws(() => p.parseHtml('<p>9th</p>'), DomLimitExceededError);
      for (const r of roots) {
        try { p.selectAllIds(r, '*'); } catch (e) { assert.ok(e instanceof DomLimitExceededError); }
        assert.ok(p.outerHtml(r).length > 0);
      }
      for (const r of roots) p.closeDoc(r);
      assert.equal(p.currentVmMemoryBytes, 0);
    } finally {
      p.clear();
    }
  }

  // 2. :empty caching + childNodes/attributes step accounting + entry deadline checks
  {
    const p = new PluginDomParser();
    const rEmpty = p.parseHtml('<div>' + '<!>'.repeat(220_000) + '</div>');
    p.setCallDeadlineMs(Date.now() + 1000);
    let t = Date.now();
    assert.equal(p.selectAllIds(rEmpty, 'div' + ':empty'.repeat(680)).length, 1);
    assert.ok(Date.now() - t < 1000, ':empty x680 on 220k comments must complete in < 1s');
    p.clear();

    // Many attributes per element (`MA.java`): 400 elems x 512 attrs x 1000 [id] hits 2M step budget in < 1s
    let oneMany = '<p';
    for (let i = 0; i < 510; i++) oneMany += ' a' + i.toString(36);
    oneMany += ' id=a class=a></p>';
    const rMa = p.parseHtml('<div>' + oneMany.repeat(400) + '</div>');
    p.setCallDeadlineMs(Date.now() + 1000);
    t = Date.now();
    assert.throws(() => p.selectAllIds(rMa, 'p' + '[id]'.repeat(1000)), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000);

    // Expired deadline rejected at entry of every DOM method
    p.setCallDeadlineMs(Date.now() - 1);
    assert.throws(() => p.parseHtml('<p>x</p>'), DomTimeoutError);
    assert.throws(() => p.selectFirstId(rMa, 'p'), DomTimeoutError);
    assert.throws(() => p.selectAllIds(rMa, 'p'), DomTimeoutError);
    assert.throws(() => p.childrenIds(rMa), DomTimeoutError);
    assert.throws(() => p.tag(rMa), DomTimeoutError);
    assert.throws(() => p.text(rMa), DomTimeoutError);
    assert.throws(() => p.html(rMa), DomTimeoutError);
    assert.throws(() => p.outerHtml(rMa), DomTimeoutError);
    assert.throws(() => p.attr(rMa, 'id'), DomTimeoutError);
    assert.throws(() => p.attrsJson(rMa), DomTimeoutError);
    p.clear();
  }

  // 3. 8 MB serialization output cap on html(), outerHtml(), and text()
  {
    const p = new PluginDomParser();
    const rQuot = p.parseHtml("<p a='" + '"'.repeat(1_600_000) + "'></p>");
    const t = Date.now();
    assert.throws(() => p.outerHtml(rQuot), DomLimitExceededError);
    assert.throws(() => p.html(rQuot), DomLimitExceededError);
    assert.ok(Date.now() - t < 1000);
    p.clear();

    const smallOut = new PluginDomParser(8, 2 * 1024 * 1024, 24 * 1024 * 1024, 2_000_000, 48 * 1024 * 1024, 16 * 1024 * 1024, 64 * 1024);
    const rTxt = smallOut.parseHtml('<p>' + 'x'.repeat(100_000) + '</p>');
    assert.throws(() => smallOut.text(rTxt), DomLimitExceededError);
    smallOut.clear();
  }

  // 4. 200,000 classed elements on `.a`, `i.a`, `.a.b`, `.a .b`, `.a ~ .b` under default 16 MB selector memory budget
  {
    const defP = new PluginDomParser();
    const rDef = defP.parseHtml('<div class="a">' + '<p class="a b">'.repeat(70_000) + '</div>');
    assert.equal(defP.selectAllIds(rDef, '.a').length, 70_001);
    assert.equal(defP.selectAllIds(rDef, 'p.a').length, 70_000);
    assert.equal(defP.selectAllIds(rDef, '.a.b').length, 70_000);
    assert.equal(defP.selectAllIds(rDef, '.a .b').length, 70_000);
    defP.clear();

    const p200k = new PluginDomParser({
      maxDocBytes: 4 * 1024 * 1024,
      maxDocMemoryBytes: 128 * 1024 * 1024,
      maxVmMemoryBytes: 256 * 1024 * 1024,
      maxProcessMemoryBytes: 256 * 1024 * 1024,
      maxSelectorMemoryBytes: 16 * 1024 * 1024,
    });
    const r200k = p200k.parseHtml('<div class="a">' + '<i class="a b"></i>'.repeat(200_000) + '</div>');
    for (const [sel, expected] of [
      ['.a', 200_001],
      ['i.a', 200_000],
      ['.a.b', 200_000],
      ['.a .b', 200_000],
      ['.a ~ .b', 199_999],
      ['.zz ~ .a', 0],
      ['i.a + i.b', 199_999],
    ]) {
      const t = Date.now();
      assert.equal(p200k.selectAllIds(r200k, sel).length, expected, `200k classed selector ${sel}`);
      assert.ok(Date.now() - t < 1000, `200k classed selector ${sel} exceeded 1s`);
    }
    p200k.clear();
  }

  // 5. Monotonic nextId never reset across clear()
  {
    const p = new PluginDomParser();
    const id1 = p.parseHtml('<p>one</p>');
    p.clear();
    const id2 = p.parseHtml('<p>two</p>');
    assert.ok(id2 > id1, `nextId must not reset on clear(): id1=${id1} id2=${id2}`);
    assert.equal(p.tag(id1), '');
    assert.equal(p.tag(id2), '#document');
    p.clear();
  }
});

test('Round 8 & 9: host-model parity (2 MB XML/HTML real pages + 150k simple elems + Item #3 shapes), 512 MB V8 safety net, 64 MB process budget, 256-char name cap, escaped attrsJson cap', async () => {
  const {
    PluginDomParser,
    DomLimitExceededError,
    MAX_PROCESS_MEMORY_BYTES,
    MAX_V8_SAFETY_MEMORY_BYTES,
    currentProcessDomMemoryBytes,
    currentV8SafetyMemoryBytes,
  } = await import('../src/dom.js');

  assert.equal(MAX_V8_SAFETY_MEMORY_BYTES, 512 * 1024 * 1024);

  // 1. 256-char tag/attr name cap (HTML & XML)
  {
    const p = new PluginDomParser();
    try {
      const okTag = 't'.repeat(256);
      const okAttr = 'a'.repeat(256);
      const rOk = p.parseHtml(`<${okTag} ${okAttr}="1"></${okTag}>`);
      assert.equal(p.selectAllIds(rOk, okTag).length, 1);
      p.clear();

      assert.throws(() => p.parseHtml(`<${'t'.repeat(257)}></${'t'.repeat(257)}>`), DomLimitExceededError);
      assert.throws(() => p.parseHtml(`<p ${'k'.repeat(257)}="1"></p>`), DomLimitExceededError);
      assert.throws(() => p.parseHtml(`<${'t'.repeat(2_000_000)}>`), DomLimitExceededError);
      assert.throws(() => p.parseXml(`<r><${'T'.repeat(257)}/></r>`), DomLimitExceededError);
      assert.throws(() => p.parseXml(`<r><a ${'K'.repeat(257)}="1"/></r>`), DomLimitExceededError);
    } finally {
      p.clear();
    }
  }

  // 2. Escaped attrsJson 8 MB output cap (2 MB '\u0001' -> 12 MB JSON)
  {
    const p = new PluginDomParser();
    try {
      const rCtrl = p.parseHtml("<p a='" + '\u0001'.repeat(2_000_000) + "'></p>");
      const pId = p.selectFirstId(rCtrl, 'p');
      const t = Date.now();
      assert.throws(() => p.attrsJson(pId), DomLimitExceededError);
      assert.ok(Date.now() - t < 1000);
      const rSmall = p.parseHtml('<p a="x\n\t\u0001&quot;\\y"></p>');
      const parsed = JSON.parse(p.attrsJson(p.selectFirstId(rSmall, 'p')));
      assert.equal(parsed.a, 'x\n\t\u0001"\\y');
    } finally {
      p.clear();
    }
  }

  // 3. Process-wide 64 MB budget across two live PluginDomParser instances (host model)
  {
    const baseProc = currentProcessDomMemoryBytes();
    const vm1 = new PluginDomParser();
    const vm2 = new PluginDomParser();
    try {
      // ~17.8 MiB host model per doc (95k <p a="b">hi</p>)
      const chunk = '<div>' + '<p a="b">hi</p>'.repeat(95_000) + '</div>';
      const r1a = vm1.parseHtml(chunk);
      const r1b = vm1.parseHtml(chunk); // vm1 ~35.6 MiB <= 48 MiB
      const r2a = vm2.parseHtml(chunk); // proc ~53.4 MiB <= 64 MiB
      assert.ok(r1a > 0 && r1b > 0 && r2a > 0);
      assert.ok(currentProcessDomMemoryBytes() <= MAX_PROCESS_MEMORY_BYTES);
      assert.throws(() => vm2.parseHtml(chunk), DomLimitExceededError); // ~71.2 MiB > 64 MiB process cap
      vm1.clear();
      // After vm1 clears, vm2 can now allocate!
      const r2b = vm2.parseHtml(chunk);
      assert.ok(r2b > 0);
    } finally {
      vm1.clear();
      vm2.clear();
    }
    assert.equal(currentProcessDomMemoryBytes(), baseProc);
    assert.equal(currentV8SafetyMemoryBytes(), 0);
  }

  // 4. Round 9 Item #2: ALL normal 2 MB pages that host accepts MUST ALSO be accepted by kit under default 32 MB cap:
  //    2 MB MPD timeline, 2 MB MPD list, 2 MB RSS, 2 MB XSPF, table_min, table_pretty, apache2, tracker2, >150k simple HTML elements
  {
    const lim2m = 2 * 1024 * 1024 - 256;
    const words = ['alpha', 'beta', 'gamma', 'delta', '视频', '电影', '剧集', '第一季', 'HD', '1080p'];
    const w = (i, k) => words[(i * 7 + k) % words.length];
    function build(lim, head, row, tail) {
      const parts = [head];
      let n = Buffer.byteLength(head) + Buffer.byteLength(tail);
      let i = 0;
      for (;;) {
        const r = row(i++);
        const b = Buffer.byteLength(r);
        if (n + b > lim) break;
        parts.push(r);
        n += b;
      }
      parts.push(tail);
      return parts.join('');
    }
    const normalPages = [
      ['mpd_timeline_2mb.xml', true, build(lim2m, '<?xml version="1.0"?>\n<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">\n<Period id="0"><AdaptationSet mimeType="video/mp4"><SegmentTemplate media="v-$Time$.m4s"><SegmentTimeline>\n', (i) => `          <S t="${i * 180000}" d="180000"/>\n`, '</SegmentTimeline></SegmentTemplate></AdaptationSet></Period></MPD>\n')],
      ['mpd_list_2mb.xml', true, build(lim2m, '<?xml version="1.0"?>\n<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">\n<Period><AdaptationSet><Representation id="1" bandwidth="1000"><SegmentList duration="2">\n', (i) => `<SegmentURL media="seg-${i}.m4s"/>\n`, '</SegmentList></Representation></AdaptationSet></Period></MPD>\n')],
      ['rss_2mb.xml', true, build(lim2m, '<?xml version="1.0" encoding="UTF-8"?>\n<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">\n<channel><title>t</title>\n', (i) => `  <item><title>${w(i, 1)} ${i}</title><link>https://example.com/p/${i}</link><enclosure url="https://cdn.example.com/${i}.mp3" length="${i * 1000}" type="audio/mpeg"/><description><![CDATA[<p>${w(i, 2)}</p>]]></description></item>\n`, '</channel></rss>\n')],
      ['playlist_2mb.xspf.xml', true, build(lim2m, '<?xml version="1.0" encoding="UTF-8"?><playlist version="1" xmlns="http://xspf.org/ns/0/"><trackList>\n', (i) => `<track><location>http://iptv.ex.com/live/${i}.m3u8</location><title>频道${i} ${w(i, 1)}</title><image>http://ex.com/logo/${i}.png</image><extension application="x"><group>${w(i, 2)}</group></extension></track>\n`, '</trackList></playlist>\n')],
      ['table_pretty_2mb.html', false, build(lim2m, '<!DOCTYPE html>\n<html><body><table class="list"><tbody>\n', (i) => `  <tr>\n    <td>${i}</td>\n    <td><a href="/item/${i}">${w(i, 1)} ${w(i, 2)}</a></td>\n    <td class="size">${(i * 37) % 9999} MB</td>\n    <td>2024-0${(i % 9) + 1}-1${i % 9}</td>\n  </tr>\n`, '</tbody></table></body></html>\n')],
      ['table_min_2mb.html', false, build(lim2m, '<html><body><table>', (i) => `<tr><td>${i % 100}</td><td>${w(i, 1)}</td><td>${i % 7}</td><td>x</td></tr>`, '</table></body></html>')],
      ['apache2_2mb.html', false, build(lim2m, '<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN">\n<html><head><title>Index</title></head><body><table>\n', (i) => { const s = String(i).padStart(4, '0'); return `<tr><td valign="top"><img src="/icons/movie.gif" alt="[VID]"></td><td><a href="Show.S01E${s}.1080p.WEB-DL.mkv">Show.S01E${s}.1080p.WEB-DL.mkv</a></td><td align="right">2024-01-${10 + (i % 18)} 10:${10 + (i % 50)}  </td><td align="right">1.${i % 9}G</td><td>&nbsp;</td></tr>\n`; }, '</table></body></html>\n')],
      ['tracker2_2mb.html', false, build(lim2m, '<html><body><table class="list">\n', (i) => `<tr class="${i % 2 ? 'odd' : 'even'}">\n  <td class="cat"><a href="/cat/5"><img src="/i/tv.png" alt="TV"></a></td>\n  <td class="name"><a href="/t/${i}" title="Show S01E${i} 1080p">Show S01E${i} 1080p WEB-DL</a> <span class="tag new">NEW</span></td>\n  <td class="size">${(i % 90) / 10 + 1} GB</td>\n  <td class="seed">${i % 300}</td>\n</tr>\n`, '</table></body></html>\n')],
      ['simple_160k_elems.html', false, '<div>' + '<p></p>'.repeat(160_000) + '</div>'],
    ];
    for (const [name, isXml, content] of normalPages) {
      const p = new PluginDomParser();
      try {
        const root = isXml ? p.parseXml(content) : p.parseHtml(content);
        assert.ok(root > 0, `Normal page '${name}' must be accepted under default 32 MB cap`);
        assert.ok(p.currentEstimatedDomBytes <= 32 * 1024 * 1024);
      } finally {
        p.clear();
      }
    }
    assert.equal(currentProcessDomMemoryBytes(), 0);
    assert.equal(currentV8SafetyMemoryBytes(), 0);
  }

  // 5. Round 9 Item #2: loose V8 safety net rejects runaway V8 allocation even when host budget is lifted
  {
    const tightV8 = new PluginDomParser({
      maxDocMemoryBytes: 64 * 1024 * 1024,
      maxVmMemoryBytes: 128 * 1024 * 1024,
      maxProcessMemoryBytes: 128 * 1024 * 1024,
      maxV8SafetyMemoryBytes: 4 * 1024 * 1024,
    });
    try {
      // 30k XML <a/> is only ~1.2 MB in host model, but ~12.9 MB in V8 linkedom -> hits 4 MB V8 safety net!
      assert.throws(() => tightV8.parseXml('<r>' + '<a/>'.repeat(30_000) + '</r>'), DomLimitExceededError);
      // 40k HTML <p></p> is only ~1.6 MB in host model, but ~6.4 MB in V8 parse5 -> hits 4 MB V8 safety net!
      assert.throws(() => tightV8.parseHtml('<div>' + '<p></p>'.repeat(40_000) + '</div>'), DomLimitExceededError);
    } finally {
      tightV8.clear();
    }
    assert.equal(currentV8SafetyMemoryBytes(), 0);
  }

  // 6. Round 10 Item #3: accept/reject parity on 2 MB `entity` (host rejects -> kit rejects)
  //    and 2 MB `xtextent` (host accepts -> kit accepts)
  {
    const p = new PluginDomParser();
    try {
      const entityFull = '<p>' + '&lt;<!>'.repeat(Math.floor((2 * 1024 * 1024 - 16) / 7)) + '</p>';
      assert.throws(() => p.parseHtml(entityFull), DomLimitExceededError);
      assert.equal(p.docCount, 0);
      assert.equal(p.currentVmMemoryBytes, 0);

      const xtextentFull = '<r>' + 'x&amp;'.repeat(Math.floor((2 * 1024 * 1024 - 128) / 6)) + '</r>';
      const rx = p.parseXml(xtextentFull);
      assert.ok(rx > 0, '2 MB xtextent XML must be accepted by kit just like Android host');
      assert.ok(p.currentEstimatedDomBytes <= 2 * 1024 * 1024, `xtextent should coalesce to ~1 MB, got ${p.currentEstimatedDomBytes}`);
    } finally {
      p.clear();
    }
    assert.equal(currentProcessDomMemoryBytes(), 0);
    assert.equal(currentV8SafetyMemoryBytes(), 0);
  }
});

test('Round 3: host memory caps, tool/host parity, redirect safe-headers, cookie warnings, unawaited Promise check, immutable globals', async () => {
  const warnings = [];
  let redirectTargetHeaders = null;
  const sixMb = Buffer.alloc(6 * 1024 * 1024, 0x5A);

  const manifest = {
    api: 1,
    id: 'dev.depth3d.r3parity',
    name: 'R3Parity',
    version: '1.0.0',
    permissions: { hosts: ['api.example.com', 'other.example.net'], userHosts: false, lan: false },
  };
  const mainJs = `
    export default {
      home() { return []; },
      async browse(ctx, arg) {
        const m = arg.categoryId;
        if (m === 'log-flood') {
          const big = 'A'.repeat(100000);
          for (let i = 0; i < 200; i++) ctx.log.info(i + ':' + big);
          return { ok: true };
        }
        if (m === 'storage-oversize') {
          await ctx.storage.set('huge', 'B'.repeat(2 * 1024 * 1024));
          return { ok: true };
        }
        if (m === 'return-oversize') {
          return 'R'.repeat(9 * 1024 * 1024);
        }
        if (m === 'dom-node-flood') {
          ctx.parse.html('<i>x'.repeat(300000));
          return { ok: true };
        }
        if (m === 'timer-flood') {
          for (let i = 0; i < 257; i++) setTimeout(() => {}, 5000);
          return { ok: true };
        }
        if (m === 'response-second-read') {
          const r = await ctx.fetch('https://api.example.com/small');
          const t = r.text();
          let secondErr = '';
          try { r.bytes(); } catch (e) { secondErr = e.name + ':' + e.message; }
          return { t, secondErr };
        }
        if (m === 'seq-18mb') {
          let sum = 0;
          for (let i = 0; i < 3; i++) {
            const r = await ctx.fetch('https://api.example.com/6mb');
            sum += r.bytes().byteLength;
          }
          return { sum };
        }
        if (m === 'plain-timeout') {
          throw new Error('upstream timeout while parsing');
        }
        if (m === 'explicit-unavail') {
          throw new ctx.Error('Unavailable', '上游维护中');
        }
        if (m === 'cross-redir') {
          const r = await ctx.fetch('https://api.example.com/cross-start', {
            headers: {
              Authorization: 'Bearer secret',
              Cookie: 'sid=1',
              Referer: 'https://api.example.com/p',
              'X-Plugin-Custom': 'strip-me',
              Accept: 'application/json',
              'Accept-Language': 'zh-CN',
              'User-Agent': 'depth3d-test/1',
              Range: 'bytes=0-5',
            },
          });
          return { status: r.status };
        }
        if (m === 'slow-redir') {
          await ctx.fetch('https://api.example.com/slow-1', { timeoutMs: 250 });
          return { ok: true };
        }
        if (m === 'unawaited-storage') {
          ctx.storage.set('forgot', 'value');
          return { ok: true };
        }
        if (m === 'fetch-oversize-body') {
          await ctx.fetch('https://api.example.com/small', { method: 'POST', body: 'X'.repeat(3 * 1024 * 1024) });
          return { ok: true };
        }
        if (m === 'fetch-oversize-url') {
          await ctx.fetch('https://api.example.com/' + 'u'.repeat(9 * 1024));
          return { ok: true };
        }
        if (m === 'fetch-oversize-headers') {
          await ctx.fetch('https://api.example.com/small', { headers: { 'X-Huge': 'H'.repeat(35 * 1024) } });
          return { ok: true };
        }
        if (m === 'fetch-queue-flood') {
          const ps = [];
          for (let i = 0; i < 25; i++) {
            ps.push(ctx.fetch('https://api.example.com/slow-hold?i=' + i).then(() => 'ok', (e) => 'err:' + (e.message || e)));
          }
          const results = await Promise.all(ps);
          return { results };
        }
        if (m === 'fetch-bad-method') {
          await ctx.fetch('https://api.example.com/small', { method: 'G'.repeat(500) });
          return { ok: true };
        }
        if (m === 'fetch-bad-redirect') {
          await ctx.fetch('https://api.example.com/small', { redirect: 'follow'.repeat(500) });
          return { ok: true };
        }
        if (m === 'fetch-body-budget-flood') {
          const body = 'B'.repeat(1800 * 1024);
          const ps = [];
          for (let i = 0; i < 6; i++) {
            ps.push(ctx.fetch('https://api.example.com/slow-hold?i=' + i, { method: 'POST', body }).then(() => 'ok', (e) => 'err:' + (e.message || e)));
          }
          return { results: await Promise.all(ps) };
        }
        if (m === 'immutable-globals') {
          return ['__d3_invoke', '__d3_invoke_optional', '__d3_exports', '__d3_eval_probe'].map((n) => {
            const d = Object.getOwnPropertyDescriptor(globalThis, n);
            let wf = false;
            try { globalThis[n] = 1; } catch { wf = true; }
            let df = false;
            try { delete globalThis[n]; } catch { df = true; }
            return { n, writable: d.writable, configurable: d.configurable, wf, df };
          });
        }
        return { items: [] };
      },
      detail() { return { id: '1', kind: 'video', title: '1' }; },
      playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
    };
  `;

  const host = new QuickJsPluginHost({
    manifest,
    mainJs,
    dnsResolver: async () => ['93.184.216.34'],
    transportOverride: async ({ url, headers }) => {
      if (url.pathname === '/small') return { status: 200, headers: {}, setCookies: [], body: Buffer.from('hello-once') };
      if (url.pathname === '/6mb') return { status: 200, headers: {}, setCookies: [], body: sixMb };
      if (url.pathname === '/cross-start') {
        return { status: 302, headers: { location: 'https://other.example.net/cross-end' }, setCookies: [], body: Buffer.alloc(0) };
      }
      if (url.pathname === '/cross-end') {
        redirectTargetHeaders = { ...headers };
        return { status: 200, headers: {}, setCookies: [], body: Buffer.from('ok') };
      }
      if (url.pathname === '/slow-hold') {
        await new Promise((r) => setTimeout(r, 120));
        return { status: 200, headers: {}, setCookies: [], body: Buffer.from('ok') };
      }
      if (url.pathname.startsWith('/slow-')) {
        await new Promise((r) => setTimeout(r, 150));
        const n = Number(url.pathname.slice('/slow-'.length));
        if (n < 4) return { status: 302, headers: { location: `https://api.example.com/slow-${n + 1}` }, setCookies: [], body: Buffer.alloc(0) };
        return { status: 200, headers: {}, setCookies: [], body: Buffer.from('late') };
      }
      return { status: 404, headers: {}, setCookies: [], body: Buffer.alloc(0) };
    },
    logSink: (level, line) => {
      if (level === 'WARN') warnings.push(line);
    },
  });

  try {
    // Item #3a: ctx.log 4 KB / 256 KB buffer cap
    await host.invoke('browse', { categoryId: 'log-flood' });
    assert.ok(host.logger.entries.length <= 200);
    assert.ok(host.logger.totalBufferedBytes <= 256 * 1024);
    for (const e of host.logger.entries) {
      assert.ok(Buffer.byteLength(e.message, 'utf8') <= 4 * 1024);
    }

    // Item #3b: storage.set pre-parse quota check
    await assert.rejects(() => host.invoke('browse', { categoryId: 'storage-oversize' }), (e) => e instanceof PluginCallError && e.code === 'Internal');
    assert.equal(host.storage.get('huge'), null);

    // Item #3c: > 8 MB return value -> Internal
    await assert.rejects(() => host.invoke('browse', { categoryId: 'return-oversize' }), (e) => e instanceof PluginCallError && e.code === 'Internal');

    // Item #3d: ctx.parse > 200k nodes rejected
    await assert.rejects(() => host.invoke('browse', { categoryId: 'dom-node-flood' }), (e) => e instanceof PluginCallError && e.code === 'Internal');

    // Item #3e: setTimeout > 256 concurrent timers rejected
    await assert.rejects(() => host.invoke('browse', { categoryId: 'timer-flood' }), (e) => e instanceof PluginCallError && e.code === 'Internal');

    // Item #8a: Response single-consume throws TypeError on second read
    const sr = await host.invoke('browse', { categoryId: 'response-second-read' });
    assert.equal(sr.t, 'hello-once');
    assert.ok(sr.secondErr.includes('TypeError'));

    // Item #8b: Sequential 3 x 6 MB = 18 MB (> 16 MB in-flight budget) succeeds because read releases budget
    const seq = await host.invoke('browse', { categoryId: 'seq-18mb' });
    assert.equal(seq.sum, 18 * 1024 * 1024);
    assert.equal(host.fetchGateway.inFlightBudget.used, 0);

    // Item #8c & #8d: Plain Error('...timeout...') -> Internal; explicit Unavailable does NOT rebuild VM!
    const beforeCreations = host.creationCount;
    await assert.rejects(() => host.invoke('browse', { categoryId: 'plain-timeout' }), (e) => e instanceof PluginCallError && e.code === 'Internal');
    assert.equal(host.creationCount, beforeCreations);
    await assert.rejects(() => host.invoke('browse', { categoryId: 'explicit-unavail' }), (e) => e instanceof PluginCallError && e.code === 'Unavailable');
    assert.equal(host.creationCount, beforeCreations, 'Explicit ctx.Error(Unavailable) must NOT rebuild VM');

    // Item #9: Cross-origin redirect strips Authorization, Cookie, Referer, X-Plugin-Custom; keeps ONLY safe general headers
    const cr = await host.invoke('browse', { categoryId: 'cross-redir' });
    assert.equal(cr.status, 200);
    assert.equal(redirectTargetHeaders.Authorization, undefined);
    assert.equal(redirectTargetHeaders.Cookie, undefined);
    assert.equal(redirectTargetHeaders.Referer, undefined);
    assert.equal(redirectTargetHeaders['X-Plugin-Custom'], undefined);
    assert.equal(redirectTargetHeaders.Accept, 'application/json');
    assert.equal(redirectTargetHeaders['Accept-Language'], 'zh-CN');
    assert.equal(redirectTargetHeaders['User-Agent'], 'depth3d-test/1');
    assert.equal(redirectTargetHeaders.Range, 'bytes=0-5');

    // Item #8e: Total timeout across slow redirect hops
    const t0 = Date.now();
    await assert.rejects(() => host.invoke('browse', { categoryId: 'slow-redir' }));
    assert.ok(Date.now() - t0 < 800, 'Slow redirect chain must abort at total timeoutMs');

    // Item #8f: Cookie parity (Max-Age > Expires, PSL & Domain mismatch -> WARN, IPv6 literal)
    warnings.length = 0;
    host.cookieJar.setCookie('https://api.example.com/', 'm=1; Max-Age=0; Expires=Wed, 21 Oct 2099 07:28:00 GMT');
    assert.equal(host.cookieJar.getHeaderForUrl('https://api.example.com/'), '');
    host.cookieJar.setCookie('https://api.example.com/', 'p1=x; Domain=com');
    host.cookieJar.setCookie('https://shop.co.uk/', 'p2=x; Domain=co.uk');
    host.cookieJar.setCookie('https://api.example.com/', 'm2=x; Domain=evil.com');
    assert.equal(warnings.length, 3);
    host.cookieJar.setCookie('http://[fd12:3456:789a::1]:8096/', 'v6=ok; Path=/');
    assert.equal(host.cookieJar.getHeaderForUrl('http://[fd12:3456:789a::0001]:8096/Items'), 'v6=ok');

    // Item #8g: Unawaited ctx.storage.set fails with diagnostic naming call site
    await assert.rejects(
      () => host.invoke('browse', { categoryId: 'unawaited-storage' }),
      (e) => e instanceof PluginCallError && e.message.includes('ctx.storage.set'),
    );

    // Round 4 Item #2: fetch body > 2 MB, URL > 8 KB, headers > 32 KB, queued > 16 all rejected immediately
    await assert.rejects(() => host.invoke('browse', { categoryId: 'fetch-oversize-body' }), (e) => e instanceof PluginCallError && e.code === 'Internal');
    await assert.rejects(() => host.invoke('browse', { categoryId: 'fetch-oversize-url' }), (e) => e instanceof PluginCallError && e.code === 'Internal');
    await assert.rejects(() => host.invoke('browse', { categoryId: 'fetch-oversize-headers' }), (e) => e instanceof PluginCallError && e.code === 'Internal');
    const qRes = await host.invoke('browse', { categoryId: 'fetch-queue-flood' });
    const qErrors = qRes.results.filter((r) => String(r).startsWith('err:'));
    const qOk = qRes.results.filter((r) => r === 'ok');
    assert.equal(qOk.length, 20, `Expected 4 active + 16 queued = 20 ok, got ${qOk.length}`);
    assert.equal(qErrors.length, 5, `Expected 5 rejected above queue cap 16, got ${qErrors.length}`);
    assert.ok(qErrors.every((e) => e.includes('排队')));

    // Round 5 Item #5: method/redirect length pre-checks + 8 MB queued+active request body cap
    await assert.rejects(() => host.invoke('browse', { categoryId: 'fetch-bad-method' }), (e) => e instanceof PluginCallError && e.code === 'Internal');
    await assert.rejects(() => host.invoke('browse', { categoryId: 'fetch-bad-redirect' }), (e) => e instanceof PluginCallError && e.code === 'Internal');
    const bbRes = await host.invoke('browse', { categoryId: 'fetch-body-budget-flood' });
    assert.ok(bbRes.results.some((r) => String(r).startsWith('err:')), '6 x 1.8 MB concurrent POSTs must trip 8 MB queued+active request body cap');
    assert.equal(host.fetchGateway.requestBodyBudget.used, 0);

    // Item #12: __d3_invoke etc. non-writable & non-configurable
    const globals = await host.invoke('browse', { categoryId: 'immutable-globals' });
    assert.equal(globals.length, 4);
    for (const g of globals) {
      assert.equal(g.writable, false);
      assert.equal(g.configurable, false);
      assert.equal(g.wf, true);
      assert.equal(g.df, true);
    }
  } finally {
    await host.dispose();
  }

  // Also verify d3plugin check fails on a plugin with unawaited ctx.storage.set
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'd3-unawaited-'));
  try {
    fs.writeFileSync(
      path.join(tmpDir, 'manifest.json'),
      JSON.stringify({ api: 1, id: 'com.example.unawaited', name: 'Unawaited', version: '1.0.0' }),
    );
    fs.writeFileSync(
      path.join(tmpDir, 'main.js'),
      `export default {
        async home(ctx) { ctx.storage.set('leaked', 1); return []; },
        browse() { return { items: [] }; },
        detail() { return { id: '1', kind: 'video', title: '1' }; },
        playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
      };`,
    );
    const report = await checkPlugin(tmpDir);
    assert.equal(report.ok, false);
    assert.ok(report.errors.some((e) => e.includes('ctx.storage.set')));
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('Round 3: Jellyfin version routing (10.7/10.8), partial home() survival, cross-origin URLs, device capability transcode', async () => {
  const loaded = await loadPluginSource(JF_PLUGIN_DIR);
  const ids = JF_FIXTURES._meta.ids;
  const userId = ids.userId;

  // 1. Version 10.7.7 -> Unsupported
  const h107 = new QuickJsPluginHost({
    manifest: loaded.manifest,
    mainJs: loaded.mainJs,
    locale: loaded.locale,
    config: { server: 'http://192.168.1.50:8096', username: 'u', password: 'p' },
    dnsResolver: async () => ['192.168.1.50'],
    transportOverride: async ({ url }) => {
      if (url.pathname === '/System/Info/Public') {
        return { status: 200, headers: {}, setCookies: [], body: Buffer.from(JSON.stringify({ Version: '10.7.7' })) };
      }
      return { status: 404, headers: {}, setCookies: [], body: Buffer.alloc(0) };
    },
    logSink: () => {},
  });
  try {
    await assert.rejects(() => h107.invoke('login'), (e) => e instanceof PluginCallError && e.code === 'Unsupported');
  } finally {
    await h107.dispose();
  }

  // 2. Version 10.8.13 -> legacy /Users/{uid}/... routes, partial home failure, cross-origin DeliveryUrl/TranscodingUrl, device cap fallback
  const seen = [];
  const h108 = new QuickJsPluginHost({
    manifest: loaded.manifest,
    mainJs: loaded.mainJs,
    locale: loaded.locale,
    config: { server: 'http://192.168.1.50:8096', username: 'd3tester', password: 'p' },
    device: { appVersion: '1.0.0', locale: 'zh-CN', model: 'Headset', maxWidth: 1920, maxHeight: 1080, codecs: ['avc', 'hevc'], hdr: false },
    dnsResolver: async () => ['192.168.1.50'],
    transportOverride: async ({ url, method }) => {
      const p = url.pathname;
      seen.push(`${method} ${p}`);
      const ok = (d) => ({ status: 200, headers: { 'content-type': 'application/json' }, setCookies: [], body: Buffer.from(JSON.stringify(d)) });
      if (p === '/System/Info/Public') return ok({ Version: '10.8.13' });
      if (p === '/Users/AuthenticateByName') return ok(JF_FIXTURES.authSuccess);
      if (p === `/Users/${userId}/Views`) return ok(JF_FIXTURES.views);
      if (p === `/Users/${userId}/Items/Resume`) return ok(JF_FIXTURES.resume);
      if (p === '/Shows/NextUp') return { status: 500, headers: {}, setCookies: [], body: Buffer.from('boom') };
      if (p === `/Users/${userId}/Items/Latest`) return ok(JF_FIXTURES.latestByParent[url.searchParams.get('ParentId')] || []);
      if (p === '/Items/cross-item/PlaybackInfo') {
        return ok({
          PlaySessionId: 's-cross',
          MediaSources: [{
            Id: 'ms-cross',
            SupportsDirectPlay: false,
            TranscodingUrl: 'https://other-cdn.example.net/live/master.m3u8?ApiKey=secret',
            RunTimeTicks: 500000000,
            MediaStreams: [
              { Type: 'Video', Index: 0, Codec: 'h264', Width: 1920, Height: 1080 },
              { Type: 'Subtitle', Index: 1, Codec: 'vtt', Language: 'zh', DeliveryUrl: 'https://subs.example.net/1.vtt?api_key=secret' },
            ],
          }],
        });
      }
      if (p === '/Items/4k-av1/PlaybackInfo') {
        return ok({
          PlaySessionId: 's-cap',
          MediaSources: [{
            Id: 'ms-cap',
            SupportsDirectPlay: true,
            RunTimeTicks: 500000000,
            MediaStreams: [{ Type: 'Video', Index: 0, Codec: 'av1', Width: 3840, Height: 2160 }],
          }],
        });
      }
      return { status: 204, headers: {}, setCookies: [], body: Buffer.alloc(0) };
    },
    logSink: () => {},
  });
  try {
    await h108.invoke('login');
    const shelves = await h108.invoke('home');
    assert.ok(shelves.some((s) => s.style === 'continue'));
    assert.ok(shelves.some((s) => s.id === `jf-latest-${ids.moviesViewId}`));
    assert.ok(seen.includes(`GET /Users/${userId}/Views`));
    assert.ok(seen.includes(`GET /Users/${userId}/Items/Resume`));
    assert.ok(seen.includes(`GET /Users/${userId}/Items/Latest`));

    const pbCross = await h108.invoke('playback', { itemId: 'cross-item' });
    assert.equal(pbCross.url, 'https://other-cdn.example.net/live/master.m3u8');
    assert.equal(pbCross.headers.Authorization, undefined);
    assert.equal(pbCross.subtitles[0].url, 'https://subs.example.net/1.vtt');
    assert.equal(pbCross.subtitles[0].headers, undefined);

    const pbCap = await h108.invoke('playback', { itemId: '4k-av1' });
    assert.equal(pbCap.mime, 'application/x-mpegURL');
    assert.ok(pbCap.url.includes('/master.m3u8'));
  } finally {
    await h108.dispose();
  }
});

test('pack and serve produce valid .d3plugin archive and repository index with matching sha256', async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'd3plugin-test-'));
  const outFile = path.join(tmpDir, 'ref.d3plugin');
  try {
    const packed = await packPluginToFile(REF_PLUGIN_DIR, outFile);
    assert.equal(packed.manifest.id, 'dev.depth3d.reference');
    assert.equal(packed.sha256.length, 64);
    const inspected = await inspectArchiveBuffer(fs.readFileSync(outFile));
    assert.equal(inspected.manifest.id, 'dev.depth3d.reference');
    assert.ok(inspected.locales['zh-CN']);

    const srv = await startDevServer(REF_PLUGIN_DIR, { host: '127.0.0.1', port: 0 });
    try {
      const idxRes = await fetch(`http://127.0.0.1:${srv.port}/index.json`);
      assert.equal(idxRes.status, 200);
      const idx = await idxRes.json();
      assert.equal(validateRepository(idx).valid, true);
      assert.equal(idx.plugins[0].id, 'dev.depth3d.reference');

      const pkgRes = await fetch(idx.plugins[0].url);
      assert.equal(pkgRes.status, 200);
      const pkgBuf = Buffer.from(await pkgRes.arrayBuffer());
      assert.equal(sha256Hex(pkgBuf), idx.plugins[0].sha256);
    } finally {
      await srv.close();
    }
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('manifest discover accepts mDNS service types and built-in udp:jellyfin/udp:emby while rejecting arbitrary UDP', () => {
  const baseManifest = JSON.parse(fs.readFileSync(path.join(JF_PLUGIN_DIR, 'manifest.json'), 'utf8'));
  assert.equal(baseManifest.version, '1.0.1');
  assert.equal(baseManifest.config[0].discover, 'udp:jellyfin');
  assert.equal(validateManifest(baseManifest).valid, true);

  const makeWithDiscover = (val) => ({
    ...baseManifest,
    config: [{ ...baseManifest.config[0], discover: val }, ...baseManifest.config.slice(1)],
  });

  for (const good of ['udp:jellyfin', 'udp:emby', '_jellyfin._tcp', '_http._tcp.', '_custom-srv._udp']) {
    const res = validateManifest(makeWithDiscover(good));
    assert.equal(res.valid, true, `expected discover='${good}' to be valid, got: ${res.errors.join('; ')}`);
  }
  for (const bad of ['udp:custom', 'udp:1234', 'udp:', 'http://192.168.1.1', 'jellyfin._tcp', 'tcp:jellyfin']) {
    const res = validateManifest(makeWithDiscover(bad));
    assert.equal(res.valid, false, `expected discover='${bad}' to be rejected`);
  }
});
