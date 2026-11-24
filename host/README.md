# depth3d Host (macOS)

macOS 14+ 原生伴侣应用（Swift + SwiftUI，GPL-3.0-only）。用户只需在 Mac 上安装这一个应用，完成引导即可：

- 通过内置串流引擎把 Mac 桌面与系统声音串流到头显（VideoToolbox HEVC 优先、H.264 兜底，无需手动装 Sunshine、无需 BlackHole、无需打开网页后台或终端）；
- 自动发现（`_depth3d-host._tcp`）并弹窗确认免 PIN 配对（同时保留兼容旧版头显的 4 位数字配对入口）；
- 将本机文件夹与已配置的网盘（可选 rclone）统一以带认证的只读 WebDAV 共享给已配对头显。

## 构建与测试

```bash
# 1. 运行单元测试（含协议、mTLS、引擎配置/日志、音频静音控制、WebDAV Range）
cd host && swift test

# 2. 构建可运行的 depth3d Host.app（自动下载校验内置引擎、生成工程并 ad-hoc 签名）
host/scripts/build.sh --arch arm64 --config Release
# 产出：host/build/depth3d Host.app
```

> 仅验证 Intel 编译：
> `xcodebuild -project host/Depth3DHost.xcodeproj -scheme Depth3DHost -configuration Release -arch x86_64 -derivedDataPath host/build/DerivedData-x86_64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build`

### 测试时的端口隔离（不干扰本机已有服务）

如果本机已经通过 Homebrew 运行着其他串流服务（占用 `47984/47989/47990/48010`）或已有 `serve-gdrive.sh`（占用 `8890`）：

- **正式运行**：启动前会预检默认串流端口；检测到占用时不会强行覆盖或停止外部服务，而是在状态页清晰提示冲突，并提供一键「改用备用通道（57989 端口组）」。WebDAV 服务在默认 `8890` 被占用时会自动顺延到空闲端口，并通过 `_depth3d-host._tcp` 的 `media` TXT 与 `/depth3d/v1/media` 告诉头显实际端口。
- **开发/测试启动**：可通过环境变量或命令行参数隔离数据目录与端口：
  ```bash
  DEPTH3D_HOST_DATA_DIR=/tmp/depth3d-host-test \
  DEPTH3D_HOST_ENGINE_PORT=57989 \
  DEPTH3D_HOST_CONTROL_PORT=58090 \
  DEPTH3D_HOST_WEBDAV_PORT=18890 \
  "host/build/depth3d Host.app/Contents/MacOS/depth3d Host"
  ```

## 关键架构决策

### 1. 为什么选择「子进程监管内置引擎」而不是「静态编译成库」？

- **故障隔离与自动恢复**：VideoToolbox、ScreenCaptureKit、Core Audio 进程 Tap、FFmpeg 等底层组件在显示器热插拔、系统休眠唤醒或权限变化时可能异常退出；独立子进程崩溃不会拖垮菜单栏、主窗口、控制接口与媒体服务，`EngineSupervisor` 会在指数退避后自动拉起。
- **依赖与生命周期隔离**：上游 Sunshine（C++20 / Boost / OpenSSL / ICU / Qt6）包含大量全局单例状态和信号处理，强行编进 SwiftUI 宿主进程会导致与 AppKit/Security.framework 冲突，且每次上游升级都要维护大范围补丁。
- **权限归属透明**：引擎以无独立 `Info.plist` 的辅助二进制放在 `Contents/Helpers/depth3d-stream-engine`，macOS TCC 将屏幕录制、辅助功能、本地网络和系统音频权限直接归属给 `depth3d Host.app`，用户永远不会在系统设置里看到上游组件名。
- **代价**：应用包体积较大（包含引擎及其动态库未压缩约 95 MB）；重启引擎切换显示器或画质时有约 1–2 秒中断；IPC 通过本机回环 HTTPS API 与日志管道进行（暂无法在编码前零拷贝拦截 `CVPixelBuffer`，3D 深度卸载阶段需改用自编译引擎，见 `docs/specs/host-depth-offload.md`）。

