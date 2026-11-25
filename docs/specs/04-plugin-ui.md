# 任务 D：把插件接进界面

## 目标

用户在头显里能：添加一个 Jellyfin 服务器 → 首页和媒体库出现它的海报行 → 进电影或剧集详情 → 播放 → 进度回写、继续观看。并且能管理播放源和插件（安装、更新、停用、卸载、看权限）。

插件运行时（`player/app/src/main/java/com/limelight/local/plugin/`）已在 main，这一轮只做接线和界面，不改运行时的行为。运行时里发现的 bug 单独列在回报里，不要顺手改。

## 必读

- `docs/specs/00-quality-bar.md`：质量标准，全部适用。
- `docs/specs/plugin-host-integration.md`：唯一允许的接入入口、禁止的路径、异常对照表。**§1–§3 是硬性要求**，本文不重复。
- `docs/specs/plugin-protocol.md`：协议。
- `design-opus/ux-spec.md`（§4.6 不用键盘的输入顺序、播放源与插件一节）和 `design-opus/mockups/sources.html`、`series.html`、`movie.html`：视觉与交互以设计稿为准，不要自己发明样式；复用 `wall/Theme.kt`、`Components.kt` 里已有的组件。
- 现有代码：`wall/WallModel.kt`、`HomeScreen.kt`、`LibraryScreen.kt`、`DetailScreen.kt`、`SettingsScreen.kt`、`PhoneInputSheet.kt`（及它的 `PhoneInputServer`）、`WebDavSheet.kt`、`wall/WallActivity.kt`、`local/LocalPlayerXR.java`、`plugins/jellyfin/`。

## 分阶段做，每阶段单独提交

### 阶段 1：插件源进入播放墙

1. 应用启动时由一个进程级的持有者创建 `PluginPackageManager`（Room 数据库、加密存储），`WallModel` 把每个已启用的插件实例通过 `openSource(instanceId)` 作为一个 `MediaSource` 加入 `sources`，和本机、WebDAV 并列。实例的增删改、插件停用/卸载后，`sources` 立即更新。
2. 首页刷新时，同时进行的插件调用不超过 2 个（见集成文档 §4.2 的内存说明）；一个插件源慢或失败不能拖住其他行。
3. 图片：`Components.kt` 的 `rememberArt` 用 `PluginHostGuards.isPluginArtwork` 分流到 `PluginImages.loadBitmap`，沿用现有的内存缓存和占位图。
4. 错误：`HomeScreen.FailedRow`、`LibraryScreen.SourceProblem`、详情页按集成文档 §3.2 的对照表分派，文案只用 `error.message` / `PluginErrorMapper`，不出现类名、错误码、英文栈。
5. 内置插件：构建时把 `plugins/jellyfin` 打成 `.d3plugin` 放进 APK assets（用 `tools/plugin-kit` 的打包逻辑或等价的 Gradle 任务，保证与 kit 打出的包字节一致或都能通过 `previewPackage`）。首次启动和 App 升级后自动安装/更新为「内置」，不弹确认；内置插件不能卸载，只能停用。

### 阶段 2：播放

1. `WallActivity.launchPlayer`：插件源只把 `(instanceId, itemId, 可选起播位置)` 传给播放器，不传 URL 和头。
2. `LocalPlayerXR`：插件源用 `PluginPlayback.createSessionController` 建会话和 `MediaSource`；失败（401/403/410）或临近过期时按集成文档 §1.1 刷新并从当前位置续播；取帧/探测用 `PluginHostGuards` 跳过 `MediaMetadataRetriever` / `MediaExtractor`，视频尺寸和帧率从 ExoPlayer 回调取。
3. 进度：实现了 `PluginProgressCapability` 的源，开始、每 10 秒、暂停、退出时回写；同时照常写本地 `WatchHistory`，首页「继续观看」对插件条目可用，点了能从断点播。
4. 字幕和音轨：`PlaybackInfo` 里的外挂字幕能加载；清晰度按配置项默认值选，播放中切换清晰度这一轮不做界面。
5. 投屏和外部打开入口对插件源隐藏，并在入口调用 `PluginHostGuards.requireNonPluginCast`。
6. 播放失败时在播放器里给中文原因和「重试 / 返回」，不能黑屏卡住。

### 阶段 3：详情页

按 `movie.html` / `series.html`：
1. 电影：背景图、标题、年份、时长、评分、简介、播放 / 继续播放 / 从头播放、已看标记、收藏（有对应能力时才显示）。
2. 剧集：季选择、集列表（缩略图、集号、标题、时长、进度条）、「继续看 SxEy」主按钮；集很多时列表懒加载分页，不能一次拉完。
3. 文件夹类型继续走现有的媒体库浏览。
4. 加载骨架、失败重试、空状态都要有。

