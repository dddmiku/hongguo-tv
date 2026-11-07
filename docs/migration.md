# TV 仓库迁移

2026 年 11 月 7 日起，TV 客户端在 [hongguo-tv](https://github.com/dddmiku/hongguo-tv) 独立维护。旧 [hongguo-desktop-releases](https://github.com/dddmiku/hongguo-desktop-releases) 停止功能开发、版本更新和问题受理，仅保留历史版本及最终迁移入口。

直接下载 [TV 新版](https://github.com/dddmiku/hongguo-tv/releases/latest)，覆盖安装 `hongguo-tv-1.1.3.APK` 或更新版本。不要先卸载；原包名 `cn.hongguo.tv` 与证书保持一致，账号、完整历史、追剧和设置原位保留。升级至 1.1.3 后，应用内“更新”自动读取新 TV 仓库。

旧客户端内置的是旧仓库更新地址，因此归档前保留一份最终清单，将 TV 指向新仓库的迁移版。此清单仅完成一次迁移；以后新版从新仓库继续获取更新。

停留在旧 TV 版本的用户直接点击应用内“更新”，旧入口返回新仓库 1.1.3 的 APK 地址、原签名和完整摘要。按系统安装提示覆盖升级后，之后的更新由 `hongguo-tv` 独立接管。旧入口与 PC 兼容桥共享清单，但 TV 始终只比较 `platforms.android-tv` 的独立版本，不会将 1.9.15 当 TV 版本。

PC 在 [hongguo-desktop](https://github.com/dddmiku/hongguo-desktop) 独立维护，Windows 用户使用 PC 仓库的安装包。TV 安装包与 Windows 安装包、版本号和问题反馈各自独立。

新 TV 仓库保留原 TV 目录的三次源码变更历史，提取时未带入旧 PC 补丁或安装器。签名密钥和本机配置只在维护者机器保存，不上传仓库。
