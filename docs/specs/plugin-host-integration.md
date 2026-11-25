# 插件运行时宿主集成指南（Wall / 播放器 / 设置页）

本文面向负责把 `com.limelight.local.plugin` 接到播放墙（`local/wall/`）、播放器（`LocalPlayerXR.java`）和设置页的界面任务，说明**唯一允许的接入入口**、**现有代码里不能复用到插件源的路径**、**边界约束机制**、**S7 异常分支改造对照表**，以及**插件安装/权限确认 UI 约束**。

底层协议与安全边界以 [plugin-protocol.md](plugin-protocol.md)（特别是 §7 与 §11）为准。

---

## 1. 唯一的对外接入入口

> **关于可见性边界与运行时守卫的如实说明**：当前 Android 工程为单一 `:app` Gradle 模块，Kotlin 的 `internal` 修饰符在单模块内对整个 `:app` 可见，并不是跨模块的编译期强隔离。为此当前已落实的边界机制与**尚未接线的入口**如下：
> 1. **运行时来源标记与现状（请务必核对）**：`PluginProtocolMapper` 会给插件产出的每一条 `Artwork` 与 `PlaybackInfo` 自动打上 `sourceKind = SourceKind.PLUGIN`。**目前只有 `Artworks`（`Artworks.load` / `Artworks.frame`，位于 `local/media/Artworks.kt`）入口有运行时拒绝**——遇到 `sourceKind == SourceKind.PLUGIN` 时记录一条告警日志并直接返回 `null`（让 `Components.kt:298` 显示占位图，不抛异常避免播放墙崩溃，且零网络请求）；**`WallActivity.launchPlayer`、播放器取帧（`LocalPlayerXR.java:450-475`）、投屏（`local/dlna/`、`CastScreen.kt`）入口尚未接线，界面任务必须在这些入口调用 `PluginHostGuards`**。
> 2. **入口收敛约定**：底层工厂收口为 `internal`，外部只调用 `PluginPlayback`、`PluginImages.loadBitmap`、`PluginPackageManager` 与 `PluginHostGuards`。
> 3. **自动化架构守卫单测（真实覆盖范围）**：[`PluginBoundaryGuardTest.kt`](../../player/app/src/test/java/com/limelight/local/plugin/PluginBoundaryGuardTest.kt) 仅做两件事：（a）静态源码扫描，禁止 `local/plugin/` 之外引用插件内部类，并禁止在未调用 `PluginHostGuards.isPlugin*` / `requireNonPlugin*` 的前提下把 `PluginMediaSource` / `SourceKind.PLUGIN` 与裸 HTTP/播放器 API 混用（**显式允许**界面/播放器代码使用 `PluginHostGuards.isPlugin*` 做分支分流）；（b）单元测试 `PluginHostGuards` 自身 4 个守卫方法的行为，并在 `Media3DashAndRoomTest.kt` 实测 `Artworks.load` 对 `SourceKind.PLUGIN` 返回 `null` 且不发网络请求。它**不能**替代界面/播放器任务在尚未接线的 `launchPlayer`、取帧、投屏入口补齐调用。若后续将插件运行时拆为独立 Gradle 子模块（如 `:plugin-runtime`），这些 `internal` 声明即可升级为编译期强制隔离。

外部界面与播放器只允许调用下面 4 个公开入口：

### 1.1 播放流：`PluginPlayback`

