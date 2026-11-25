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

const MPD_BASE_URL_RE = /<BaseURL\b[^>]*>\s*([^<]+?)\s*<\/BaseURL>/gi;
const MPD_ATTR_URL_RE = /\b(?:media|initialization|sourceURL)\s*=\s*"([^"]+)"/gi;

function extractUrlsFromMpd(mpdXml) {
  const out = [];
  for (const m of String(mpdXml).matchAll(MPD_BASE_URL_RE)) {
    const u = m[1].trim();
    if (u.includes('://') || u.startsWith('file:') || u.startsWith('content:')) out.push(u);
  }
  for (const m of String(mpdXml).matchAll(MPD_ATTR_URL_RE)) {
    const u = m[1].trim();
    if (u.includes('://') || u.startsWith('file:') || u.startsWith('content:')) out.push(u);
  }
  return out;
}

export function validateImageUrl(label, img, policy, errors) {
  if (img === null || img === undefined) return;
  const url = typeof img === 'string' ? img : (typeof img === 'object' ? img.url : null);
  if (!url) return;
  try {
    policy.validateResourceUrlPrecheck(url);
  } catch (e) {
    errors.push(`${label} rejected by network policy: ${e.message}`);
  }
}

export function validateItemUrls(prefix, item, policy, errors) {
  if (!item || typeof item !== 'object') return;
  validateImageUrl(`${prefix}.poster`, item.poster, policy, errors);
  validateImageUrl(`${prefix}.thumb`, item.thumb, policy, errors);
  validateImageUrl(`${prefix}.backdrop`, item.backdrop, policy, errors);
  for (let i = 0; i < (item.people || []).length; i++) {
    validateImageUrl(`${prefix}.people[${i}].image`, item.people[i]?.image, policy, errors);
  }
  for (let i = 0; i < (item.seasons || []).length; i++) {
    validateItemUrls(`${prefix}.seasons[${i}]`, item.seasons[i], policy, errors);
  }
  for (let i = 0; i < (item.episodes || []).length; i++) {
    validateItemUrls(`${prefix}.episodes[${i}]`, item.episodes[i], policy, errors);
  }
  for (let i = 0; i < (item.related || []).length; i++) {
    validateItemUrls(`${prefix}.related[${i}]`, item.related[i], policy, errors);
  }
}

export function validatePlaybackResult(playbackInfo, policy, errors) {
  if (!playbackInfo || typeof playbackInfo !== 'object') return;
  if (playbackInfo.live === true && playbackInfo.dash) {
    errors.push('playback: live=true combined with dash is unsupported in protocol v1 (§11.2)');
  }
  const check = (label, url) => {
    try {
      policy.validateResourceUrlPrecheck(url);
    } catch (e) {
      errors.push(`playback ${label} rejected by network policy: ${e.message}`);
    }
  };
  if (playbackInfo.url) check('url', playbackInfo.url);
  if (playbackInfo.dash) {
    if (typeof playbackInfo.durationMs !== 'number' || playbackInfo.durationMs <= 0) {
      errors.push('playback: dash mode requires positive durationMs (§11.2)');
    }
    (playbackInfo.dash.video || []).forEach((t, i) => {
      check(`dash.video[${i}].url`, t.url);
      (t.backupUrls || []).forEach((b, j) => check(`dash.video[${i}].backupUrls[${j}]`, b));
    });
    (playbackInfo.dash.audio || []).forEach((t, i) => {
      check(`dash.audio[${i}].url`, t.url);
      (t.backupUrls || []).forEach((b, j) => check(`dash.audio[${i}].backupUrls[${j}]`, b));
    });
  }
  if (playbackInfo.mpd) {
    for (const u of extractUrlsFromMpd(playbackInfo.mpd)) {
      check('mpd URL', u);
    }
  }
  (playbackInfo.subtitles || []).forEach((s, i) => check(`subtitles[${i}].url`, s.url));
}

