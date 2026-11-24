# depth3d 插件开发指南

这篇文档帮你用 10 分钟写出一个能在 depth3d 里运行的视频来源插件，并在电脑上完成自测与打包。

权威协议规范请直接查阅：
- 完整协议与 API 参考：[docs/specs/plugin-protocol.md](specs/plugin-protocol.md)
- 共用 JSON Schema（App 与命令行工具同源校验）：[docs/specs/plugin-schema/](specs/plugin-schema/)
- 覆盖全部能力的参考实现：[plugins/reference/](../plugins/reference/)
- 真实服务端对接示例（Jellyfin）：[plugins/jellyfin/](../plugins/jellyfin/)

---

## 1. 插件是什么

在 depth3d 里，**插件只负责提供数据，不写界面**。插件是用 ES2020 编写的单文件 JavaScript 模块，运行在头显内置的 QuickJS 沙箱中：

- 不能访问本地文件系统、相册或其他插件的数据；
- 所有网络请求都经过宿主的 `ctx.fetch` 网关，严格受 `manifest.json` 声明的域名与局域网权限约束；
- 最终交付物是一个 `.d3plugin` 压缩包（标准 zip），结构如下：

```text
my-plugin/
├── manifest.json        # 必需：元信息、权限、能力、配置表单、登录方式
├── main.js              # 必需：ES2020 模块，默认导出插件对象
├── icon.png             # 可选：256×256 图标
└── locales/
    └── zh-CN.json       # 可选：manifest 与 ctx.t() 的多语言文案
```

---

## 2. 准备开发工具 `d3plugin`

仓库自带与头显端同语义的命令行开发工具 `tools/plugin-kit/`（内置 QuickJS 引擎与共用 JSON Schema 校验器，需要 Node.js 20+）：

```bash
cd tools/plugin-kit
npm install
# 方式一：直接用相对路径运行
node ./bin/d3plugin.js --help
# 方式二：链接成全局命令 d3plugin
npm link
```

---

## 3. 十分钟写出你的第一个插件

### 第一步：创建目录与 `manifest.json`

```bash
mkdir -p my-media-plugin/locales
```

创建 `my-media-plugin/manifest.json`：

```json
{
  "api": 1,
  "id": "com.example.mymedia",
  "name": "$t:name",
  "version": "1.0.0",
  "author": "YourName",
  "description": "$t:desc",
  "license": "MIT",
  "minApp": "1.0.0",
  "multiInstance": true,
  "capabilities": ["search", "subtitles"],
  "permissions": {
    "hosts": [],
    "userHosts": true,
    "lan": true
  },
  "config": [
    {
      "key": "server",
      "type": "url",
      "label": "$t:server_label",
      "required": true,
      "placeholder": "http://192.168.1.20:8080"
    }
  ],
  "auth": "none"
}
```

创建 `my-media-plugin/locales/zh-CN.json`：

```json
{
  "name": "我的局域网片库",
  "desc": "示例媒体源插件",
  "server_label": "服务器地址",
  "need_server": "请先在设置中填写服务器地址。"
}
```

要点说明：
- `id` 使用反向域名，安装后不可变；`author` 可选；
- `version` 使用 semver，同来源发布更新时必须严格大于旧版本；同 `id` 不同来源视为替换而非更新，需用户确认且不继承原实例数据；
- `permissions.hosts` 支持精确域名与前缀通配 `*.cdn.example.com`；`lan: true` 仅放开 `hosts` 与 `userHosts` 中位于局域网的主机，不放开整个局域网；用户在 `type: "url"` 配置项填写的主机允许使用 HTTP；无论如何都禁止访问回环、链路本地（`169.254/16`、`fe80::/10`）与保留地址（`240/4`、`255.255.255.255` 等）；
- `permissions.userHosts: true` 允许访问用户在 `type: "url"` 配置项里填写的服务器主机。

### 第二步：实现 `main.js`

每个插件必须通过 `export default { ... }` 导出 4 个核心函数：`home`、`browse`、`detail`、`playback`。所有函数都可以是 `async`。

