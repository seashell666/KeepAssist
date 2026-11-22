# Keep 助手 · KeepAssist

针对 Keep（`com.gotokeep.keep` 9.1.110）的 Xposed 模块，运行在 **Vector / LSPosed** 上。

它把「打开 Keep → 找到课 → 点开始」这一串操作压到最少：跳过开屏广告、启动直接到「我的课程 → 练过」、进详情页 2 秒不动就开练、训练中自动息屏、练完自动回到列表并把课补下载好。全部行为可在设置页配置。

> **当前版本 v2.2**（`versionName=2.2`，`versionCode=13`，2026-11-22）
> 实测环境：小米 11 / Android 13 / MIUI / Keep 9.1.110 / Magisk 28.1 / Vector
> 需要 **root** 与 Xposed 框架，作用域仅限 Keep。

## 功能概览

| 需求 | 做法 |
|---|---|
| 开屏广告 | 开屏页创建后 10ms 内切走，广告来不及加载 |
| 直达练过列表 | 跳过首页，拉起 `MyCourseActivity` 并切到「练过」tab |
| 详情页自动开练 | 2 秒无操作自动点 GO；碰过一次屏幕本次就作废（延迟可调） |
| 训练中省电 | 7 秒无操作注入 `KEYCODE_SLEEP` 息屏 |
| 训练结束亮屏 | 离开训练页且屏幕黑着时注入 `KEYCODE_WAKEUP` |
| 进 Keep 静音 | 默认媒体音量设为 0；可改固定百分比或跟随进入时音量 |
| 离线可练 | 无网时启动直接去纯本地的「下载课程」页 |
| 练过自动下载 | 练完入队，回到列表且停手后补下；已下载的课直接跳过 |
| 弹窗/广告 | Hook `Dialog.show` / `PopupWindow` / `startActivity`，按特征自动处理 |
| 练完收尾 | 自动点「完成」、关分享页、把岔路拉回「练过」 |
| 长按移除 | 长按课程项从「练过」隐藏 |
| 长按打开设置 | 按住「我的课程」**整条标题栏** 0.7 秒，标题变红 `#D2525E` |

## 安装

从 [Releases](https://github.com/seashell666/KeepAssist/releases) 下载 `KeepAssist-Magisk-v2.2.zip`（推荐，自动装 APK 并配置 root 权限）或 `KeepAssist-v2.2.apk`：

1. 刷入 Magisk 包，或手动安装 APK
2. Vector / LSPosed 管理器 → 模块 → 启用「Keep 助手」
3. 作用域勾选 **Keep (`com.gotokeep.keep`)**
4. 强制停止 Keep 后重新打开

命令行启用（需要 root）：

```sh
su -c '/data/adb/lspd/cli modules enable com.shellz.keepassist'
su -c '/data/adb/lspd/cli scope set com.shellz.keepassist com.gotokeep.keep/0'
```

## 打开设置页

**v2.2 起桌面没有图标**，唯一入口是：

> 打开 Keep →「我的课程」→ 按住顶部**整条标题栏**（左右留白也算）0.7 秒

标题按下即变红，松手恢复；短按不触发。第一次会弹一次 Magisk 授权框（写 `/data/adb/keepassist.conf`）。

也可以直接改配置文件，保存后最多 15 秒生效，无需重启 Keep：

```sh
su -c 'cat > /data/adb/keepassist.conf' <<'EOF'
auto_go=1
auto_go_delay=2
sleep_sec=7
vol_lock=1
vol_target=0
auto_download=1
wifi_only=1
checked_days=7
EOF
```

| 配置项 | 默认 | 说明 |
|---|---|---|
| `auto_go` | 1 | 详情页自动开练总开关 |
| `auto_go_delay` | 2 | 无操作多少秒后开练（1~10） |
| `sleep_sec` | 7 | 训练中无操作多少秒息屏（0=关闭） |
| `vol_lock` | 1 | 是否自动设定音量 |
| `vol_target` | 0 | 目标音量；`-1` = 跟随进入 Keep 时的音量 |
| `auto_download` | 1 | 练过的课自动补下载 |
| `wifi_only` | 1 | 只在 Wi-Fi 下自动下载 |
| `checked_days` | 7 | 下载状态复检周期（天，0=只确认一次） |

## 文档

- [v2.2 发布说明](RELEASE_NOTES.md) — 本次改了什么、升级步骤、文件校验
- [更新日志](CHANGELOG.md) — v1.3 → v2.2 完整版本历史
- [完整技术文档](docs/README.md) — 每个版本的设计取舍、实测数据、日志、已知限制
- [构建与发布](docs/BUILD.md) — 工具链准备、构建脚本、release 如何自动生成

## 已知限制（摘要）

- 自动开练的 2 秒是「无操作」的 2 秒，加上 Keep 启动训练页约 1~1.5 秒，实际看到训练画面约 3~3.5 秒。
- 已练过但只缓存了一星半点的历史课补不回来：Keep 对「已下载」是幂等的，模块无法强制重下。
- 设置页不跟随系统深色模式，开关为自绘控件；权限类弹窗一律留给用户自己点。
- 连不上某个已保存的 Wi-Fi 与本模块无关：模块只在 Keep 进程内只读查询网络状态，不申请任何权限、不调用 `WifiManager` 的写操作。

完整清单见 [docs/README.md](docs/README.md) 的「已知限制」与「常见问题」章节。

## 兼容性声明

本项目是针对特定 Keep 版本与特定设备的个人自动化工具，依赖 Xposed Hook 的 Activity / View 结构与文案特征。Keep 更新后可能需要按日志微调。非官方项目，与 Keep 无关。
