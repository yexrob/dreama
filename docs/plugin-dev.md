# depth3d 插件开发指南

这篇文档帮你用 10 分钟写出一个能在 depth3d 里运行的视频来源插件，并在电脑上完成自测与打包。

权威协议规范请直接查阅：
- 完整协议与 API 参考：[docs/specs/plugin-protocol.md](specs/plugin-protocol.md)
- 宿主播放器/播放墙接入规范（含 S7 异常分派与 `PluginPlayback`/`PluginImages` 入口）：[docs/specs/plugin-host-integration.md](specs/plugin-host-integration.md)
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

### 第四步：在头显上加载与调试

写好插件后，有两条无需架设 HTTPS 插件仓库就能在头显上安装和调试的路：

#### 方式一：开发者模式（从电脑实时加载，开发调试推荐）

让电脑和头显连在**同一个 Wi‑Fi**，在电脑上启动实时打包服务：

```bash
d3plugin serve ./my-media-plugin --port 8787
```

`d3plugin serve` 每次收到请求都会从磁盘实时重新打包，仅暴露 `/`、`/index.json`、`/manifest.json`、`/plugin.d3plugin`、`/health` 五个固定路径，并同时在局域网 UDP 端口 `18787` 响应头显的自动发现（加 `--no-discover` 可关闭自动发现；只应答来自局域网私有地址的探测）。启动后终端会打印本机局域网地址（例如 `http://192.168.1.20:8787`）。

在头显里操作：

1. 打开「设置 › 播放源与插件 › 插件」页签，滑到底部点「开发者」。
2. **找到电脑**（二选一）：
   - 等待上方「在局域网里找到的开发服务器」自动列出电脑上的插件（也可点「重新搜索」），直接点「加载」；
   - 或点右上角「输入电脑地址」，用手机扫码（或切到头显键盘）填入电脑的局域网地址（支持 `192.168.1.20:8787`、`http://192.168.1.20:8787`、IPv6 ULA 等，仅允许 RFC1918 / IPv6 ULA 私有地址，严格锁定协议与 `主机:端口`，拒绝回环/链路本地/公网及任何跨主机/端口/协议重定向）。
3. 核对确认卡片上的权限与「开发版 · 来自 <电脑地址>，未经仓库校验」提示，点「确认安装」。
4. 回到「播放源」页签添加该插件的播放源实例后即可在首页/媒体库联调。
5. **改代码后一键重载**：在开发者页（或该插件的详情页）点「重新加载」。**不需要改 `manifest.json` 的版本号**，只要内容（SHA-256）变了就会生效：
   - 运行时重建，已建实例的配置、`ctx.storage` 和 Cookie 全部保留；首页与媒体库会自动刷新；
   - 权限未变或缩小时直接重载，权限扩大（`hosts`/`userHosts`/`lan` 任一增加）时重新弹出确认卡片，确认后立即生效；
   - 如果电脑换了局域网 IP，在开发者页用新地址加载同 `id` 开发版会弹出「切换开发服务器地址」确认，确认后保留已有播放源、配置与本地数据；
   - 如果电脑关机或暂时连不上，头显会保留当前已装的版本并提示可从开发者页用新地址加载；
   - 开发者页右侧直接展示该插件最近日志（重载后自动滚到最新），遇到 JS 语法错误、`manifest.json` 校验失败或 `init` 抛错时会展示经脱敏（自动抹除密码/令牌/Cookie）的错误消息与行号。

> 注意：开发版插件带有「开发版」标识且不参与仓库自动更新；若头显里已装同 `id` 的内置、仓库或本地文件版，装开发版（或反过来换回正式版）会弹出显式替换确认并清除原实例数据。

#### 方式二：从文件安装（离线 `.d3plugin` 包或单个 HTTPS 直链）

先打包生成 `.d3plugin`：

```bash
d3plugin pack ./my-media-plugin -o dist/my-media-plugin-1.0.0.d3plugin
```