```kotlin
// 方式 A：单次构建 Media3 MediaSource（自动处理直链 / HLS / 合成 DASH / 内联 MPD / 备用源故障切换 / 字幕 / NetworkPolicy）
val mediaSource: androidx.media3.exoplayer.source.MediaSource =
    PluginPlayback.createMediaSource(
        context = context,
        source = pluginMediaSource,
        playbackInfo = playbackInfo,
        selectedQualityId = chosenQualityId,
        selectedAudioId = chosenAudioId,
        fallbackDurationMs = item.durationMs,
    )

// 方式 B：带过期刷新（expiresAt 提前 60s）与清晰度/音轨切换的完整会话控制器
val controller = PluginPlayback.createSessionController(pluginMediaSource)
val session = controller.load(itemId = item.id, fallbackDurationMs = item.durationMs)
val mediaSource = controller.createMediaSource(session)

// 切换清晰度（自动拦截 locked 清晰度并抛出 SourceForbiddenException）
val nextSession = controller.switchQuality(qualityId = "1080p", currentPositionMs = player.currentPosition)
player.setMediaSource(controller.createMediaSource(nextSession), nextSession.resumePositionMs)

// 播放失败（401 / 403 / 410）或定时检测链接即将过期时刷新
val refreshed = controller.refreshIfExpiredOrFailed(
    currentPositionMs = player.currentPosition,
    httpStatusCode = lastHttpCode,
    fallbackDurationMs = item.durationMs,
)
if (refreshed != null) {
    player.setMediaSource(controller.createMediaSource(refreshed), refreshed.resumePositionMs)
}
```

### 1.2 图片加载：`PluginImages.loadBitmap`

所有来自 `SourceKind.PLUGIN` 的图片（`Item.poster`、`Item.thumb`、`Item.backdrop`、`PersonCredit.image`、`UserProfile.avatar`）**必须**调用 `PluginImages.loadBitmap`，不要拿原始字节自行 `BitmapFactory.decodeByteArray`：

```kotlin
// maxEdgePx 与现有 Artworks.Size 对齐（CARD = 720，BACKDROP = 1280）
val bitmap: Bitmap? = PluginImages.loadBitmap(
    source = pluginMediaSource,
    artwork = artwork,
    maxEdgePx = size.px,
)
```

`PluginImages.loadBitmap` 内部强制完成全部安全与内存防护：
1. 拒绝 `artwork.isVideoFrame == true`（防止误走 `MediaMetadataRetriever`）。
2. 每跳校验 `NetworkPolicy`（DNS 解析后拦截回环/链路本地/保留地址与越权私网）、锁定已校验 IP 直连（禁系统代理）、拦截 HTTPS→HTTP 降级重定向、跨源清除 `Authorization`/`Cookie`/自定义头，限制下载体上限 16 MB。
3. 解码前先通过 `BitmapFactory.Options.inJustDecodeBounds = true` 读取宽高，拦截超大尺寸像素炸弹（原始单边 > 16384 或总像素 > 6400 万直接拒绝），再按 `inSampleSize` + `RGB_565` 降采样缩放至不超过 `maxEdgePx` 与 `maxPixels` 后返回 `Bitmap`。

### 1.3 插件安装与权限预览：`PluginPackageManager`

1. 弹出安装确认卡片前，**必须**先调用 `packageManager.previewPackage(zipBytes, sourceUrl, repoUrl)` 获取 `PluginInstallPreview`：
   - 展示 `preview.manifest.name` / `version` / `author` / `permissions.hosts` / `permissions.userHosts` / `permissions.lan`。
   - **局域网通配符告警**：当 `preview.allowsWildcardLanAccess == true`（即 `permissions.lan == true` 且 `permissions.hosts` 含通配符如 `*.home.arpa`、`*.lan.example.com`）时，确认页必须醒目展示 **`「可访问你局域网里的任意设备」`**（也可直接读取 `preview.permissionWarnings`）。
   - **不同来源替换告警**：当 `preview.requiresReplacementConfirmation == true`（不同仓库、不同路径 URL，或**任何本地文件同 ID 安装**）时，必须提示「继续安装将替换原插件并清除所有配置、登录信息和本地数据」，用户明确确认后才传 `confirmReplaceFromDifferentSource = true` 调用 `installFromBytes` / `installFromFile` / `installFromRepository`。
2. 同来源更新后若 `InstallResult.needsPermissionReconfirm == true`，该插件被暂停加载（调用 `openSource` 后的业务方法会抛出 `SourceNeedsSetupException`），界面需展示新增权限，用户同意后调用 `packageManager.confirmPermissions(pluginId)` 恢复。

