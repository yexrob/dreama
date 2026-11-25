# 任务 E：让第三方插件能在头显上装起来（开发者模式 + 本地文件安装）

## 背景

外部开发者按 `docs/plugin-dev.md` 写完插件后，在头显上唯一走得通的安装路径是 HTTPS 仓库。文档（`docs/plugin-dev.md` §3、`docs/specs/plugin-protocol.md` §8/§9）承诺的两条路在界面里都不存在：

- 「播放源 › 插件 › 开发者 › 从电脑加载」：运行时有 `PluginRepositoryClient.downloadDevPackage()` 和 `NetworkPolicy.forDevServer()`，但没有任何调用点。
- 安装单个 `.d3plugin`：`PluginPackageManager.installFromFile()` 和 `PluginRepositoryClient.downloadDirectPackage()` 没有调用点。

这一轮把这两条路接通，做到产品级，不是调试后门。

## 必读

- `docs/specs/00-quality-bar.md`、`docs/specs/plugin-host-integration.md`（§1–§3 硬性要求）、`docs/specs/plugin-protocol.md` §8 §9 §11、`docs/plugin-dev.md`。
- `docs/specs/04-plugin-ui.md` 和它落地的代码：`wall/` 下的播放源与插件管理页、`PluginManagementLogic.kt`、`PluginFormScreen.kt`、`PluginHost.kt`、`stream/PhoneInputServer.kt`、`stream/HostDiscovery.kt`（`UdpLanServerDiscovery`、`UdpDiscoveryProtocol`）。
- `design-opus/ux-spec.md` 播放源与插件一节、`design-opus/mockups/sources.html`：沿用现有组件和样式，不新发明视觉。
- `tools/plugin-kit/src/serve.js`、`cli.js`。

## 要做的事

### 1. 开发者模式（从电脑加载）

入口：「播放源与插件 › 插件」页签底部一行「开发者」，进去是开发者页。

1. **找到电脑**，两种方式并列：
   - 自动发现：`d3plugin serve` 增加一个 UDP 应答（见第 3 节）。开发者页打开时用现有的 `UdpLanServerDiscovery` 机制探测，列出找到的开发服务器（显示插件名、版本、电脑地址）。有「重新搜索」。
   - 手动填地址：用手机填（`PhoneInputServer` 单字段模式）或头显键盘。接受 `192.168.1.20:8787`、`http://192.168.1.20:8787`、带或不带路径的写法，规范化后请求 `/index.json`。
2. **只允许局域网私有地址**（RFC1918 / IPv6 ULA），明文 HTTP 只在这条路径上允许；回环、链路本地、公网地址给出明确的中文拒绝原因。沿用 `NetworkPolicy.forDevServer`，如它已做到就只补测试，没做到就在回报里说明并补上。重定向到别的主机一律拒绝。
3. **安装**：取 `/index.json` → 下载 `/plugin.d3plugin` → `previewPackage` → 和仓库安装同一张确认卡片（权限、局域网通配符告警、不同来源替换告警按集成文档 §1.3）。卡片上额外标明「开发版 · 来自 <电脑地址>，未经仓库校验」。确认后安装。
4. **开发版的标识与隔离**：
   - 插件列表里开发版带「开发版」标记和来源地址；来源记录为 dev（区别于内置、仓库、文件）。
   - 开发版不能悄悄替换内置或仓库装的同 id 插件：走 `PluginSourceMismatchException` 的显式确认，文案写清「会把来自 X 的正式版换成开发版」。反过来仓库更新要替换开发版时同样要确认。
   - 开发版不参与仓库自动更新检查。
5. **重新加载**：开发版插件的详情页和开发者页各有「重新加载」。它重新拉取并安装；**版本号相同但内容（SHA-256）不同也必须生效**（开发时不会每次改版本号）。重新加载后：
   - 该插件的运行时实例销毁重建，已建的实例（配置、`ctx.storage`、cookie）保留；
   - 权限没变就不再弹确认；权限变了（`hosts`/`userHosts`/`lan` 任一扩大）必须重新确认，缩小不用；
   - 首页/媒体库里它的行自动刷新；
   - 电脑连不上时保留已装的版本，提示原因，不把插件弄坏。