在头显里打开「设置 › 播放源与插件 › 插件 › 添加插件 › 从文件安装」，有三种安装方式（均会在预览阶段校验 8 MB 体积上限、zip 完整性、`manifest.json` 和 `minApp`，确认后严格安装预览时读入的同一份字节，不重新读盘或重新下载）：

1. **通过 `adb push` 放到应用专属目录（推荐，所有 Android 版本免存储权限）**：
   ```bash
   # Release 包
   adb push dist/my-media-plugin-1.0.0.d3plugin /sdcard/Android/data/dev.dreama.player/files/plugins/
   # Debug 包（applicationIdSuffix 为 .debug）
   adb push dist/my-media-plugin-1.0.0.d3plugin /sdcard/Android/data/dev.dreama.player.debug/files/plugins/
   ```
   推送后在头显「从文件安装」页面点「刷新」，直接点对应 `.d3plugin` 即可安装。
2. **放进头显 `Download/` 目录或点「选择文件」**：
   - 在 **Android 10** 设备上，**授予存储权限后**页面会自动列出 `Download/` 里的 `.d3plugin`；未授权时页面会提示点「允许访问」或直接点「选择文件」。
   - 在 **Android 11+** 分区存储设备上，系统限制直接枚举 `Download/` 里的非媒体文件，若未列出请直接点右上角「选择文件」（系统文件选择器 `ACTION_OPEN_DOCUMENT`）选取 `.d3plugin`。
3. **粘贴单个 `.d3plugin` 的 HTTPS 地址**：点「粘贴 HTTPS 地址」，用手机扫码粘贴以 `https://` 开头的单包地址，头显下载校验后确认安装（仅支持 HTTPS，不允许私网地址）。

---

## 4. `ctx` 宿主 API 速查