```js
function serverBase(ctx) {
  const url = (ctx.config.server || '').trim().replace(/\/+$/, '');
  if (!url) throw new ctx.Error('NeedsSetup', ctx.t('need_server'));
  return url;
}

export default {
  async home(ctx) {
    const base = serverBase(ctx);
    const resp = await ctx.fetch(`${base}/api/home`);
    if (resp.status !== 200) {
      throw new ctx.Error('Unavailable', '暂时连不上媒体服务器，请检查网络。');
    }
    const data = resp.json();
    return [
      {
        id: 'recent',
        title: '最近添加',
        style: 'poster',
        more: 'all',
        items: data.items.map((it) => ({
          id: String(it.id),
          kind: 'movie',
          title: it.title,
          poster: it.posterUrl,
          year: it.year,
          durationMs: it.durationMs,
        })),
      },
    ];
  },

  async browse(ctx, { categoryId = '', cursor = null } = {}) {
    const base = serverBase(ctx);
    const resp = await ctx.fetch(
      `${base}/api/list?cat=${encodeURIComponent(categoryId)}&cursor=${encodeURIComponent(cursor || '')}`
    );
    const data = resp.json();
    return { items: data.items, next: data.nextCursor || null };
  },

  async detail(ctx, { itemId }) {
    const base = serverBase(ctx);
    const resp = await ctx.fetch(`${base}/api/items/${encodeURIComponent(itemId)}`);
    if (resp.status === 404) {
      throw new ctx.Error('NotFound', '这个视频已经不存在了。');
    }
    return resp.json();
  },

  async playback(ctx, { itemId, quality }) {
    const base = serverBase(ctx);
    const resp = await ctx.fetch(
      `${base}/api/play/${encodeURIComponent(itemId)}?q=${encodeURIComponent(quality || 'auto')}`
    );
    const data = resp.json();
    return {
      url: data.streamUrl,
      mime: data.mime,
      headers: data.headers,
      subtitles: data.subtitles,
    };
  },

  async search(ctx, { query, cursor }) {
    const base = serverBase(ctx);
    const resp = await ctx.fetch(
      `${base}/api/search?q=${encodeURIComponent(query)}&cursor=${encodeURIComponent(cursor || '')}`
    );
    const data = resp.json();
    return { items: data.items, next: data.nextCursor || null };
  },
};
```

### 第三步：在电脑上调试与校验

```bash
# 运行单个接口查看输出（与头显用同一套 QuickJS 引擎与权限网关）
d3plugin run home ./my-media-plugin --config '{"server":"http://192.168.1.20:8080"}'
d3plugin run playback ./my-media-plugin --item movie-1 --quality 1080p --config '{"server":"http://192.168.1.20:8080"}'

# 全面自检：校验 manifest、QuickJS 模块加载、导出函数、沙箱隔离与数据结构 Schema
d3plugin check ./my-media-plugin

# 同时检查仓库内置的参考插件和 Jellyfin 插件
d3plugin check plugins/reference plugins/jellyfin
```

### 第四步：打包与头显联调

```bash
# 打包成 .d3plugin 并输出 SHA-256（用于填入插件仓库 index.json）
d3plugin pack ./my-media-plugin -o dist/my-media-plugin-1.0.0.d3plugin

# 开发模式：启动本地实时打包服务，在头显「播放源 › 插件 › 开发者」填入本机地址
d3plugin serve ./my-media-plugin --port 8787
```

`d3plugin serve` 每次收到请求都会从磁盘实时重新打包，暴露：
- `/index.json`：实时附带最新 `sha256` 的单插件仓库索引；
- `/manifest.json`：当前 manifest；
- `/plugin.d3plugin`：实时打包的 `.d3plugin`。

---

## 4. `ctx` 宿主 API 速查