export function validateMethodResult(method, result, manifest, policy) {
  const errors = [];
  const schemaMap = {
    home: 'HomeResult',
    browse: 'ItemPage',
    detail: 'Item',
    playback: 'PlaybackInfo',
    search: 'ItemPage',
    suggest: 'SuggestResult',
    filters: 'FiltersResult',
    danmaku: 'DanmakuResult',
    categories: 'CategoriesResult',
    login: 'FormLoginResult',
    loginPoll: 'LoginPollResult',
  };
  const schemaDef = method === 'loginStart'
    ? (manifest.auth === 'device' ? 'DeviceLoginStartResult' : 'QrLoginStartResult')
    : schemaMap[method];

  if (schemaDef) {
    if (result === undefined || result === null) {
      errors.push(`${method}() returned ${result}, expected ${schemaDef}`);
      return errors;
    }
    const check = validateProtocolType(schemaDef, result);
    if (!check.valid) {
      errors.push(...check.errors.map((e) => `${method}(): ${e}`));
    }
  }

  // Validate embedded URLs under §11.3 resource policy
  if (method === 'home' && Array.isArray(result)) {
    result.forEach((s, si) => (s?.items || []).forEach((it, ii) => validateItemUrls(`home[${si}].items[${ii}]`, it, policy, errors)));
  } else if ((method === 'browse' || method === 'search') && result?.items) {
    result.items.forEach((it, ii) => validateItemUrls(`${method}.items[${ii}]`, it, policy, errors));
  } else if (method === 'detail' && result) {
    validateItemUrls('detail', result, policy, errors);
  } else if (method === 'playback' && result) {
    validatePlaybackResult(result, policy, errors);
  } else if (method === 'login' && result?.user?.avatar) {
    validateImageUrl('login.user.avatar', result.user.avatar, policy, errors);
  } else if (method === 'loginPoll' && result?.user?.avatar) {
    validateImageUrl('loginPoll.user.avatar', result.user.avatar, policy, errors);
  }

  return errors;
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

    // 3. Verify sandbox & §11.3 network boundary
    const probeRes = await host.runProbe(`
      const blocked = [];
      for (const target of [
        ['https://unauthorized.depth3d-check.invalid/test', {}],
        ['http://127.0.0.1:9999/secret', {}],
        ['http://127.1:9999/secret', {}],
        ['http://0x7f.0.0.1:9999/secret', {}],
        ['http://localhost.:9999/secret', {}],
        ['http://169.254.169.254/latest/meta-data/', {}],
        ['file:///etc/passwd', {}],
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
      checks.push('permission & §11.3 sandbox boundary probe');
    }

    // 4. Functional data-structure & URL-policy check (runs home -> browse -> detail -> playback + capabilities)
    const hasRequiredConfig = (manifest.config || []).every((f) => {
      if (!f.required) return true;
      const val = host.config[f.key];
      return val !== undefined && val !== null && String(val).trim() !== '';
    });

    if (errors.length === 0) {
      try {
        const shelves = await host.invoke('home');
        const hErrs = validateMethodResult('home', shelves, manifest, host.networkPolicy);
        if (hErrs.length) errors.push(...hErrs);
        else checks.push(`home() schema & image URLs (${shelves.length} shelves)`);

        const rootPage = await host.invoke('browse', { categoryId: '' });
        const bErrs = validateMethodResult('browse', rootPage, manifest, host.networkPolicy);
        if (bErrs.length) errors.push(...bErrs);
        else checks.push(`browse('') schema & image URLs (${rootPage.items.length} items)`);

        const allItems = [
          ...(Array.isArray(shelves) ? shelves.flatMap((s) => s.items || []) : []),
          ...(rootPage?.items || []),
        ];
        const playable = allItems.find((it) => ['movie', 'video', 'episode', 'live'].includes(it.kind)) || allItems[0];
        if (playable) {
          const detailItem = await host.invoke('detail', { itemId: playable.id });
          const dErrs = validateMethodResult('detail', detailItem, manifest, host.networkPolicy);
          if (dErrs.length) errors.push(...dErrs);
          else checks.push(`detail('${playable.id}') schema & image URLs`);

          const playTarget = ['movie', 'video', 'episode', 'live'].includes(detailItem?.kind)
            ? detailItem.id
            : detailItem?.episodes?.[0]?.id;
          if (playTarget) {
            const pb = await host.invoke('playback', { itemId: playTarget });
            const pErrs = validateMethodResult('playback', pb, manifest, host.networkPolicy);
            if (pErrs.length) errors.push(...pErrs);
            else checks.push(`playback('${playTarget}') schema & URL policy`);
          }
        }

        const caps = new Set(manifest.capabilities || []);
        if (caps.has('categories')) {
          const cats = await host.invoke('categories');
          const cErrs = validateMethodResult('categories', cats, manifest, host.networkPolicy);
          if (cErrs.length) errors.push(...cErrs);
          else checks.push('categories() schema');
        }
        if (caps.has('search')) {
          // 1. Empty & whitespace query must not throw and must return empty ItemPage
          for (const emptyQ of ['', '   ']) {
            const emptyRes = await host.invoke('search', { query: emptyQ });
            const emptyErrs = validateMethodResult('search', emptyRes, manifest, host.networkPolicy);
            if (emptyErrs.length) {
              errors.push(...emptyErrs.map((e) => `search(${JSON.stringify(emptyQ)}): ${e}`));
            } else if ((emptyRes.items || []).length > 0 || emptyRes.next) {
              errors.push(`search(${JSON.stringify(emptyQ)}) must return empty items and next=null`);
            }
          }

          // 2. Standard query (+ fallback token derived from home/browse titles to exercise pagination)
          let probeQuery = 'test';
          let sr = await host.invoke('search', { query: probeQuery });
          let sErrs = validateMethodResult('search', sr, manifest, host.networkPolicy);
          if (sErrs.length) errors.push(...sErrs);
          else checks.push('search() schema & image URLs');

          if (!sErrs.length && !sr.next && (sr.items || []).length === 0 && allItems.length > 0) {
            const charFreq = new Map();
            for (const it of allItems) {
              const title = String(it?.title || '').trim();
              const seenInTitle = new Set();
              for (const ch of title) {
                if (/\s/.test(ch)) continue;
                if (!seenInTitle.has(ch)) {
                  seenInTitle.add(ch);
                  charFreq.set(ch, (charFreq.get(ch) || 0) + 1);
                }
              }
            }
            const bestChar = [...charFreq.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
            if (bestChar) {
              probeQuery = bestChar;
              sr = await host.invoke('search', { query: probeQuery });
              sErrs = validateMethodResult('search', sr, manifest, host.networkPolicy);
              if (sErrs.length) errors.push(...sErrs);
            }
          }

          // 3. Pagination progression: follow up to 3 subsequent pages, checking schema,
          //    page-2 duplicate content (ignored cursor), and cursor cycles of any length.
          if (!sErrs.length && sr?.next) {
            const page1Ids = new Set(
              (sr.items || []).map((it) => it?.id).filter((id) => typeof id === 'string' && id.length > 0),
            );
            const seenCursors = new Set([sr.next]);
            let curCursor = sr.next;
            let paginationOk = true;
            const maxExtraPages = 3;
            for (let pageIdx = 2; pageIdx <= 1 + maxExtraPages && curCursor; pageIdx++) {
              const srNext = await host.invoke('search', { query: probeQuery, cursor: curCursor });
              const snErrs = validateMethodResult('search', srNext, manifest, host.networkPolicy);
              if (snErrs.length) {
                errors.push(...snErrs.map((e) => `search(page${pageIdx}): ${e}`));
                paginationOk = false;
                break;
              }
              if (pageIdx === 2 && page1Ids.size > 0 && Array.isArray(srNext.items) && srNext.items.length > 0) {
                const page2Ids = srNext.items.map((it) => it?.id).filter((id) => typeof id === 'string' && id.length > 0);
                if (page2Ids.length > 0 && page2Ids.every((id) => page1Ids.has(id))) {
                  errors.push('search() page 2 duplicated page 1 items (plugin appears to ignore cursor)');
                  paginationOk = false;
                  break;
                }
              }
              const nextCursor = srNext?.next || null;
              if (nextCursor) {
                if (seenCursors.has(nextCursor)) {
                  errors.push(`search() pagination cursor cycle detected at '${nextCursor}' (infinite loop risk)`);
                  paginationOk = false;
                  break;
                }
                seenCursors.add(nextCursor);
              }
              curCursor = nextCursor;
            }
            if (paginationOk) {
              checks.push('search() pagination cursor progression');
            }
          }

          // 4. Kind filter & unknown/unsupported kind tolerance (must not throw)
          const srKind = await host.invoke('search', { query: probeQuery, kind: 'movie' });
          const skErrs = validateMethodResult('search', srKind, manifest, host.networkPolicy);
          if (skErrs.length) errors.push(...skErrs.map((e) => `search(kind=movie): ${e}`));

          const srUnknownKind = await host.invoke('search', { query: probeQuery, kind: 'unknown_future_kind' });
          const suErrs = validateMethodResult('search', srUnknownKind, manifest, host.networkPolicy);
          if (suErrs.length) {
            errors.push(...suErrs.map((e) => `search(unsupported kind): ${e}`));
          } else if (!skErrs.length) {
            checks.push('search() empty query, kind & unsupported kind tolerance');
          }

          // 5. Optional suggest(): validate when exported, silently pass when omitted
          if (exportSet.has('suggest')) {
            const sug = await host.invoke('suggest', { query: probeQuery });
            const sugErrs = validateMethodResult('suggest', sug, manifest, host.networkPolicy);
            if (sugErrs.length) errors.push(...sugErrs);
            const emptySug = await host.invoke('suggest', { query: '' });
            const emptySugErrs = validateMethodResult('suggest', emptySug, manifest, host.networkPolicy);
            if (emptySugErrs.length) {
              errors.push(...emptySugErrs.map((e) => `suggest(''): ${e}`));
            } else if (Array.isArray(emptySug) && emptySug.length > 0) {
              errors.push("suggest('') must return an empty array []");
            }
            if (!sugErrs.length && !emptySugErrs.length) {
              checks.push('suggest() schema & empty query');
            }
          } else {
            checks.push('suggest() optional (omitted)');
          }
        }
        if (caps.has('filters')) {
          const fl = await host.invoke('filters', { categoryId: '' });
          const fErrs = validateMethodResult('filters', fl, manifest, host.networkPolicy);
          if (fErrs.length) errors.push(...fErrs);
          else checks.push('filters() schema');
        }
        if (caps.has('danmaku') && playable) {
          const dm = await host.invoke('danmaku', { itemId: playable.id, fromMs: 0, toMs: 60000 });
          const dmErrs = validateMethodResult('danmaku', dm, manifest, host.networkPolicy);
          if (dmErrs.length) errors.push(...dmErrs);
          else checks.push('danmaku() schema');
        }
        if (manifest.auth === 'qr' || manifest.auth === 'device') {
          const ls = await host.invoke('loginStart');
          const lsErrs = validateMethodResult('loginStart', ls, manifest, host.networkPolicy);
          if (lsErrs.length) errors.push(...lsErrs);
          else checks.push('loginStart() schema');
          if (ls?.state) {
            const lp = await host.invoke('loginPoll', { state: ls.state });
            const lpErrs = validateMethodResult('loginPoll', lp, manifest, host.networkPolicy);
            if (lpErrs.length) errors.push(...lpErrs);
            else checks.push('loginPoll() schema');
          }
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