### 1.4 播放源实例与生命周期：`PluginPackageManager.openSource`

- 播放墙从 `packageManager.openSource(instanceId)` 获取 `PluginMediaSource`（标准 `MediaSource` + 可选能力接口 `PluginSearchCapability` / `PluginProgressCapability` / `PluginFavoritesCapability` / `PluginPlayedCapability` / `PluginFiltersCapability` / `PluginDanmakuCapability` / `PluginCategoriesCapability` / `PluginAuthCapability`）。
- 退出或卸载时由 `PluginPackageManager` 负责关闭 QuickJS 运行时并刷盘 Cookie。

---

## 2. 现有代码不可复用的路径与明确禁止的做法

界面与播放器任务按下面对照表分流，**绝对不能**把 `SourceKind.PLUGIN` 走入现有本机/WebDAV 路径：

| 现有路径 / 禁区 | 位置 | 为什么对插件源禁止 | 当前接线状态与插件源唯一正确入口 |
| --- | --- | --- | --- |
| `Artworks.load(...)` | `local/media/Artworks.kt:45-100`（调用点 `local/wall/Components.kt:298` `rememberArt`） | 内部 `fetch()` 使用裸 `OkHttpClient`，不校验 `NetworkPolicy`、不锁 DNS、不拦截降级重定向、不剥离跨源头，且直接 `decodeStream` 无 `inJustDecodeBounds` 像素上限 | **已内置运行时拒绝**（记录告警日志并返回 `null` 显示占位图，不抛异常）；界面任务在 `Components.kt:298`（`rememberArt`/`ArtBox`）应使用 `PluginHostGuards.isPluginArtwork(art)` 分流到 `PluginImages.loadBitmap(pluginMediaSource, artwork, maxEdgePx = size.px)` |
| `isVideoFrame` / `MediaMetadataRetriever.setDataSource(url, headers)` | `local/media/Artworks.kt:68-92` 与 `local/LocalPlayerXR.java:450-475`（含 `MediaExtractor.setDataSource`） | 由系统媒体服务直连网络，完全绕过 `NetworkPolicy`、代理禁用与跨源去头 | `Artworks.frame` 已内置运行时拒绝（打日志并返回 `null`）；**`LocalPlayerXR.java:450-475` 尚未接线**，界面/播放器任务必须在入口调用 `PluginHostGuards.requireNonPluginFrameProbe(sourceKind)`（或用 `PluginHostGuards.isPluginSource(sourceKind)` 跳过 `MediaMetadataRetriever`/`MediaExtractor`，改用 `PluginImages.loadBitmap` 或占位图，并从 ExoPlayer `Player.Listener.onVideoSizeChanged` / `Format.frameRate` 获取视频尺寸与帧率） |
| `WallActivity.launchPlayer` + `DefaultHttpDataSource` | `local/wall/WallActivity.kt:113-124` 与 `local/LocalPlayerXR.java:145` | 直接把 `PlaybackInfo.url` 塞进 `Intent` 并拼 `DefaultHttpDataSource`，绕过网络策略、DNS 锁定、跨源去头、MPD/HLS 子请求校验、备用源切换与 JSON 字幕转换 | **尚未接线**；界面/播放器任务必须在 `WallActivity.launchPlayer` 与 `LocalPlayerXR` 入口先用 `PluginHostGuards.isPluginPlayback(info)` / `PluginHostGuards.requireNonPluginPlayback(info, sourceKind)` 分流：插件源仅传递 `(sourceId, itemId)` 到播放器，并在播放器内通过 `PluginPlayback.createSessionController(pluginMediaSource)` 或 `PluginPlayback.createMediaSource(...)` 构建 `MediaSource` |
| DLNA 投屏与包外组件 | `local/dlna/`（`CastReceiver`、`MediaRenderer.kt`、`Didl.kt`、`Ssdp.kt`，当前为头显侧 DLNA 接收端）与 `local/wall/CastScreen.kt`（投屏页），以及外部 `Intent`、`DownloadManager`、第三方播放器 | 包外组件与局域网渲染器不受 `NetworkPolicy` 约束，且可能将内网资源或鉴权令牌泄露给外部设备 | **尚未接线**；任何外发投屏或包外拉起入口必须调用 `PluginHostGuards.requireNonPluginCast(sourceKind, info, item)` 拒绝插件源；对 `SourceKind.PLUGIN` 隐藏/禁用投屏与外部打开入口，仅通过 `PluginPlayback` 在应用内播放 |