6. **看日志**：开发者页直接显示该插件最近日志（复用 `PluginLogger.recentEntries()` 的现有界面），重新加载后自动滚到最新。插件加载失败（语法错误、manifest 不合法、`init` 抛错）时，把错误消息和（若有）行号原样显示给开发者——这是唯一允许显示英文技术细节的页面，其他页面仍只显示中文概述。
7. 开发者页顶部一句话说明用法：在电脑上运行 `d3plugin serve <目录>`，头显和电脑在同一个 Wi‑Fi。

### 2. 从文件安装

入口：「插件」页签的「添加」里，与「添加插件仓库」并列一项「从文件安装」。

1. 列出头显存储里 `Download/` 和 `Android/data/<包名>/files/plugins/` 下的 `*.d3plugin`（文件名、大小、修改时间），后者不需要任何存储权限，是给 `adb push` 用的保底路径。`Download/` 读不到（权限或系统限制）时不报错，只显示能读到的，并在页面上写明两个目录的完整路径。**先在 Android 10（targetSdk 以现有为准）上确认现有权限能不能读 `Download/` 里的非媒体文件；读不到就用系统文件选择器（`ACTION_OPEN_DOCUMENT`）作为这一项的实现，并在回报里说明选了哪种、为什么。**
2. 选中后走 `previewPackage` → 同一张确认卡片 → 安装。来源记录为 file，列表里显示「本地文件」。
3. 同样支持「粘贴一个 .d3plugin 的 HTTPS 地址」直接安装（`downloadDirectPackage`），用手机填地址；仅 HTTPS。
4. 超过 8 MB、不是合法包、manifest 不合法、`minApp` 高于当前版本，各有明确的中文原因。

### 3. `tools/plugin-kit`

1. `d3plugin serve` 启动时同时在 UDP 上应答发现请求：端口和报文自己定（不要占用 7359），请求是固定字符串，应答是 JSON `{ "name", "id", "version", "port" }`，只应答来自私有地址的请求。`--no-discover` 可关。启动日志里打印本机局域网地址和头显上要点的入口。
2. `d3plugin serve` 的 HTTP 只服务 `/`、`/index.json`、`/manifest.json`、`/plugin.d3plugin`、`/health`（现状），不新增能读任意文件的路径。
3. 文档：`docs/plugin-dev.md` §3 把「在头显上加载」写成可照着做的步骤（两条路各一段，含 `adb push` 的目标路径）；`docs/specs/plugin-protocol.md` §8/§9 与实现对齐；`tools/plugin-kit/README.md` 同步。

## 质量要求

- 所有网络与文件操作不阻塞主线程；有忙碌状态、可取消；离开页面不泄漏协程。
- 安装失败、取消、进程被杀后，已装的插件和实例保持可用（沿用现有的回滚方式）。
- 可点目标尺寸、悬停反馈、整行可点按设计规范。
- 文案简体中文，走字符串资源。
- 不改变内置插件、仓库安装、现有播放源的行为。不放宽 `PluginBoundaryGuardTest`。

## 测试

- 开发地址规范化与拒绝表（私有/回环/链路本地/公网/IPv6/带路径/重定向到别的主机）。
- 同版本不同 SHA 的重新加载生效；权限扩大需确认、缩小不需；电脑离线时保留旧版。
- 开发版与内置/仓库同 id 的互相替换都要显式确认。
- 文件安装：超限、坏包、`minApp` 过高、正常。
- UDP 发现：kit 侧应答、只答私有地址；App 侧解析与去重、过期剔除。
- 用 MockWebServer 模拟 `d3plugin serve` 做一条 JVM 端到端：发现/填地址 → 预览 → 安装 → 建实例 → `home()` → 改包内容 → 重新加载 → `home()` 返回新内容，实例存储仍在。
- kit 的现有 18 个测试和 App 现有 549 个测试全部通过，连续跑两遍。

## 规则

- 可改：`player/`、`tools/plugin-kit/`、上面列出的文档。不改 `host/`、`plugins/jellyfin/`、`design-opus/`。
- 不要碰 adb 和头显，不要安装 APK。
- `./gradlew :app:assembleNonRootDebug :app:testNonRootDebugUnitTest` 和 kit 的测试通过后提交（英文说明），可按「开发者模式 / 文件安装 / kit 与文档」分次提交，不要 push。
- 全部做完再回报。回报用中文：改了哪些文件、关键决定（尤其 `Download/` 的读取方式、UDP 端口与报文、同版本重装的实现）、测试数量、没做到或有疑问的地方、发现的运行时问题。逐条写实际状态，不要写「全部完成」这类总括。
