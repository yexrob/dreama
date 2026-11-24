# 任务 C：插件运行时与开发工具

## 目标
实现 `docs/specs/plugin-protocol.md`（权威文本，逐条照做；发现协议本身有问题时不要悄悄偏离，在交付说明里提出修改建议）。做完之后：一个 `.d3plugin` 包可以被安装、配置、登录，并通过现有的 `MediaSource` 接口向播放墙提供内容；插件作者有命令行工具在电脑上开发和自测。

先读：`docs/specs/00-quality-bar.md`、`docs/specs/plugin-protocol.md`、`docs/product-plan.md` §2.4、`player/app/src/main/java/com/limelight/local/media/`（现有的 `MediaSource`、`Model.kt`、两个内置来源）。

## 范围（这一轮只做引擎，不接界面）
另一个任务正在大改 `local/wall/` 和 `local/stream/`，所以这一轮**不要改** `local/wall/`、`local/stream/`、`AndroidManifest.xml`、`Game*.java`、`binding/`。新代码放在新包 `com.limelight.local.plugin` 和仓库根目录的 `tools/plugin-kit/`、`plugins/`。必须改 `local/media/Model.kt` 来补协议里有而模型里没有的字段时，只加可选字段、不改已有字段的含义，并在交付说明里列出。

## 要做
1. **QuickJS 宿主**：选一个许可与 GPLv3 兼容、仍在维护的 Android QuickJS 绑定（或直接用 NDK 编 quickjs-ng），说明选择理由，登记到 README 第三方表。每实例一个运行时，内存与超时限制、销毁重建按协议 §7。
2. **`ctx` API**（协议 §5）全部实现：fetch 网关（主机白名单、重定向校验、私有网段与回环拒绝、响应上限、cookie 罐，基于现有 OkHttp）、storage（加密落盘）、cookies、crypto、parse、log（凭据打码）、device、t。
3. **包管理**：解析与校验 manifest；安装、更新（权限变多时标记需重新确认）、停用、卸载；仓库索引拉取与 sha256 校验；实例的增删与配置存储（Room，和现有 `wall.db` 放一起，写好迁移）。
4. **`PluginMediaSource`**：把一个插件实例适配成 `MediaSource`（home/browse/detail/playback），并提供协议里可选能力的 Kotlin 接口（search、progress、favorites、played、filters、danmaku、categories、登录三种流程），供之后的界面任务调用。错误码映射到现有的三个异常并按协议 §6 补齐其余类型。
5. **`PlaybackInfo` 落到播放器**：给出把协议的 `url` / `dash{video,audio}` / `mpd`、`headers`、`qualities`、`subtitles`、`expiresAt` 转成 Media3 `MediaSource` 的工厂（音视频分离的流用 `MergingMediaSource` 或生成 MPD，说明选择；备用地址失败切换；地址过期后重新取）。只做工厂和单元测试，不改 `LocalPlayerXR`。
6. **开发工具 `tools/plugin-kit/`**（协议 §9）：`d3plugin run|check|pack|serve`，Node 实现，和 App 用同一套语义（同样的权限校验和数据结构校验，校验规则从一份 JSON Schema 生成或共用，Schema 放在 `docs/specs/plugin-schema/`）。
7. **参考插件与样例**：
   - `plugins/reference/`：覆盖每个字段、每种能力、每种错误、三种登录方式的固定数据插件，作为一致性测试的对象。
   - `plugins/jellyfin/`：真实可用的 Jellyfin 插件（官方 REST API：登录、首页、媒体库、剧/季/集、搜索、进度上报、收藏、外挂字幕、清晰度/转码选择）。没有真实服务器可测时，用录制的响应做测试并在交付说明里写明没连过真机。
   - 不要写对接任何视频网站的插件；但要用 `plugins/reference/` 里的一组用例证明协议能表达那类来源需要的东西：二维码登录、音视频分离的 DASH 流加 Referer 请求头、清晰度锁定原因、分P 选集、弹幕分段、地址过期重取。
8. **文档**：`docs/plugin-dev.md`，中文，面向插件作者的入门：十分钟写出一个能跑的插件，加上完整 API 参考的链接。

## 验证
- 单元测试覆盖：manifest 校验、权限网关（白名单、重定向逃逸、私有网段、回环）、每个 `ctx` API、错误映射、超时与内存超限后的恢复、仓库校验、Room 迁移、`PlaybackInfo` 工厂、参考插件的全量一致性测试、Jellyfin 插件对录制响应。
- `cd player && export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk && ./gradlew :app:assembleNonRootDebug :app:testNonRootDebugUnitTest` 通过；`tools/plugin-kit` 自己的测试通过；`d3plugin check plugins/reference plugins/jellyfin` 通过。
- QuickJS 是原生库，JVM 单元测试跑不了的部分：说明用什么办法测的（例如同一份 JS 语义在 Node 版 kit 里测 + 宿主桥接层对假引擎测），以及哪些只能上机验证。
- 不要碰 adb 和头显，不要 push。
