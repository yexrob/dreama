# 任务 B：depth3d Host（Mac 伴侣 App）

## 目标
用户在 Mac 上只装一个 App「depth3d Host」，打开后按引导点几下，头显里就能看到这台 Mac：能串流桌面和游戏（有声音），能看 Mac 上的片子和网盘。用户不需要知道 Sunshine、rclone、WebDAV、PIN、端口这些东西，也不需要打开终端和网页后台。

现在的做法是用户自己 `brew install sunshine`、开网页后台填 PIN、另跑一个 `scripts/serve-gdrive.sh`。这些都要被 Host 取代。

先读：`docs/specs/00-quality-bar.md`、`docs/specs/host-protocol.md`（与头显的约定，必须照它实现）、`docs/product-plan.md`（§2.2、§2.4、§3）、`README.md` 里「Mac 作为串流主机」和 Google Drive 两节、`scripts/serve-gdrive.sh`。

## 形态
- 原生 macOS App，Swift + SwiftUI，菜单栏常驻（`MenuBarExtra`）+ 一个主窗口（引导和设置）。最低 macOS 14，Apple Silicon 优先，Intel 能编过即可。代码放在仓库新目录 `host/`（Xcode 工程用 XcodeGen 的 `project.yml` 或 Swift Package 生成，仓库里不提交用户相关的 xcuserdata）。GPLv3。
- 界面语言简体中文，视觉与头显端一致的克制风格（深色、琥珀色强调，参考 `design-opus/ux-spec.md` 的配色与字号思路，但按 macOS 习惯做，不要把头显界面搬过来）。

## 要做
1. **串流引擎（内置，不让用户另装）**
   - 把 Sunshine（LizardByte/Sunshine，GPLv3）作为内置引擎打进 App 包：`Contents/Helpers/` 里带它的可执行文件和依赖的 dylib，由 Host 以子进程方式拉起、监管（崩溃自动重启、退出时一起退出）、读它的日志判断状态。构建脚本负责取得并打包引擎（固定版本，记录来源与校验和），脚本要可重复执行。说明你选「子进程」而不是「编成库链接」的理由和代价；如果你判断编成库更好并且能做完，也可以，但不能半途而废。
   - Host 自己生成引擎的配置（端口、编码器、显示器、凭据），放在 `~/Library/Application Support/depth3d Host/`，不碰用户 `~/.config/sunshine`。引擎自带的网页后台不暴露给用户；Host 通过它的本地 API（或配置文件）完成配对提交 PIN、列出/移除已配对设备。
   - 编码走 VideoToolbox 硬件编码（HEVC 优先，H.264 兜底）。
2. **权限引导**：首次启动的引导页逐项检查并带用户去开：屏幕录制、（控制鼠标键盘需要的）辅助功能、本地网络。每项实时显示是否已开，开完自动进入下一步，不要求重启 App（确实需要重启引擎时自动重启）。
3. **系统声音**：用 ScreenCaptureKit / Core Audio tap 抓系统声音送进串流，不要求用户安装 BlackHole。说明实现方式；如果引擎本身已支持系统音频 tap，就正确地配置和验证它，并处理「串流时 Mac 本机是否静音」的选项。
4. **自动配对**：实现 `host-protocol.md` 的发现和 `/depth3d/v1/*` 接口。头显请求配对时，在 Mac 上弹出确认窗（「允许“PICO 4”连接这台 Mac？」，可勾「以后自动允许」）。已配对设备列表可在设置里查看和移除。标准的 PIN 配对也要支持：设置里有「输入头显上的 4 位数字」的入口，给还没升级的头显端或第三方客户端用。
5. **媒体服务**：把 `scripts/serve-gdrive.sh` 的功能做进 Host：用户在设置里添加「文件夹」（本机目录）和「网盘」（先支持通过已安装的 rclone 的 remote；没装 rclone 时这一项显示引导而不是报错），Host 提供带认证的 WebDAV（只读即可），凭据随机生成并通过 `/depth3d/v1/media` 交给已配对的头显。可以内置 rclone（MIT）当 WebDAV 服务器，或用 Swift 自己实现只读 WebDAV（支持 Range），二选一并说明理由。
6. **3D 加速（Mac 替头显算深度）——只做方案和可行性验证，不进主流程**
   - 头显芯片算深度图只能做到每秒 5–20 张，Mac 的 GPU 快得多。验证这条路：Host 在编码前用 Core ML 跑 ZipDepth（仓库 `proto/` 里有模型和转换脚本），把深度图和画面一起送到头显（例如把深度拼在画面旁边/下方一起编码，或走第二路流）。
   - 交付：一份 `docs/specs/host-depth-offload.md`，写清方案对比、在这台开发机上实测的每帧耗时和可达帧率（给出测量命令）、对带宽和延迟的影响、头显端需要的改动。能跑通的原型代码放 `host/Experiments/`。不要把它接进正式串流流程。
7. **菜单栏与主窗口**：菜单栏图标显示状态（就绪 / 有设备在串流 / 需要处理）；菜单里有「正在连接的设备」、暂停串流、打开设置、退出。主窗口：首次引导；之后是状态页（一句话说明现在的状态 + 需要处理的事项）、设备、媒体、设置（开机启动、串流时静音本机、显示器选择、画质档位）。
8. **打包**：`host/scripts/build.sh` 产出可运行的 `depth3d Host.app`（ad‑hoc 签名即可），并写明以后做 Developer ID 签名、公证、自动更新（Sparkle）需要补的步骤，不要在这一轮接入真实证书。

## 验证
- 单元测试：`host-protocol.md` 的接口处理（请求解析、配对状态机、鉴权）、引擎配置生成、引擎日志状态解析、WebDAV（如自己实现）。`swift test` 或 `xcodebuild test` 跑通。
- 在这台 Mac 上实际构建并启动 App，确认：引导页权限状态正确、引擎被拉起并监听端口（`lsof -iTCP -sTCP:LISTEN`）、mDNS 能看到 `_depth3d-host._tcp`（`dns-sd -B`）、`/depth3d/v1/info` 返回正确、WebDAV 能列目录并支持 Range。注意这台机器上用户已经用 brew 装了一个 Sunshine 服务在跑（端口 47984/47989/47990/48010），**不要停它、不要改它的配置**；测试时给内置引擎换一组端口，并在交付说明里写明正式默认端口的冲突处理（检测到占用时怎么提示）。
- 不要碰 adb 和头显。交付说明里写清负责人如何和头显联调。
