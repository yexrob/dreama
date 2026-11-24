# 任务 A：把电脑串流融合进头显 App

## 目标
现在「电脑串流」是直接跳进上游 MoonlightXR 的旧界面（`PcView` 电脑列表、`AppView` 应用列表、`StreamSettings` 设置页、配对弹窗），观感和操作与我们的 App 完全是两个产品。做完之后：用户全程只看到 depth3d 自己的界面，串流只是「投屏」页里的一种画面来源；上游的旧界面不再能被用户走到。

先读：`docs/specs/00-quality-bar.md`、`docs/product-plan.md`、`design-opus/ux-spec.md`（§2.2、§3 投屏相关、§6.9）、`design-opus/mockups/cast.html`（切换底部各状态）、`design-opus/mockups/settings.html`、`design-opus/mockups/vr-player.html`。

## 现有代码（player/app/src/main/java/com/limelight/）
- 我们的界面：`local/wall/`（Compose，`WallActivity` 是启动页；`CastScreen.kt` 是投屏页，下半部分「电脑串流」现在只是跳转按钮）。
- 上游的协议与后台，**保留并复用**：`computers/ComputerManagerService`（发现、轮询、配对状态）、`nvstream/`（HTTP、配对、连接）、`discovery/`、`binding/`（解码、输入、音频、XR 渲染器）。
- 上游的界面，**要替换掉**：`PcView.java`、`AppView.java`、`grid/`、`preferences/StreamSettings.java` 及 `res/xml/preferences.xml`、`ui/`、`HelpActivity`、配对用的 `Dialog`。
- 串流会话：`Game.java` / `GameXR.java`（会话生命周期、输入、和 `XrRenderer` 的对接）。VR 内的控制条由渲染器画；本地播放的那条在 `local/TransportBar.java`。

## 要做
1. **电脑列表（投屏页下半部分）**：在 Compose 里绑定 `ComputerManagerService`，实时显示局域网里发现的电脑：名字、在线/离线、已配对/未配对、正在串流。卡片样式照 cast.html。离线的电脑可以「唤醒」（Wake‑on‑LAN，上游已有）和「移除」。「添加电脑」：先自动发现；找不到时给出手机扫码填地址的入口（扫码页先做成输入页也行，但头显端不能要求用键盘打 IP，除非用户主动选择「用头显键盘输入」）。
2. **配对**：点未配对的电脑进入全页配对状态（cast.html 的「电脑配对」）：88 px 的 4 位 PIN、说明「在电脑上输入这 4 个数字」、取消按钮；成功后自动回到列表并标为已配对；失败说明原因（PIN 错、超时、对方拒绝、版本不兼容）。预留接口：如果对端是 depth3d Host（任务 B），走免 PIN 的自动配对（见下「与 Host 的约定」），界面上直接显示「正在连接书房 Mac…」。
3. **连接什么**：默认直接串流整个桌面（上游的 Desktop app），一步进入。电脑上有多个可串流项目时，卡片上提供「更多」进入项目列表（海报网格，样式和媒体库的海报网格一致）。
4. **连接中 / 失败**：点连接后在我们的界面里显示全页「正在连接…」（可取消），各阶段失败给出人话原因和重试；不再出现上游的进度弹窗和 Toast。
5. **串流设置**：照 settings.html 在 Compose 设置页里做「串流」分区：分辨率、帧率、码率（给「自动 / 流畅 / 清晰」三档，高级里才是具体数字）、编码（自动 / H.264 / HEVC）、鼠标模式、手柄。值仍写入上游的 SharedPreferences 键（`PreferenceConfiguration` 读它们），这样协议层不用改。PICO 4 这类 Gen 1 头显默认 1440p / 72 fps；选到 4K 或 90 fps 时提示「3D 会变卡」。上游设置里与产品无关或危险的项不暴露。3D、屏幕、环境等其余设置同样迁到 Compose 设置页（照 settings.html）。
6. **VR 内串流控制条**：按 ux-spec §6.9。显示电脑名和「串流中」，网络差时显示延迟/丢包提示；有键盘、手柄按钮；快捷菜单有「串流」标签。实现方式优先复用 `local/TransportBar.java` 的思路（Java 画贴图，渲染器显示）；如果必须改 `jni/xr-renderer/`，改动要小并单独说明。退出串流回到投屏页，而不是上游的列表。
7. **清理上游外壳**：从 manifest 移除旧界面的 Activity 入口（`PcView`、`AppView`、`StreamSettings`、`HelpActivity`、快捷方式跳板等），确认没有任何路径能走到；删除因此不再使用的 Java 类、布局、字符串、图片资源；应用名、图标、通知、关于页统一为 depth3d。Java 包名 `com.limelight` 暂不改（JNI 依赖它），在交付说明里写明。上游的崩溃上报、更新检查如指向上游服务器，关掉。
8. **统一「正在播放」入口**：首页如果有一台已配对且在线的电脑，在「投屏」页之外，首页最下面加一行「电脑」（卡片同上），一步进入串流。

## 与 Host 的约定（任务 B 会实现服务端）
- mDNS 服务类型 `_depth3d-host._tcp`，TXT：`v=1`、`name=<电脑名>`、`id=<稳定的主机 id>`、`pair=auto`、`media=<WebDAV 端口，可缺省>`。Host 同时仍以标准 GameStream 方式被发现（同一台机器，用 `id` 与 GameStream 的 uniqueid/地址对应去重）。
- 自动配对：头显对 Host 的 `https://<ip>:<port>/depth3d/v1/pair` 发 `POST {"device":"<头显名>","pin":"<头显生成的 4 位 PIN>"}`，然后立刻按标准 GameStream 流程用这个 PIN 发起配对；Host 在 Mac 上弹确认（「允许 PICO 4 连接？」），用户点允许后 Host 把 PIN 交给内置的串流服务，配对完成。被拒绝返回 403，超时 60 s。头显端把这套封装成一个接口，Host 不在时退回显示 PIN。
- 这份约定的权威文本放在 `docs/specs/host-protocol.md`，两个任务都以它为准；需要改时在交付说明里写明改了什么。

## 验证
- 单元测试：电脑列表的状态合并（发现/轮询/配对状态 → 界面状态）、设置值与上游 pref 键的映射、自动配对客户端（用假服务器）。
- `cd player && export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk && ./gradlew :app:assembleNonRootDebug :app:testNonRootDebugUnitTest` 通过。
- 不要碰 adb 和头显（负责人会上机测）。交付说明里写清上机测试步骤。
