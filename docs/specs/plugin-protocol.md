# depth3d 插件协议（v1）

插件让第三方把一个视频来源接进 depth3d：媒体服务器（Jellyfin、Emby）、网盘、视频网站（如 bilibili）、直播源。插件只负责「有什么可看、从哪里拉流」，播放、3D、界面全部由 App 负责。这份文档是协议的权威文本，App 内的实现和所有插件都以它为准。

## 1. 原则
- **插件只出数据，不出界面。** 插件返回分区、条目、播放地址、表单描述；App 用自己的设计把它们画出来。因此所有来源在头显里观感一致，插件作者不需要懂 VR。
- **沙箱。** 插件是 JavaScript，跑在 App 内嵌的 QuickJS 里：没有文件系统、没有任意网络、没有原生代码。它能访问的域名写在 manifest 里，安装时给用户看。
- **声明式能力。** 插件在 manifest 里声明自己实现了哪些可选能力，App 只在有能力时显示对应入口（搜索框、收藏、弹幕开关…）。
- **向前兼容。** 协议只加不改：新字段都是可选的，App 忽略不认识的字段，插件忽略不认识的参数。破坏性改动才升 `api` 主版本。
- **内置来源走同一套模型。** 本机视频、WebDAV、depth3d Host 用 Kotlin 写，但实现的是同一个 `MediaSource` 接口、同一套数据结构；协议里有的东西内置来源都必须能表达，反过来也一样。

## 2. 插件包
一个插件是一个 zip（扩展名 `.d3plugin`），内容：

```
manifest.json     必需
main.js           必需，入口（ES2020，单文件；作者可自行打包）
icon.png          可选，256×256
locales/zh-CN.json  可选，manifest 和表单里 `$t:key` 文案的翻译
```

### 2.1 manifest.json
```json
{
  "api": 1,
  "id": "com.example.jellyfin",
  "name": "Jellyfin",
  "version": "1.2.0",
  "author": "Example",
  "description": "连接你的 Jellyfin 服务器",
  "homepage": "https://example.com/depth3d-jellyfin",
  "license": "MIT",
  "minApp": "1.0.0",
  "multiInstance": true,
  "capabilities": ["search", "progress", "favorites", "subtitles", "danmaku", "live"],
  "permissions": {
    "hosts": ["api.example.com", "*.cdn.example.com"],
    "userHosts": true,
    "lan": true
  },
  "config": [
    { "key": "server", "type": "url",      "label": "服务器地址", "required": true, "discover": "_jellyfin._tcp" },
    { "key": "user",   "type": "text",     "label": "用户名" },
    { "key": "pass",   "type": "password", "label": "密码" },
    { "key": "quality","type": "select",   "label": "默认清晰度", "options": [{"value":"auto","label":"自动"},{"value":"1080","label":"1080P"}], "default": "auto" }
  ],
  "auth": "form"
}
```

| 字段 | 说明 |
|---|---|
| `api` | 协议主版本，现在是 `1` |
| `id` | 反向域名，全局唯一，安装后不可变 |
| `version` | semver；更新时必须变大 |
| `multiInstance` | `true` 表示可以添加多个实例（两台 Jellyfin）；视频网站类一般是 `false` |
| `capabilities` | 实现了哪些可选函数，见 §4.2 |
| `permissions.hosts` | 插件可以访问的域名白名单，支持前缀通配 `*.`；只允许 https，除非域名在 `lan` 范围 |
| `permissions.userHosts` | `true` 表示还可以访问用户在配置表单里填的 `url` 类型字段的主机（自建服务器用） |
| `permissions.lan` | 是否允许访问局域网地址和 http；默认 `false` |
| `config` | 配置表单，App 自动渲染。类型：`text`、`password`、`url`、`number`、`select`、`toggle`。`discover` 给出 mDNS 服务类型时，App 先在局域网找候选让用户点选。头显里填表优先走「手机扫码填写」 |
| `auth` | `none` / `form`（用 config 里的账号密码登录）/ `qr`（插件给一个二维码，用户用手机 App 扫，见 §4.3）/ `device`（设备码：显示网址和短码） |

## 3. 数据模型
所有 id 都是插件自己定义的不透明字符串，App 不解析。所有字段除标注「必需」外都可省略。

