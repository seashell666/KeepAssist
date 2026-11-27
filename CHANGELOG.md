# 更新日志

`versionName` 与 `versionCode` 以 `AndroidManifest.xml` 为准。每个版本的详细设计说明见 [完整文档](./README.md) 对应章节。

> 说明：v1.8 起才有明确的 `versionCode` 记录（9），之后的 10 / 11 / 12 / 13 / 14 / 15 依次对应 v1.9 / v2.0 / v2.1 / v2.2 / v2.3 / v2.4。
> v1.3 ~ v1.7 属于早期开发阶段，未保留独立的版本号记录，下面按功能章节归并列出。

## v2.5 · versionCode 16 · 2026-11-27（当前版本）

修训练中「息屏就中断」的老大难，并给「下载课程」页补上长按进设置入口。

**修复**

- **训练中息屏不再中断（假息屏）**：根因是 v2.4 及以前用 `KEYCODE_SLEEP` 真息屏，
  屏幕一灭 Keep 训练引擎收到 `Activity.onPause` 就把训练暂停（实测计时停在 00:08、
  `media.audio_flinger` 0 个 active track），亮屏才从暂停处继续。
  现在改为「假息屏」：给训练页 `Window` 加 `FLAG_KEEP_SCREEN_ON` 并设
  `LayoutParams.screenBrightness=0.0f`，Activity 保持前台，训练/计时/音频全部继续，
  视觉全黑；触摸（`ACTION_DOWN`）即恢复亮度并重新 `armIdle`。
  训练页 `onPause`（离开）时也恢复亮度。实测假息屏后计时 00:16 → 00:53 持续推进、
  动作正常切换，`mWakefulness=Awake`。

**新增**

- **「下载课程」页长按入口**：`CourseDownloadActivity` 接入 `installTouchSpy`，
  新增 `onDownloads()` 与 `handleTitleOnlyTouch()`；左右空白处按住 0.7 秒标题变红
  `#D2525E` 并打开设置页（只做标题长按，不做列表项长按移除）。
  实测断网冷启动自动进下载页后，左（x=100）/右（x=980）空白长按均成功。

**调整**

- 设置页「训练中自动息屏」文案改为「到时间没操作就屏幕变黑，训练和音频继续；拉到底关闭」。

## v2.4 · versionCode 15 · 2026-11-26

设置页改成改一下自动保存、下载面板彻底不再出现，另修退出后音量不还原等 9 项。

**修复**

- **设置页全面自动保存**：以前所有改动必须点最下面的「保存」才落盘（音量拖到 67%，
  退出再进又变回 0%）。现在 Pill / SeekBar / etDays 任何改动 → `autoSave()`（420ms 合并）
  → 写 conf，音量同时立即生效；`Pill` 新增 `onChange` 回调（`setChecked` 不触发，回填不误存）；
  `sLoaded` 门闩防回填期写半成品。保存按钮保留可手动确认。
- **下载面板真正不可见**：`hookDialog` 的 after 不再立刻用 findViewById 判断（那一刻内容未
  inflate，判定必败 → 紧跟 `showPanel` 恢复可见 → 屏幕闪一下）。改为 520ms 后复判：
  `sDlBusy || isDownloadPanel(d)` → 继续 `hidePanel`，否则才恢复。
- **退出 Keep 还原音量**：新增 `static int sVolRestore`，`startVolSession` 记进入前真实音量，
  `stopVolSession` 还回去（实测 75 → 0 → 回桌面 75）。
- **隐藏课程整体停用**：`Cfg.hideCourse` 默认 false，`applyHidden`/`syncCountLabel` 提前 return；
  设备 prefs 里 5 条 `hidden_courses` 已清空；设置页「长按课程移除」默认关，保存不再写 `hide_course=1`。
- **自动开练提速**：`armIdle` 的 auto-GO hold 上限 3→1 秒；`tryWant` 的「已想练，skip」分支补
  `sWantBusy=false`。实测 armed → try#1 间隔 2014ms。
- **下载判定只认 `download.db`**：本地碎片不算已下载（只打 `partial cache`）；
  `checkedFresh` 分支不再阻断重试。
- **下载泵屏幕灭不放弃**：训练优先判断；屏幕灭前 3 次 4 秒快试，之后每 20 秒等亮屏。
- **音量接管（保险）**：新增 `hookVolume()` 拦 `AudioManager.setStreamVolume/adjustStreamVolume`，
  当前 Keep 9.1.110 不抢音量（`vol: keep asked` 未出现）。
- **设置页音量卡可感知**：新增「系统当前音量：X%（cur/max）」，onResume 与保存后刷新。

**兼容**

- 签名不变（`5c74c435…`），v2.3 可直接覆盖升级，无需清数据。

## v2.3 · versionCode 14 · 2026-11-23

修掉三个列表相关的真 bug，并新增三个开关（放在设置页最底部）。

**修复**

- **课程页按返回不再退出 App**：冷启动 `CLEAR_TASK` 后任务栈里只有课程页，按返回/左滑
  会把唯一页面 finish 掉 → 退回桌面。现在拦 `Activity.finish()` 和 `onBackPressed` 两条路，
  课程页是任务栈根时先顶起 MainActivity；同时给冷启动的「防顶替」逻辑加例外
  （之前它会把刚拉起的首页 250ms 内 finish 掉，表现成"按返回没反应"）。