### 阶段 4：播放源与插件管理页

按 `sources.html`，入口在设置页「播放源与插件」，以及媒体库左栏底部「添加播放源」。

1. **播放源**页签：列出所有源（本机、Host/WebDAV、插件实例），只有出问题的才显示状态。进入一行可以：改配置、重新登录、改名、删除（二次确认，说明会清掉这个源的登录信息）。
2. **添加播放源**：
   - 「在局域网里找到」：对声明了 `config[].discover` 的已安装插件做 mDNS 发现（复用现有 Host 发现用的 NSD 封装，别再起一套），列出找到的服务器，点「添加」后服务器地址已填好，只需要账号。
   - 「手动添加」：列出已安装且启用的插件，各一项。
3. **配置表单**：按 manifest 的 `config` 渲染（`url` / `text` / `password` / `select` / `bool` 等协议里有的类型）。需要打字的字段**用手机填**：扩展 `PhoneInputServer`，加一个「表单」模式，手机网页一次填完所有字段，头显端实时显示「已填 n/m」并在提交后自动继续。沿用现有的二维码 + 128 位令牌 + 4 位验证码 + 尝试次数锁定；`password` 字段在手机网页和头显上都不回显，不进日志，只经 `PluginPackageManager.createInstance` / `updateInstanceConfig` 落盘。`select` / `bool` 直接在头显上点。保留「改用头显键盘」的显式退路。
4. 提交后调用登录（`PluginAuthCapability`，Jellyfin 是 `form`），成功才创建实例并回到媒体库、选中新源；失败留在表单，按 `SourceAuthFailedException` / `SourceUnavailableException` 给出原因，已填内容保留（密码除外）。
5. **插件**页签：每个插件一行，写明版本、来源（内置 / 哪个仓库）、能访问什么（局域网、哪些域名）。进入可以：看权限详情、停用/启用、卸载（内置的没有卸载）、看最近日志（`PluginLogger.recentEntries()`，可清空）。
6. **添加插件仓库**：仓库地址用手机填（`PhoneInputServer` 单字段模式）。列出仓库里的插件，安装前先 `previewPackage`，弹确认卡片，按集成文档 §1.3 展示权限、局域网通配符告警、不同来源替换告警；更新后需要重新确认权限的插件在列表里标出并能确认。**不要**对 `PluginSourceMismatchException` 自动确认替换。
7. 所有耗时操作（下载、安装、登录）有进度或忙碌状态，可取消，离开页面不泄漏协程。

## 质量要求

- 所有可点目标 ≥ 设计稿规定的最小尺寸，整行可点；射线悬停有反馈。
- 任何插件调用都不能阻塞主线程；插件超时、崩溃、返回非法数据时界面给出可操作的提示，App 不崩。
- 旋转不存在，但 Activity 重建（系统回收后恢复）后状态要对：正在填的表单可以丢，已保存的不能丢。
- 文案全部简体中文，走现有的字符串资源方式。
- 不改变本机、WebDAV/Host、串流、DLNA、AirPlay 的现有行为。

## 测试

- 单元测试：`WallModel` 对插件源的增删和并发上限；错误分派表每一行；表单渲染对每种字段类型；`PhoneInputServer` 表单模式（令牌、验证码、锁定、密码不回显不进日志）；内置插件首次安装和升级；播放器对插件源的参数传递（不含 URL）。
- 用 `plugins/jellyfin/fixtures` 加 MockWebServer 做一条端到端的 JVM 测试：建实例 → `home()` → 详情 → `playback()` → 进度回写。
- `PluginBoundaryGuardTest` 必须继续通过；不要靠往允许名单里加文件来过，确有必要时在回报里逐条说明理由。
- 现有 487 个测试全部通过，连续跑两遍。

## 规则

- 只改 `player/`（界面、播放器接线、构建脚本、测试）和必要的文档；不改 `host/`、`tools/plugin-kit/` 的行为、`plugins/jellyfin/main.js`（发现插件 bug 就写进回报）。
- 不要碰 adb 和头显，不要安装 APK。
- 每个阶段完成、`./gradlew :app:assembleNonRootDebug :app:testNonRootDebugUnitTest` 通过后提交一次（英文说明），不要 push。
- 四个阶段全部做完再回报。回报用中文：每个阶段改了哪些文件、关键决定、测试数量、没做到或有疑问的地方、发现的运行时/插件问题。不要写「全部完成」这类总括，逐条写实际状态。