完整说明见 [docs/specs/plugin-protocol.md §5](specs/plugin-protocol.md#5-app-给插件的-apictx)：

| API | 用途与注意点 |
|---|---|
| `ctx.config` | 只读（已冻结）的用户配置对象 |
| `ctx.instanceId` | 当前实例的唯一 ID，可用于区分多实例状态 |
| `ctx.fetch(url, opts)` | 沙箱 HTTP 请求，支持 `method`、`headers`、`body`、`timeoutMs`、`redirect`（`follow`/`manual`/`error`），显式 `Cookie` 头与实例 Cookie 罐合并（同名显式优先）；禁止设置 `Host` 头与 HTTPS 降级重定向；单响应体上限 8 MB、每实例并发上限 4、在途字节上限 16 MB；返回 `{ status, ok, url, headers, text(), json(), bytes() }`（`bytes()` 返回 `ArrayBuffer`） |
| `ctx.storage` | **异步**（必须 `await`）每实例独立加密存储：`await ctx.storage.get(k)`、`set(k, v)`、`remove(k)`、`keys()`，值为任意 JSON，总大小上限 1 MB；登录令牌请存这里 |
| `ctx.cookies` | **异步**（必须 `await`）每实例独立加密 Cookie 罐（上限 256 KB）：`await ctx.cookies.get(url)` 返回可直接填入 `Cookie` 头的字符串，`await ctx.cookies.set(url, cookie)`，`await ctx.cookies.clear()` |
| `ctx.crypto` | `md5`、`sha1`、`sha256`、`hmac(algo, key, data)`、`aes.encrypt/decrypt(mode, key, iv, data, aad?)`（支持 `CBC` 与 `GCM`）、`rsa.encrypt(publicKeyPem, data, padding?)`、`randomBytes(n)`、`base64.encode/decode`、`hex.encode/decode` |
| `ctx.parse` | `html(text)` / `xml(text)` 返回惰性只读 DOM 句柄（单次调用最多 8 个文档、单个上限 4 MB），支持 `querySelector` / `querySelectorAll` / `selectFirst` / `select` / `attr` / `attrs` / `text` / `html` / `outerHtml` / `children` |
| `ctx.log` | `debug` / `info` / `warn` / `error`，自动加前缀 `depth3d: plugin <id>` 并抹掉常见凭据和密码 |
| `ctx.device` | `{ appVersion, locale, model, maxWidth, maxHeight, codecs, hdr }`，用于按设备解码能力选流 |
| `ctx.t(key, vars?)` | 读取 `locales/*.json` 中的翻译文案，支持 `{{name}}` 占位符 |
| `new ctx.Error(code, msg?, opts?)` | 抛出协议定义错误；`RateLimited` 可传 `{ retryAfterSec: 30 }` |

---

## 5. 错误处理与运行时约束

- **不要抛出原始技术异常给用户看**：业务可预期错误请抛出 `new ctx.Error(code, '给用户看的简体中文短句')`，可选的 `code` 为：`NeedsAuth`、`AuthFailed`、`NeedsSetup`、`Unavailable`、`NotFound`、`Forbidden`、`RateLimited`、`Unsupported`、`Internal`。
- **无状态假设**：宿主随时可能销毁并重建 QuickJS 实例（例如超时或超过 64 MB 堆内存限制后）。跨调用必须持久化的凭据或会话 ID 请写入 `ctx.storage`。
- **超时、内存与取消**：普通调用超时 15 秒，`playback` 超时 30 秒，超时后宿主取消在途网络请求、映射为 `Unavailable` 并重建运行时；单实例堆上限 64 MB。调用返回的 Promise 落定时，未完成的定时器和 `fetch` 会立即取消。
- **音视频分离流、图片与地址过期**：当片源音视频分离时，在 `PlaybackInfo` 里返回 `dash: { video, audio }` 且**必须提供 `durationMs`**（带 `initRange`/`indexRange` 时 App 自动合成带 `dvb:priority` 的标准 MPD 支持字节范围拖动与按顺序容灾切换，否则使用 `MergingMediaSource` 按设备编码与所选音轨合并并自动切换 `backupUrls`）。`live: true` 不可与 `dash` 同时使用（直播请返回 `url` 如 HLS）。播放器拉取的所有流地址、清单内地址、分片、字幕和图片地址均在每次请求与每跳重定向经过网络策略校验，跨源重定向时剥离敏感头。如果播放链接带过期时间，请填写 `expiresAt`（毫秒时间戳），播放器会在过期前 60 秒自动重取。
- **DRM 与包体积**：协议 v1 不支持 Widevine 等 DRM，遇到加密流请明确抛出 `new ctx.Error('Unsupported', '该片源受 DRM 保护，暂不支持播放。')`。单个 `.d3plugin` 安装包上限 8 MB，仓库地址必须使用 HTTPS。

---

## 6. 发布到插件仓库

将 `.d3plugin` 放到 HTTPS 静态托管后，按 [docs/specs/plugin-protocol.md §8](specs/plugin-protocol.md#8-分发) 生成仓库索引 `index.json`（格式受 [repository.schema.json](specs/plugin-schema/repository.schema.json) 约束）：

```json
{
  "name": "我的插件仓库",
  "plugins": [
    {
      "id": "com.example.mymedia",
      "name": "我的局域网片库",
      "version": "1.0.0",
      "description": "示例媒体源插件",
      "url": "https://example.com/plugins/com.example.mymedia-1.0.0.d3plugin",
      "sha256": "<d3plugin pack 输出的 64 位十六进制 SHA-256>",
      "minApp": "1.0.0"
    }
  ]
}
```