```ts
type Kind = 'movie' | 'series' | 'season' | 'episode' | 'video' | 'folder' | 'live' | 'collection' | 'person'

interface Item {
  id: string                 // 必需
  kind: Kind                 // 必需
  title: string              // 必需
  subtitle?: string          // 第二行：UP 主、年份 · 类型、S1E3 …
  poster?: Image             // 竖图 2:3
  thumb?: Image              // 横图 16:9（剧集单集、普通视频用它）
  backdrop?: Image
  year?: number
  overview?: string
  rating?: number            // 0–10
  durationMs?: number
  progressMs?: number        // 插件一侧记录的观看位置（有 progress 能力时）
  played?: boolean
  favorite?: boolean
  badges?: string[]          // 小角标：'4K'、'HDR'、'会员'、'直播中'
  stereo?: '2d' | 'sbs' | 'ou' | 'mvhevc' | '180' | '360'   // 已经是 3D/全景的片源，App 不再转 3D
  // 层级
  parentId?: string          // 所属剧 / 季 / 合集
  index?: number             // 第几集、第几季
  childCount?: number
  // 详情页才需要
  seasons?: Item[]           // series 的详情
  episodes?: Item[]          // season 的详情；视频网站的「分P / 选集」也用它
  related?: Item[]
  people?: { name: string, role?: string, image?: Image, id?: string }[]
  genres?: string[]
  extra?: { label: string, value: string }[]   // 详情页的附加信息行
}

type Image = string | { url: string, headers?: Record<string,string>, blurhash?: string }

interface Shelf {            // 首页的一行
  id: string
  title: string
  style?: 'poster' | 'thumb' | 'continue' | 'hero' | 'category'
  items: Item[]
  more?: string              // 有更多时给出 categoryId，行标题可点进去
}

interface Page<T> { items: T[], next?: string }   // next 是不透明的翻页游标；没有就是到底了

interface PlaybackInfo {
  // 三选一
  url?: string                         // 渐进式文件、HLS、DASH 清单
  dash?: { video: Track[], audio: Track[] }   // 音视频分离的流（bilibili 这类），App 自己合成清单
  mpd?: string                         // 插件直接给出的 DASH MPD 文本
  mime?: string                        // url 的类型提示：'application/x-mpegURL' 等
  headers?: Record<string,string>      // 拉流时带的请求头（Referer、Cookie、Authorization）
  qualities?: { id: string, label: string, selected?: boolean, locked?: string }[]  // locked：不能选的原因，如「需要大会员」
  subtitles?: { id: string, label: string, lang?: string, url: string, format: 'srt'|'vtt'|'ass'|'json', headers?: Record<string,string>, default?: boolean }[]
  audios?: { id: string, label: string, lang?: string }[]     // url 模式下由容器内音轨决定时可省略
  startMs?: number
  live?: boolean
  expiresAt?: number                   // 地址过期时间（epoch ms），过期后 App 重新调 playback()
  stereo?: Item['stereo']
  next?: string                        // 播完自动接着播的 itemId
  session?: string                     // 插件自己的播放会话 id，会在 reportProgress 里带回
}

interface Track { id: string, url: string, backupUrls?: string[], mime: string, codecs: string,
                  bandwidth: number, width?: number, height?: number, frameRate?: number,
                  quality?: string,    // 对应 qualities[].id
                  initRange?: string, indexRange?: string }   // SegmentBase 用
```

## 4. 插件要实现的函数
`main.js` 导出一个对象（`export default { ... }`）。所有函数都可以是 async。第一个参数之外，每次调用 App 都会注入当前实例的上下文 `ctx`（见 §5）。

### 4.1 必需
| 函数 | 说明 |
|---|---|
| `home(ctx): Shelf[]` | 首页分区。未登录时可以返回公开内容，也可以抛 `NeedsAuth` |
| `browse(ctx, { categoryId, cursor?, sort?, filters? }): Page<Item>` | 一个分类或文件夹里的条目。`categoryId` 为空串表示来源的根目录 |
| `detail(ctx, { itemId }): Item` | 详情。series 带 `seasons`，season 带 `episodes` |
| `playback(ctx, { itemId, quality?, audio? }): PlaybackInfo` | 播放地址。用户切换清晰度时带着 `quality` 再调一次 |

