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
| `gs` | 内置串流服务的 GameStream HTTP 端口（默认 47989） |

内置串流服务仍按标准方式（`_nvstream._tcp`）被发现，头显用地址 + `gs` 端口把两条记录合并成一台电脑。

## 控制接口
HTTPS，自签名证书；头显首次连接时记住证书指纹（TOFU），之后指纹变了要提示用户。

- `GET /depth3d/v1/info` → `{"v":1,"name":"…","id":"…","os":"macOS 15.1","stream":{"ready":true,"reason":null},"media":{"port":8890}|null}`。`stream.ready=false` 时 `reason` 是给用户看的中文短句（如「还没有允许屏幕录制」）。
- `POST /depth3d/v1/pair`，请求体 `{"device":"<头显名>","deviceId":"<头显稳定 id>","pin":"1234"}`。Host 在电脑上弹确认；允许后把 PIN 交给串流服务并返回 `200 {"ok":true}`，头显随即走标准 GameStream 配对。拒绝返回 `403`，60 s 没人点返回 `408`。同一 `deviceId` 被允许过并且勾了「以后自动允许」，直接 200。
- `GET /depth3d/v1/media` → `{"webdav":{"port":8890,"user":"…","pass":"…"}}`，只对已配对的 `deviceId` 返回（请求头 `X-Depth3d-Device: <deviceId>`，并用配对时交换的客户端证书做 TLS 客户端认证）；否则 `401`。

版本不兼容时返回 `426`，头显提示「请更新电脑上的 depth3d Host」。
