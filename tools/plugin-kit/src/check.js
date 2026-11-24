import fs from 'node:fs';
import path from 'node:path';
import { PluginLocale } from './locale.js';
import { NetworkPolicyError } from './network-policy.js';
import { inspectArchiveBuffer } from './pack.js';
import { PluginCallError, QuickJsPluginHost } from './runtime.js';
import { validateManifest, validateProtocolType } from './schema.js';

const REQUIRED_EXPORTS = ['home', 'browse', 'detail', 'playback'];
const CAPABILITY_EXPORTS = {
  search: ['search'],
  progress: ['reportProgress'],
  favorites: ['setFavorite'],
  played: ['setPlayed'],
  filters: ['filters'],
  danmaku: ['danmaku'],
  categories: ['categories'],
};

function validatePlaybackUrls(playbackInfo, policy, errors) {
  const check = (label, url) => {
    try {
      policy.validatePlaybackUrlSyntax(url);
    } catch (e) {
      errors.push(`playback ${label} rejected by network policy: ${e.message}`);
    }
  };
  if (playbackInfo.url) check('url', playbackInfo.url);
  if (playbackInfo.dash) {
    (playbackInfo.dash.video || []).forEach((t, i) => {
      check(`dash.video[${i}].url`, t.url);
      (t.backupUrls || []).forEach((b, j) => check(`dash.video[${i}].backupUrls[${j}]`, b));
    });
    (playbackInfo.dash.audio || []).forEach((t, i) => {
      check(`dash.audio[${i}].url`, t.url);
      (t.backupUrls || []).forEach((b, j) => check(`dash.audio[${i}].backupUrls[${j}]`, b));
    });
  }
  (playbackInfo.subtitles || []).forEach((s, i) => check(`subtitles[${i}].url`, s.url));
}

export async function loadPluginSource(targetPath) {
  const abs = path.resolve(targetPath);
  const stat = fs.statSync(abs);
  if (stat.isFile() && abs.endsWith('.d3plugin')) {
    const inspected = await inspectArchiveBuffer(fs.readFileSync(abs));
    const locale = new PluginLocale(inspected.locales['zh-CN'] ? JSON.parse(inspected.locales['zh-CN']) : {});
    return { manifest: inspected.manifest, mainJs: inspected.mainJs, locale, target: abs };
  }
  if (!stat.isDirectory()) {
    throw new Error(`Target is neither a plugin directory nor a .d3plugin archive: ${abs}`);
  }
  const manifestPath = path.join(abs, 'manifest.json');
  const mainJsPath = path.join(abs, 'main.js');
  if (!fs.existsSync(manifestPath)) throw new Error(`Missing manifest.json in ${abs}`);
  if (!fs.existsSync(mainJsPath)) throw new Error(`Missing main.js in ${abs}`);
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const mainJs = fs.readFileSync(mainJsPath, 'utf8');
  const locale = PluginLocale.fromDir(abs);
  return { manifest, mainJs, locale, target: abs };
}

