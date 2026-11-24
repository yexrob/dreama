# depth3d

在 PICO 4（骁龙 XR2 Gen 1）上把普通 2D 视频实时转成立体 3D。

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

头显应用库里叫 **depth3d (Debug)**，打开后列出所有视频，点一个就用 3D 播放。扳机键暂停或继续，摇杆每推一下跳 10 秒。基于 MoonlightXR 75d92f2，代码在 `player/app/src/main/java/com/limelight/local/`。

### Google Drive（经 Mac）

Mac 上用 rclone 把 Drive 开成局域网 WebDAV，并通过 Bonjour（`_webdav._tcp`）广播，头显打开「Google Drive」会自己找到：

```bash
scripts/serve-gdrive.sh            # 默认 gdrive:，端口 8890，只读
```

登录名 `depth3d`，密码在 `~/.config/depth3d/webdav-pass`（第一次运行时生成），头显里填一次即可。也可以用 adb 直接写进去：

```bash
adb shell am start -n dev.depth3d.player.debug/com.limelight.local.RemoteBrowserActivity \
  --es base http://<Mac IP>:8890 --es user depth3d --es pass <密码>
```

首页另外保留了 Moonlight 原来的串流入口（「Mac 串流」）。

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