### 4.2 可选（在 `capabilities` 里声明）
| 能力 | 函数 |
|---|---|
| `search` | `search(ctx, { query, cursor?, kind? }): Page<Item>`；`suggest(ctx, { query }): string[]`（可选） |
| `progress` | `reportProgress(ctx, { itemId, positionMs, durationMs, event: 'start'|'pause'|'progress'|'stop'|'finish', session? })`；播放中约每 10 秒一次 |
| `favorites` | `setFavorite(ctx, { itemId, favorite })` |
| `played` | `setPlayed(ctx, { itemId, played })` |
| `filters` | `filters(ctx, { categoryId }): { sorts: Option[], filters: { key, label, options: Option[] }[] }` |
| `subtitles` | 只表示 `PlaybackInfo.subtitles` 会给出外挂字幕 |
| `danmaku` | `danmaku(ctx, { itemId, fromMs, toMs }): { timeMs, text, color?, mode?: 'scroll'|'top'|'bottom' }[]`，App 按时间段分批取 |
| `live` | 表示会出现 `kind: 'live'` 的条目 |
| `categories` | `categories(ctx): { id, title, icon? }[]`，媒体库左侧的分类列表；不实现时用 `browse('')` 的结果 |

### 4.3 登录
- `auth: 'form'`：App 渲染 config 表单后调用 `login(ctx): { ok: true, user?: { name, avatar? } }`，失败抛 `AuthFailed`（带给用户看的原因）。
- `auth: 'qr'`：
  1. `loginStart(ctx): { qr: string, hint?: string, expiresInSec: number, state: string }`，`qr` 是要编码进二维码的文本，App 负责画码；
  2. App 每 2 秒调 `loginPoll(ctx, { state }): { status: 'pending'|'scanned'|'done'|'expired', user? }`；
  3. `done` 之前插件自己把凭据写进 `ctx.storage`。
- `auth: 'device'`：`loginStart` 返回 `{ url, code, expiresInSec, state }`，其余同上。
- `logout(ctx)`（可选）。
- 任何函数发现凭据失效都抛 `NeedsAuth`，App 把这个来源标成「需要重新登录」并在用户点它时进入登录流程。

### 4.4 生命周期
`init(ctx)`（可选，实例创建或 App 启动后第一次用到时调一次）、`dispose(ctx)`（可选）。插件不得依赖常驻：App 随时可以销毁运行时，下次调用前重新 `init`。跨调用的状态放 `ctx.storage`。

## 5. App 给插件的 API（`ctx`）
| API | 说明 |
|---|---|
| `ctx.config` | 用户填的配置值（只读对象） |
| `ctx.instanceId` | 这个实例的 id |
| `ctx.fetch(url, { method, headers, body, timeoutMs, redirect, responseType })` | 返回 `{ status, headers, text(), json(), bytes() }`。只能访问权限范围内的主机；自动带实例自己的 cookie 罐；不能设置 `Host`；响应体上限 16 MB |
| `ctx.storage.get/set/remove/keys` | 每实例的键值存储（值是 JSON），加密落盘，上限 1 MB。凭据放这里 |
| `ctx.cookies.get(url) / set(url, cookie) / clear()` | 实例的 cookie 罐；`PlaybackInfo.headers` 需要 Cookie 时从这里取 |
| `ctx.crypto` | `md5`、`sha1`、`sha256`、`hmac`、`aes`（CBC/GCM）、`rsa`（公钥加密）、`randomBytes`、`base64`、`hex` |
| `ctx.parse.xml(text)` / `ctx.parse.html(text)` | 返回可用选择器查询的只读文档 |
| `ctx.log.debug/info/warn/error` | 进 App 日志（前缀 `depth3d: plugin <id>`），App 会抹掉看起来像凭据的值 |
| `ctx.device` | `{ appVersion, locale, model, maxWidth, maxHeight, codecs: ['avc','hevc','av1'], hdr: boolean }`，供插件选流 |
| `ctx.t(key, vars?)` | 取 locales 里的文案 |

没有的东西：定时器只有 `setTimeout`（单次调用内）；没有 `eval`/`Function` 以外的动态加载；没有 WebSocket（v1）；没有文件、相册、其他插件的数据。

