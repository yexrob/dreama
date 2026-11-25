import fs from 'node:fs';
import path from 'node:path';
import { checkPlugin, loadPluginSource, validateMethodResult } from './check.js';
import { packPluginToFile } from './pack.js';
import { PluginCallError, QuickJsPluginHost } from './runtime.js';
import { validateManifest } from './schema.js';
import { startDevServer, listLanIpv4Addresses } from './serve.js';
import { PluginStorage } from './storage.js';

const BOOLEAN_FLAGS = new Set(['help', 'h', 'no-discover']);

function parseArgs(argv) {
  const positionals = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token.startsWith('--')) {
      const eq = token.indexOf('=');
      if (eq >= 0) {
        const k = token.slice(2, eq);
        const v = token.slice(eq + 1);
        appendFlag(flags, k, v);
      } else {
        const k = token.slice(2);
        const next = argv[i + 1];
        if (!BOOLEAN_FLAGS.has(k) && next !== undefined && !next.startsWith('-')) {
          appendFlag(flags, k, next);
          i += 1;
        } else {
          appendFlag(flags, k, true);
        }
      }
    } else if (token.startsWith('-') && token.length === 2) {
      const k = token.slice(1);
      const next = argv[i + 1];
      if (!BOOLEAN_FLAGS.has(k) && next !== undefined && !next.startsWith('-')) {
        appendFlag(flags, k, next);
        i += 1;
      } else {
        appendFlag(flags, k, true);
      }
    } else {
      positionals.push(token);
    }
  }
  return { positionals, flags };
}

function appendFlag(flags, key, value) {
  if (key in flags) {
    if (Array.isArray(flags[key])) flags[key].push(value);
    else flags[key] = [flags[key], value];
  } else {
    flags[key] = value;
  }
}

function loadConfigFlag(raw) {
  if (!raw || raw === true) return {};
  if (typeof raw === 'string') {
    const trimmed = raw.trim();
    if (trimmed.startsWith('{')) return JSON.parse(trimmed);
    if (fs.existsSync(trimmed)) return JSON.parse(fs.readFileSync(trimmed, 'utf8'));
  }
  throw new Error(`Invalid --config value: ${raw}`);
}

function printHelp() {
  console.log(`depth3d plugin-kit CLI (d3plugin)

Usage:
  d3plugin run <method> [pluginDir] [options]
    Methods: home, browse, detail, playback, search, suggest, filters,
             danmaku, categories, login, loginStart, loginPoll, logout,
             reportProgress, setFavorite, setPlayed
    Options:
      --item <id>          itemId for detail / playback / danmaku / favorites / played / progress
      --category <id>      categoryId for browse / filters (default "")
      --cursor <cursor>    pagination cursor for browse / search
      --sort <value>       sort option for browse
      --filter <k=v>       repeatable filter pair for browse
      --query <text>       query for search / suggest
      --kind <kind>        kind filter for search
      --quality <id>       quality id for playback
      --audio <id>         audio track id for playback
      --from <ms>          fromMs for danmaku (default 0)
      --to <ms>            toMs for danmaku (default 60000)
      --state <state>      state token for loginPoll
      --config <json|file> plugin config JSON object or path
      --storage <file>     JSON file to persist ctx.storage across runs

  d3plugin check <pluginDir|.d3plugin>... [--config <json|file>]
    Validates manifest.json against shared JSON Schema, loads main.js in
    QuickJS, verifies exports, probes sandbox/network isolation, and validates
    output shapes and URL policies against protocol-types.schema.json.

  d3plugin pack <pluginDir> [-o <output.d3plugin>]
    Packages a plugin directory into a .d3plugin zip and prints its SHA-256.

  d3plugin serve <pluginDir> [--port 8787] [--host 0.0.0.0] [--no-discover]
    Serves live-repacked /index.json, /manifest.json, and /plugin.d3plugin
    for headset developer mode, with LAN UDP auto-discovery enabled by default.
`);
}

