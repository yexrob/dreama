# depth3d Plugin Kit (`d3plugin`)

`tools/plugin-kit` provides the `d3plugin` CLI for developing, testing, validating, packing, and serving depth3d media source plugins on a desktop machine using the same QuickJS sandbox, network policy, DOM parser limits, and JSON Schemas as the headset app.

Requires **Node.js 20+**.

## Installation

```bash
cd tools/plugin-kit
npm install

# Run directly
node ./bin/d3plugin.js --help

# Or link globally as `d3plugin`
npm link
```

## Commands

### `d3plugin run <method> <dir>`

Invoke a single plugin method (`home`, `browse`, `detail`, `playback`, `search`, `suggest`, `categories`, `filters`, `danmaku`, `login`, `loginStart`, `loginPoll`, `logout`, `setFavorite`, `setPlayed`, `reportProgress`) inside QuickJS.

```bash
d3plugin run home ./my-plugin --config '{"server":"http://192.168.1.20:8080"}'
d3plugin run playback ./my-plugin --item movie-1 --quality 1080p --config '{"server":"http://192.168.1.20:8080"}'
```

Options:
- `--config <json>` — JSON string for `ctx.config`
- `--category <id>`, `--cursor <str>`, `--item <id>`, `--query <str>`, `--quality <id>`, `--audio <id>`
- `--locale <tag>` — Override `ctx.device.locale` (default `zh-CN`)
- `--storage <file>` — Persist `ctx.storage` across CLI runs

### `d3plugin check <dir...>`

Validate one or more plugin directories against the shared JSON Schemas, QuickJS module loader, export contract, sandbox restrictions, and `await` checks for async storage/cookies APIs.

```bash
d3plugin check ../../plugins/reference ../../plugins/jellyfin
```

### `d3plugin pack <dir> [-o <out.d3plugin>]`

Pack a plugin directory into a `.d3plugin` zip archive (8 MB cap, deterministic entries) and print its SHA-256 digest.

```bash
d3plugin pack ./my-plugin -o dist/my-plugin-1.0.0.d3plugin
```

### `d3plugin serve <dir> [--port 8787] [--host 0.0.0.0] [--no-discover]`

Serve a plugin directory directly to a depth3d headset on the same Wi‑Fi. Every request packs the plugin fresh from disk so changes take effect immediately when you tap **重新加载** on the headset (without bumping `manifest.json`'s `version`).

**HTTP endpoints** (strict allowlist — no arbitrary file reading):
- `GET /` — Summary JSON (`name`, `id`, `version`, endpoint paths)
- `GET /index.json` — Single-plugin repository index with live `sha256`
- `GET /manifest.json` — Current `manifest.json`
- `GET /plugin.d3plugin` — Freshly packed `.d3plugin` archive
- `GET /health` — `{"ok": true}`

**LAN UDP auto-discovery**:
- Listens on UDP port **`18787`** by default (avoids Jellyfin/Emby port `7359`).
- Responds only to probe packets equal to `depth3d-plugin-dev-discover-v1` sent from **private LAN addresses** (RFC1918 IPv4 or IPv6 ULA; ignores loopback, link-local, and public sources).
- Replies with a pre-cached JSON payload `{ "name", "id", "version", "port" }` (resolving `$t:` localized names via `locales/zh-CN.json`, truncated to fit a strict 512-byte cap; zero synchronous disk reads per UDP packet) and enforces both per-source and global rate limits (4/s per IP, 30/s total).
- Pass `--no-discover` to disable the UDP responder.

On the headset, open **设置 › 播放源与插件 › 插件 › 开发者** to pick up the server automatically or enter the printed LAN address manually.

## Testing

```bash
cd tools/plugin-kit
npm test
```

See also [docs/plugin-dev.md](../../docs/plugin-dev.md) and [docs/specs/plugin-protocol.md](../../docs/specs/plugin-protocol.md).
