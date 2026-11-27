# Keep 助手 v2.5

`versionName=2.5` · `versionCode=16` · 2026-11-27

这一版专治「练到一半屏幕变黑，训练和声音也断了」的老毛病，另外给离线时的「下载课程」页补上了长按进设置的入口。

## 修了什么

### 1. 训练中息屏不再中断训练（核心修复）

**根因**：v2.4 及以前用的是 `KEYCODE_SLEEP` 真息屏 —— 相当于替你按了一下电源键。Keep 的训练引擎只要收到 `Activity.onPause`（屏幕灭、按 Home 都算），就会把训练暂停：计时停住（实测停在 00:08）、音频轨全部停掉（`media.audio_flinger` 0 个 active track），亮屏后才从暂停处继续。这不是手机的毛病，是 Keep 自己的规矩。

**现在的做法**：改成「假息屏」—— 给训练页窗口加 `FLAG_KEEP_SCREEN_ON`（防系统超时真关屏），再把 `LayoutParams.screenBrightness` 压到 `0.0f`。屏幕看起来全黑、照样省电，但 Activity 一直在前台，**训练、计时、音频全部继续跑**。碰一下屏幕（`ACTION_DOWN`）立刻恢复亮度，并重新计 7 秒；离开训练页时也会恢复亮度。

**实测**：假息屏后 `mWakefulness=Awake`，计时从 00:16 走到 00:53、动作正常切换；触摸后日志 `window brightness restored` 立即恢复。

### 2. 「下载课程」页也能长按进设置

以前只有「我的课程」标题栏能长按 0.7 秒打开设置页。断网时启动会直接跳到「下载课程」页，那一页没有入口。

现在 `CourseDownloadActivity` 同样接入了标题栏长按：按住左右任意空白处，标题变红 `#D2525E`，0.7 秒后打开 Keep 助手设置页。这一页只做标题长按，不做列表项长按移除。

**实测**：断网冷启动 → 自动进下载页（日志 `downloads resumed: title long-press armed`）→ 左侧空白（x=100）、右侧空白（x=980）分别长按 0.8 秒，均成功打开设置页。

### 3. 设置页文案同步

「训练中自动息屏」的副标题改为：**到时间没操作就屏幕变黑，训练和音频继续；拉到底关闭**。

## 升级方法

直接覆盖安装，不用卸载（签名与 v2.4 一致）：

1. 安装 `KeepAssist-v2.5.apk`（或刷 `KeepAssist-Magisk-v2.5.zip`）
2. Vector / LSPosed 管理器里把「Keep 助手」开关**关掉再打开**，或重启手机
3. 打开 Keep → 冷启动直达「我的课程」
4. 长按标题栏 0.7 秒能进设置页，说明模块已加载

## 文件校验

| 文件 | 大小 | SHA-256 |
|---|---|---|
| KeepAssist-v2.5.apk | 161,081 B | `f8327077499e3837eae828e705296c560122093b6d253296735af2f1ff7975d6` |
| KeepAssist-Magisk-v2.5.zip | 154,089 B | `401b7f1d1765b0918c86c55c56cacdc5f11fafa1c24c23d2bd6d4b46efe7b60d` |

## 已验证清单

- [x] 冷启动直达「我的课程 → 练过」（`JUMP splash -> course @1790ms`）
- [x] 点课程 2 秒自动开练
- [x] 训练中 7 秒假息屏，训练/计时/音频继续（计时 00:16 → 00:53）
- [x] 触摸屏幕恢复亮度并重新计 7 秒
- [x] 训练结束自动回「练过」列表
- [x] 「我的课程」标题栏长按 → 设置页
- [x] 「下载课程」页左右空白长按 → 设置页（本版新增）
- [x] 音量锁定 47%（71/150），退出恢复原音量
- [x] 下载泵读取 `download.db`、队列管理正常
- [x] root：Magisk 中 Keep（uid 10672）与 Keep 助手（uid 10670）均 policy=2 永久允许

## 兼容性

- Keep `com.gotokeep.keep` 9.1.110（versionCode 50223）
- 小米 11（M2011K2C）/ Android 13 / MIUI 14 / Magisk 28.1 / Vector v2.2
- 需要 root 与 Xposed 框架（Vector / LSPosed），作用域仅限 Keep
