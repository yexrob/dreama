# 开源许可与第三方组件声明

depth3d Host 以 **GNU General Public License v3.0 (GPL-3.0-only)** 开源发布。

## 内置第三方组件

| 组件 | 版本 | 来源 | 许可 |
|---|---|---|---|
| Sunshine（内置串流引擎） | v2026.914.233613（commit `63d35f70`） | https://github.com/SunshineStream/Sunshine（原 `LizardByte/Sunshine` 改名后的官方组织） | GPL-3.0-only |
| OpenSSL（随内置串流引擎分发） | 3.x | https://github.com/openssl/openssl | Apache-2.0 |
| miniupnpc（随内置串流引擎分发） | 2.3.x | https://github.com/miniupnp/miniupnp | BSD-3-Clause |
| ICU4C（随内置串流引擎分发） | 78.x | https://github.com/unicode-org/icu | Unicode-3.0 |
| Qt 6 Core/Gui/Widgets/Svg/DBus（随内置串流引擎分发） | 6.11.x | https://www.qt.io/ | LGPL-3.0-only |

## 可选外部集成（不随应用打包）

| 组件 | 来源 | 许可 | 说明 |
|---|---|---|---|
| rclone | https://rclone.org/ | MIT | 仅在用户本机已安装且主动添加网盘来源时调用 |

上述组件的许可均与本应用的 GPLv3 许可兼容。完整许可文本见各自上游仓库与本仓库根目录。