同时必须遵守以下 3 条红线：
1. **禁止对 `PluginSourceMismatchException` 自动传 `confirmReplaceFromDifferentSource = true`**，尤其是本地文件安装同 ID 插件时（本地文件安装同 ID 一律按替换处理，必须由用户显式确认）。
2. **禁止把 `ctx.config` 里 `type == "password"` 的字段写入明文日志或未加密 SharedPreferences**；配置读写一律走 `PluginPackageManager.createInstance` / `updateInstanceConfig`。
3. **禁止在界面直接展示异常类名、协议错误码（如 `NeedsAuth`、`Internal`）或英文栈信息**——`PluginErrorMapper` 已为所有异常提供中文用户文案。

---

## 3. 播放墙异常分支改造指南（S7 必改项）

### 3.1 现状问题（重要）

按协议 §11.2（S7），`SourceNeedsAuthException` **刻意不再继承 `SourceNeedsSetupException`**，防止旧页面把「登录过期 / 需要重新登录」误判成「本机存储权限未开启」。

当前 `local/wall/` 里已有两处只判断了 `error is SourceNeedsSetupException`：
- `player/app/src/main/java/com/limelight/local/wall/HomeScreen.kt:179`（`FailedRow`）
- `player/app/src/main/java/com/limelight/local/wall/LibraryScreen.kt:165`（`SourceProblem`）

如果不补分支，插件抛出 `SourceNeedsAuthException`（以及 `SourceForbiddenException`、`SourceRateLimitedException`、`PluginInternalException` 等）时，这两处都会落入 `else` 分支并错误显示成 **「连不上」**。

### 3.2 界面任务必须新增的异常对照表

`HomeScreen.FailedRow`、`LibraryScreen.SourceProblem`、详情页与播放器错误弹窗都应按以下顺序分派（**不要把 `SourceNeedsAuthException` 改回继承 `SourceNeedsSetupException`**）：

| 异常类型（`com.limelight.local.media`） | 含义 | 文案来源 | 主操作按钮 | 次操作按钮（可选） |
| --- | --- | --- | --- | --- |
| `SourceNeedsAuthException` | 未登录、令牌过期或本地加密凭据损坏 | `error.message`（默认：「需要重新登录，请点此进入登录页。」） | **「去登录」** → 按 `source.authMode` 打开表单 / 二维码 / 设备码登录流 | — |
| `SourceAuthFailedException` | 登录凭据错误（表单密码错 / 扫码被拒） | `error.message`（默认：「登录失败，请检查账号或凭据后重试。」） | 留在登录页内联提示并允许 **「重试」** | — |
| `SourceNeedsSetupException` | 未配置服务器、插件已停用或更新后需重确认权限 | `error.message`（默认：「尚未完成配置，请先填写连接设置。」） | 本机源：**「允许访问」**；WebDAV：**「检查设置」**；插件源：**「去设置 / 确认权限」** | — |
| `SourceUnavailableException` | 网络不可达、服务器 5xx 或插件调用超时 | `error.message`（默认：「暂时连不上这个来源，请检查网络或服务器状态后重试。」） | **「重试」** | 插件源：**「检查设置」** |
| `ItemNotFoundException` | 内容已删除或失效 | `error.message`（默认：「这个内容已经不在了，请返回刷新列表。」） | **「返回并刷新」** | — |
| `SourceForbiddenException` | 会员/地区限制或锁定清晰度（`quality.locked`） | `error.message`（默认：「暂时无法访问这个内容，请检查账号权限或地区限制。」） | **「知道了 / 切换其他清晰度」** | — |
| `SourceRateLimitedException` | 触发限流（读取 `error.retryAfterSec`） | `error.message`（带倒计时秒数） | **「稍后重试」**（在 `retryAfterSec` 秒内禁用或倒计时） | — |
| `SourceUnsupportedException` | DRM 或协议 v1 不支持的流格式（如 `live` + `dash`） | `error.message`（默认：「这个内容类型暂不支持播放，请尝试其他片源。」） | **「换个片源」** | — |
| `PluginInternalException` | 插件脚本崩溃、返回非法结构或超限（如返回值 > 8 MB） | `error.message`（默认：「这个插件出错了，可以查看日志或停用插件。」） | **「查看日志」**（读 `PluginLogger.recentEntries()`） | **「停用插件」** |