export async function checkPlugin(targetPath, opts = {}) {
  const errors = [];
  const warnings = [];
  const checks = [];

  let loaded;
  try {
    loaded = await loadPluginSource(targetPath);
  } catch (e) {
    return { ok: false, target: targetPath, errors: [e.message], warnings, checks };
  }

  const { manifest, mainJs, locale } = loaded;

  // 1. Validate manifest against shared JSON schema + semantic rules
  const mCheck = validateManifest(manifest);
  if (!mCheck.valid) {
    errors.push(...mCheck.errors.map((e) => `manifest: ${e}`));
    return { ok: false, target: targetPath, pluginId: manifest?.id, errors, warnings, checks };
  }
  checks.push('manifest schema & semantics');

  // 2. Load inside real QuickJS runtime and inspect exports
  const host = new QuickJsPluginHost({
    manifest,
    mainJs,
    config: opts.config || {},
    locale,
    dnsResolver: opts.dnsResolver || null,
    transportOverride: opts.transportOverride || null,
    logSink: () => {},
  });

  try {
    const exportsList = await host.exportedMethods();
    const exportSet = new Set(exportsList);

    for (const reqFn of REQUIRED_EXPORTS) {
      if (!exportSet.has(reqFn)) {
        errors.push(`main.js missing required export: ${reqFn}()`);
      }
    }
    for (const cap of manifest.capabilities || []) {
      for (const fn of CAPABILITY_EXPORTS[cap] || []) {
        if (!exportSet.has(fn)) {
          errors.push(`manifest declares capability '${cap}' but main.js does not export ${fn}()`);
        }
      }
    }
    if (manifest.auth === 'form' && !exportSet.has('login')) {
      errors.push("manifest declares auth='form' but main.js does not export login()");
    }
    if ((manifest.auth === 'qr' || manifest.auth === 'device') &&
        (!exportSet.has('loginStart') || !exportSet.has('loginPoll'))) {
      errors.push(`manifest declares auth='${manifest.auth}' but main.js must export loginStart() and loginPoll()`);
    }
    checks.push('QuickJS module load & exports');

    // 3. Verify sandbox boundary (unauthorized host + loopback + Host header rejected)
    const probeRes = await host.runProbe(`
      const blocked = [];
      for (const target of [
        ['https://unauthorized.depth3d-check.invalid/test', {}],
        ['http://127.0.0.1:9999/secret', {}],
        ['https://api.example.com/', { headers: { Host: 'evil.com' } }],
      ]) {
        try {
          await ctx.fetch(target[0], target[1]);
          blocked.push(false);
        } catch (e) {
          blocked.push(true);
        }
      }
      return blocked;
    `);
    if (!Array.isArray(probeRes) || !probeRes.every(Boolean)) {
      errors.push('sandbox probe failed: unauthorized fetch was not blocked');
    } else {
      checks.push('permission & sandbox boundary probe');
    }

    // 4. Functional data-structure check (runs home -> browse -> detail -> playback + capabilities)
    const hasRequiredConfig = (manifest.config || []).every((f) => {
      if (!f.required) return true;
      const val = host.config[f.key];
      return val !== undefined && val !== null && String(val).trim() !== '';
    });

    if (errors.length === 0) {
      try {
        const shelves = await host.invoke('home');
        const hVal = validateProtocolType('HomeResult', shelves);
        if (!hVal.valid) {
          errors.push(...hVal.errors.map((e) => `home(): ${e}`));
        } else {
          checks.push(`home() schema (${shelves.length} shelves)`);
        }

        const rootPage = await host.invoke('browse', { categoryId: '' });
        const bVal = validateProtocolType('ItemPage', rootPage);
        if (!bVal.valid) {
          errors.push(...bVal.errors.map((e) => `browse(''): ${e}`));
        } else {
          checks.push(`browse('') schema (${rootPage.items.length} items)`);
        }

        // Find a playable item for detail() + playback()
        const allItems = [
          ...(Array.isArray(shelves) ? shelves.flatMap((s) => s.items || []) : []),
          ...(rootPage?.items || []),
        ];
        const playable = allItems.find((it) => ['movie', 'video', 'episode', 'live'].includes(it.kind)) || allItems[0];
        if (playable) {
          const detailItem = await host.invoke('detail', { itemId: playable.id });
          const dVal = validateProtocolType('Item', detailItem);
          if (!dVal.valid) errors.push(...dVal.errors.map((e) => `detail('${playable.id}'): ${e}`));
          else checks.push(`detail('${playable.id}') schema`);

          const playTarget = ['movie', 'video', 'episode', 'live'].includes(detailItem?.kind)
            ? detailItem.id
            : detailItem?.episodes?.[0]?.id;
          if (playTarget) {
            const pb = await host.invoke('playback', { itemId: playTarget });
            const pVal = validateProtocolType('PlaybackInfo', pb);
            if (!pVal.valid) {
              errors.push(...pVal.errors.map((e) => `playback('${playTarget}'): ${e}`));
            } else {
              validatePlaybackUrls(pb, host.networkPolicy, errors);
              checks.push(`playback('${playTarget}') schema & URL policy`);
            }
          }
        }

        // Optional capabilities smoke check
        const caps = new Set(manifest.capabilities || []);
        if (caps.has('categories')) {
          const cats = await host.invoke('categories');
          const cVal = validateProtocolType('CategoriesResult', cats);
          if (!cVal.valid) errors.push(...cVal.errors.map((e) => `categories(): ${e}`));
          else checks.push('categories() schema');
        }
        if (caps.has('search')) {
          const sr = await host.invoke('search', { query: 'test' });
          const sVal = validateProtocolType('ItemPage', sr);
          if (!sVal.valid) errors.push(...sVal.errors.map((e) => `search(): ${e}`));
          else checks.push('search() schema');
          if (exportSet.has('suggest')) {
            const sug = await host.invoke('suggest', { query: 'te' });
            const sugVal = validateProtocolType('SuggestResult', sug);
            if (!sugVal.valid) errors.push(...sugVal.errors.map((e) => `suggest(): ${e}`));
            else checks.push('suggest() schema');
          }
        }
        if (caps.has('filters')) {
          const fl = await host.invoke('filters', { categoryId: '' });
          const fVal = validateProtocolType('FiltersResult', fl);
          if (!fVal.valid) errors.push(...fVal.errors.map((e) => `filters(): ${e}`));
          else checks.push('filters() schema');
        }
        if (caps.has('danmaku') && playable) {
          const dm = await host.invoke('danmaku', { itemId: playable.id, fromMs: 0, toMs: 60000 });
          const dmVal = validateProtocolType('DanmakuResult', dm);
          if (!dmVal.valid) errors.push(...dmVal.errors.map((e) => `danmaku(): ${e}`));
          else checks.push('danmaku() schema');
        }
      } catch (err) {
        if (err instanceof PluginCallError &&
            (err.code === 'NeedsSetup' || err.code === 'NeedsAuth') &&
            !hasRequiredConfig) {
          checks.push(`unconfigured guard (cleanly threw ${err.code})`);
          warnings.push(`Skipped live data validation because required config is not provided (threw ${err.code})`);
        } else if (err instanceof NetworkPolicyError) {
          errors.push(`permission violation during check: ${err.message}`);
        } else {
          errors.push(`runtime check failed: ${err instanceof PluginCallError ? `${err.code}: ${err.message}` : err.message}`);
        }
      }
    }
  } catch (err) {
    errors.push(`QuickJS load failed: ${err.message}`);
  } finally {
    await host.dispose();
  }

  return {
    ok: errors.length === 0,
    target: targetPath,
    pluginId: manifest.id,
    version: manifest.version,
    errors,
    warnings,
    checks,
  };
}