function buildMethodArg(method, flags) {
  switch (method) {
    case 'home':
    case 'categories':
    case 'login':
    case 'loginStart':
    case 'logout':
      return undefined;
    case 'browse': {
      const arg = { categoryId: flags.category ?? '' };
      if (flags.cursor) arg.cursor = String(flags.cursor);
      if (flags.sort) arg.sort = String(flags.sort);
      if (flags.filter) {
        const list = Array.isArray(flags.filter) ? flags.filter : [flags.filter];
        arg.filters = {};
        for (const pair of list) {
          const idx = String(pair).indexOf('=');
          if (idx > 0) arg.filters[pair.slice(0, idx)] = pair.slice(idx + 1);
        }
      }
      return arg;
    }
    case 'detail':
      if (!flags.item) throw new Error('detail requires --item <id>');
      return { itemId: String(flags.item) };
    case 'playback': {
      if (!flags.item) throw new Error('playback requires --item <id>');
      const arg = { itemId: String(flags.item) };
      if (flags.quality) arg.quality = String(flags.quality);
      if (flags.audio) arg.audio = String(flags.audio);
      return arg;
    }
    case 'search': {
      if (!flags.query) throw new Error('search requires --query <text>');
      const arg = { query: String(flags.query) };
      if (flags.cursor) arg.cursor = String(flags.cursor);
      if (flags.kind) arg.kind = String(flags.kind);
      return arg;
    }
    case 'suggest':
      if (!flags.query) throw new Error('suggest requires --query <text>');
      return { query: String(flags.query) };
    case 'filters':
      return { categoryId: String(flags.category ?? '') };
    case 'danmaku':
      if (!flags.item) throw new Error('danmaku requires --item <id>');
      return {
        itemId: String(flags.item),
        fromMs: Number(flags.from ?? 0),
        toMs: Number(flags.to ?? 60_000),
      };
    case 'loginPoll':
      if (!flags.state) throw new Error('loginPoll requires --state <state>');
      return { state: String(flags.state) };
    case 'reportProgress':
      if (!flags.item) throw new Error('reportProgress requires --item <id>');
      return {
        itemId: String(flags.item),
        positionMs: Number(flags.position ?? 0),
        durationMs: Number(flags.duration ?? 0),
        event: String(flags.event ?? 'progress'),
        ...(flags.session ? { session: String(flags.session) } : {}),
      };
    case 'setFavorite':
      if (!flags.item) throw new Error('setFavorite requires --item <id>');
      return { itemId: String(flags.item), favorite: flags.value !== 'false' };
    case 'setPlayed':
      if (!flags.item) throw new Error('setPlayed requires --item <id>');
      return { itemId: String(flags.item), played: flags.value !== 'false' };
    default:
      return flags.arg ? JSON.parse(String(flags.arg)) : undefined;
  }
}