完整说明见 [docs/specs/plugin-protocol.md §5](specs/plugin-protocol.md#5-app-给插件的-apictx)：

| API | 用途与注意点 |
|---|---|
| `ctx.config` | 只读（已冻结）的用户配置对象 |
| `ctx.instanceId` | 当前实例的唯一 ID，可用于区分多实例状态 |
| `ctx.fetch(url, opts)` | 沙箱 HTTP 请求，支持 `method`、`headers`、`body`、`timeoutMs`（**整条重定向链总时限**）、`redirect`（`follow`/`manual`/`error`），显式 `Cookie` 头与实例 Cookie 罐合并（同名显式优先）；禁止设置 `Host` 头与 HTTPS 降级重定向；跨源重定向仅保留 `Accept`、`Accept-Language`、`User-Agent`、`Range`，其余头（含 `Authorization`、`Cookie`、`Referer` 与插件自定义头）全部剥离；**请求上限**：URL ≤ 8 KB、请求头总计 ≤ 32 KB、单请求体 ≤ 2 MB、排队及在途请求体总量 ≤ 8 MB、并发执行 ≤ 4 且排队上限 16（超限立即拒绝）；**响应上限**：单响应体 ≤ 8 MB、在途响应总字节 ≤ 16 MB；返回 `{ status, ok, url, headers, text(), json(), bytes() }`（`bytes()` 返回 `ArrayBuffer`）。**响应体为一次性消费**：首次调用 `text()`/`json()`/`bytes()` 后即释放宿主缓冲与在途字节配额，再次读取会抛出 `TypeError('Response body already consumed')`（App 与 `d3plugin` 行为一致） |
| `ctx.storage` | **异步**（必须 `await`，`d3plugin run`/`check` 发现漏写 `await` 会直接报错并指出调用点）每实例独立加密存储：`await ctx.storage.get(k)`、`set(k, v)`、`remove(k)`、`keys()`，值为任意 JSON，总大小上限 1 MB（写入前按 UTF-8 字节数检查配额）；登录令牌请存这里 |
| `ctx.cookies` | **异步**（必须 `await`，`d3plugin run`/`check` 同样检测漏写 `await`）每实例独立加密 Cookie 罐（上限 256 KB）：`await ctx.cookies.get(url)` 返回可直接填入 `Cookie` 头的字符串，`await ctx.cookies.set(url, cookie)`，`await ctx.cookies.clear()`；遵循 RFC 6265（`Max-Age` 优先于 `Expires`，忽略公共后缀或 Domain 不匹配的 Cookie 并记录 `WARN` 日志） |
| `ctx.crypto` | `md5`、`sha1`、`sha256`、`hmac(algo, key, data)`、`aes.encrypt/decrypt(mode, key, iv, data, aad?)`（支持 `CBC` 与 `GCM`）、`rsa.encrypt(publicKeyPem, data, padding?)`、`randomBytes(n)`、`base64.encode/decode`、`hex.encode/decode` |
| `ctx.parse` | `html(text)` / `xml(text)` 返回惰性只读 Document 根句柄（`tag === '#document'`，支持 `doc.close()` 立即释放文档内存，调用结束后宿主也会自动释放本次调用创建的全部文档；同一文档内重复查询命中的同一元素会复用同一句柄，句柄数上限 = 元素数 + 1）。HTML 遵循 WHATWG HTML5 树构建（App 用 Jsoup、`d3plugin` 用 `parse5`），标签和属性名大小写不敏感、重复属性保留首个、`html()`/`outerHtml()` 无缩进且实体转义两端一致；XML 标签和属性名严格区分大小写并保留未定义实体如 `&eacute;`；`querySelector`/`selectFirst`/`querySelectorAll`/`select` 仅匹配后代元素、不匹配自身；`text` 在 HTML 中忽略 `<script>`/`<style>`、合并 ASCII 空白与 `&nbsp;` 且保留 U+2003 等 Unicode 空白；缺失属性 `attr()` 返回 `null`。**资源上限（两端一致，超限统一抛 `DomLimitExceeded`）**：单文档输入文本 ≤ 2 MB、标签名与属性名长度 ≤ 256 字符（解析时拒绝）、单文档估算内存 ≤ 32 MB（流式解析阶段对绝大多数超限输入提前中止，解析结束后再遍历最终 DOM 树精确重算一次以覆盖 HTML5 表格外挪动 / foster parenting 与格式化重建等重排节点，并以精确值记账）；每个 VM 同时存活文档数 ≤ 8、单 VM 所有存活文档估算字节 + 句柄表 + 类名缓存合计 ≤ 48 MB，进程级全部插件 VM 合计 ≤ 64 MB（循环解析多个文档时可调 `doc.close()` 回收配额，调用结束或 VM 销毁时自动归还）；**每个取出的元素句柄约占 184 字节并计入上述句柄表/VM 预算，因此在 19 万元素级别的大页面上调用 `select('*')` 这类把全部元素取成句柄的查询会触发句柄表上限，建议使用更窄的选择器（如 `tr > td a[href]`、`.item .title`、`SegmentTimeline > S`）**；`html()`/`outerHtml()`/`text()` 以及转义后的 `attrsJson()` 序列化输出 ≤ 8 MB；选择器文本 ≤ 4 KB、逗号分组 ≤ 32、每组复合段 ≤ 32、`:not`/`:is` 嵌套深度 ≤ 4、标识符/属性值长度 ≤ 256、`:nth-*` 参数范围 `±1,000,000`；单次查询步数预算 200 万步（含子节点、属性列表、属性字符比较与类名扫描）且查询记忆表内存预算 16 MB，并受调用超时约束。`d3plugin` 采用与头显端一致的宿主估算模型（保证本地与真机对同一页面的接受/拒绝结果一致），同时另设 512 MB V8 兜底上限保护本地 Node 进程。**支持的 CSS 选择器子集**（App 与 `d3plugin` 严格一致）：类型/通配/命名空间（`div`、`*`、`dvb|x`、`*|x`、`|x`）、`#id`、`.class`、属性选择器（`[a]`/`=`/`~=`/`|=`/`^=`/`$=`/`*=` 及 `i`/`s` 标志）、组合子（空格、`>`、`+`、`~`、`,`）以及标准结构伪类 `:first-child`、`:last-child`、`:only-child`、`:nth-child(...)`、`:nth-last-child(...)`、`:first-of-type`、`:last-of-type`、`:only-of-type`、`:nth-of-type(...)`、`:nth-last-of-type(...)`、`:empty`、`:root`、`:not(...)`、`:is(...)`。**不支持 Jsoup 专有选择器**（如 `:eq()`、`:lt()`、`:gt()`、`:contains()`、`:matches()` 等），遇到会直接抛错 |
| `ctx.log` | `debug` / `info` / `warn` / `error`，单条上限 4 KB（脱敏前截断）、环形缓冲上限 200 条且总计不超过 256 KB，自动加前缀 `depth3d: plugin <id>` 并抹掉常见凭据和密码 |
| `ctx.device` | `{ appVersion, locale, model, maxWidth, maxHeight, codecs, hdr }`，用于按设备解码能力选流 |
| `ctx.t(key, vars?)` | 读取 `locales/*.json` 中的翻译文案，支持 `{{name}}` 占位符 |
| `new ctx.Error(code, msg?, opts?)` | 抛出协议定义错误；`RateLimited` 可传 `{ retryAfterSec: 30 }` |

### `ctx.parse.html` 已知的标签乱序树差异（App Jsoup 与 `d3plugin` `parse5`）

在结构良好的 HTML 文档上，App（Jsoup）与开发工具（`parse5`）的 DOM 树、选择器匹配与 `html`/`outerHtml`/`text` 序列化完全一致；但在以下 **3 类严重乱序/畸形 HTML** 中，两套解析器存在已知的树结构差异（代表性用例已录入 [`docs/specs/plugin-schema/dom-vectors.json`](specs/plugin-schema/dom-vectors.json) 并标记为 `knownDifference: true`，两端均持续回归断言）：

1. **`<select>` 内的非 `<option>` 标签**：例如 `<select><div><p>a</p></div><option>b</option></select>` 或 `<select><textarea>raw</textarea></select>`。`parse5` 严格按 WHATWG `in select` 插入模式忽略非法开始标签（并在 `<textarea>`/`<input>` 处提前闭合 `<select>`）；Jsoup 会把 `<div>`/`<p>`/`<textarea>` 保留为 `<select>` 的子节点。
2. **`<html>` 之前的空白与 `</body>` 之后的文本节点位置**：例如 `</em> </span><p>a</p>`、`<html><body><p>a</p></body>   ` 或 `<html><body><p>a</p></body>tail`。Jsoup 会在 `<html>` 前保留前导空白文本节点、在 `</body>` 后把尾随文本（包括空白与非空白文本）挂在 `<html>` 下；`parse5` 按 WHATWG 规范忽略 `<html>` 前的空白并把 `</body>` 后的文本归入 `<body>` 内（不影响 `doc.text`，仅在读取 `doc.outerHtml` 或 `body.outerHtml` 时可见）。
3. **乱序嵌套下的 `<template>`**：例如 `<select><nobr><template>...</template></nobr></select>` 或 `<template><table><form></form></table></template>`。在常规文档里两端的 `<template>` 内容均直接可查询且表现一致；但在 `<select>` / `<table>` 等畸形标签交错嵌套时，`parse5` 严格按 WHATWG 模板插入模式剥离非法外层标签或空 `<form>`，而 Jsoup 可能保留原嵌套节点。

编写插件时请避免依赖上述畸形 HTML 的树结构细节，优先按具体业务标签或属性选择目标元素。

---

## 5. 实现搜索与联想（`search` / `suggest`）

如果播放源支持搜索，在 `manifest.json` 的 `capabilities` 中加入 `"search"`，并在 `main.js` 中导出 `search(ctx, { query, cursor, kind })`；输入联想 `suggest(ctx, { query })` 为**完全可选**导出，不实现时宿主与 `d3plugin check` 均会静默跳过，不会报错或刷日志。

### `search(ctx, { query, cursor, kind })`

- **返回结构**：必须返回 `ItemPage`（`{ items: Item[], next: string | null }`），元素格式与 `browse` 一致。
- **空查询处理**：当 `query` 为空字符串或仅包含空白字符时，应**在登录/会话检查之前**直接返回 `{ items: [], next: null }`，不要向远端发空查询或返回全库数据。
- **排序由插件决定**：宿主按播放源分区域展示结果，**永远不会**对单个插件返回的 `items` 重排；请直接把最相关的结果排在前面。
- **分页游标**：没有下一页时返回 `next: null`。下一页游标不可重复（包括跨多页的游标循环），且第 2 页不可忽略 `cursor` 重复返回第 1 页内容（宿主也会对条目去重、跳过全重复页并截断循环游标）。进入单源「展开」视图时，宿主直接复用第 1 页结果并在接近底部时按 `cursor` 翻页。
- **可选 `kind` 筛选**：宿主在用户切换类型标签时会传入 `kind`（如 `'movie'`、`'series'`、`'video'`）；**不支持筛选的插件可以直接忽略 `kind`**，并且遇到未知/未来新增的 `kind` 值时**不可抛错**（宿主会在本地按类型做二次过滤；若第 1 页本地过滤后为空或不足且存在 `next`，宿主会在同一 10 秒预算内自动向后补拉最多 3 页；「剧集」筛选同时包含 `series` 与 `episode`）。
- **时延、并发与超时预算**：
  - 宿主在用户提交搜索后**同时并发请求所有**支持搜索的播放源（不设 4 源排队上限，避免排队吃掉超时预算）；**300 ms** 内到达的源按配置顺序批量落位，300 ms 之后到达的源按完成顺序追加到底部并 200 ms 淡入（不重排已有行）；若 3 秒后仍有源未回，末尾才会显示「还在搜」。
  - 单源端到端搜索超时上限为 **10 秒**（含需要本地 `kind` 过滤补拉的额外页）；超时后宿主取消该源请求并展示单行重试提示，不影响其他播放源。
- **隐私脱敏**：在 `search` 与 `suggest` 调用期间，宿主会自动把本次查询词（原串、规范化串及 URL 编码形式）从插件 `ctx.log.*` 与宿主异常日志中脱敏，但插件仍应避免主动把用户查询词拼进外发诊断或无关存储。
- **错误表达**：
  - 未登录或登录失效请抛出 `new ctx.Error('NeedsAuth', '...')`，宿主会在该源原位展示「登录」按钮，用户登录成功后自动回到搜索页并只重搜这一个源；
  - 触发限流请抛出 `new ctx.Error('RateLimited', '...', { retryAfterSec: 30 })`，宿主会在该源行内显示倒计时后再允许重试；
  - 网络或服务异常请抛出 `new ctx.Error('Unavailable', '...')`，宿主会在该源行内展示「重试」按钮。

### `suggest(ctx, { query })`（可选）

- 返回去重后的字符串数组 `string[]`（建议不超过 8 条）；空查询时在登录检查前直接返回 `[]`。
- 宿主在用户输入停止 **250 ms** 且达到最小长度（中文 ≥ 1 字，英文/数字 ≥ 2 字符）后才会并发调用，单源超时 **800 ms**，任何失败、超时或未实现均静默忽略，不弹窗、不中断输入。
- **不触发冷启动、不抢占在途调用**：若该插件的 QuickJS VM 尚未初始化完成，或该源此刻已有其他调用（如 `home`、`search`、`detail`、`playback`）在执行，宿主会直接跳过本次该源的 `suggest`，绝不因联想触发冷启动 `init(ctx)` 或打断已有请求。
- 联想接口应尽量轻量（例如调用服务端 hints/autocomplete 接口，避免做重型全文检索）。

### 本地自检

运行 `d3plugin check ./my-media-plugin` 时，对声明了 `"search"` 能力的插件会自动校验：
1. 空字符串与纯空白查询返回 `{ items: [], next: null }`；
2. 返回值符合 `ItemPage` Schema 且所有图片 URL 满足网络权限策略；
3. 若返回 `next` 游标，自动连续拉取后续最多 3 页，校验 Schema、第 2 页未忽略 `cursor` 重复返回第 1 页内容，以及不存在任意长度的游标循环；
4. 传入已知 `kind`（`'movie'`）与未知 `kind` 均不报错且返回合法 `ItemPage`；
5. 若导出 `suggest` 则验证返回值 Schema 与空查询返回 `[]`；未导出时静默通过。

---

## 6. 错误处理与运行时约束

- **不要抛出原始技术异常给用户看**：业务可预期错误请抛出 `new ctx.Error(code, '给用户看的简体中文短句')`，可选的 `code` 为：`NeedsAuth`、`AuthFailed`、`NeedsSetup`、`Unavailable`、`NotFound`、`Forbidden`、`RateLimited`、`Unsupported`、`Internal`。普通 `Error`（哪怕消息里包含 `timeout`）一律归类为 `Internal`，只有宿主真实超时或中断才映射为 `Unavailable`。
- **VM 复用与无状态假设**：普通的调用方取消（如输入下一字取消 `suggest`、800 ms 联想超时、提交新搜索、离开页面）会取消在途 `ctx.fetch` 和定时器并等待 JS 异步自然退出，**不会**销毁 QuickJS VM；仅当 JS 无法在短时间内退出而被强行中断（例如死循环或永不落定的 Promise）、触发宿主调用超时（15 s / 30 s）或超过 64 MB 堆内存限制时，宿主才会销毁并在下次调用时重建 VM（插件主动抛出 `new ctx.Error('Unavailable', ...)` 不会触发重建）。跨调用必须持久化的凭据或会话 ID 请写入 `ctx.storage`。
- **超时、内存与资源上限**：普通调用超时 15 秒，`playback` 超时 30 秒，超时后宿主取消在途网络请求、映射为 `Unavailable` 并重建运行时；单实例堆上限 64 MB；单次调用返回值序列化上限 8 MB（超出映射为 `Internal`）；同时活跃的 `setTimeout` 上限 256 个。调用返回的 Promise 落定时，未完成的定时器和 `fetch` 会立即取消。
- **音视频分离流、图片与地址过期**：当片源音视频分离时，在 `PlaybackInfo` 里返回 `dash: { video, audio }` 且**必须提供 `durationMs`**（带 `initRange`/`indexRange` 时 App 自动合成带 `dvb:priority` 的标准 MPD 支持字节范围拖动与按顺序容灾切换，否则使用 `MergingMediaSource` 按设备编码与所选音轨合并并支持打开失败与播放中途断流自动切换 `backupUrls`）。`live: true` 不可与 `dash` 同时使用（直播请返回 `url` 如 HLS）。播放器拉取的所有流地址、清单内地址、分片、字幕和图片地址均在每次请求与每跳重定向经过网络策略校验，跨源重定向剥离敏感头，且与主地址不同源的字幕不继承 `PlaybackInfo.headers`（只带字幕自身的 `headers`）。如果播放链接带过期时间，请填写 `expiresAt`（毫秒时间戳），播放器会在过期前 60 秒自动重取。
- **DRM 与包体积**：协议 v1 不支持 Widevine 等 DRM，遇到加密流请明确抛出 `new ctx.Error('Unsupported', '该片源受 DRM 保护，暂不支持播放。')`。单个 `.d3plugin` 安装包上限 8 MB，仓库地址必须使用 HTTPS。

---

## 7. 发布到插件仓库

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
