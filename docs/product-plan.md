# depth3d 产品方案

目标：从「能用的原型」变成独立产品——PICO 4（及其他 OpenXR 一体机）上的 **2D→实时 3D 影院**。
任何画面进来（本地、网盘、媒体库、电脑串流、iPad/手机投屏），出去都是带视差的立体画面，屏幕悬浮在透视的真实房间里。

## 1. 整体结构

```
┌──────────────── 头显 App（Android / OpenXR）────────────────┐
│  2D 面板：播放墙 / 详情 / 插件管理 / 投屏接收 / 设置           │
│                                                              │
│  画面来源（Session）            播放引擎          XR 渲染器    │
│  ├ 媒体播放：插件给出的 URL ──→ Media3 ExoPlayer ─┐           │
│  ├ 电脑串流：Moonlight 协议 ──→ MediaCodec ───────┤→ 深度模型 │
│  ├ AirPlay 镜像（iPad/iPhone）→ MediaCodec ───────┤  + warp   │
│  ├ DLNA 推送（手机 App 投屏）→ ExoPlayer ─────────┤  → 双眼   │
│  └ 安卓手机镜像（自研发送端）→ MediaCodec ────────┘           │
│                                                              │
│  媒体库：插件运行时（QuickJS）→ Source / Category / Item 模型  │
└──────────────────────────────────────────────────────────────┘
          ▲ 局域网（Bonjour 自动发现）
┌──────── Mac 伴侣 App「depth3d Host」（菜单栏）────────┐
│  串流：内嵌 Sunshine（GPLv3，裁剪成库）                 │
│  声音：ScreenCaptureKit 抓系统声音，不再需要 BlackHole   │
│  配对：头显扫到 Mac 后自动配对，不用手填 PIN             │
│  媒体：本地文件夹 + rclone 网盘 → WebDAV，作为一个播放源  │
└──────────────────────────────────────────────────────┘
```

所有画面最终都交给同一个渲染器，进它的 input Surface。所以每加一种来源，只需要解决「把画面解码到这个 Surface」，3D 那条链不用动。

## 2. 需求拆解与技术方案

### 2.1 iPad / iPhone 投屏 → AirPlay 镜像接收
- 基于 **UxPlay**（GPLv3，C 语言，已实现 AirPlay 镜像握手和 FairPlay 解密），用 NDK 编进头显 App。参考 jqssun/android-airplay-server（UxPlay 的 Android 移植）。
- iPad 在「控制中心 → 屏幕镜像」里直接看到「depth3d」。H.264/H.265 码流用 MediaCodec 解码到渲染器的 Surface，音频用 AAC/ALAC → AudioTrack。
- 风险：
  - UxPlay 只支持「镜像」，不支持 AirPlay 2 的「视频 URL 投送」（即在 App 里点 AirPlay 推一个视频）。iPadOS 将来也可能改协议。
  - AirPlay 不是开放协议，商业发行有法律灰色地带。

### 2.2 串流（电脑桌面 / 游戏）
- 头显端：现有的 Moonlight 客户端保留。
- 「把 Sunshine 融合进产品」放在 Mac 端最合适：Sunshine 是被串流的那台电脑上跑的服务，放不进头显。具体做法是做一个 **depth3d Host**：
  - 把 Sunshine 编成库内嵌，带一个菜单栏外壳。
  - 首次启动引导授权「屏幕录制」。
  - 用 ScreenCaptureKit 抓系统声音（macOS 13+ 支持），去掉 BlackHole。
  - 和头显走自动配对：头显发现 Mac → Host 弹确认 → 双方自动交换证书，不用打开网页填 PIN。
  - 同时承担媒体服务器的角色（2.4 里的 WebDAV/rclone）。
- Windows 先直接用上游 Sunshine，以后再出 Host。
- 要修的问题：串流时深度频率被错误降到每秒 5 张（见第 4 节第一项）。

### 2.3 手机投屏
- **DLNA 接收端（DMR）**：B 站、爱奇艺、优酷、腾讯视频等 App 里的「投屏」按钮大多走 DLNA。头显做一个 UPnP MediaRenderer，收到 URL 后用 ExoPlayer 播放，并支持进度、暂停、音量等控制回传。这一项最快见效，安卓手机和 iPhone 上的这些 App 都能用。实现可以用 jUPnP（Cling 的续作）或 UPnPCast。
- **iPhone 整屏镜像**：和 2.1 同一套 AirPlay。
- **安卓手机整屏镜像**：系统级 Miracast 和 Google Cast 接收端都做不了（需要系统权限或 Google 认证）。办法是出一个小的安卓发送 App：用 MediaProjection 录屏 → H.264 + 音频，走自有协议发到头显，复用镜像那条解码路径。

### 2.4 外部播放源、播放墙、插件系统（对标 Infuse / Jellyfin）

**数据模型**

```
Source（播放源实例，如「我的 Jellyfin」「Google Drive」）
 └ Library / Category（电影、剧集、收藏、最近添加、某个文件夹…）
    └ Item：movie | series | season | episode | folder | video | live
       字段：id、title、poster、backdrop、year、overview、rating、
            duration、progress、stereoHint（2d/sbs/ou/180/360）
       └ PlaybackInfo：url、headers、字幕列表、音轨列表、起播位置
```

