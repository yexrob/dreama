import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { checkPlugin, loadPluginSource } from '../src/check.js';
import { PluginCookieJar } from '../src/cookies.js';
import { pluginCrypto } from '../src/crypto.js';
import { FetchGateway } from '../src/fetch-gateway.js';
import { redactSecrets } from '../src/logger.js';
import { NetworkPolicy, NetworkPolicyError } from '../src/network-policy.js';
import { inspectArchiveBuffer, packPluginToFile, sha256Hex } from '../src/pack.js';
import { PluginCallError, QuickJsPluginHost } from '../src/runtime.js';
import { validateManifest, validateProtocolType, validateRepository } from '../src/schema.js';
import { startDevServer } from '../src/serve.js';
import { PluginStorage } from '../src/storage.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../../..');
const REF_PLUGIN_DIR = path.join(REPO_ROOT, 'plugins/reference');
const JF_PLUGIN_DIR = path.join(REPO_ROOT, 'plugins/jellyfin');
const JF_FIXTURES = JSON.parse(
  fs.readFileSync(path.join(JF_PLUGIN_DIR, 'fixtures/recorded-responses.json'), 'utf8'),
);

test('manifest validation rejects invalid api, loopback hosts, duplicate keys, and mismatched defaults', () => {
  const valid = JSON.parse(fs.readFileSync(path.join(REF_PLUGIN_DIR, 'manifest.json'), 'utf8'));
  assert.equal(validateManifest(valid).valid, true);

  assert.equal(validateManifest({ ...valid, api: 2 }).valid, false);
  assert.equal(validateManifest({ ...valid, id: 'NotReverseDomain' }).valid, false);
  assert.equal(validateManifest({ ...valid, version: '1.0' }).valid, false);
  assert.equal(
    validateManifest({ ...valid, permissions: { hosts: ['localhost'] } }).valid,
    false,
  );
  assert.equal(
    validateManifest({ ...valid, permissions: { hosts: ['127.0.0.1'] } }).valid,
    false,
  );
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

test('network policy enforces whitelist, wildcard, userHosts, lan, loopback, private IPs, and redirects', async () => {
  const fakeDns = async (host) => {
    if (host === 'api.example.com' || host === 'a.cdn.example.com' || host === 'evil.com') return ['93.184.216.34'];
    if (host === 'rebind.example.com') return ['127.0.0.1'];
    if (host === 'priv.example.com') return ['192.168.1.50'];
    if (host === 'my.lan.server') return ['10.0.0.20'];
    if (host === 'cgnat.example.com') return ['100.64.1.5'];
    if (host === 'ula.example.com') return ['fd12:3456:789a::1'];
    return ['93.184.216.34'];
  };

  const publicPolicy = new NetworkPolicy(
    { hosts: ['api.example.com', '*.cdn.example.com'], userHosts: false, lan: false },
    new Set(),
    fakeDns,
  );

  // Allowed HTTPS on whitelisted exact & wildcard hosts
  await publicPolicy.validateFetchUrl('https://api.example.com/v1/home');
  await publicPolicy.validateFetchUrl('https://a.cdn.example.com/seg.m4s');

  // Blocked: non-whitelisted host, bare wildcard parent, HTTP on public host, loopback, DNS rebind, private IP, CGNAT, ULA
  await assert.rejects(() => publicPolicy.validateFetchUrl('https://evil.com/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('https://cdn.example.com/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('http://api.example.com/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('http://127.0.0.1:8080/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('https://localhost/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('https://rebind.example.com/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('https://priv.example.com/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('https://cgnat.example.com/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('https://ula.example.com/'), NetworkPolicyError);
  await assert.rejects(() => publicPolicy.validateFetchUrl('file:///etc/passwd'), NetworkPolicyError);

  // LAN + userHosts policy allows private HTTP, still blocks loopback & public HTTP
  const lanPolicy = new NetworkPolicy(
    { hosts: [], userHosts: true, lan: true },
    new Set(['my.lan.server']),
    fakeDns,
  );
  await lanPolicy.validateFetchUrl('http://my.lan.server:8096/System/Info');
  await lanPolicy.validateFetchUrl('http://192.168.1.50:8096/Items');
  await assert.rejects(() => lanPolicy.validateFetchUrl('http://127.0.0.1:8096/'), NetworkPolicyError);
  await assert.rejects(() => lanPolicy.validateFetchUrl('http://api.example.com/'), NetworkPolicyError);

  // FetchGateway blocks redirect escape to non-whitelisted host and Host header override
  const jar = new PluginCookieJar();
  const gw = new FetchGateway(publicPolicy, jar, {
    transportOverride: async ({ url }) => {
      if (url.pathname === '/escape') {
        return { status: 302, headers: { location: 'https://evil.com/steal' }, setCookies: [], body: Buffer.alloc(0) };
      }
      if (url.pathname === '/ok-redir') {
        return { status: 302, headers: { location: 'https://a.cdn.example.com/final' }, setCookies: ['sid=abc; Path=/; Secure'], body: Buffer.alloc(0) };
      }
      return { status: 200, headers: { 'content-type': 'text/plain' }, setCookies: [], body: Buffer.from('ok') };
    },
  });

  await assert.rejects(() => gw.fetch({ url: 'https://api.example.com/escape' }), NetworkPolicyError);
  await assert.rejects(
    () => gw.fetch({ url: 'https://api.example.com/ok', headers: { Host: 'spoof.com' } }),
    NetworkPolicyError,
  );
  const okRes = await gw.fetch({ url: 'https://api.example.com/ok-redir' });
  assert.equal(okRes.status, 200);
  assert.equal(okRes.body.toString('utf8'), 'ok');
  assert.equal(jar.getHeaderForUrl('https://api.example.com/test'), 'sid=abc');
});

test('ctx APIs (crypto, storage 1MB limit, cookies, parse HTML/XML, log redaction, setTimeout, frozen config)', async () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  });

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
        // Frozen config check
        let configFrozen = false;
        try { ctx.config.secretPass = 'mutated'; } catch { configFrozen = true; }

        // Storage round-trip & quota check
        ctx.storage.set('obj', { a: 1, b: '中文' });
        const stored = ctx.storage.get('obj');
        const keys = ctx.storage.keys();
        ctx.storage.remove('obj');
        let quotaHit = false;
        try { ctx.storage.set('huge', 'x'.repeat(1100 * 1024)); } catch { quotaHit = true; }

        // Cookies
        ctx.cookies.set('https://api.example.com/', 'k1=v1; Path=/; Secure');
        const cookieVal = ctx.cookies.get('https://api.example.com/path');

        // Crypto
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

        // HTML & XML parse
        const hdoc = ctx.parse.html('<div class="card" data-id="42"><span>电影名</span></div>');
        const hEl = hdoc.querySelector('.card');
        const xdoc = ctx.parse.xml('<root><item id="9"><title>剧集A</title></item></root>');
        const xTitle = xdoc.querySelector('item title').text;

        // Scoped setTimeout
        const delayed = await new Promise((resolve) => setTimeout(() => resolve('tick'), 15));

        // Log redaction check
        ctx.log.info('Login with password=' + ctx.config.secretPass + ' Authorization: Bearer tok12345');

        ctx.storage.set('probeResult', {
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
    author: 'depth3d',
  };
  const mainJs = `
    export default {
      init(ctx) {
        const n = (ctx.storage.get('inits') || 0) + 1;
        ctx.storage.set('inits', n);
      },
      home() { return []; },
      browse(ctx, { categoryId }) {
        if (categoryId === 'hang') {
          while (true) {}
        }
        if (categoryId === 'oom') {
          const buf = new Uint8Array(6 * 1024 * 1024);
          return { items: [{ id: String(buf.length), kind: 'video', title: 'never' }] };
        }
        return { items: [{ id: 'ok', kind: 'video', title: 'recovered-' + ctx.storage.get('inits') }] };
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

    // Timeout -> Unavailable + destroy
    await assert.rejects(
      () => host.invoke('browse', { categoryId: 'hang' }),
      (err) => err instanceof PluginCallError && err.code === 'Unavailable',
    );
    // Next call rebuilds VM and re-runs init()
    const ok2 = await host.invoke('browse', { categoryId: 'ok' });
    assert.equal(ok2.items[0].title, 'recovered-2');
    assert.equal(host.creationCount, 2);

    // OOM -> Internal + destroy
    await assert.rejects(
      () => host.invoke('browse', { categoryId: 'oom' }),
      (err) => err instanceof PluginCallError && err.code === 'Internal',
    );
    // Next call rebuilds VM cleanly again
    const ok3 = await host.invoke('browse', { categoryId: 'ok' });
    assert.equal(ok3.items[0].title, 'recovered-3');
    assert.equal(host.creationCount, 3);
  } finally {
    await host.dispose();
  }
});

test('reference plugin passes full conformance check and covers video-platform patterns', async () => {
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
    // QR login flow: pending -> scanned -> done + cookie set
    const qrStart = await host.invoke('loginStart');
    assert.equal(validateProtocolType('QrLoginStartResult', qrStart).valid, true);
    assert.equal((await host.invoke('loginPoll', { state: qrStart.state })).status, 'pending');
    assert.equal((await host.invoke('loginPoll', { state: qrStart.state })).status, 'scanned');
    const done = await host.invoke('loginPoll', { state: qrStart.state });
    assert.equal(done.status, 'done');
    assert.equal(done.user.name, '扫码用户');
    assert.equal((await host.invoke('loginPoll', { state: 'expired-state' })).status, 'expired');

    // Multi-part video detail (分P 选集)
    const mp = await host.invoke('detail', { itemId: 'multipart-video' });
    assert.equal(validateProtocolType('Item', mp).valid, true);
    assert.equal(mp.episodes.length, 3);

    // Separated DASH stream + Referer + Cookie + locked quality + expiring URL refresh
    const pb1 = await host.invoke('playback', { itemId: 'multipart-p1' });
    assert.equal(validateProtocolType('PlaybackInfo', pb1).valid, true);
    assert.ok(pb1.dash.video.length >= 2);
    assert.ok(pb1.dash.audio.length >= 1);
    assert.equal(pb1.headers.Referer, 'https://api.reference.invalid/video/multipart');
    assert.ok(pb1.headers.Cookie.includes('ref_sess=qr_'));
    assert.equal(pb1.qualities.find((q) => q.id === '4k-vip').locked, '需要会员');
    assert.equal(pb1.next, 'multipart-p2');
    assert.ok(pb1.dash.video[0].url.includes('token=v1'));

    // Re-fetch after expiry returns new token=v2
    const pb2 = await host.invoke('playback', { itemId: 'multipart-p1' });
    assert.ok(pb2.dash.video[0].url.includes('token=v2'));

    // Segmented danmaku
    const dm1 = await host.invoke('danmaku', { itemId: 'multipart-p1', fromMs: 0, toMs: 60_000 });
    const dm2 = await host.invoke('danmaku', { itemId: 'multipart-p1', fromMs: 60_000, toMs: 120_000 });
    assert.equal(validateProtocolType('DanmakuResult', dm1).valid, true);
    assert.equal(dm1.length, 3);
    assert.equal(dm2.length, 1);

    // Every protocol error code
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

test('jellyfin plugin passes static check and full recorded REST API flow', async () => {
  const staticCheck = await checkPlugin(JF_PLUGIN_DIR);
  assert.equal(staticCheck.ok, true, `jellyfin check failed: ${staticCheck.errors.join('; ')}`);

  const loaded = await loadPluginSource(JF_PLUGIN_DIR);
  const recordedCalls = [];

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
    if (headers['X-Emby-Token'] !== JF_FIXTURES.authSuccess.AccessToken) {
      return jsonResp(401, { message: 'Invalid token' });
    }
    if (p === '/Users/user-alice-001/Items/Resume') return jsonResp(200, JF_FIXTURES.resume);
    if (p === '/Shows/NextUp') return jsonResp(200, JF_FIXTURES.nextUp);
    if (p === '/Users/user-alice-001/Views') return jsonResp(200, JF_FIXTURES.views);
    if (p === '/Users/user-alice-001/Items/Latest') {
      return jsonResp(200, qs.get('ParentId') === 'lib-movies' ? JF_FIXTURES.latestMovies : JF_FIXTURES.latestShows);
    }
    if (p === '/Users/user-alice-001/Items' && qs.get('SearchTerm')) return jsonResp(200, JF_FIXTURES.searchResults);
    if (p === '/Users/user-alice-001/Items') {
      return jsonResp(200, qs.get('StartIndex') === '1' ? JF_FIXTURES.browseMoviesPage2 : JF_FIXTURES.browseMoviesPage1);
    }
    if (p === '/Users/user-alice-001/Items/mov-dune') return jsonResp(200, JF_FIXTURES.movieDetail);
    if (p === '/Items/mov-dune/Similar') return jsonResp(200, JF_FIXTURES.similarMovies);
    if (p === '/Users/user-alice-001/Items/ser-threebody') return jsonResp(200, JF_FIXTURES.seriesDetail);
    if (p === '/Shows/ser-threebody/Seasons') return jsonResp(200, JF_FIXTURES.seriesSeasons);
    if (p === '/Users/user-alice-001/Items/sea-threebody-1') return jsonResp(200, JF_FIXTURES.seasonDetail);
    if (p === '/Shows/ser-threebody/Episodes') return jsonResp(200, JF_FIXTURES.seasonEpisodes);
    if (p === '/Items/mov-dune/PlaybackInfo') return jsonResp(200, JF_FIXTURES.playbackInfo);
    if (p === '/Search/Hints') return jsonResp(200, JF_FIXTURES.searchHints);
    if (p.startsWith('/Sessions/Playing') || p.includes('/FavoriteItems/') || p.includes('/PlayedItems/') || p === '/Sessions/Logout') {
      return { status: 204, headers: {}, setCookies: [], body: Buffer.alloc(0) };
    }
    return jsonResp(404, { message: 'Not Found' });
  };

  const host = new QuickJsPluginHost({
    manifest: loaded.manifest,
    mainJs: loaded.mainJs,
    locale: loaded.locale,
    config: { server: 'http://192.168.1.10:8096', username: 'alice', password: 'secret', quality: 'direct' },
    dnsResolver: async () => ['192.168.1.10'],
    transportOverride: mockTransport,
    logSink: () => {},
  });

  try {
    // Before login -> NeedsAuth
    await assert.rejects(() => host.invoke('home'), (e) => e instanceof PluginCallError && e.code === 'NeedsAuth');

    // Login
    const loginRes = await host.invoke('login');
    assert.equal(validateProtocolType('FormLoginResult', loginRes).valid, true);
    assert.equal(loginRes.user.name, 'Alice');

    // Home shelves (Continue Watching + Next Up + Movies Latest + Shows Latest)
    const shelves = await host.invoke('home');
    assert.equal(validateProtocolType('HomeResult', shelves).valid, true);
    assert.equal(shelves.length, 4);
    assert.equal(shelves[0].style, 'continue');
    assert.equal(shelves[0].items[0].stereo, 'sbs');

    // Categories (music excluded)
    const cats = await host.invoke('categories');
    assert.equal(validateProtocolType('CategoriesResult', cats).valid, true);
    assert.equal(cats.length, 2);

    // Browse pagination
    const p1 = await host.invoke('browse', { categoryId: 'lib-movies' });
    assert.equal(validateProtocolType('ItemPage', p1).valid, true);
    assert.equal(p1.next, '1');
    const p2 = await host.invoke('browse', { categoryId: 'lib-movies', cursor: p1.next });
    assert.equal(p2.next, null);
    assert.equal(p2.items[0].id, 'mov-arrival');

    // Series -> Season -> Episodes
    const ser = await host.invoke('detail', { itemId: 'ser-threebody' });
    assert.equal(ser.seasons.length, 1);
    const sea = await host.invoke('detail', { itemId: 'sea-threebody-1' });
    assert.equal(sea.episodes.length, 2);

    // Movie detail
    const mov = await host.invoke('detail', { itemId: 'mov-dune' });
    assert.equal(validateProtocolType('Item', mov).valid, true);
    assert.equal(mov.people.length, 2);
    assert.equal(mov.related.length, 1);

    // Playback Direct Play + external subtitles (PGS bitmap ignored, SRT/VTT/ASS kept)
    const pbDirect = await host.invoke('playback', { itemId: 'mov-dune' });
    assert.equal(validateProtocolType('PlaybackInfo', pbDirect).valid, true);
    assert.ok(pbDirect.url.includes('/Videos/mov-dune/stream?'));
    assert.equal(pbDirect.subtitles.length, 3);
    assert.equal(pbDirect.audios.length, 2);
    assert.equal(pbDirect.stereo, 'sbs');

    // Playback HLS Transcode quality switch
    const pb720 = await host.invoke('playback', { itemId: 'mov-dune', quality: '720p', audio: '2' });
    assert.equal(validateProtocolType('PlaybackInfo', pb720).valid, true);
    assert.ok(pb720.url.includes('/master.m3u8?'));
    assert.ok(pb720.url.includes('MaxHeight=720'));
    assert.ok(pb720.url.includes('AudioStreamIndex=2'));
    assert.equal(pb720.mime, 'application/x-mpegURL');

    // Search + suggest
    const sr = await host.invoke('search', { query: '沙丘' });
    assert.equal(sr.items[0].id, 'mov-dune');
    const hints = await host.invoke('suggest', { query: '沙' });
    assert.deepEqual(hints, ['沙丘', '三体']);

    // Progress, favorite, played, logout
    await host.invoke('reportProgress', { itemId: 'mov-dune', positionMs: 60_000, durationMs: 9_300_000, event: 'progress', session: pbDirect.session });
    await host.invoke('setFavorite', { itemId: 'mov-dune', favorite: true });
    await host.invoke('setPlayed', { itemId: 'mov-dune', played: true });
    await host.invoke('logout');
    assert.ok(recordedCalls.some((c) => c.path === '/Sessions/Playing/Progress'));
    assert.ok(recordedCalls.some((c) => c.path === '/Users/user-alice-001/FavoriteItems/mov-dune'));
    assert.ok(recordedCalls.some((c) => c.path === '/Users/user-alice-001/PlayedItems/mov-dune'));
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
