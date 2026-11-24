# 插件运行时宿主集成指南（Wall / 播放器 / 设置页）

本文面向负责把 `com.limelight.local.plugin` 接到播放墙（`local/wall/`）、播放器（`LocalPlayerXR.java`）和设置页的界面任务，说明**唯一允许的接入入口**、**明确禁止的做法**、**S7 异常分支改造对照表**，以及**插件安装/权限确认 UI 约束**。

底层协议与安全边界以 [plugin-protocol.md](plugin-protocol.md)（特别是 §7 与 §11）为准。

---

## 1. 唯一的对外接入入口

插件模块已把底层工厂（`PlaybackMediaSourceFactory`、`SafePluginHttpDataSource`、`SafePluginHttpExecutor`、`PluginImageFetcher`、`FailoverUrlDataSource`）收口为包/模块内部实现，外部界面与播放器只允许调用下面 4 个公开入口：

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

### 1.2 图片加载：`PluginImages`

所有来自 `SourceKind.PLUGIN` 的图片（`Item.poster`、`Item.thumb`、`Item.backdrop`、`PersonCredit.image`、`UserProfile.avatar`）必须通过 `PluginImages.loadBytes` 获取字节，再交给解码/缓存层：

```kotlin
val bytes: ByteArray = PluginImages.loadBytes(pluginMediaSource, artwork)
val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
```

`PluginImages.loadBytes` 内部强制执行 §11.3：每跳校验 `NetworkPolicy`（DNS 解析后拦截回环/链路本地/保留地址与越权私网）、锁定已校验 IP 直连（禁系统代理）、拦截 HTTPS→HTTP 降级重定向、跨源清除 `Authorization`/`Cookie`/自定义头，且限制单图上限 16 MB。

### 1.3 插件安装与权限预览：`PluginPackageManager`

1. 弹出安装确认卡片前，**必须**先调用 `packageManager.previewPackage(zipBytes, sourceUrl, repoUrl)` 获取 `PluginInstallPreview`：
   - 展示 `preview.manifest.name` / `version` / `author` / `permissions.hosts` / `permissions.userHosts` / `permissions.lan`。
   - **局域网通配符告警**：当 `preview.allowsWildcardLanAccess == true`（即 `permissions.lan == true` 且 `permissions.hosts` 含通配符如 `*.local`、`*.lan`）时，确认页必须醒目展示 **`「可访问你局域网里的任意设备」`**（也可直接读取 `preview.permissionWarnings`）。
   - **不同来源替换告警**：当 `preview.requiresReplacementConfirmation == true`（不同仓库、不同路径 URL，或**任何本地文件同 ID 安装**）时，必须提示「继续安装将替换原插件并清除所有配置、登录信息和本地数据」，用户明确确认后才传 `confirmReplaceFromDifferentSource = true` 调用 `installFromBytes` / `installFromFile` / `installFromRepository`。
2. 同来源更新后若 `InstallResult.needsPermissionReconfirm == true`，该插件被暂停加载（调用 `openSource` 后的业务方法会抛出 `SourceNeedsSetupException`），界面需展示新增权限，用户同意后调用 `packageManager.confirmPermissions(pluginId)` 恢复。

### 1.4 播放源实例与生命周期：`PluginPackageManager.openSource`

- 播放墙从 `packageManager.openSource(instanceId)` 获取 `PluginMediaSource`（标准 `MediaSource` + 可选能力接口 `PluginSearchCapability` / `PluginProgressCapability` / `PluginFavoritesCapability` / `PluginPlayedCapability` / `PluginFiltersCapability` / `PluginDanmakuCapability` / `PluginCategoriesCapability` / `PluginAuthCapability`）。
- 退出或卸载时由 `PluginPackageManager` 负责关闭 QuickJS 运行时并刷盘 Cookie。

---

## 2. 明确禁止的做法

接入播放器和 Wall 时，**绝对不能**做以下 5 件事：

1. **禁止读取 `PlaybackInfo.url` 和 `PlaybackInfo.headers` 自己拼 `DefaultHttpDataSource`**（`LocalPlayerXR.java:145` 当前只针对本机和 WebDAV 这样做）。一旦传给 `DefaultHttpDataSource`，`NetworkPolicy`、DNS 锁定、HTTPS 降级拦截、跨源去头、MPD/HLS 子请求校验、备用源切换和 JSON 字幕转 VTT 会全部失效。
2. **禁止把插件 `Artwork.url` / `Artwork.headers` 直接交给未受约束的 `OkHttpClient`、`HttpURLConnection`、Glide 或默认 Coil**——这会绕过 §11.3 的图片 SSRF 防护与跨源脱敏。
3. **禁止对 `PluginSourceMismatchException` 自动传 `confirmReplaceFromDifferentSource = true`**，尤其是本地文件安装同 ID 插件时（本地文件安装同 ID 一律按替换处理，必须由用户显式确认）。
4. **禁止把 `ctx.config` 里 `type == "password"` 的字段写入明文日志或未加密 SharedPreferences**；配置读写一律走 `PluginPackageManager.createInstance` / `updateInstanceConfig`。
5. **禁止在界面直接展示异常类名、协议错误码（如 `NeedsAuth`、`Internal`）或英文栈信息**——`PluginErrorMapper` 已为所有异常提供中文用户文案。

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