- **长按删除删对那一门**：Keep 的 `PullRecyclerView` 把所有课程行装在同一个直接子 View 里
  （实测高 2376px / 5 门课），旧逻辑取直接子 View 永远拿到整个列表 → 标题恒为第一门。
  改为按行识别：递归找「可点击 + 含 `textName` + 高度不像整个列表」的最小 View。
- **隐藏不再污染复用的 item**：旧实现把 item 压成 `height=0` 后不还原，LayoutParams 跟着
  被复用的 View 走，导致"删 A 结果 B 消失"、删完还显示旧数量。现在每轮扫描会主动还原
  未移除行的高度与可见性。
- **「练过」和「想练」互不影响**：隐藏记录改为 `tab|课程标题`，只作用于当前标签页。

**新增**

- 顶部「N 节课程 / N 节想练的课程」跟随实际剩余数同步（`sync_count`）
- 设置页底部三个开关：长按课程移除（`hide_course`）、移除后同步课程数量（`sync_count`）、
  打开课程时自动加入「想练」（`auto_want`，默认开）
- 旧版隐藏记录（无 tab 前缀）自动迁移为「练过」作用域

## v2.2 · versionCode 13 · 2026-11-22

当前版本。入口与外观收尾，不涉及自动化逻辑改动。

**新增 / 改动**

- 长按「我的课程」**整条标题栏**（含左右留白）0.7 秒打开设置页；按下瞬间标题变红 `#D2525E`，松手恢复
- 命中范围按标题父容器高度自适应：向上找 2 层、取高度 < 12% 屏高的容器，硬夹在 18% 屏高内，不吃到列表第一项
- 短按（< 700ms）、提前松手、手指滑走都不触发；与列表项长按移除（550ms）是两套独立判断
- 从 Keep 进程拉起设置页改走 root `am start`，绕开 MIUI「应用链式启动」确认框；1.6 秒未切前台则用普通 `startActivity` 兜底
- **移除桌面图标**：不再声明 `MAIN` / `LAUNCHER`，Vector / LSPosed 仍靠 `xposedmodule` meta-data 识别模块
- 图标白色 K 由 `#FF3B30` 改为 `#D2525E`；深紫渐变底与右上蓝星保持不变

**文档修正**

- 修正 v1.8 章节「桌面会多一个图标」「保持打开时的音量」「改完需强制停止 Keep」等与当前版本冲突的旧表述
- 安装步骤改为长按标题栏入口
- 新增常见问题：连不上已保存 Wi-Fi 是否与模块有关、Vector 模块列表图标缓存

## v2.1 · versionCode 12

- 复用 Keep 应用图标作为模块图标，白色 K 改红 `#FF3B30`，Vector / LSPosed 模块列表可显示
- 初版长按「我的课程」标题文字打开设置页

## v2.0 · versionCode 11

把「会打扰你」的行为全部按住。

- **音量重做**：进入 Keep 默认把媒体音量设为 0（静音），不再「保持进入时的音量」；支持固定百分比或 `vol_target=-1` 跟随进入时音量；息屏期间继续锁
- 删除详情页 prewarm，2 秒直接 GO；下载移到练完之后的队列泵
- 切后台不再被拉回 Keep（`keepInForeground`，后台停止 `wrapTick` / `pump`）
- 权限弹窗不再被误点（`isPermissionLike`：权限 / 允许 / 拒绝 / 授权 / 后台运行 / 隐私一律留给用户）
- 透明面板只作用于下载面板，非下载弹窗正常显示
- 已下载的课直接跳过，一个 UI 都不点；后台线程查 `download.db` 的 `workoutId` 前 20 位 + 本地文件
- 读数据库移出主线程，避免 ANR

## v1.9 · versionCode 10

- 设置页视觉重做：淡紫渐变背景、白色圆角卡片、自绘 `Pill` 开关、紫色保存按钮
- 下载过程全程隐形（`Dialog.show()` before/after 处理）
- 真正 2 秒开练；详情页「为你推荐」不再打断开练
- 长按课程从「练过」移除

## v1.8 · versionCode 9

- 新增设置界面，全部行为可配置（`/data/adb/keepassist.conf`）
- 修掉三个「抢你」的 bug：`onDetail` re-resume 复活自动开练、课程 id 丢失、训练结束被拉回前台
- 下载可靠性加固；权限弹窗误点、付费课跳购买页等边界处理
- 记录「练过却不在下载课程页」的历史课无法补回的限制

## v1.7

- 把「下载」真正做成：点 GO 后一边训练一边下载
- 断网时练的课联网后自动补；下载状态检查去重
- 澄清「下载课程」页显示 0 M 不代表没下载

## v1.6

- 自动开练改成「一碰就作废」三条规则
- 练完立刻下载，不再等下次进详情页

## v1.5

- 离线可练：无网络时启动直接去「下载课程」纯本地页
- 练过一次就自动下载
- 修掉点「…」时被自动开练抢走

## v1.4

- 训练结束自动亮屏
- 媒体音量锁（初版：保持打开 Keep 时的音量）

## v1.3

- 课程详情页 2 秒无操作自动开练
- 训练中 7 秒无操作自动息屏

## v1.0 ~ v1.2

基础能力：开屏广告秒跳过、启动直达「我的课程 → 练过」、弹窗与广告自动处理、训练完成收尾链路。