export async function main(argv) {
  const { positionals, flags } = parseArgs(argv);
  const cmd = positionals[0];
  if (!cmd || flags.help || flags.h) {
    printHelp();
    return 0;
  }

  try {
    if (cmd === 'run') {
      const method = positionals[1];
      if (!method) {
        console.error('Error: run requires a method name (e.g. d3plugin run home ./my-plugin)');
        return 1;
      }
      const pluginDir = positionals[2] || '.';
      const loaded = await loadPluginSource(pluginDir);
      const mVal = validateManifest(loaded.manifest);
      if (!mVal.valid) {
        console.error(`Invalid manifest.json:\n  - ${mVal.errors.join('\n  - ')}`);
        return 1;
      }
      const config = loadConfigFlag(flags.config);
      const storage = new PluginStorage(flags.storage ? path.resolve(String(flags.storage)) : null);
      const host = new QuickJsPluginHost({
        manifest: loaded.manifest,
        mainJs: loaded.mainJs,
        config,
        storage,
        locale: loaded.locale,
      });
      try {
        const arg = buildMethodArg(method, flags);
        const result = await host.invoke(method, arg);
        // S9: Strictly validate return schema & URL policies (fail non-zero, not just warn)
        const validationErrors = validateMethodResult(method, result, loaded.manifest, host.networkPolicy);
        console.log(JSON.stringify(result === undefined ? null : result, null, 2));
        if (validationErrors.length > 0) {
          console.error(`Validation failed for ${method}():`);
          validationErrors.forEach((e) => console.error(`  - ${e}`));
          return 1;
        }
        return 0;
      } catch (err) {
        if (err instanceof PluginCallError) {
          console.error(`PluginError [${err.code}]: ${err.message}${err.retryAfterSec ? ` (retryAfterSec=${err.retryAfterSec})` : ''}`);
          return 2;
        }
        throw err;
      } finally {
        await host.dispose();
      }
    }

    if (cmd === 'check') {
      const targets = positionals.slice(1);
      if (targets.length === 0) targets.push('.');
      const config = loadConfigFlag(flags.config);
      let allOk = true;
      for (const target of targets) {
        const res = await checkPlugin(target, { config });
        const label = res.pluginId ? `${target} (${res.pluginId}@${res.version})` : target;
        if (res.ok) {
          console.log(`PASS ${label}`);
          for (const c of res.checks) console.log(`  [ok] ${c}`);
          for (const w of res.warnings) console.log(`  [warn] ${w}`);
        } else {
          allOk = false;
          console.error(`FAIL ${label}`);
          for (const c of res.checks) console.error(`  [ok] ${c}`);
          for (const e of res.errors) console.error(`  [error] ${e}`);
        }
      }
      return allOk ? 0 : 1;
    }

    if (cmd === 'pack') {
      const pluginDir = positionals[1] || '.';
      const outPath = flags.o || flags.output || null;
      const res = await packPluginToFile(pluginDir, outPath);
      console.log(`Packed ${res.manifest.id}@${res.manifest.version}`);
      console.log(`  file:   ${res.outputPath}`);
      console.log(`  size:   ${res.size} bytes`);
      console.log(`  sha256: ${res.sha256}`);
      return 0;
    }

    if (cmd === 'serve') {
      const pluginDir = positionals[1] || '.';
      const port = Number(flags.port ?? 8787);
      const host = String(flags.host ?? '0.0.0.0');
      const discover = !flags['no-discover'];
      const srv = await startDevServer(pluginDir, { port, host, discover });
      const lanIps = listLanIpv4Addresses();
      const primaryAddr = lanIps[0] ? `${lanIps[0]}:${srv.port}` : `127.0.0.1:${srv.port}`;
      console.log(`depth3d plugin dev server listening on http://${host}:${srv.port}`);
      if (lanIps.length > 0) {
        console.log('LAN addresses (for headset on the same Wi-Fi):');
        for (const ip of lanIps) {
          console.log(`  http://${ip}:${srv.port}`);
        }
      } else {
        console.log(`  local:    http://127.0.0.1:${srv.port}`);
      }
      console.log(`  index:    http://${primaryAddr}/index.json`);
      console.log(`  manifest: http://${primaryAddr}/manifest.json`);
      console.log(`  package:  http://${primaryAddr}/plugin.d3plugin`);
      if (!discover) {
        console.log('  discover: disabled (--no-discover)');
      } else if (srv.discoverEnabled) {
        console.log(`  discover: UDP port ${srv.discoverPort} (LAN private addresses only)`);
      } else if (srv.discoverError) {
        console.warn(`  discover: unavailable (${srv.discoverError.message}); enter address manually`);
      }
      console.log('');
      console.log('Headset entry: 设置 › 播放源与插件 › 插件 › 开发者');
      console.log(
        srv.discoverEnabled
          ? `  会自动发现此服务器，也可手动输入 ${primaryAddr}`
          : `  请点击「输入电脑地址」并填写 ${primaryAddr}`
      );
      await new Promise((resolve) => {
        process.on('SIGINT', async () => { await srv.close(); resolve(); });
        process.on('SIGTERM', async () => { await srv.close(); resolve(); });
      });
      return 0;
    }

    console.error(`Unknown command: ${cmd}`);
    printHelp();
    return 1;
  } catch (err) {
    console.error(`Error: ${err.message}`);
    return 1;
  }
}