### 3.3 示例 Kotlin 分派骨架

```kotlin
when (error) {
    is SourceNeedsAuthException -> showAuthPrompt(error.message, onLogin = { nav.pluginLogin(src) })
    is SourceAuthFailedException -> showInlineAuthError(error.message)
    is SourceNeedsSetupException -> showSetupPrompt(error.message, onSetup = {
        when (src.source.kind) {
            SourceKind.LOCAL -> nav.storage()
            SourceKind.WEBDAV -> nav.editWebDav()
            SourceKind.PLUGIN -> nav.pluginSettings(src)
        }
    })
    is SourceRateLimitedException -> showRetryLater(error.message, cooldownSec = error.retryAfterSec)
    is SourceForbiddenException -> showNotice(error.message, danger = false)
    is ItemNotFoundException -> showNotFound(error.message, onBack = { nav.backAndRefresh() })
    is SourceUnsupportedException -> showNotice(error.message, danger = false)
    is PluginInternalException -> showPluginError(error.message, onLogs = { nav.pluginLogs(src) }, onDisable = { disablePlugin(src) })
    is SourceUnavailableException -> showOffline(error.message ?: "连不上「${src.source.name}」", onRetry = retry)
    else -> showOffline("连不上「${src.source.name}」", onRetry = retry)
}
```

---

## 4. 插件运行时资源上限与单 VM 最坏瞬时内存占用

为避免在头显设备上引发 OOM，宿主对插件运行时实施分层资源预算。接入方在设计并发刷新（例如首页同时并发加载多个插件源）时，必须区分**纳入进程级共享账本的常驻 DOM 配额**与**不计入进程账本的每 VM 独立/瞬时开销**。

### 4.1 纳入进程级 DOM 账本的配额（全进程共享）

| 维度 | 上限 | 说明 |
| --- | --- | --- |
| 进程级存活 DOM 总预算 | **64 MB** | 全部活跃插件 VM 共享同一原子账本，覆盖所有存活 DOM 树、元素句柄表（约 184 B/句柄）与长类名查询缓存；流式解析期按 256 KB 分块增量预占，调用结束、`doc.close()` 或 VM 销毁时同步归还 |
| 单 VM 存活 DOM 总预算 | **48 MB** | 单实例同时存活文档 ≤ 8 份；在 19 万元素级别大页面上调用 `select('*')` 会为全部元素建句柄并触发 48 MB 句柄表上限 |
| 单文档估算内存 | **32 MB** | 压实 `Attributes` / `Element$NodeList` / `LeafNode` / `FormElement.linkedEls` 并分离 `Document.parser` 后，按实际结构精确记账 |

