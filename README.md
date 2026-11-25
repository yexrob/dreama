# Dreama（原 depth3d）

在 PICO 4（骁龙 XR2 Gen 1）上把普通 2D 视频实时转成立体 3D。

> 2026-11-25 起 App 对外名称改为 **Dreama**，Android applicationId 由 `dev.depth3d.player` 改为 `dev.dreama.player`。协议级标识保持不变：mDNS 服务类型 `_depth3d-host._tcp`、插件 id `dev.depth3d.*`、日志目录 `Download/depth3d`，Mac 伴侣应用仍叫 `depth3d Host`。

## 参照

- **起因**：B 站 UP 主 Ao_Lei 给 PiliPlus 加了 VR 播放和 AI 转 3D（BV1tD4E6NE7s、BV1ZBsc6aERv）。他没公开算法，但评论区说得很清楚：新版是照着 **MoonlightXR** 做的，深度模型是 **ZipDepth**。
- **ZipDepth**（[fabiotosi92/ZipDepth](https://github.com/fabiotosi92/ZipDepth)，ECCV 2026，MIT）：6.1M 参数的单目深度模型，从 Depth Anything V2-Large 蒸馏而来。输出相对逆深度，值越大越近。仓库里有 `zipdepth_base_npu.pth`，是给移动端和 ONNX 导出用的无 unfold 版本。
- **MoonlightXR**（[Gilleece/moonlight-android-xr](https://github.com/Gilleece/moonlight-android-xr)，GPLv3）：一个 OpenXR 原生的串流客户端，全部在头显本地算出双眼画面。DepthRay 播放器也是这个作者做的。它的 README 写明了 XR2 Gen 1（Quest 2、Pico 4）上的做法：
  - ZipDepth 用 **int8 动态量化**，**256×256**，经 LiteRT XNNPACK 跑在 **CPU** 上（`zipdepth_256_int8dr.tflite`，6 MB）
  - **每秒 12 张深度图**，和画面帧率脱钩，深度大约滞后 50 ms
  - 画面 1440p、72 Hz
  - 作者注明 Pico 4 **没测过**

## 处理流程（和头显上的一致）

```
解码帧 -> 4x4 盒式缩小 -> ZipDepth（256 短边） -> 逐帧鲁棒归一化 + 时间平滑（切镜头重置）
      -> 颜色引导的联合双边上采样到 1/4 分辨率 + 边缘吸附
      -> 每只眼反解偏移：找落到本像素的源像素，多个时最近的胜出（遮挡）
      -> 按偏移采样全分辨率画面，左右边缘渐弱
```

默认参数取自 MoonlightXR 的盲测结论：分离度为画面宽度的 0.6%，再大不会更有立体感，只会更累、边缘更脏。

## 阶段

1. **桌面原型（`proto/`，已跑通）**：把视频转成全宽 SBS，在 PICO 4 自带播放器里按左右 3D 看，先验证效果、调参数。
2. **模型上头显**：把 ZipDepth NPU 版导出成 256 int8 的 TFLite，在 PICO 4 上实测 CPU（XNNPACK）和 GPU delegate 的耗时，确认每秒 12 张深度图做不做得到。
3. **PICO 4 原生播放器（`player/`，第一版已跑通）**：OpenXR（PICO 的 loader）+ MediaCodec 解码本地或网盘视频 + GLES 实现上面的 warp（双边上采样、偏移搜索、采样三个 pass）+ 两个视角各一个 layer。渲染部分打算直接借 MoonlightXR 的 `xr-renderer`，所以整个项目跟着用 **GPLv3**。

## PICO 4 实测（2026-11-24）

在空闲状态下用 TFLite `benchmark_model` 测，取 40 次的平均：

| 模型 | 后端 | 每张耗时 |
|---|---|---|
| ZipDepth int8 动态量化，256×256 | CPU XNNPACK 1 线程 | 31.9 ms |
| 同上 | CPU XNNPACK 2 线程 | 23.2 ms |
| 同上 | **CPU XNNPACK 4 线程** | **16.7 ms** |
| 同上 | GPU delegate | 18.1 ms |
| ZipDepth fp16，512×288 | GPU delegate | 34.4 ms |
| 同上 | CPU XNNPACK 4 线程 | 77.9 ms |

结论：深度模型走 CPU int8 256、4 线程，把 GPU 留给解码后的 warp 和合成。每秒 12 张只占约 20% 的时间，还有余量提到 15–20 张。播放时有解码和渲染负载，数字会变差，要在播放器里再测一次。

PICO 4 自带播放器的「3D 左右」按半宽格式解。原型默认输出 `--layout pico`：整帧 2W×2H，每只眼保留全部横向分辨率。

## 播放器实测（2026-11-24，PICO 4，1920×800 24fps H.264）

| 项目 | 数值 |
|---|---|
| 深度模型 | ZipDepth fp16 512×288，GPU delegate（MoonlightXR 默认路线） |
| 推理 | 约 26 ms 一张，每秒 19.6–20.5 张（上限 20），没有因忙跳帧 |
| 深度滞后 | 平均 49 ms（1.2 帧），最差 127 ms |
| 显示 | 72 Hz，warp 每帧 GPU 1.93 ms，丢帧 0，漏刷新 0 |

主观：非常流畅。播放时 GPU 推理比空闲时 benchmark 的 34 ms 还快，所以不用退回 CPU int8 那条路。

### 播放器构建

```bash
cd player
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleNonRootDebug
adb install -r -g app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
```

头显应用库里叫 **depth3d (Debug)**。基于 MoonlightXR 75d92f2，代码在 `player/app/src/main/java/com/limelight/local/`。从这一版起需要 Android 6.0（API 23）以上，头显都满足。

### 首页（播放墙）

打开后是照 `design-opus` 做的播放墙（Compose，`local/wall/`）：

- **首页**：最上面是最近看过的片子（没看过就是最新的一个），按「继续播放」从上次的位置接着看；下面一行「继续观看」，混排所有播放源，点卡片直接续播；再往下每个播放源一行海报，点行标题进入这一类。某个源连不上时只有它那一行显示「连不上」和「重试」。
- **媒体库**：左边是播放源，右边是选中源里的文件夹；没有海报的文件夹用里面几段视频的画面拼成缩略图。点进去是 6 列海报网格。
- **详情**：继续播放 / 从头播放，下面是进度和剩余时间。
- **投屏**：上半部分是投屏接收，显示接收端的名字和状态：iPad / iPhone 屏幕镜像（见下文「AirPlay 屏幕镜像」）和手机视频 App 投屏（见下文「手机投屏」）。下面的「电脑串流」进入 Moonlight 原来的电脑列表。右上角齿轮是串流设置。

本机视频的海报是从视频里截的一帧（十分之一处，最多 5 分钟），截一次就缓存在应用缓存目录，不重复解码；网盘里的视频不截帧，用色块和片名代替。观看进度存在 Room 里（`wall.db`），暂停、退出播放时写入；看过 30 秒以上、剩余超过 1 分钟（且超过 5%）的才进「继续观看」。

### 播放源

播放源都实现同一个接口 `local/media/MediaSource.kt`（首页 → 分类浏览 → 详情 → 播放信息，全部是 suspend），数据模型按产品方案 2.4：Source → Category → Item → PlaybackInfo。QuickJS 插件也实现这个接口。现在内置：

- **本机**：MediaStore 里的所有视频，按所在文件夹分类，另有「全部视频」。第一次打开会请求存储权限，拒绝后首页那一行有「允许访问」。
- **Google Drive（WebDAV）**：见下一节。没有配置过时，会在局域网里自动找 `_webdav._tcp`，找到后首页空状态和媒体库里会显示出来，点一下填密码即可；媒体库的「管理播放源」可以改地址和密码。保存前会先连一次，密码错了当场提示。

- **Jellyfin / Emby（内置插件）**：设置里的「播放源与插件」或媒体库左栏的「添加播放源」。会在局域网里自动搜索服务器（UDP 7359 探测），也可以手动填地址；地址和账号密码用手机扫码填。加好后首页和媒体库出现它的海报行，电影和剧集有详情页（选季选集、继续看），播放进度回写到服务器。同一页还能管理插件：查看权限、停用、卸载、看日志、添加第三方插件仓库。已知缺口：VR 画面里还不显示外挂字幕；详情页不能选清晰度、字幕和音轨；尚未在头显上验证。

播放时扳机键暂停或继续，摇杆每推一下跳 10 秒。

### 搜索

顶栏右上角的放大镜。进去先看到「最近搜过」和「最近看过」，点搜索框才弹键盘。提交后每个播放源各占一行，谁先返回谁先出现；一个源出错或要登录只影响它自己那一行。点视频直接播放，点单集会进到所属剧集并定位到那一集。规格见 `docs/specs/08-search.md`。尚未在头显上验证。

### 播放时的手柄调色板和调屏手势

- **调色板**：播放时按左手菜单键，或点控制条上的「调整」。面板挂在左手手腕上，可以点图钉钉在空中、用握键拖动。四个页签：3D（开关、强度、片源格式：自动 / 左右 / 上下）、屏幕（大小、距离、透视调暗）、弹幕、字幕音轨。弹幕和字幕样式目前只有设置，画面上还不显示（正在做）。
- **调屏手势**：指着画面（不是控制条）按住扳机，再推右摇杆：前后调距离，左右调大小，松开保存。影院房间里不可用。
- 调色板只在本地和插件播放里出现，PC 串流不受影响。手感数值尚未在头显上调过。

### Google Drive（经 Mac）

Mac 上用 rclone 把 Drive 开成局域网 WebDAV，并通过 Bonjour（`_webdav._tcp`）广播，头显会自己找到：

```bash
scripts/serve-gdrive.sh            # 默认 gdrive:，端口 8890，只读
```

登录名 `depth3d`，密码在 `~/.config/depth3d/webdav-pass`（第一次运行时生成），头显里填一次即可。也可以用 adb 直接写进去：

```bash
adb shell am start -n dev.dreama.player.debug/com.limelight.local.wall.WallActivity \
  --es base http://<Mac IP>:8890 --es user depth3d --es pass <密码>
```

Moonlight 原来的串流入口在「投屏」页的「电脑串流」里。

### 手机投屏（DLNA）

头显是一个标准的 DLNA 接收端（UPnP MediaRenderer），名字默认叫 **Dreama**。

1. 头显打开 Dreama，进「投屏」页，看到「正在等待投屏」。
2. 手机和头显连同一个 Wi-Fi，在 B 站、爱奇艺、优酷、腾讯视频等 App 里播放视频，点「投屏」（电视图标），选「Dreama」。
3. 头显直接进入 3D 播放。手机上的暂停、继续、拖进度、调音量、退出投屏都对头显生效，手机上的进度条跟着头显走。

接收端只在 App 打开时运行（播放墙在前台，或正在播放），是一个带常驻通知的前台服务，持有 Wi-Fi 组播锁；关掉 App 几秒后就停，手机列表里随之消失。正在播放时又投来新视频，会直接切过去。

实现在 `player/app/src/main/java/com/limelight/local/dlna/`：自己写的精简 DMR（SSDP 发现和通告、设备描述、AVTransport / RenderingControl / ConnectionManager、GENA LastChange 事件），没有引入 UPnP 库：jUPnP 是 CDDL 许可，和 GPLv3 不兼容；Cling 已停止维护且依赖 Jetty。协议层是纯 Kotlin，有 JVM 单测：

```bash
./gradlew :app:testNonRootDebugUnitTest --tests 'com.limelight.local.dlna.*'
```

播放器换成了 Media3 ExoPlayer（Apache 2.0），能放手机常投的 MP4、FLV、HLS（m3u8）、DASH；本机文件、WebDAV（带认证头）、续播位置和进度写回照旧。调试日志：`adb logcat -s depth3d-dlna`。

已知限制：

- 只是「推地址」，不是屏幕镜像：头显按手机给的地址自己去拉流。DRM 保护的片子放不了。
- 有的 App 投屏优先走自家私有协议（比如 B 站对自家电视端），或者只把高画质、部分片子开放给自家电视 App 和会员；能不能在列表里看到 depth3d、能投到什么清晰度，由 App 决定。
- 发现靠局域网组播：访客网络、开了 AP 隔离、或路由器拦组播（IGMP snooping 配置不当）时手机找不到头显。手机刚打开投屏列表时可能要等几秒。
- 新投来的视频画面比例和当前不同（比如横屏换竖屏）时，VR 会话要重开一次，会黑一下。
- 设计里「正在播放时来了新投屏」的确认卡片、陌生设备首次确认都还没做，现在是直接切换、直接允许。
- 名字暂时固定为 depth3d（存在 `dlna` 偏好的 `name` 里，设置页以后再做）。不支持投图片；只有声音的投屏能放，但画面是黑的。

### AirPlay 屏幕镜像（iPad / iPhone）

App 打开期间，头显在 Wi-Fi 里是一台叫 **depth3d** 的 AirPlay 接收端（前台服务 `local/airplay/AirPlayService`，通知栏里能看到）。用法：

1. 头显和 iPad / iPhone 连同一个 Wi-Fi，打开 depth3d，停在任意页面都行。
2. iPad 上从右上角下拉出控制中心 → 「屏幕镜像」→ 选「Dreama」。
3. 头显自动进入 VR，iPad 的画面出现在屏幕上并实时转成 3D；声音从头显出。
4. 在 iPad 上点「停止镜像」，或在头显里退出播放，都会断开并回到首页。

「投屏」页会跟着状态变：等待投屏 → 正在连接 → 正在显示（进入 VR 后）；没连 Wi-Fi 时整页提示去打开 Wi-Fi 设置。

实现：UxPlay 的 `lib/`（协议、配对、FairPlay 解密）用 NDK 编进 `libairplay.so`（`jni/airplay/`），不用 GStreamer：H.264 → MediaCodec → 一个 GL 合成器（按比例放进固定的画布）→ 渲染器的输入 Surface；AAC-ELD → MediaCodec → AudioTrack。mDNS 用系统的 NsdManager 注册 `_airplay._tcp` 和 `_raop._tcp`（TXT 和 UxPlay 一致）：系统的 mDNS 守护进程负责所有网卡、重名探测和后台应答，也不会和 App 里已有的 jmDNS（找电脑、找 WebDAV）抢 5353 端口。

已知限制：

- **没有 PIN，也没有首次确认**：ux-spec 3.6 要的「小林的 iPhone 想投屏到这里」确认卡片在 VR 里还没有，所以同一个 Wi-Fi 里谁选了 depth3d 都能直接投上来。
- 只做屏幕镜像。在视频 App 里点 AirPlay 投「视频」（HLS）不支持；只投音乐（ALAC）没有声音。
- 只报 H.264；H.265 的代码在，但默认不向 iPad 提供。
- 画布按第一帧定成横屏（最大 1920×1080）。iPad 竖着拿或中途旋转，画面按比例缩放、两侧黑边，不重开 VR。
- 音画同步只是「解码完立刻播」，音频缓冲约 120 ms，不按时间戳对齐；画面经过深度模型，可能比声音慢一点。
- 从别的 App 里（不是 depth3d 在前台时）开始镜像，系统可能不让后台拉起 VR 页面，这时点通知栏的「正在显示……的屏幕」进入。
- 接收端只编 arm64-v8a（APK 里的 .so 不压缩，`libairplay.so` 约 5 MB，大头是又静态链接了一份 OpenSSL）；其他 ABI 放的是一个拒绝加载的空壳，投屏页会显示「投屏没有启动」。四个 ABI 都能编，去掉 `jni/airplay/Android.mk` 里的 ABI 判断和空壳即可。

Mac 上可以不连头显先测协议这一层（记录、/info、配对、FairPlay 握手、断开重连）：

```bash
sh player/app/src/main/jni/airplay/mac/build.sh     # 需要 Xcode 命令行工具和 brew 的 openssl@3，带 ASan/UBSan
```

#### 引入的第三方代码

| 代码 | 版本 / 来源 | 许可 | 位置 |
|---|---|---|---|
| UxPlay `lib/`（不含 dnssd.c 和 CMake 文件） | v1.73.7，[FDH2/UxPlay](https://github.com/FDH2/UxPlay) df67c212 | GPLv3；其中原 RPiPlay 部分为 LGPL-2.1+ | `jni/airplay/uxplay/` |
| playfair（FairPlay 解密，随 UxPlay） | 同上 | GPLv3 | `jni/airplay/uxplay/lib/playfair/` |
| llhttp（随 UxPlay） | 9.3.0 | MIT | `jni/airplay/uxplay/lib/llhttp/` |
| libplist（只取库本身） | 2.6.0，[libimobiledevice/libplist](https://github.com/libimobiledevice/libplist) 2117b8fd | LGPL-2.1+ | `jni/airplay/libplist/` |
| OpenSSL libcrypto | 4.0.2，仓库里 moonlight-core 已有的预编译库 | Apache-2.0 | `jni/moonlight-core/openssl/` |
| quickjs-kt（插件运行时 QuickJS 绑定） | 1.0.18，[dokar3/quickjs-kt](https://github.com/dokar3/quickjs-kt)（内嵌 QuickJS / c-vector） | Apache-2.0（QuickJS 与 c-vector 为 MIT） | `player/app/build.gradle` |
| jsoup（`ctx.parse.html` / `xml`） | 1.24.1，[jhy/jsoup](https://github.com/jhy/jsoup) | MIT | `player/app/build.gradle` |
| kotlinx-serialization-json（JSON AST / Schema 校验） | 1.13.0，[Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 | `player/app/build.gradle` |
| quickjs-emscripten（开发工具 QuickJS WASM） | 0.32.0，[justjake/quickjs-emscripten](https://github.com/justjake/quickjs-emscripten) | MIT | `tools/plugin-kit/package.json` |
| ajv（共用 JSON Schema 校验） | 8.20.0，[ajv-validator/ajv](https://github.com/ajv-validator/ajv) | MIT | `tools/plugin-kit/package.json` |
| linkedom（开发工具 `ctx.parse.xml`） | 0.18.15，[WebReflection/linkedom](https://github.com/WebReflection/linkedom) | ISC | `tools/plugin-kit/package.json` |
| parse5（开发工具 `ctx.parse.html` HTML5 树构建） | 8.1.1，[inikulin/parse5](https://github.com/inikulin/parse5) | MIT | `tools/plugin-kit/package.json` |
| jszip（`.d3plugin` 打包解包） | 3.10.2，[Stuk/jszip](https://github.com/Stuk/jszip) | MIT / GPL-3.0-or-later | `tools/plugin-kit/package.json` |
| 测试依赖：`mockwebserver` 5.5.0、`kotlinx-coroutines-test` 1.11.0、`sqlite-jdbc` 3.54.1.0、`room-testing` 2.8.5、`robolectric` 4.18.1 | Maven Central | Apache-2.0 / MIT（Robolectric） | `player/app/build.gradle`（`testImplementation`） |
| Sunshine（Mac Host 内置串流引擎及随附 OpenSSL 3 / miniupnpc / ICU4C / Qt 6 动态库） | v2026.914.233613（`63d35f70`），[SunshineStream/Sunshine](https://github.com/SunshineStream/Sunshine)（原 `LizardByte/Sunshine` 改名后的官方组织，旧地址 301 跳转至此）官方 macOS DMG | GPL-3.0-only（随附库为 Apache-2.0 / BSD-3-Clause / Unicode-3.0 / LGPL-3.0-only） | 构建时由 `host/scripts/fetch-engine.sh` 按固定 SHA-256 校验后打包进 `depth3d Host.app/Contents/` |

都和本项目的 GPLv3 兼容，许可原文在各自目录里。接入方式参考了 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)；`jni/airplay/android/`、`local/airplay/` 和 `host/` 是本项目自己写的。

## 原型用法

```bash
cd proto
uv sync
curl -L -o checkpoints/zipdepth_base_npu.pth \
  https://github.com/fabiotosi92/ZipDepth/raw/main/checkpoints/zipdepth_base_npu.pth
uv run python -m depth3d_proto.convert 输入.mp4 -o 输出_3D_LR.mp4 [--seconds 30] [--separation 0.6] [--convergence 0.35]
uv run python -m depth3d_proto.convert 输入.mp4 --debug   # 右半边换成深度图
```

参数：
- `--input-size`：模型输入的短边，默认 256，对应 PICO 4 走 CPU 的那条路
- `--depth-rate`：每秒深度图张数，默认 12，模拟头显的算力预算

M 系列 Mac 上用 MPS，256 输入大约 32–38 ms 一张。warp 目前是 numpy 实现，大约 50 ms 一帧，只用于离线出片，到头显上会改成 GPU shader。

### Mac 作为串流主机（depth3d Host）

现在 Mac 端提供独立伴侣应用 **`depth3d Host`**（`host/`，原生 Swift + SwiftUI，macOS 14+，GPLv3），取代手工安装 Sunshine、开网页填 PIN、装 BlackHole 和跑 `scripts/serve-gdrive.sh`：

```bash
host/scripts/build.sh --arch arm64 --config Release
# 产出：host/build/depth3d Host.app
```

打开后按首次引导开启「屏幕录制 / 辅助功能 / 本地网络」三项权限即可：
- 内置串流引擎（VideoToolbox HEVC 优先、H.264 兜底）与 macOS 原生系统音频采集（Core Audio Process Tap，支持「串流时静音本机」）；
- 通过 `_depth3d-host._tcp` 自动发现并弹窗免 PIN 配对（同时保留 4 位数字配对入口）；
- 统一只读 WebDAV 共享本机文件夹与已配置的 rclone 网盘，凭据自动下发给已配对头显。

详细架构、端口冲突处理和后续签名公证步骤见 [`host/README.md`](host/README.md)；Mac 端 Core ML ZipDepth 深度卸载可行性验证见 [`docs/specs/host-depth-offload.md`](docs/specs/host-depth-offload.md)。

### 本地播放控制条

指向画面时底部出现控制条（暂停时常驻）：播放/暂停键、已播时间、进度条（悬停显示目标时间，
扳机跳转）、总时长。画面其他位置按扳机为暂停/继续，摇杆每格 ±10 秒。