### 2. 系统声音抓取（无需 BlackHole）

- 内置的 Sunshine `v2026.914.233613` 已在 macOS 14.2+ 上原生实现 Core Audio Process Tap（`AudioHardwareCreateProcessTap` + `CATapDescription`），当 `audio_sink` 留空时自动启用，不再需要用户安装 BlackHole 等虚拟声卡。
- 「串流时静音本机」由 `SystemAudioController` 协同保障：检测到串流开始时记录默认输出设备的原始静音状态并将 Mac 扬声器静音（Core Audio Process Tap 在硬件输出静音之前采集进程音频流，因此头显声音不受影响），串流结束、取消勾选、进入休眠或退出应用时严格恢复原静音状态。

### 3. 媒体服务为何选择「Swift 原生只读 WebDAV + 按需代理本机 rclone」？

- **零额外体积 & 零外部依赖支持本机目录**：内置约 85 MB 的 `rclone` 二进制会让只需共享本地文件夹的用户付出额外体积代价；Swift 原生只读 WebDAV（支持 Basic Auth、`OPTIONS`/`PROPFIND`/`GET`/`HEAD`、`Range` 206 分段流式读取与路径越权防护）体积几乎为零，64 KB 分块直读大文件内存占用极低。
- **统一入口兼顾网盘**：当用户本机已安装 `rclone` 并添加网盘时，`RcloneBridgeManager` 为每个网盘拉起仅监听 `127.0.0.1` 随机端口的只读 `rclone serve webdav`（沿用 `scripts/serve-gdrive.sh` 的流式分块参数），由 Swift WebDAV 服务统一挂载到 `/<网盘名>/` 下并重写 `<href>`。头显端只需一套凭据、一个端口，即可同时浏览本机文件夹和网盘。

## 后续发布步骤（Developer ID 签名、公证、Sparkle 自动更新）

当前 `host/scripts/build.sh` 默认使用 ad-hoc 签名（`-`），不接入真实证书。正式公开发布前需补齐以下步骤：

1. **Developer ID 签名与 Hardened Runtime**：
   - 在 Apple Developer 后台申请 `Developer ID Application` 证书并导入构建机钥匙串；
   - 在 `host/Sources/Depth3DHost/Resources/depth3dHost.entitlements` 中配置 Hardened Runtime 所需 entitlements（`com.apple.security.cs.disable-library-validation` 视 Qt/引擎签名一致性决定，优先对所有嵌入 dylib/framework 用同一 Team ID 签名以保持库校验开启；加上 `com.apple.security.device.audio-input` 如需兼容回退）；
   - 构建时传入 `DEPTH3D_CODESIGN_IDENTITY="Developer ID Application: <Team Name> (<Team ID>)" host/scripts/build.sh`（脚本已自动附加 `--options runtime --timestamp`）。
2. **Apple 公证（Notarization）与装订（Stapling）**：
   - 将签名后的 `depth3d Host.app` 打包为 ZIP 或 DMG（如 `create-dmg` / `hdiutil create`）；
   - 运行 `xcrun notarytool submit "depth3d Host.dmg" --keychain-profile "<profile>" --wait`；
   - 公证通过后运行 `xcrun stapler staple "depth3d Host.dmg"` 与 `spctl --assess --type execute --verbose "depth3d Host.app"` 校验 Gatekeeper。
3. **自动更新（Sparkle 2，MIT 许可，与 GPLv3 兼容）**：
   - 在 `project.yml` / `Package.swift` 中引入 `https://github.com/sparkle-project/Sparkle`；
   - 使用 Sparkle 的 `generate_keys` 生成 EdDSA 公私钥，将公钥写入 `Info.plist` 的 `SUPublicEDKey`，设置 `SUFeedURL` 指向托管的 `appcast.xml`；
   - 在菜单栏与「设置 › 关于」接入 `SPUStandardUpdaterController` 的「检查更新」入口，并在 CI 发布流水线中用 `generate_appcast` 自动签发更新清单。