> **Jsoup 内部字段强校验（失败即拒绝）**：`PluginDomParser` 在类初始化时一次性校验并缓存 `Attributes.size`、`Attributes.keys`、`Element.attributes`、`Element.childNodes`、`Element.EmptyNodeList`、`LeafNode.value`、`FormElement.linkedEls` 7 个内部字段（已在 `proguard-rules.pro` 显式保留）。任一字段缺失时，所有 DOM 解析均直接抛出 `DomHostIncompatibleException` 拒绝解析，绝不静默兜底或低估内存。**升级 Jsoup 必须先过这条测试**（`CtxApiAndRecoveryTest.round10VerifiesDeterministicEstimationAndFailClosedJsoupReflection`）。

### 4.2 不计入进程账本的每 VM 独立与最坏瞬时占用

以下缓冲与运行时内存**不计入**上述 64 MB 进程级 DOM 账本，每个并发活跃的插件 VM 都可能独立触达其上限：

| 资源项 | 逻辑上限 | 最坏瞬时内存（每并发 VM） | 所在堆与生命周期 |
| --- | --- | --- | --- |
| QuickJS VM 堆与栈 | 64 MB 堆 + 512 KB 栈 | **~64.5 MB** | Native 堆；随 `PluginRuntime` 存活，超时或 OOM 重建时释放 |
| DOM 解析输入字符串 | ≤ 2 MB UTF-8 | **2–8 MB** | JVM/ART 堆；非 Latin-1 UTF-16 `String` 最高 4 MB，`parseXml` 未定义实体转义副本再增至多 4 MB，解析返回即释放 |
| DOM 序列化输出缓冲（`html` / `outerHtml` / `text` / 转义后 `attrsJson`） | ≤ 8 MB UTF-8 | **16–32 MB** | JVM/ART 堆；`BoundedOutputBuffer` 的 `StringBuilder`（UTF-16 最高 16 MB）+ `toString()` 成品副本 + 桥接传回 QuickJS 的瞬时拷贝，调用返回即释放 |
| DOM 估算用的字符串去重集合（`IdentityStringSet`） | ≤ 699,050 项（32 MB / 每项至少 48 B），表容量 ≤ 2^20 | **~6 MB** | JVM/ART 堆；4 MB 表 + 扩容时 2 MB 旧表，每个进行中的 `parseHtml` / `parseXml` 一份，流式阶段结束清空后才建终态重算用的那份，解析返回即释放 |
| CSS 选择器查询记忆表与兄弟表 | ≤ 16 MB 逻辑预算 | **~16 MB** | JVM/ART 堆；单次 `select` / `selectAll` 期间存在，查询返回即释放 |
| 插件返回值 JSON 序列化缓冲 | ≤ 8 MB UTF-8 | **8–16 MB** | QuickJS + JVM/ART 桥接层瞬时拷贝，单次 `invoke` 返回后释放 |
| `ctx.fetch` 响应体在途缓冲 | ≤ 16 MB（单响应 ≤ 8 MB，并发 ≤ 4） | **~16 MB** | JVM/ART 堆；调用 `text()` / `json()` / `bytes()` 一次性消费后立即释放 |
| `ctx.fetch` 请求体排队与在途缓冲 | ≤ 8 MB（单请求体 ≤ 2 MB，并发 ≤ 4、排队 ≤ 16） | **~8 MB** | JVM/ART 堆；请求完成、失败或取消后释放；另每请求 URL ≤ 8 KB、头 ≤ 32 KB |
| 实例持久化与日志缓冲 | `storage` ≤ 1 MB、`cookies` ≤ 256 KB、`log` ≤ 256 KB | **~1.5 MB** | JVM/ART 堆；常驻实例生命周期 |

**单并发活跃 VM 账本外最坏瞬时合计**：**~130–145 MB**（其中 Native QuickJS 堆最高 ~64.5 MB，JVM/ART 堆瞬时峰值最高 ~65–80 MB；若同时叠加上表全进程共享的 64 MB DOM 账本，单个活跃插件在极端并发 `fetch` + 序列化 + 查询场景下的总峰值可达 **~200 MB**）。宿主 UI 在首页刷新多个插件源时建议限制并发活跃插件调用数（例如最多 2–3 个并行），避免多 VM 瞬时缓冲叠加触发低内存设备 LMk。