## 6. 错误
插件抛 `new ctx.Error(code, message?)`，`code` 是下面之一；别的异常一律当 `Internal`。`message` 是给用户看的中文短句，可省略，省略时 App 用自己的文案。

| code | App 的表现 |
|---|---|
| `NeedsAuth` | 来源标「需要登录」，引导登录 |
| `AuthFailed` | 登录页显示原因 |
| `NeedsSetup` | 引导去填配置 |
| `Unavailable` | 这一行显示「连不上」和重试 |
| `NotFound` | 「这个内容已经不在了」，并从继续观看里移除 |
| `Forbidden` | 显示 message（地区限制、需要会员、需要购买） |
| `RateLimited` | 稍后自动重试，带 `retryAfterSec` |
| `Unsupported` | 这个内容类型放不了（DRM 等） |
| `Internal` | 「这个插件出错了」，可查看日志、停用插件 |

## 7. 限制与安全
- 单次调用默认 15 秒超时（`playback` 30 秒）；超时当 `Unavailable`。单个运行时内存上限 32 MB，超了就销毁重建。
- 每个实例一个独立运行时、独立存储和 cookie 罐，互不可见。
- 所有网络请求经 App 的 `fetch` 网关：校验主机白名单，拒绝重定向到白名单之外，拒绝访问回环地址；没有 `lan` 权限时拒绝私有网段。
- `PlaybackInfo` 里的地址由播放器直接拉取，不受 `hosts` 限制（CDN 域名常变），但只允许 http(s)，且没有 `lan` 权限时不允许私有网段。
- DRM（Widevine 等）v1 不支持：插件遇到应抛 `Unsupported`。
- 安装时 App 展示：名称、作者、来源地址、要访问的域名、是否访问局域网。更新后权限变多要重新确认。

## 8. 分发
- **插件仓库**是一个 JSON 索引，用户在「播放源 › 添加 › 插件仓库」里用手机扫码填入地址：
  ```json
  { "name": "某某的插件仓库", "plugins": [
    { "id": "com.example.jellyfin", "name": "Jellyfin", "version": "1.2.0", "description": "…",
      "icon": "https://…/icon.png", "url": "https://…/jellyfin-1.2.0.d3plugin", "sha256": "…", "minApp": "1.0.0" } ] }
  ```
- App 下载后校验 `sha256`，再校验包内 manifest 的 `id`、`version` 与索引一致。
- 也可以直接安装单个 `.d3plugin` 的地址，或从头显存储里选文件（开发用）。
- 官方仓库只收对接用户自有服务（Jellyfin、Emby、SMB、Plex、Alist…）的插件；对接视频网站的插件由第三方仓库分发，App 不内置、不推荐，用户自己添加并自行承担使用责任。

## 9. 开发与一致性测试
- `tools/plugin-kit/`：`d3plugin` 命令行（Node），在电脑上用同一份 QuickJS 运行时跑插件：`d3plugin run home`、`d3plugin run playback --item <id>`、`d3plugin pack`、`d3plugin check`（校验 manifest、返回数据的结构、权限外访问）。
- 一致性测试套件：一个参考插件（`plugins/reference/`，返回固定数据、覆盖每个字段和每种错误），App 的单元测试用它验证宿主实现。
- App 的「播放源 › 插件 › 开发者」里可以开「从电脑加载」：填电脑上 `d3plugin serve` 的地址，改代码后头显里下拉即刷新。

## 10. 以后的版本可能加（现在不做，但 v1 的结构不挡路）
WebSocket 与直播弹幕推送、下载到本地、插件间共享的刮削器（TMDB）、原生（APK + AIDL）插件、DRM、插件自带设置页之外的自定义操作按钮（`actions`）。

## 11. 补充规定（第一版实现审查后加入；与前文冲突时以本节为准）

