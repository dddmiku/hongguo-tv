# 红果客厅 TV

独立 TV 源码与更新仓库：[dddmiku/hongguo-tv](https://github.com/dddmiku/hongguo-tv)。安装包在 [Releases](https://github.com/dddmiku/hongguo-tv/releases)，问题反馈在 [Issues](https://github.com/dddmiku/hongguo-tv/issues)。PC 独立维护于 [hongguo-desktop](https://github.com/dddmiku/hongguo-desktop)。

旧混合发布仓库 `hongguo-desktop-releases` 自 **2026 年 11 月 7 日**起停止维护。TV 从 1.1.3 起直接读取本仓库更新清单，继续使用原包名和签名。请覆盖安装新版，账号、历史、追剧和设置会保留，不需要先卸载。详见 [迁移说明](docs/migration.md)。

Android TV / 电视盒子客户端。1.1.0 接手原 TV 项目，沿用 `cn.hongguo.tv` 包名与正式签名，可覆盖升级。

1.3.1 修复国内手机播放签名、压缩请求体与后续集取流，失败时尝试官方备用 CDN 并有界重新取流。此版本提供已在模拟器实际解码验证的 ARM64 包；ARM32 不包含在本次发布中，本轮没有实体电视测试。

新版采用墨蓝与暖金主题，精选、片库、追剧、历史和搜索有独立入口。精选突出一部作品，首屏保留更多片单；片库支持真实体裁与题材筛选、排序和翻页。遥控器左右在行内移动、上下跨行，返回时保留页面与焦点。剧集详情支持直接续播、从第一集看和任意选集。

1.3.0 增加全部季概览。打开剧集详情，可看到同系列各季标题、总集数及当前季，遥控器直接切换季详情；播放时按上键打开设置，选择“全部季”即可从指定季首集播放。总集数读取真实剧集详情，季概览加载失败可重试，当前季仍可选集播放。

开启自动连播后，最后三集查询关联，最后一集提前缓存下一季首集，播完后自动进入下一季。自动续季要求官方同系列关联、连续季号与可播首集；仅标题相似或关联不明确时由用户手动选择。切季保留画质、倍速和画面模式，各季分别保存历史与追剧状态；暂停、快进退、返回和换号会取消旧续播请求。手动选择剧集优先准备当前内容，播放器被系统重建后恢复当前季、集数和位置，换号后不会继承旧账号播放。

1.2.0 增加手机红果短剧 App 扫码登录：账号页选择“扫码登录”，手机进入“我的 → 右上角菜单 → 工具 → 扫一扫”，扫描电视二维码并确认。验证码登录继续保留。二维码到期可刷新，取消、返回或换号会丢弃旧扫码；只有手机账号、历史和收藏均可读取后才保存登录态。扫码协议与验证边界见 [扫码说明](docs/2026-11-09_协议-TV扫码登录-report.md)。

完整历史使用账号隔离的 SQLite，保留真实集数、毫秒位置、观看时间及作品元数据，不按 30/100 条删除。界面每页 24 条，可检索所有历史；云端逐页读取，本机已有记录先显示，失败和断网不清空。旧版没有日期的记录标注“观看时间未知”，不会伪造当天时间。

进度先落本机，后台补传历史与独立续播两份记录；两份都成功才确认。重试保护手机更新的真实观看时间，用户回看较低集数也按最近观看处理。账号切换保留各自片库，上传确认不会清除之后的新进度。

播放器：确定暂停/继续，左右快进退，上键打开播放设置，下键选集。全部季、画质、倍速、比例、自动连播和追剧均能用遥控器操作。字幕式控制提示只在操作时显示；短于五秒的位置也精确保留，退出前立即保存。

## 构建

需要 JDK 11、Gradle 7.5、Android SDK 33。工程使用纯 Java 与系统 View，仅 JNA 是第三方 Java 运行依赖。

仓库自带 GitHub Actions，检查独立发布合同并构建隔离 QA 包；正式发布继续使用维护者保存的原签名密钥。

```powershell
gradle :app:assembleRelease
```

Gradle 产物在 `app/build/outputs/apk/release/hongguo-tv-1.3.1.apk`。正式签名使用本机 `keystore.properties` 和 `keystore/hongguo-tv.jks`，均排除在源码仓库之外。请覆盖安装，不要先卸载旧版。

安装包、入口图标和 TV banner 必须是 PNG；系统安装器不能使用固有尺寸无效的 shape 图标。LEANBACK_LAUNCHER 与 LAUNCHER 保持独立 intent-filter。签名上下文由 Application 统一初始化，直接启动账号、历史或播放器也可使用。

## 验证

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python tools/verify-player-lifecycle.py --install --full
python tools/verify-seasons.py --install
python tools/verify-seasons.py --details
python tools/verify-seasons.py --live
python -m unittest discover -s tools -p test_publish.py
```

`.qa` 使用独立应用 ID 与数据，不操作已安装正式版的账号。设备测试包含真实 SQLite 1500 条分页、搜索、账号隔离、进度冲突、过期确认、删除隐藏，以及真实内容、遥控器焦点、解码播放、暂停与精确续播。新增返回首页、历史、追剧时的延迟取流失败回归、空剧集退出和九轮实际解码返回验收。单独复现生命周期用 `python tools/verify-player-lifecycle.py --install`；报告只覆盖写到 `_work/crash-qa/`。

季测试分为受控播放器集成、真实详情与真实解码续季三个入口，运行前自动将媒体音量设为零。真实解码测试要求独立 `.qa` 未登录，使用《家里家外》第一季第 79 集验证进入第二季第 1 集与旧季进度归属；受控测试通过不等于真实媒体解码通过。报告固定写入 `_work/season-device/`，本轮结果和设备范围见 [验收记录](docs/verification.md)。

1.3.0 已通过 63 项 JVM 测试、4 项发布合同和 31 项静音设备测试，包含真实自动续季与播放器重建后恢复第二季。原签名覆盖安装保留已登录账号、1419 条完整历史、3 部收藏和设置，设备实际安装包与正式构建字节一致。

## 发布

先确认正式包、设备验收和源码提交，再执行：

```powershell
python tools/publish.py --source-commit <已推送的40位源码提交>
```

TV Release 标签为 `v1.3.1`，安装资产使用 `hongguo-tv-1.3.1.APK`。发布器先上传草稿并核对资产大小和 SHA-256，再公开。已公开版本不替换资产；发现修复应递增版本号。

更新清单只发布本仓库的 `platforms.android-tv` 与 TV 版本，无需读取 PC 版本或旧仓库；PC 与 TV 各自拥有源码、发行包、更新清单与版本线。旧仓库保留一份最终迁移清单，供旧安装读取，后续常规发布不再写入旧仓库。

源码与验证边界见 `docs/architecture.md`、`docs/verification.md`。
