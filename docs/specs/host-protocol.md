# depth3d Host ⇄ 头显 约定（v1）

## 发现
Host 在局域网用 mDNS 广播 `_depth3d-host._tcp`，端口为 Host 控制接口的 HTTPS 端口。TXT 记录：

| 键 | 值 |
|---|---|
| `v` | `1` |
| `name` | 显示给用户的电脑名 |
| `id` | 稳定的主机 id（首次启动生成的 UUID） |
| `pair` | `auto`（支持免 PIN 配对） |
| `media` | 媒体服务（WebDAV）端口；没开启时不带这个键 |
| `gs` | 内置串流服务的 GameStream HTTP 端口（默认 47989）；串流引擎未就绪（端口冲突、暂停、异常）时不带这个键 |

内置串流服务仍按标准方式（`_nvstream._tcp`）被发现，头显用地址 + `gs` 端口把两条记录合并成一台电脑。Host 不向局域网广播 `_webdav._tcp`；已配对头显统一通过 `/depth3d/v1/media` 获取媒体服务端口与凭据。

## 控制接口
HTTPS，自签名证书；头显首次连接时记住证书指纹（TOFU），之后指纹变了要提示用户。服务端启用可选 TLS 客户端认证（允许无客户端证书访问 `/info` 与首次 `/pair`，并能读取头显在 `/pair` 二次连接与 `/media` 时出示的客户端证书）。

- `GET /depth3d/v1/info` → `{"v":1,"name":"…","id":"…","os":"macOS 15.1","stream":{"ready":true,"reason":null},"media":{"port":8890}|null}`。`stream.ready=false` 时 `reason` 是给用户看的中文短句（如「还没有允许屏幕录制」）。
- `POST /depth3d/v1/pair`，请求体 `{"device":"<头显名>","deviceId":"<头显稳定 id>","pin":"1234"}`。
  - 若内置串流引擎当前未就绪（端口冲突、暂停、崩溃等），立即返回 `503`。
  - 首次配对不带 TLS 客户端证书：Host 在电脑上弹出不抢焦点的确认窗（显示请求方 IP，默认不勾选「以后自动允许」）；允许后把 PIN 仅提交给匹配该请求方 IP 的引擎配对会话并返回 `200 {"ok":true}`，头显随即走标准 GameStream 配对；仅在 GameStream 配对真正成功后，Host 才将 `deviceId` 与该次配对产生的客户端证书指纹绑定落盘。拒绝返回 `403`，60 s 没人点返回 `408`。同一时间只显示一个确认窗，并发请求或被拒绝/超时后的冷却期内返回 `429`。
  - 之后的免确认（「以后自动允许」）：头显在发起 `POST /depth3d/v1/pair` 时**必须携带配对时交换的 TLS 客户端证书**。Host 仅在 `deviceId` 存在且 TLS 客户端证书 SHA-256 指纹与该设备绑定记录一致且勾了「以后自动允许」时直接返回 `200`；未带证书或证书指纹不匹配的请求一律视为未验证来源，照常弹窗等待用户确认。
- `GET /depth3d/v1/media` → `{"webdav":{"port":8890,"user":"…","pass":"…"}}`，只对已完成配对且绑定了客户端证书的 `deviceId` 返回（请求头 `X-Depth3d-Device: <deviceId>`，且 TLS 客户端证书指纹必须与该设备配对时绑定的证书一致并仍在引擎已配对列表中）；否则 `401`。当 Host 移除任一已配对设备时，会立即轮换 WebDAV 凭据并更新 `_depth3d-host._tcp` TXT 通知；其余仍配对的头显在收到 WebDAV `401` 或 TXT 变更通知后重新调用 `GET /depth3d/v1/media` 获取新凭据。

版本不兼容时返回 `426`，头显提示「请更新电脑上的 depth3d Host」。
