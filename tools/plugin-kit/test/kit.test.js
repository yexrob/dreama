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
  const playbackBodies = [];

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

    if (p === '/Users/AuthenticateByName' && method === 'POST') {
      const reqBody = JSON.parse(body.toString('utf8'));
      if (reqBody.Pw === 'bad-pass') return jsonResp(401, { message: 'Unauthorized' });
      return jsonResp(200, JF_FIXTURES.authSuccess);
    }
    if (!String(headers.Authorization || '').includes(`Token="${expectedToken}"`)) {
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
    if (p.startsWith('/Sessions/Playing') || p.startsWith('/UserFavoriteItems/') || p.startsWith('/UserPlayedItems/') || p === '/Sessions/Logout') {
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
    await host.invoke('setFavorite', { itemId: ids.h264MovieId, favorite: true });
    await host.invoke('setPlayed', { itemId: ids.h264MovieId, played: true });
    // S10: ZERO ms_* keys written to ctx.storage
    assert.deepEqual(storage.keys().sort(), ['deviceId', 'session']);

    await host.invoke('logout');
    assert.ok(recordedCalls.some((c) => c.path === '/UserViews'));
    assert.ok(recordedCalls.some((c) => c.path === '/UserItems/Resume'));
    assert.ok(recordedCalls.some((c) => c.path === `/UserFavoriteItems/${ids.h264MovieId}`));
    assert.ok(recordedCalls.some((c) => c.path === `/UserPlayedItems/${ids.h264MovieId}`));
  } finally {
    await host.dispose();
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