### 11.1 调用与运行时
- 同一实例的调用串行执行。`reportProgress` 在实例忙时可以被宿主丢弃。
- 一次调用在它返回的 Promise 落定时结束；此时还没完成的定时器和 `fetch` 一律取消，不会拖住调用。`clearTimeout` 必须真正取消。
- `ctx.storage.*` 和 `ctx.cookies.*` 全部是异步的，必须 `await`。开发工具里它们返回 Promise，忘了 `await` 在开发时就会出错。
- `init` 失败等同于该次调用失败，下次调用重新 `init`。内存超限或超时后运行时被销毁重建，`ctx.storage` 之外的状态全部丢失。
- 运行时堆上限 64 MB。`fetch` 响应体上限 8 MB；每实例同时最多 4 个 `fetch`、在途字节合计不超过 16 MB；`bytes()` 返回 `ArrayBuffer`。`ctx.parse` 每次调用最多 8 个文档、单个不超过 4 MB，查询结果是惰性句柄，不整体序列化。cookie 罐上限 256 KB。
- `ctx.fetch` 的 `responseType` 参数取消（用返回值上的 `text()/json()/bytes()`）。
- `new ctx.Error(code, message?, { retryAfterSec? })`。
- `ctx.cookies.get(url)` 返回可直接放进 `Cookie` 请求头的字符串（`"k1=v1; k2=v2"`）；`set(url, cookie)` 接受一条 `Set-Cookie` 文本或 `"k=v"`。`ctx.fetch` 里显式写的 `Cookie` 头与罐里的合并，同名时显式的优先。

### 11.2 数据
- 可选字段的值为 `null` 等同于没写。枚举出现宿主不认识的值时：`kind` 不认识的条目被丢弃，其余枚举回落到默认值；任何情况下都不得因此让整次结果失败。数值字段超出合理范围时忽略该字段。Schema 只对必需字段和类型从严。
- `manifest.author` 可选。
- `Option = { value: string, label: string }`；`browse` 的 `sort` 是某个 `sorts[].value`，`filters` 是 `{ [key]: value }`。
- `PlaybackInfo` 增加 `durationMs?`（`dash` 模式必须给）。`expiresAt` 是 epoch 毫秒；宿主在过期前 60 秒重新取，离过期不足 60 秒的地址取到后直接用、不循环重取。
- `PlaybackInfo.title?` 可选；没有时宿主用条目标题。
- 字幕的 `headers` 叠加在 `PlaybackInfo.headers` 之上（同名覆盖）。
- `live: true` 与 `dash` 同时出现在 v1 不支持，宿主报 `Unsupported`；直播用 `url`（HLS 等）。
- 弹幕的 `color` 是 `"#RRGGBB"` 字符串或 0xRRGGBB 整数，两者都接受。

### 11.3 网络边界（改写 §7 的相关条目）
- `hosts` 限制的是插件代码能**读**到哪些站点的数据，不是保密边界：安装界面的措辞是「这个插件会访问：…」，不承诺数据不会去别处。
- `lan: true` 只放开 `hosts` 和用户填写的主机（`userHosts`）中位于局域网的那些，以及对它们使用 http；不是放开整个局域网。
- 用户在 `url` 类型配置项里亲手填的主机允许 http（自建服务器常见），表单上提示「这个地址没有加密」。
- 播放器替插件拉取的一切——播放地址、备用地址、MPD/HLS 清单里出现的每个地址、分片、字幕、图片，以及它们的每一跳重定向——都只允许 http(s)，并在**每个请求**上按解析后的实际地址检查：回环、链路本地、保留地址一律拒绝；私有网段只有在该主机属于 `lan` 放开的范围时才允许。地址的各种写法（省略写法、十六进制、八进制、IPv4 映射/兼容的 IPv6、NAT64）都按解析结果判断，并且连接的就是检查过的那个地址。https 不得降级重定向到 http。
- `PlaybackInfo.headers` 只发给插件明确给出的地址（主地址、备用地址、清单里与它们同源的地址）；跨源重定向时去掉 `Authorization`、`Cookie` 及插件自定的头。
- 图片地址同上规则；`file:`、`content:` 等一律拒绝。

### 11.4 安装与更新
- 插件的身份是「id + 来源」：来源是安装它的仓库地址（或单包地址的源）。同 id 但来源不同的包不是更新，而是替换，必须由用户确认，并且**不继承**原实例的配置、存储和 cookie。
- 仓库地址必须是 https；下载不跟随到私有网段；有总超时和包体积上限（8 MB）。
- 校验 id、版本等字符串时必须整串匹配（不接受末尾换行等）。宿主和开发工具必须读取 zip 的同一份目录结构，并拒绝中央目录与本地文件头不一致的包。