**插件接口**（一个插件就是一个 JS 包，跑在 QuickJS 沙箱里）
- `manifest.json`：id、名称、版本、需要的权限（能访问哪些域名、能不能访问局域网）、配置项表单（地址、账号等），由 App 自动渲染成设置界面。
- 必须实现：`home()`（首页分区）、`browse(categoryId, page)`、`detail(itemId)`、`playback(itemId)`。
- 可选实现：`search(q)`、`reportProgress(itemId, pos)`、`login(config)`。
- 宿主给插件的 API：沙箱化的 `fetch`（按 manifest 白名单）、`storage`、`xml`/`html` 解析、`crypto`、日志。没有文件系统，也不能任意访问网络。
- 安装：粘贴插件仓库地址（一个 JSON 索引），支持更新和停用。
- 早期内置插件（也用同一套接口写，用来验证接口好不好用）：
  - 本机视频
  - WebDAV（现有 Google Drive 那条路改造进来）
  - SMB
  - Jellyfin / Emby（官方 REST API，自带海报和进度同步）
  - DLNA 媒体服务器浏览
  - depth3d Host
- 重型插件以后再考虑 APK + AIDL 形式，比如需要原生解码或私有协议的。

**播放墙**（2D 面板，Jetpack Compose）
- 首页：继续观看、最近添加，然后按播放源分区，每个分区是横向滚动的海报行。
- 详情页：背景图、简介、季和集、版本选择、字幕和音轨、3D 模式。
- 刮削：对没有元数据的来源（文件夹、WebDAV、SMB），像 Infuse 那样解析文件名，再去 TMDB 拿海报和简介（需要 TMDB API key）。
- 本地数据库（Room）：观看进度、收藏、刮削缓存，跨播放源统一的「继续观看」。

### 2.5 我补充的需求

| 类别 | 需求 | 说明 |
|---|---|---|
| 3D 质量 | 原生 3D 识别 | 已经是 SBS/OU、iPhone 空间视频（MV-HEVC）、180°/360° 的片源直接按格式显示，不跑深度模型 |
| 3D 质量 | 每部片单独的 3D 强度和汇聚距离 | 默认值加个人记忆，详情页里可调 |
| 3D 质量 | Mac 端离线高质量转换 | 用大模型（Depth Anything V2-L）预先转成 SBS 文件，头显直接播，画质比实时转换高一档 |
| 播放 | 换用 ExoPlayer（Media3） | MKV、HEVC/HDR、HLS/DASH、内封字幕、多音轨；MediaPlayer 不够用 |
| 播放 | 字幕放到合适的深度 | 字幕层放在画面前方、跟视差一致，避免看着打架 |
| 播放 | 断点续播、倍速、A-B 重复、睡眠定时 | |
| 交互 | 手势控制 | 已有捏合点击，补手势拖进度 |
| 交互 | 手机遥控页 | 局域网网页遥控器，戴着头显时别人也能帮你选片 |
| 观感 | 透视下悬浮屏 | 默认透视加 6DoF，屏幕位置、大小、曲率按「场景」记忆 |
| 观感 | 影院环境 | 现有的几个环境保留 |
| 产品化 | 品牌和首次引导 | 产品名、图标、首次进入的引导，有 Mac 时引导安装 Host |
| 产品化 | 自动更新、崩溃上报 | 崩溃上报需用户同意后开启 |
| 产品化 | 多设备 | Quest 3 / 3S（同为 OpenXR），后续是 Vision Pro |
| 合规 | GPLv3 | 见第 3 节 |

## 3. 授权（已定：GPLv3 开源发行，2026-11-24）

MoonlightXR（现在的渲染器底座）、Sunshine、UxPlay 都是 **GPLv3**。只要用了它们：
- 整个 App 必须以 GPLv3 开源发行（可以收费，但源码要公开）。
- 想闭源，就得重写渲染器、串流和 AirPlay 三块，工作量是数倍。

建议：开源发行，靠服务和体验收费（Host 的增值功能、云端插件仓库、高质量离线转换等）。

## 4. 里程碑

| 阶段 | 内容 | 用户能感受到的 |
|---|---|---|
| M0 底座 | 修串流深度降频；换 ExoPlayer；Source/Item 模型；Room 数据库；Compose 播放墙；本地和 WebDAV 改写成内置插件 | 有海报墙的播放器，继续观看 |
| M1 DLNA | 头显做 DLNA 接收端 | 手机上 B 站、爱奇艺等点「投屏」直接到头显变 3D |
| M2 AirPlay | 集成 UxPlay | iPad/iPhone 屏幕镜像到头显 |
| M3 Mac Host | 内嵌 Sunshine，系统声音，自动配对，媒体服务 | 一个 App 装完就能串流、看网盘，有声音 |
| M4 插件生态 | QuickJS 运行时、插件仓库、Jellyfin/Emby/SMB、TMDB 刮削 | 接入自己的媒体服务器 |
| M5 扩展 | 安卓手机镜像发送端；原生 3D 与空间视频；离线高质量转换；Quest 适配 | |
