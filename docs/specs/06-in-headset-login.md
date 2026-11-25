# 任务 F：在头显里完成输入和登录，不依赖手机

## 背景

现在凡是要打字或登录的地方都让用户用手机扫头显里的二维码。戴着头显扫不了码，摘下来也不一定扫得上。手机应该是可选项，不是必经之路。

播放墙（`local/wall/WallActivity`）是普通的 2D Android 界面，系统键盘和 `WebView` 都可以直接用。

## 必读

`docs/specs/00-quality-bar.md`、`docs/specs/plugin-host-integration.md`、`docs/specs/plugin-protocol.md`（§4.3 登录、§5 `ctx.cookies`、§6 权限）、`docs/specs/04-plugin-ui.md` 及其落地代码（表单页、`PhoneInputServer`、`PhoneInputSheet`、WebDAV 添加页）、`design-opus/ux-spec.md` §4.6。

## 要做的事

### 1. 输入默认用头显键盘

1. 所有文本字段（插件配置表单、WebDAV、仓库地址、开发服务器地址、搜索）默认是可直接点的输入框，点了弹系统键盘；`password` 类型不回显。键盘弹出时被编辑的字段必须可见（滚动或上移）。
2. 「用手机填」降为每个表单上的一个次要按钮。打开后以**短地址 + 4 位验证码**为主（大字），二维码缩小放在旁边。安全机制（令牌、验证码、锁定）不变。
3. `design-opus/ux-spec.md` §4.6 的顺序相应改为：局域网发现 → 头显键盘 → 手机（可选）。只改这一节的文字，不动别的设计内容。

### 2. 协议新增 `auth: 'web'`（在头显内的网页里登录）

给只能通过网页登录的服务用（账号密码页、短信验证码、滑块验证）。

manifest：
```json
"auth": "web",
"webLogin": {
  "url": "https://passport.example.com/login",
  "done": { "cookies": ["SESSDATA"], "urlPrefix": "https://www.example.com/" },
  "userAgent": "desktop"
}
```
- `url` 可省略，改由可选的 `loginStart(ctx): { url, state? }` 动态给出。
- `done`：`cookies`（这些名字的 cookie 都出现在已授权域名上）和 `urlPrefix`（导航到此前缀）至少给一个；都给时任一满足即完成。
- `userAgent`: `'desktop' | 'mobile'`，默认 `mobile`。

宿主行为：
1. 打开一个全屏登录页，内嵌 `WebView`，顶部显示当前域名、关闭按钮、「刷新」。页内输入用系统键盘。
2. **隔离**：该 `WebView` 用独立的数据目录/Profile，不与别的插件实例或 App 其他网页共享 cookie；每次打开先清空；关闭后清空。只允许导航到 manifest `permissions.hosts`（及该实例的 `userHosts`）内的 https 地址，其余导航拦截并提示域名；不允许 `file:`/`content:`/`intent:`/`javascript:` 顶层导航，不注入任何 JS 接口，禁用文件访问、下载、新窗口、地理位置/相机/麦克风授权。
3. 完成条件满足时：把已授权域名下的 cookie 写进该实例的 cookie 罐（等同 `ctx.cookies.set`），关闭页面，调用 `loginFinish(ctx, { url, state? }): { ok: true, user? }`（插件在这里校验并可把需要的东西存进 `ctx.storage`）。失败抛 `AuthFailed`，界面留在登录入口并显示原因。
4. 用户关闭、超时（10 分钟）、网页加载失败都有明确状态和重试；不泄漏 `WebView`。
5. cookie 值不进日志；`PluginLogger` 对 `loginFinish` 的入参脱敏。
6. 设备没有可用的 WebView 实现时，给出中文说明，不崩溃。

`auth: 'qr'` 和 `'device'` 保留。对声明了 `webLogin` 的 `qr` 插件，登录页上并列提供「在头显里登录」和「用手机扫码」，默认前者。

### 3. 工具与文档

- `tools/plugin-kit`：manifest schema 与 `check` 认识 `auth: 'web'` / `webLogin` / `loginFinish`；`d3plugin run login` 对 web 登录支持 `--cookies '<Cookie 头文本>'` 来模拟宿主写入 cookie 后调用 `loginFinish`。
- `docs/specs/plugin-protocol.md` §4.3、manifest schema、`docs/plugin-dev.md` 补上 `auth: 'web'`，写一个完整小例子。
- `plugins/reference` 增加覆盖 `auth: 'web'` 的用例（可以是第二个参考插件或一个开关），供 App 的一致性测试用。

## 测试

- 导航白名单：授权域名放行、子域通配、非授权域名/非 https/特殊 scheme 拦截。
- 完成判定：只有 cookie、只有 urlPrefix、两者都给；cookie 只取授权域名的。
- cookie 写入实例罐后 `ctx.fetch` 带上；另一个实例看不到。
- 关闭、超时、`loginFinish` 抛 `AuthFailed` 的状态流转。
- 表单：键盘输入路径不启动 `PhoneInputServer`；手机路径仍然可用且原有测试通过。
- 导航与完成判定的逻辑放在不依赖 `WebView` 的纯类里做 JVM 测试；`WebView` 本身的接线保持薄。
- App 与 kit 现有测试全部通过，连续跑两遍。

## 规则

- 可改：`player/`、`tools/plugin-kit/`、`plugins/reference/`、上述文档、`design-opus/ux-spec.md` §4.6。不改 `host/`、`plugins/jellyfin/main.js`。
- 不碰 adb 和头显，不安装 APK，不 push。构建与测试通过后提交（英文说明）。
- 做完用中文逐条回报：改了哪些文件、关键决定（WebView 隔离用的具体机制及其在 Android 10 上是否可用）、测试数量、没做到或有疑问的地方。
