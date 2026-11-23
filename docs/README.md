# Keep 助手 (KeepAssist)

> **当前版本 v2.3**（`versionCode=14`，2026-11-23）
> 实测环境：小米 11 / Android 13 / MIUI / Keep 9.1.110 / Magisk 28.1 / Vector
> 安装包见 GitHub Releases；版本历史见 [CHANGELOG.md](../CHANGELOG.md)，本次发布说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。

针对 Keep（`com.gotokeep.keep` 9.1.110）的 Xposed 模块，运行在 **Vector / LSPosed** 框架上。

## 它做了什么

| 需求 | 实现方式 |
|---|---|
| 跳过开屏广告（KFC 那张） | 开屏页创建后 **10ms 内**直接切走，广告根本来不及加载 |
| 进去就直达「我的课程 → 练过」 | 跳过首页，直接拉起 `MyCourseActivity` 并自动切到「练过」tab |
| **课程详情页 2 秒不动就开练**（v1.3，v1.6 收紧，v1.8 可调，v2.0 恒定 2 秒） | 进详情页后 2 秒无操作自动点 GO；**只要碰过一次屏幕（含滑动、点空白）本次就彻底不自动开练**；延迟和开关都能改 |
| **训练中 7 秒不动就息屏**（v1.3） | 训练页同样计时，7 秒无操作注入 `KEYCODE_SLEEP` 直接熄屏省电 |
| **训练结束自动亮屏**（v1.4） | 检测到离开训练页且屏幕是黑的，注入 `KEYCODE_WAKEUP` 点亮，让你看得到总结页 |
| **进入 Keep 按目标音量播放**（v1.4，v2.0 重做） | 默认把媒体音量压到 **0（静音）**，你在外面把音量开到多大都不影响；可在设置页改成固定百分比或"跟随进入时音量"；息屏期间也继续锁 |
| **没网也能练已缓存的课**（v1.5） | 检测到无网络（含"连上了但没有外网"）时，启动直接去「下载课程」这个纯本地页，点进去就能开练 |
| **练过的课自动下载**（v1.5，v1.7 重做，v1.8 加固，v2.0 改为后台补） | 练完入队，回到列表页且你停手后才补下；已下载过的课直接跳过，**不再打开右上角面板**；默认只在 Wi-Fi 下下、7 天复检一次 |
| 屏蔽中途悬浮弹窗 | Hook `Dialog.show` + `PopupWindow.show*`，识别广告特征后自动点关闭 |
| 练完后卡在运动总结页 | 识别左上角「完成」并自动点击（**只在训练刚结束时**，翻历史记录不会误触） |
| 练完后被丢到日程页 / 弹分享页 | 收尾看门狗把一切岔路拉回「练过」，日程页和分享页在启动时就被改道 |
| 训练完成后的奖牌广告 | 识别「x s 后可关闭」倒计时按钮，**不等倒计时**直接点掉 |
| 「继续努力」弹窗 | 检测文案后自动点确定类按钮 |
| 广告 SDK 落地页 | Hook `startActivity`，命中优量汇/穿山甲/汇川等 SDK 直接阻断 |
| **长按课程从「练过」移除**（v1.9） | 长按列表项 → 紫色确认弹窗 → 该项本地隐藏，不再出现在列表 |
| **长按标题栏打开设置页**（v2.1，v2.2 扩到整条） | 在「我的课程」页按住顶部**整条标题栏**（左右留白也算）0.7 秒，标题变红 `#D2525E` → 打开 Keep 助手设置页 |
| **桌面无图标**（v2.2） | APK 不再声明 launcher，桌面/抽屉不出现「Keep 助手」，只留长按入口 |
| **自己的图标**（v2.1，v2.2 改色） | 复用 Keep 的 K 图标，白色 K 换成 `#D2525E`；Vector/LSPosed 模块列表显示它 |

---

## v1.3 新增：无操作自动化

两个功能共用一套「多久没摸屏幕」的计时：

- 触摸来自窗口根 View 的 `dispatchTouchEvent`，**不是** `Activity.dispatchTouchEvent`
  （详情页上面盖着一层 Keep 自己的 PopupWindow，事件压根到不了 Activity，
  详见后文「触摸检测为什么绕了四个弯」）
- 每次 `ACTION_DOWN` / `ACTION_MOVE` 都把倒计时重置
- 页面一 `onPause`（返回、切后台、跳别的页）就立刻取消，不会在别的页面误动作

### 1. 课程详情页 2 秒自动开练

```
我的课程（练过） → 你点某个课程
      ↓
课程详情页（CourseDetail8Activity）
      ↓ 2 秒没有任何触摸
自动点底部绿色 GO → 进入训练（TrainingEngineActivity）
```

**怎么点的**：页面里有两个 `Detail8GoCellView`，一个是真正显示的、一个是
`floatingGoCellView` 副本（GONE）。只认 `isShown()` 的那个，取它内部
`startContainer`（实测 `clickable=true`）执行 `performClick()`；
找不到就退回按屏幕比例注入点击（宽 50%、高 88.1%）。

**点空会重试**：页面骨架屏还没渲染完时按钮可能取不到，所以 1.2 秒后若仍停在详情页
会自动重来，最多 3 次。期间你自己碰了屏幕就放弃，不跟你抢。

### 2. 训练中 7 秒自动息屏

```
训练页（TrainingEngineActivity）
      ↓ 7 秒没有任何触摸
注入 KEYCODE_SLEEP(223) → 屏幕直接熄灭
```

**为什么要 root**：模块跑在 Keep 进程里（普通 uid），没有 `DEVICE_POWER` 权限，
`PowerManager.goToSleep()` 会被系统拒绝。所以走 Magisk 的 `su` 注入按键。
刷 Magisk 包时会自动把 Keep 的 su 策略写成「允许」，不会弹授权框。

**息屏后**：训练继续跑，音频照常。你点亮屏幕后重新计时，再 7 秒不动又会自动熄屏。

---

## v1.4 新增：训练结束亮屏 + 音量锁

### 1. 训练完成后自动亮屏

v1.3 的「7 秒不动就息屏」有个副作用：练到后期屏幕是黑的，练完 Keep 弹出总结页，
你看到的还是黑屏，自动点「完成」也等于在暗处操作。

现在只要**检测到离开训练页、且屏幕确实是灭的**，就注入 `KEYCODE_WAKEUP(224)`
点亮屏幕（后面跟一句 `wm dismiss-keyguard` 兜底，防止灭屏久了锁屏被拉起来）。

```
训练页 →（7 秒无操作）→ 息屏 → 练完 → 总结页出现
                                    ↓
                     "training ended -> next=VpSummaryActivity"
                                    ↓
                          wake screen → 亮屏 → 再点「完成」
```

**为什么这个信号在黑屏下也可靠**：实测过——屏幕 `mWakefulness=Asleep` 时
用 `am start` 拉起另一个 Activity，模块的 `onResume` 照样被回调
（日志里 `resume: ...CalendarActivity` 正常打出）。所以「训练页 → 别的页面」
这个跳转信号不受息屏影响。

**会不会把亮着的屏幕关掉**：不会。`KEYCODE_WAKEUP` 只唤醒；而且注入前先读
`PowerManager.isInteractive()`，屏幕本来亮着就直接跳过，不打搅你。
3 秒内的重复触发也会被去抖。

### 2. 媒体音量锁（保持打开 Keep 时的音量）

```
打开 Keep → 记下此刻的媒体音量作为基准（例如 70）
    ↓
会话期间每 1.2 秒查一次：被改成 90 了
    ↓
1.2 秒内拉回 70（走 AudioManager，不弹 UI、不影响你）
```

**基准怎么定**：每次 Keep 从前台到后台再回来，都会重新取一次基准。
所以想换音量就是——**退出 Keep → 调好音量 → 再进 Keep**，新音量就是新基准。

**会话边界**：内部维护一个「已 resumed 的页面集合」，集合空 = Keep 到后台了，
会话结束；从空变非空 = Keep 刚被打开，重新取基准。

**怎么设的**：优先 `AudioManager.setStreamVolume(STREAM_MUSIC, v, 0)`
（Keep 自己有 `MODIFY_AUDIO_SETTINGS`，已确认授予），设完再读一次确认生效；
真没生效才退到 root 的 `cmd media_session volume --stream 3 --set N`（5 秒节流）。
实测走的是 API 那条，没有用到 root。

实测记录：

```
vol session start, baseline=70
vol drifted 60 -> restore 70
vol -> 70 (api)
...
回桌面把音量改成 100，重开 Keep：
vol session end (baseline=70)
vol session start, baseline=100     ← 新基准生效，不再拉回 70
```

> ⚠️ 会话期间是**严格锁死**的：在 Keep 里按音量键会在 1.2 秒内被拉回。
> 临时想静音/调音量，先退出 Keep 调好再进来。如果你更想要「手动调过的就采纳」，
> 告诉我改一下逻辑即可。

---

## v1.5 新增：离线可练 + 练过的课自动下载

### 1. 没网也能进课程开练

断网后「我的课程 → 练过」这个列表是**接口数据**，会变成
「网络未连接 / 请检查你的网络设置后刷新」，一个课都没有 —— 但课程本身
（`files/keep/course_<id>`、`plan_cache_<id>`、训练视频）都是本地缓存的。

Keep 里另有一个**纯本地**的列表页：`CourseDownloadActivity`
（「我的课程 → 某课程 → … → 下载训练设置 → 管理已下载课程」）。
它不查接口，直接读本地下载记录，断网照常显示。

所以模块在三个入口都做了判断：

```
无网络时：
  开屏页 → CourseDownloadActivity（下载课程）
  MainActivity（其它 App 拉起）→ 同上
  MyCourseActivity（练过列表是空的）→ 同上
有网络时：一切照旧（→ MyCourseActivity 的「练过」）
```

网络判断用 `ConnectivityManager.getActiveNetwork()` + `NET_CAPABILITY_INTERNET`，
读不到状态时按「有网」处理（宁可走老路，不把人扔到离线页）。

实测（关 Wi-Fi 冷启动，全程无网）：

```
JUMP splash -> downloads(offline) @642ms
main: rogue start over target -> finish main      ← 见下方"补刀"
resume: ...CourseDownloadActivity
```

点进「俯卧撑入门」：详情页正常渲染 → 2 秒后自动点 GO →
`TrainingEngineActivity` 里视频正常播放（截图为「1/14 肩部深层激活」），
全程零网络请求。

**为什么要"补刀"**：Keep 的 Splash 在我们 `finish()` 它之后，仍会把自己
「进主页」那套流程跑完，几百毫秒后把 `MainActivity` 压在下载课程页上面。
只跳一次会停在主页，所以 `onResume` 里再加一条：冷启动 4 秒内如果
`MainActivity` 又冒出来，直接 `finish()` 掉它，下层的下载课程页就露出来了
（比再跳一次干净，没有闪屏）。同理，冷启动途中被 Keep 自己的弹层
（例如「你还有训练未完成」）挤回主页时，弹层一关会补一次跳转；
**不会替用户点「退出」**，那是他自己的训练进度。

### 2. 练过一次，就自动下载

```
训练开始 → 从详情页的 Intent 拿到 collectionId 存起来
    ↓
训练结束 → 课程 id 进「待下载」队列（SharedPreferences 持久化）
    ↓
下次进这门课的详情页 → 自动执行：
      点右上角「…」(setContainer)
        → 面板里点「下载」(imageFunc)
        → 下载设置面板里点「全部下载」(textRight)
        → 关掉面板，把 2 秒自动开练的计时重新挂上
```

- 已经下载过的课：面板里显示「已下载」，直接出队，不点任何东西
- 面板是异步挂载的，每一步都带重试；「…」点空了（页面还没渲染完）会整步重来
- 机器人干活期间**禁止自动点 GO**，否则页面被抢走、下载就断了
- 有 25 秒看门狗兜底，任何一步卡住都不会把自动开练永久冻住
- 队列存在 `SharedPreferences("keepassist")`，杀进程不丢

实测：点击那一刻面板里出现「8 分钟缓解肩颈酸痛 改善含胸驼背 训练 15.56M」，
之后 `Movies/MultiVideos` 从 40M 涨到 56M；再进同一门课，面板显示「已下载」。

### 3. 修掉：点「…」的时候被自动开练抢走

之前进详情页想点右上角「…」看下载设置，点着点着 2 秒到点就被自动 GO 推进训练。

根因：右上角「…」弹出的是**独立 Dialog 窗口**（`ty=APPLICATION` + `DIM_BEHIND`），
里面所有点击都不经过 Activity 的 DecorView，所以"多久没摸屏幕"完全感知不到，
计时照走，2 秒一到就替他开练了。

现在 hook `Dialog.show` / `Dialog.dismiss` 维护一个「当前打开的弹层」集合：

```
详情页上只要有弹层 → 立刻取消自动开练计时
弹层关掉          → 重新挂 2 秒计时（你手动关掉面板后照样会自动开练）
真正开练前再查一次 → 还有弹层就不点
```

实测日志：`dialog opened on detail -> auto-GO cancelled`，之后停在详情页 6 秒不动也不会被带走。

---

## v1.6：自动开练改成"一碰就作废" + 练完立刻下好

### 1. 为什么又改了一次

v1.5 的做法是"碰屏幕就把 2 秒往后推"：滑几下、停手 2 秒，照样被推进训练。
实际用起来就是——**你正在看课程介绍、正在滑动，一停手就被抢走**。

更糟的是「练完回到详情页」：结束训练 → 退回详情页 → 2 秒后又被推回训练页，
想干点别的都不行。

### 2. 现在的规则（三条）

```
① 进详情页 → 2 秒不动 → 自动点 GO（老功能保留）
② 这 2 秒内只要碰过一次屏幕（滑动、点空白、点任何地方）
   → 本次进入页面的自动开练永久作废，GO 交给你自己点
③ 刚练完 60 秒内回到详情页 → 一律不自动开练（练完 → 回详情页 → 又被推进训练的死循环）
```

判断"碰过屏幕"用的是窗口根 View 的 `dispatchTouchEvent`（见后文「触摸检测为什么绕了四个弯」），
详情页、弹层窗口、DecorView 三处都挂，任何一次 `ACTION_DOWN` / `ACTION_MOVE` 都算。
进页面后 400ms 内的触摸会被忽略（那是上一个页面点进来的残留）。

实测：

```
detail resumed -> auto-GO armed (2s)
idle timer off (user touched detail)       ← 我滑了一下
auto-GO cancelled: user is operating
（10 秒后仍停在详情页，不会再被推进训练）

training ended -> next=...CourseDetail8Activity
detail: just trained 0s ago -> auto-GO off  ← 练完回详情页，不抢
```

### 3. 练完立刻下好（不再是"下次进详情页才下"）

```
训练结束（含中途点红方块结束）
    ↓ 记下课程 id，进待下载队列（SharedPreferences）
    ↓ 等收尾流程走完（总结页 → 完成 → 回我的课程，约 6 秒）
自动打开该课详情页
    ↓ 点「…」→「下载」→「全部下载」
    ↓ 盯住面板直到出现「1.05M/6.62M · 正在下载」
      （看到这个才关面板 —— 关早了 Keep 还没把任务派出去，等于白点）
    ↓ 关面板、自动 finish 回我的课程
```

**v1.5 为什么没下成**：点完 1.5 秒就关面板、2 秒后又自动开练。
训练一抢走前台，下载就没落盘（`download.db` 无记录、`Movies` 目录不增长）。
现在点完会盯着面板等派发，且**本次进入详情页不自动开练**。

实测（一轮完整链路）：

```
dl: queued 6a54fd881b21cf0001fd759e
dl: scheduled right after training 6a54fd881b21cf0001fd759e
dl: auto opened detail for 6a54fd881b21cf0001fd759e
dl: 全部下载 clicked=true
dl: dispatch confirmed -> close panel
dl: robot done (dispatched)
dl: closing auto-opened detail (done)
（Movies 目录 52M → 74M，页面自动退回我的课程）
```

其他保护：机器人干活期间禁止自动 GO；同一个课程 id 不会重复点两遍；
25 秒看门狗 + 45 秒"自动打开的详情页"兜底关闭；离线时队列留在本地，下次联网再下。

### 4. 关于「下载课程」页里显示 0 M

实测确认：**显示 0 M 不等于没下载**。

- Keep 的下载记录在 `/data/data/com.gotokeep.keep/databases/download.db`，
  键是**课程 id 的最后一个字符 +1**（课程 `...7441` → 下载键 `...7440`），
  `status=1` 就是已下载。
- 断网实测：0 M 的那门「20 分钟无绳音乐燃脂跳绳」从「下载课程」页点进去，
  详情页正常、点 GO 后视频正常播放（有截图）。
- 显示 0 M 的原因是这门课是 `courseType=screenVideo`（21 分钟整段一镜到底视频），
  Keep 对这类课程不统计「已下载体积」，所以那一栏永远是 0 M。
  逐动作课程（如「俯卧撑入门」）才有正常的 12.97M 这种数字。
- 「8 分钟缓解肩颈酸痛」在下载课程页删掉后，「我的课程 → 练过」里仍然会有——
  那是训练记录列表，两个不是一回事。

---

## v1.7：把「下载」真正做成

### 1. v1.6 的两个硬伤

**硬伤一：点完就当作下好了。**
点完「全部下载」立刻把课程从待下载队列里删掉。可面板上什么都没变、
`download.db` 也没多记录的情况下也照删——失败被当成成功了。

**硬伤二：用面板文案判断“下载开没开始”。**
实测发现：点击确实生效了（数据库增加了记录），但**面板文案一个字都不变**。
反过来，面板里本来就有「已下载」「6.62M」这些字，也会被误判成“正在下载”。

### 2. 现在怎么判断（关键）

直接读 Keep 自己的下载库：

```
/data/data/com.gotokeep.keep/databases/download.db
    table download_data(workoutId, status, downloadTime)
```

点「全部下载」前记一个快照（`条数:最新时间`），点完之后轮询，
条数变多或时间变新 = 任务真的派出去了。

实测对比（同一次点击）：

```
dl: db before=7:1795254266481
dl: 全部下载 clicked=true
dl# wait0 db=8:1795254565880 ui=false      ← 面板没变化，但库变了
dl: dispatch confirmed -> close panel (dbChanged=true)
```

### 3. 「点 GO 就一边训练一边下载」

```
进详情页
  ↓ 2 秒不动（说明你确实要练，不是随便看看）
点「…」→「下载」→「全部下载」
  ↓ 确认数据库真的多了一条（约 1 秒）
点 GO 开练
  ↓ 下载任务已经在 Keep 后台跑了，训练开着也照下不误
```

实测：

```
auto-GO: prepare download first
dl: first visit, queued 565bfb0f2f5f272b8a7880eb
dl# options text=已下载
dl: already downloaded -> dequeue
auto-GO re-armed after download
DETAIL auto-GO try#1 ok=true
auto-GO confirmed (left detail)
```

已下载的课只多花 1 秒（打开面板看一眼就走），没下载的课会真的往**下**，
然后照常开练。

**你只要滑动一下屏幕，上面这套就不会跑**——那说明你只是想看看课程，
GO 交给你自己点。

### 4. 断网时练的课，联网后自动补

Keep 是**训练时边播边存**，所以断网时练过的课什么也没存下——
这就是「练过却不在下载课程页」而且「断网点 GO 报网络错误」的原因。

现在断网时练完的课会进队列（SharedPreferences 持久化，杀进程不丢），
等你**停在课程列表页、屏幕亮着、网又回来了**，就一门一门自动补下：

```
pump: armed in 5000ms, queue=1
pump: open 6229f5a50163030001e71936 (1 left)
dl: 全部下载 clicked=true
dl: dispatch confirmed -> close panel (dbChanged=true)
dl: closing auto-opened detail (done)
pump: queue empty, done
```

实测：`download.db` 5 → 6 条，`Movies` 76M → 91M，
「5 分钟箭步蹲」14.22M 出现在下载课程页。

保护：用户刚碰过屏幕不抢页面（等 3 秒）；同一门课最多试 2 次；
不在课程列表页会重试 5 次后放弃；40 秒看门狗。

### 5. 不要重复检查

每门课第一次进详情页会做一次「下载状态确认」，结果记在
`keepassist.xml` 的 `dl_checked` 里，之后不再重复——否则每次进详情页都要多等 1 秒。

### 6. 补充：0 M 的课也能练

「5 分钟箭步蹲」训练过一次后，在下载课程页显示 0 M，但在详情页里显示「已下载」，
断网点 GO 也能正常播。

**结论：详情页的「已下载」看的是本地视频文件，下载课程页的体积看的是另一套统计。
两者不同步，但能不能离线练只取决于文件在不在。**

---

## v1.8：修掉三个会「抢你」的 bug + 全部可配置

v1.7 之前的功能都在，这一版主要修「模块自以为是」的地方。

### 1. 设置界面（新）

装完模块，桌面会多一个「Keep 助手」图标，点开就是设置页（**v2.2 起桌面图标已移除**，设置页改为长按「我的课程」标题栏 0.7 秒打开，见 v2.2 章节）：

| 设置项 | 默认 | 说明 |
|---|---|---|
| 详情页自动开练 | 开 | 关掉后进详情页只做下载，不替你点 GO |
| 开练延迟 | 2 秒 | 1~10 秒，碰一下屏幕本次仍然作废 |
| 进入 Keep 自动设定音量（v2.0 起，原名「保持打开时的音量」） | 开，目标 0（静音） | 关掉后音量键恢复正常 |
| 训练中自动息屏 | 开（7 秒） | 滑块拉到 0 = 关闭 |
| 练过的课自动下载 | 开 | 关掉后只保留手动下载 |
| 只在 Wi-Fi 下自动下载 | 开 | 关掉后蜂窝也下整门课，注意流量 |
| 下载状态复检周期 | 7 天 | 0 = 只确认一次，以后不再复检 |

配置写在 `/data/adb/keepassist.conf`（root 读写），Keep 进程最多每 15 秒读一次。
**v2.0 起保存后最多 15 秒生效，不必重启 Keep**（早期版本需要强制停止再打开）。

没有 root 时设置页会明确提示「保存失败」，不会静默吞掉。

### 2. 三个真 bug

**① 练完会把你从别的 App 拉回 Keep**

老代码在训练结束 6 秒后直接 `startActivity` 打开详情页去下载。
`sCur` 只是「最后一个 resume 的 Keep 页面」，Keep 退到后台它也不会变 ——
所以你在微信里，模块照样会把 Keep 顶到前台，下载完再关掉。

现在所有自动跳页前都要过一道 `keepInForeground()`：
必须有页面处于 resumed 状态、且栈顶就是 Keep。不在前台就留在队列里，
等你下次回到课程列表，由队列泵接手。

**② 「碰一下屏幕就取消」会被一次 resume 撤销**

`onDetail()` 原本无条件把 `sUserTookOver` 置回 `false`。
你碰过屏幕 → 切一下后台再回来 / 某个弹层导致 Activity 重新 resume → 2 秒计时复活，
又被推进训练。

现在区分「同一次进入」和「新一次进入」：同一个 Activity 实例、120 秒内再次 resume
不重置任何状态；已经交给用户（碰过屏幕或已点过 GO）就直接返回，不重新挂计时。

**③ 训练中途退出再练，课程 id 丢了**

进训练页时会把 `sDetailCourseId` 清空。而训练中途退出回到的是**同一个详情页实例**，
`onCreate` 不会再跑 —— 第二次点 GO 就继承不到 id，练完日志是
`dl: training course id unknown, skip`，这门课永远不进下载队列。

现在：
- `onDetail()` 每次都从 Intent 重读 `collectionId`
- 进训练页时不再清空 `sDetailCourseId`
- 训练页自己的 Intent 也作为兜底（只认 `collectionId` / `courseId`，不做全量扫描，
  免得把 `userId` 之类的 24 位 hex 当成课程 id）

日志会打 `training cid=...` 或 `training cid unknown (...)`，一眼能看出走的是哪条。

### 3. 下载更靠得住

**下载状态不再终身信任。** `dl_checked` 从「一堆 id」改成「id@时间戳」，
默认 7 天后重新确认一次。你在 Keep 里手动删了下载、或者任务派发后被取消，
不会再被永久标成「下过了」。

**派发后再确认一次。** 点完「全部下载」只能证明 Keep 收下了任务，
所以 90 秒后会量一下这门课的 `files/keep/course_<id>` 目录：
没变大就撤回「已确认」并重新入队（每门课最多补一次）。
前后都是 0 的情况会判为「无法判断」直接跳过，避免路径不对时反复重下。

**只吃 Wi-Fi。** 进详情页 2 秒不动就会下**整门课**，大课几百兆。
默认只有 Wi-Fi 才自动下载，蜂窝只留在队列里，联网条件满足再补。

**网络要「真的能上网」。** 除了 `NET_CAPABILITY_INTERNET`，下载还要求
`NET_CAPABILITY_VALIDATED` —— 连上酒店/机场那种没登录的 Wi-Fi 时不会硬着头皮下。

**队列泵更克制。** 手停时间从 2.5 秒提到 4 秒，后台一律不动手。
你停在列表上想事情，页面不会再自己跳走。

### 4. 实测：为什么「练过却不在下载课程页」还是补不回来

v1.8 一开始想的是：详情页显示「已下载」时不要直接收工，进下载面板点一次
「全部下载」，让 Keep 自己写 `download.db` 记录。真机验证（「每天2分钟：改善肩关节受限」）结果：

```
dl# options text=已下载
dl: options says 已下载, still open download sheet
dialog+ => 下载训练设置 | 全部下载 | 1. 每天2分钟… | 训练 | 1.86M · 已下载
dl: db before=9:1795254616220
dl: 全部下载 clicked=true
dl# wait0 db=9:1795254616220
dl: dispatch unconfirmed after 4500ms, close anyway
dl: robot done (no-dispatch)
```

**Keep 对「已下载」的课是幂等的**：点「全部下载」只是把面板关掉，不会重写
`download.db`。所以这类课永远不会出现在「下载课程」页——不是模块没点，是 Keep 不写。

能做的只有两条：
- 新练的课在训练前就真正走一遍「全部下载」，这类课会正常出现（v1.7 起）
- 历史课（当年训练边播边存下来的）只能从详情页点进去练，下载课程页看不到

顺带确认了 `armVerify` 的路径是对的：
`/data/data/com.gotokeep.keep/files/keep/course_<cid>` 确实是文件（实测 81344 字节）。

### 5. 顺手修的误判

- 下载面板「有没有课程条目」的判断从 `contains("M")` 改成正则 `\d+(\.\d+)?[KMG]`，
  不会再被课程名里的字母 K/M/G 骗到。
- 「下载开没开始」不再用 `M/` 这种文案判据（`6.62M/6.62M` 是**下完了**，不是正在下），
  只认 `download.db` 快照变化和「正在下载 / 排队 / 等待下载 / 下载中」。
- 日志写入加了同步，root 线程和主线程同时写不会互相截断。

---

## v1.9：界面重做 + 下载全程隐形 + 真的 2 秒

### 1. 设置页换了套视觉

原来那版是系统控件堆出来的，白底黑字、直角、开关还看不出开没开。现在参照一套紫色卡片风格的界面重做：

- 顶部淡紫渐变背景，小字 kicker + 30sp 大标题
- 白色 20dp 圆角卡片，每张卡左侧一个淡紫图标块（GO / VOL / OFF / DL / WI / 7D）
- 自绘胶囊开关：开 = 紫色轨道 + 白点靠右，关 = 灰轨道 + 白点靠左
- 底部紫色渐变圆角「保存」
- 去掉 ActionBar，状态栏同色

> 系统 `Switch` 的 `trackTint` 在 MIUI 上不生效，全部开关都变成一个紫圆点、看不出状态，
> 所以开关改成 `View` 自绘（只画圆角矩形 + 圆点），状态完全可控。

### 2. 下载过程不再闪面板

以前点 GO 之前要「…→下载→全部下载」，面板会在屏幕上闪两三下，很出戏。
现在 hook `Dialog.show`，在窗口 attach 之前就把 `alpha` 和 `dimAmount` 清零：

```
dl: panel hidden
dl: panel hidden
dl: options text=已下载
dl: 全部下载 clicked=true
```

面板仍然在 View 树里、`performClick()` 照常工作，只是画不出来。用户看到的只是
「在详情页停留一下 → 进训练」。实测详情页截图里完全看不到菜单和下载设置面板。

### 3. 2 秒真的能开练了

老逻辑是串行的：

```
2 秒到点 → 跑完下载（约 4 秒）→ 再重新挂 2 秒 → 点 GO   ≈ 6 秒
```

现在：

- **已确认下载过的课**（`dl_checked` 未过期）：完全不碰下载，2 秒直接点 GO
- **没确认过的课**：进页面 500ms 就开始跑隐形下载，和 2 秒倒计时**并行**；
  2 秒到点时如果任务已经派给 Keep 下载器就立刻走，最多再等 3 秒

实测（已确认的课）：

```
detail resumed -> auto-GO armed (2s)      @ 19:09:57.160
DETAIL auto-GO try#1 ok=true              @ 19:09:59.176   ← 间隔 2016ms
```

实测（未确认的课，第一次）：

```
detail resumed -> auto-GO armed (2s)      @ 70.1727
auto-GO: waiting download dispatch        @ 72.3727   ← 2 秒到点，下载还在派发
dl: GO now (dispatched=true, waited=900ms)@ 74.9400
intent TRAINING                           @ 76.5
```

第一次约 4.5 秒（含 Keep 自己启动训练页的 1.5 秒），之后这门课就是 2 秒。

### 4. 详情页的「为你推荐」不再打断开练

进某些课程时 Keep 会弹「为你推荐 / 点击查看」的推广弹窗，挡住 GO，还会把 2 秒计时取消掉。
现在识别到这类文案直接点「取消」，关掉后 `noteDialog` 重新挂计时。

### 5. 长按课程从「练过」移除

```
长按 550ms → 弹紫色确认框 → 点「移除」
   → 课程名写入 hidden_courses（Keep 的 prefs）
   → 列表里对应 item 设为 GONE + 高度压 0（不留空位）
   → 7 秒内持续扫描，滚动复用后也会重新压掉
```

实现要点：

- 触摸检测复用详情页那套 `DecorView.dispatchTouchEvent`，COURSE 页也挂上
- 长按位置 → 找 `RecyclerView` 的直接子 View（item）→ 取 item 内**最靠上**的 TextView 当标题
- 被隐藏的 item 高度是 0 但 `isShown()` 仍为 true，命中判断必须额外看 `getHeight() > 0`，
  否则长按第一项会命中它上面那门已经隐藏的课

> 只做本地隐藏：Keep 里的训练记录、服务器数据都不动。
> 想恢复被移除的课，清掉 `keepassist.xml` 里的 `hidden_courses` 即可（会连队列一起重置）。

### 6. 顺手加的

- 点 GO 后如果 1.5 秒还没跳走才重试（原来是 800ms，训练页启动慢的时候会重复点、二次加载）
- `dl_checked` 里的旧格式（v1.7 及以前，只有 id）按「已过期」处理，
  升级后第一次进课会把漏掉的下载状态补查一遍

---

## v2.0：把「会打扰你的」全部按住

这一版没有加新功能，全部是修 v1.9 里实际能感觉到的毛病。

### 1. 音量：进来就是静音，而不是"保持你进来时的音量"

v1.4 的实现是「进 Keep 时记下当时音量，之后被改就拉回」。问题是：你在外面刷视频把
音量开到 55，再进 Keep，它会把 **55 锁住** —— 训练语音照样大声，等于没起作用。

现在配置里多了一个 `vol_target`：

```
vol_target=0     把媒体音量压到 0，完全静音（默认）
vol_target=44    固定到 44/150（约 29%）
vol_target=-1    跟随"进入 Keep 那一刻"的音量（v1.4 的老行为）
```

设置页对应一张卡片：开关 + 0~100% 滑块 + 「跟随进入时的音量」。
保存后 **最多 15 秒生效，不用重启 Keep**。

顺带修掉两个会让它失效的漏洞：

- **息屏后锁被解除**：训练页 7 秒自动息屏会触发 `onPause`，老代码把它当成
  "Keep 退到后台"，900ms 后结束音量会话。黑屏期间再改音量就没人管了。
  现在屏幕黑着不算后台，锁继续生效。
- **训练时响应太慢**：轮询是 1.2 秒，够你听到半句教练语音。训练期间改成 300ms。

实测：

```
（进 Keep 前把媒体音量调到 60）
vol session start, target=0
vol -> 0 (api)                 ← 3 秒内压到 0

（训练中息屏后把音量调到 90）
vol session kept (screen off, not background)
vol drifted 90 -> restore 0
vol -> 0 (api)                 ← 300ms 轮询，黑屏状态下也拉回

（设置页把目标改成 44，不动 Keep）
cfg: ... volTarget=44
vol target changed 0 -> 44
vol -> 44 (api)
```

Keep 的训练语音、提示音都走 `STREAM_MUSIC`（实测 `USAGE_MEDIA / CONTENT_TYPE_MUSIC`），
所以锁这一个流就够了。

### 2. 2 秒开练不再被下载拖慢

v1.9 说"下载和倒计时并行"，其实是串行的：2 秒到点时下载面板才刚点开，
自动开练要等任务派发（0.9~1.5 秒）+ 点 GO + 训练页启动（1.5 秒），
实测 **4.5~6 秒**才真正进训练。

现在详情页**完全不碰下载**：2 秒到点直接点 GO。下载改成练完入队、回到列表页
且你停手 8 秒后才补（打开详情页 → 透明面板 → 派发 → 关页，全程约 3 秒）。

实测（`detail resumed` → `auto-GO try#1`）：**2011~2020ms**，换课也一样。

### 3. 切后台不会再被拉回 Keep

v1.8 修了「队列泵在后台把 Keep 顶到前台」，但收尾看门狗（`wrapTick`）还有同一条路：
它每 220ms 看一次 `sCur`，而 `sCur` 是"最后一个 resume 的 Keep 页面"，
切到微信也不会变 —— 练完 25 秒内切走，照样被 `startActivity` 拉回来。

现在 `wrapTick` / `wrapJumpBack` 都要求 Keep 真的在前台；你一走开，收尾直接停。
另外收尾期间**你自己点进某门课**（想练下一门）不会再被弹回列表。

实测：练完回列表 → 按 Home → 22 秒后仍停在桌面，日志只有
`pump: keep in background, stay in queue`。

### 4. 不再误点权限弹窗

`inspect()` 原来有一条"通用关闭"规则：弹窗里只要有「跳过 / 关闭 / 我知道了 /
暂不 / 以后再说」就点掉。这很危险 ——

- 「以后再说」在系统权限框里等于**拒绝**，点掉之后 Keep 会丢掉存储/通知/后台权限；
- 「允许 / 拒绝」这种授权框被点一下，功能就永久关了，而界面上完全看不出原因。

现在这类弹窗（含"权限 / 允许 / 拒绝 / 授权 / 后台运行 / 隐私 / 同意并"）
一律不碰，收尾期"一律关掉"也放它们一马。

### 5. 透明面板只作用在下载面板上

老代码是 `sDlBusy` 就把**所有** Dialog 设成透明。下载机器人在跑的那几秒里，
Keep 如果弹了错误提示、权限框、「训练时间过短」，都会变成**看不见但挡着点击**的
隐形窗口 —— 表现为"界面卡住、点不动"。

现在按 Keep 自己的控件 id（`textFunc` / `imageFunc` / `textRight`）判断是不是下载面板，
是才透明，不是就恢复可见。

### 6. 已下载的课真的不再走那套流程

用户的原话是"训练过的课已经下载过了，就不用走这个流程了"。

判据换成 Keep 自己的下载库 `download.db`：

```
download_data.workoutId  vs  collectionId
  639072a419f55b0001067440    course_639072a419f55b0001067441
  └──────── 前 20 位相同 ────────┘
```

命中就直接出队并记成已确认，**一个 UI 都不点**。库读在后台线程 + 内存缓存
（60 秒），主线程只查一个 HashSet。

顺带修了一个测试中撞出来的边界：如果队列里的 id 对应的是付费课，详情页会直接跳到
`CompleteSaleActivity`（购买页），老代码只会 `finish` 详情页，于是**人就被留在购买页**。
现在只要"为下载而打开的页面"落在详情页/课程页/主页/下载页之外，就立刻关掉并收工。

### 7. 读数据库不再卡主线程

`dlDbSnapshot()`（打开 `download.db` 查条数）和 `courseSize()`（递归统计目录）
原来都在主线程跑。Keep 自己在写这个库时会等锁，表现就是"点课程卡一下"。
现在都挪到后台线程，结果 post 回主线程再用。

### 8. 其他

- 练完 60 秒内**只有同一门课**禁止自动开练，换课 8 秒后就能继续（原来要干等一分钟）
- 课程 id 继承窗口 30 分钟 → 5 分钟，避免"20 分钟前看过 A 课、现在从日程练 B 课"
  把 A 记进下载队列
- 网络判断加 `NET_CAPABILITY_VALIDATED`：连上酒店那种"没登录"的 Wi-Fi 会正确走离线入口
- `su` 读配置加 2.5 秒超时（Magisk 授权卡住时不会再让刷新永久挂起）
- 设置页读配置加 4 秒超时
- 空闲计时遇到 `sCur == null`（页面重建）会重试，不再永久丢失

---

## v2.1 → v2.2：整条标题栏 + 桌面无图标 + 红色 #D2525E

### 1. 长按「我的课程」整条标题栏 0.7 秒 → 打开设置页

```
按下          → 标题立刻变红（#D2525E）
0.7 秒        → 打开 Keep 助手（松手后恢复原色）
提前松手/滑走 → 只闪一下红，不打开
```

命中范围是**整条标题栏**：

- x 从屏幕最左到最右 —— 左边的返回箭头旁、右边的空白都算
- y 取标题所在标题栏容器的高度（往上找 2 层父容器，挑高度 < 12% 屏高的那个）
- 找不到合适容器时退化为「标题上下各放宽 22dp」
- 上限硬夹在屏幕 18% 以内，不会误吃下面列表第一项

和列表项长按（550ms 弹移除确认）是两套判断：命中标题栏就完全不进列表逻辑。
短按标题栏（< 700ms）不会触发任何跳转，标题颜色原样恢复。

**为什么不用普通 startActivity**：从 Keep 进程拉起另一个 App 会被 MIUI 的
「应用链式启动」拦一次确认框（"Keep 想要打开 Keep 助手，是否允许？"），
长按 0.7 秒的体验直接断了。所以走 root 的 `am start`，实测无确认框；
1.6 秒内没切过去就退回普通 startActivity 兜底。

### 2. 桌面不显示图标

v2.2 起 APK **不再声明 `MAIN` / `LAUNCHER`**：

- 桌面和抽屉里都不会出现「Keep 助手」
- 设置页只靠 root 的显式 `am start` 拉起（不需要 launcher 入口）
- Vector/LSPosed 靠 `xposedmodule` meta-data 识别模块，与 launcher 无关，列表里照常显示
- 卸载/重装后也一样，不会再冒出一个桌面图标

### 3. 图标红色改为 #D2525E

- 从 Keep `base.apk` 取 `mipmap-xxxhdpi/ic_launcher_keep.webp`（432×432，深紫渐变底 + 白 K + 右上蓝星）
- 白色系像素按白度混合到 `#D2525E`，深紫底与蓝星不动
- 生成 mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192 五档 PNG，`icon` 与 `roundIcon` 都指向它

### 4. Vector 模块列表图标不刷新？不是 apk 的问题

Vector 管理器会把模块图标缓存在**管理器进程内存**里。覆盖安装 APK 后
（`/data/app/~~xxx==` 路径会变），已经打开的 manager 还拿着旧图标。

**关掉 Vector 管理器再打开**（或重启手机一次）就正常。实测重启后列表里是深紫底 + 红 K。

---

## v2.3：修掉「返回退出 App」和「长按删错课」+ 列表开关

### 1. 课程页按返回不再退出 App

以前冷启动是 `FLAG_ACTIVITY_CLEAR_TASK` 直接跳进「我的课程」的，任务栈里只剩这一个页面。
这时按返回、或从屏幕左边缘右滑，Keep 把它 finish 掉 —— 栈空了就退回桌面。
只有先翻一下 tab（在 Keep 内部压了返回栈）再返回才正常，看起来就像"得先绕一圈"。

现在拦两层：

- `Activity.finish()`：课程页是任务栈根时，先把 MainActivity 顶上去再放行
- `onBackPressed`：被子类重写、不调 `super` 时，吃掉这次返回，由我们收尾

同时给冷启动的「防顶替」逻辑加了例外，避免刚拉起来的首页被自己 250ms 内 finish 掉
（实测就是这个自相矛盾导致"按返回没反应/直接退出"）。

### 2. 长按删除终于删对那一门

Keep 用的是自定义 `PullRecyclerView`，它把**所有课程行装在同一个直接子 View 里**
（实测那个子 View 高 2376px、装了 5 门课）。旧逻辑取"RecyclerView 的直接子 View"，
永远只能拿到整个列表，标题自然永远是第一门 —— 这就是"长按哪门都删第一门"。

现在改成按行识别：在命中点下面递归找「可点击 + 含 `textName` + 高度不像整个列表」的
最小 View，长按哪行就是哪行。

### 3. 隐藏不再污染 RecyclerView 复用的 View

旧实现把 item 压成 `height=0` 就不管了，而这个 LayoutParams 跟着**被复用的 View** 走：
于是"删一门、另一门也消失"，而且删完顶部还显示旧数量。
现在每轮扫描都会对"没被移除的行"主动把高度和可见性还原。

### 4. 「练过」和「想练」互不影响

隐藏记录改成 `tab|课程标题`，只作用在当前标签页；顶部「N 节课程 / N 节想练的课程」
也会跟着改成实际剩余数量，不再出现"全删完了还写着 5 节"。

### 5. 新增三个开关（放在设置页最底部）

| 开关 | 默认 | 说明 |
|---|---|---|
| 长按课程移除 | 开 | 关掉后长按不再弹移除确认 |
| 移除后同步课程数量 | 开 | 顶部「N 节课程」跟随实际剩余 |
| 打开课程时自动加入「想练」 | 开 | 进入详情页自动点一次「想练」，已加入则不动 |

> 「自动加入想练」依赖详情页底部的「想练」按钮：点完会变成绿色的「已想练」，已加入的课不会被重复点；Keep 改版换掉按钮时在设置里关掉即可。

---

## 训练完成后的完整链路

你练完一节课之后，Keep 原本的路径是：

```
运动总结页(左上角「完成」) → 点完成 → 主页/日程 tab → 弹分享页 → 关掉 → 停在日程页
```

接管后：

```
运动总结页 → 自动点「完成」(300ms 内)
          → Keep 想跳主页/日程  → 拦下并改道「我的课程」
          → 想弹分享页          → 直接关掉
          → 落到「我的课程 / 练过」，守候 5 秒挡住延迟跳转
```

**怎么区分「刚练完」和「翻看历史记录」**：总结页的左上角按钮是同一个控件，
训练刚结束时它是可见的「完成」，从日程翻历史时它是隐藏状态（用的是返回箭头）。
模块只认"可见 + 位于左上角 + 可点击"的「完成」，所以你以后翻看旧记录不会被强行弹出去。

### 点击方式

总结页的「完成」只是个 `TextView`，它的祖先链上**没有任何 clickable 的容器**，
真正接收点击的是与它重叠的兄弟按钮 `app:id/imgLeftClick`。所以不能用"向上找可点祖先"，
必须按资源 ID 取那个 ImageView 再 `performClick()`。

点击顺序：`imgLeftClick.performClick()` → 可点祖先 → 屏幕坐标注入 → 左上角比例坐标。

另有一层不依赖界面文字的兜底：记录训练页退出的时间，20 秒内出现的总结页一律视为"刚练完"。

---

## 实测数据（小米 11 / Android 13 / Keep 9.1.110 / Magisk 28.1）

```
JUMP splash -> course @748ms        ← 跳过开屏
course: 练过 tapped   @1120ms       ← 已切到「练过」

detail resumed -> auto-GO armed (2s)
  tapGo performClick -> true
auto-GO confirmed (left detail)     ← T+4s 已在训练页

training resumed -> auto-sleep armed (7s)
TRAINING idle 7s -> screen off
screenOff su rc=0                   ← T+12s 屏幕已灭

training ended -> next=...CalendarActivity
wake screen <- training-end
wake su rc=0                        ← T+16s 屏幕已亮（v1.4）

vol session start, baseline=70
vol drifted 60 -> restore 70
vol -> 70 (api)                     ← 音量被拉回（v1.4）

（关掉 Wi-Fi，全程无网）
JUMP splash -> downloads(offline) @642ms
resume: ...CourseDownloadActivity
intent DETAIL <<cid=565bfb0f...>>   ← 点进课程，详情页用本地缓存渲染
detail resumed -> auto-GO armed (2s)
DETAIL auto-GO try#1 ok=true
intent TRAINING
training resumed -> auto-sleep armed (7s)   ← 离线训练正常开始（v1.5）
```

- 冷启动到「练过」：**约 0.9~1.2 秒**（三次实测 859 / 918 / 1120 ms）
- 详情页 → 训练页：**2 秒 + Keep 自己启动训练页的 1~1.5 秒**
- 训练页 → 息屏：**7 秒 + 约 0.1 秒**
- 训练结束 → 亮屏：**触发即刻 + 约 0.6 秒**（root 起进程 + 按键注入）
- 音量被改 → 拉回：**平时 1.2 秒以内，训练期间 0.3 秒以内**

v2.0 专项实测：

```
（进 Keep 前媒体音量 60）
vol session start, target=0
vol -> 0 (api)                       ← 3 秒内压到 0

（训练中息屏后把音量调到 90）
vol session kept (screen off, not background)
vol drifted 90 -> restore 0
vol -> 0 (api)                       ← 黑屏状态下也拉回

（设置页把目标改成 44，不重启 Keep）
cfg: ... volTarget=44
vol target changed 0 -> 44
vol -> 44 (api)                      ← 15 秒内生效

detail resumed -> auto-GO armed (2s)  @136058
DETAIL auto-GO try#1 ok=true          @138062   ← 2004ms

（练完自然结束）
training ended -> next=VpSummaryActivity
dl: queued 621e1f...
wake: screen already on
SUMMARY(complete) -> after-training
WRAPUP on -> BLOCK wrapup detour -> MainActivity
tapDone imgLeftClick.performClick -> true
course resumed -> WRAPUP settled -> guard 8s

（15 秒后队列泵检查，这门课已在下载库里）
dl: already in download.db -> 621e1f...
dl: dequeued
pump: queue empty, done              ← 没有打开详情页，列表没跳
```


> 为什么开屏不是严格 1 秒：Keep 用 SecNeo（梆梆壳）加固，光壳解密 + Application 初始化
> 就占掉约 780ms，这段在 Java 层绕不过去。

---

## 安装

### 当前设备（已完成）

模块已安装并启用，作用域已绑定 Keep，su 权限已配置。**无需再操作**。

### 换机 / 重装

1. 刷入 `KeepAssist-Magisk-v2.3.zip`（会自动装 APK + 配置 su 权限）
   或手动安装 `KeepAssist-v2.3.apk`
2. 打开 Vector / LSPosed 管理器 → 模块 → 启用「Keep 助手」
3. 作用域勾选 **Keep (`com.gotokeep.keep`)**
4. 强制停止 Keep 再打开
5. 想改开关：打开 Keep →「我的课程」→ **按住顶部整条标题栏 0.7 秒**（标题变红即打开设置页）。
   v2.2 起桌面**不再有图标**，只走这个入口；第一次会弹一次 Magisk 授权框（写 `/data/adb/keepassist.conf` 用）

命令行启用（需要 root）：
```sh
su -c '/data/adb/lspd/cli modules enable com.shellz.keepassist'
su -c '/data/adb/lspd/cli scope set com.shellz.keepassist com.gotokeep.keep/0'
```

**手动改配置**（不想开设置页时）：
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
> 单位：`auto_go_delay` 秒、`sleep_sec` 秒（0=关闭）、`checked_days` 天（0=不复检）。
> 改完强制停止 Keep 再打开。

**手动补 su 权限**（自动息屏用，Magisk 包里已自动做）：
```sh
su -c "magisk --sqlite 'INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (10467,2,0,1,1)'"
```
> `uid` 用 `stat -c %u /data/data/com.gotokeep.keep` 查；`policy=2` 是允许，`1` 是每次询问。

---

## 技术要点

**只 hook 系统框架类，不碰 Keep 内部类。** 因为 Keep 被加固，类名/方法名都拿不到，
但 `Activity`、`Dialog`、`PopupWindow` 这些框架类永远在：

- `Activity.onCreate` → 判断是 `splash.SplashActivity`，立即跳 `MyCourseActivity`
  （无网络时改跳 `CourseDownloadActivity`）
- `Activity.onResume` → 分派到 Splash / 首页 / 课程页 / 详情页 / 训练页各套逻辑
- `PopupWindow$PopupDecorView.dispatchTouchEvent` → 记录"最后一次触摸"（见下）
- `Activity.onPause` → 页面离开即取消计时
- `Dialog.show` / `Dialog.dismiss` → 扫 View 树找广告特征；同时维护"当前有没有弹层"，
  有弹层就不点 GO、不点完成（详情页的「…」面板和下载面板都是独立 Dialog 窗口，
  里面的点击不经过 Activity 的 DecorView，只看触摸判断不出来）
- `PopupWindow.show*` → 扫 View 树找广告特征
- `Activity.startActivity*` → 拦截广告 SDK 落地页
- `Activity.onBackPressed` → 课程页返回时进首页，不直接退出 App

### 配置为什么走 /data/adb/keepassist.conf

模块跑在 Keep 进程里（uid 是 Keep 的），而设置页跑在模块自己的进程里，
两边的 SharedPreferences 互相读不到（Android 13 已经禁掉世界可读）。
XSharedPreferences 在不同 LSPosed 分支上行为不一致，所以干脆用一个两边都能读的
root 文件：设置页 `su -c 'cat > ...'` 写，Keep 进程 `su -c cat` 读，内存缓存 15 秒。

代价是每次刷新要 spawn 一个 su（只在进页面时触发，不是轮询），
换来的是可 adb 手改、可看日志核对。

### 触摸检测为什么绕了四个弯（重要，别再回头试）

"多久没操作"听着简单，实际在这个 App 上试了四种方式，前三种全废：

| 尝试 | 结果 |
|---|---|
| hook `Activity.dispatchTouchEvent` | 一条事件都收不到 |
| hook `ViewRootImpl.dispatchInputEvent` | `hookAllMethods` 找不到方法，零命中 |
| 给 `DecorView` 挂 `OnTouchListener` | 不生效，走不到 View 的 listener 逻辑 |
| **hook `PopupWindow$PopupDecorView.dispatchTouchEvent`** | **成功** |

真正原因是：**课程详情页上面盖着一层 Keep 自己的 PopupWindow**
（`dumpsys window windows` 里能看到 `Window #14 ... PopupWindow`，`isVisible=true`）。
触摸先被这个 PopupWindow 的窗口吃掉，压根到不了 Activity 的 DecorView ——
所以不管你 hook 哪一层 Activity，都是空的。

现在同时挂两处：`PopupDecorView`（有浮层时命中）+ `DecorView`（无浮层时命中），
用 decorView 实例比对来确认事件属于当前页面。

> 诊断这类问题的土办法：把常量临时调大（比如 2 秒改成 10 秒），
> 进页面后每隔两秒喂一次 `input tap`，再用日志时间戳看动作有没有被推迟。
> 只测"2 秒后会不会触发"是看不出 hook 到底有没有生效的 —— 因为没触摸也是 2 秒，
> 结果一模一样。这是本次踩坑最久的地方。

关键锚点（资源 ID 未被混淆，可放心依赖）：
`textSkip`（开屏跳过）、`textName`（底部 tab）、`name`（我的课程入口）、
`startContainer` / `Detail8GoCellView`（详情页 GO）。

关键 Activity：
- 课程详情 `...wt.business.course.detail8.activity.CourseDetail8Activity`
- 训练页 `com.keep.trainingengine.host.TrainingEngineActivity`
- 总结页 `...variplay.business.summary.activity.VpSummaryActivity`
- 已下载课程 `...wt.business.course.detail.activity.CourseDownloadActivity`（纯本地，离线入口）

---

## 日志

```sh
adb shell su -c 'cat /data/data/com.gotokeep.keep/files/keepassist.log'
```

从 v1.3 起，进程刚起来时的日志（`=== loaded ===`、`hookTouch installed`）也会落盘，
不再只出现在 logcat 里。

看这两个功能有没有工作，搜这几行：

```
detail resumed -> auto-GO armed
DETAIL auto-GO try#1 ok=true
auto-GO confirmed (left detail)
training resumed -> auto-sleep armed
TRAINING idle 7s -> screen off
screenOff su rc=0
```

v1.4 的两个功能看这几行：

```
wake screen <- training-end          ← 训练结束触发亮屏
wake su rc=0                         ← 亮屏成功
wake: screen already on <- ...       ← 屏幕本来就亮，跳过（正常）
vol session start, baseline=70       ← 进入 Keep，记下基准音量
vol drifted 60 -> restore 70         ← 音量被改动，拉回
vol session end (baseline=70)        ← Keep 到后台，会话结束
```

v1.5 的两个功能看这几行：

```
JUMP splash -> downloads(offline)    ← 没网，直达本地已下载课程页
course: offline -> jump downloads    ← "练过"列表拉不出内容，改道
main: rogue start over target -> finish main   ← 把抢上来的主页顶掉
dl: queued <courseId>                ← 训练结束，课程进待下载队列
dl: start for <courseId>             ← 进详情页，机器人开工
dl: options panel opening (try 1)
dl: clicked download item (下载)
dl: 全部下载 clicked=true
dl: robot done (clicked)
auto-GO re-armed after download      ← 下载期间压住的自动开练恢复计时
dl: already downloaded -> dequeue    ← 下次进来已经是"已下载"
```

v1.7 的改动看这几行：

```
auto-GO: prepare download first             ← 2 秒不动，先准备下载
dl: first visit, queued <cid>               ← 这门课第一次进，记下来
dl: db before=7:1795254266481               ← 点之前数据库快照
dl# wait0 db=8:1795254565880 ui=false       ← 库多了一条，面板却没变
dl: dispatch confirmed -> close panel (dbChanged=true)
auto-GO re-armed after download             ← 下完继续开练

pump: armed in 5000ms, queue=1              ← 停止在课程页，开始补下
pump: open <cid> (1 left)
pump: queue empty, done
```

如果 `screenOff su rc` / `wake su rc` 不是 0，说明 su 权限没配好。

---

## 已知限制

1. **详情页自动开练的延迟是"猜"出来的**：默认 2 秒，可在设置页改 1~10 秒。
   它的本质是"你多久没动 = 你要练"，读不了心；碰一次屏幕就作废是唯一的补救。
   从搜索、推荐点进来的详情页同样会触发，只想看看就随手碰一下屏幕。
2. **练完回详情页不会自动开练**：训练结束 60 秒内进**同一门课**的详情页不自动开练。
   换一门课练不受影响（8 秒后就能自动开练）。手动点 GO 照常可用。
3. **训练中自动息屏**：如果某个环节需要你看屏幕操作（比如确认动作），
   息屏后要按电源键点亮，训练本身不会中断。可在设置页关掉或改秒数。
4. **热启动复用实例**：Keep 的 `MainActivity` 重写了 `onNewIntent` 且不调 `super`，
   所以「从课程页返回首页 → 切后台 → 再点图标」会停在首页。
   正常使用（点图标冷启动）不受影响。
5. 弹窗规则基于文案特征。Keep 若改文案，需要按日志微调。
6. 课程列表要联网加载，网络慢时页面会有几百毫秒空白。
7. **音量是按"档位"锁定的**：Keep 在前台时按音量键会被拉回目标值
   （训练期间 300ms、平时 1.2 秒）。想临时改音量，先在设置页把「自动设定音量」关掉，
   或退出 Keep 改好再进（只在 `vol_target=-1` 模式有效）。
8. **音量档位是 0~150**：设置页的百分比按 1.5 倍换算成档位，四舍五入，
   所以 29% 实际写的是 44 档。蓝牙耳机切换设备时档位映射可能不同。
9. **亮屏是全局的**：训练结束那一刻只要屏幕是黑的就会点亮，不管你人在不在手机旁边。
10. **离线只能练"下载过"的课**：没下载的课在「下载课程」页点进去没有训练视频可用。
    这也是加自动下载的原因。注意那一栏显示 0 M 不代表没下载（见第 13 条）。
11. **离线时训练记录不上传**：练完的时长/卡路里要等联网后才同步（Keep 自己的行为）。
12. **自动下载要等网络**：队列是持久化的，没网时只打一行 `dl: offline, keep in queue`，
    等你停在课程列表、**手停 8 秒**且网络恢复时才补下（会短暂跳一次详情页，约 3 秒后自动回来）。
    **默认只在 Wi-Fi 下自动下载**，蜂窝时队列不动；想用流量就在设置页关掉「只在 Wi-Fi 下」。
    连上没有外网的 Wi-Fi（酒店门户那种）也不会下，会等到系统验证网络可用。
13. **已练过但没下载下来的历史课，补不回来**：Keep 判断「已下载」看的是本地视频文件。
    如果一门课当年是断网练的、只缓存了一星半点，详情页会显示「已下载」，
    这时点下载是空操作（Keep 认为不需要下）。
    这类课只能靠 Keep 自己在后续训练中继续缓存，模块无法强制重下。
    **v2.0 起**：`download.db` 里已经有记录的课直接跳过，一个 UI 都不点；
    没记录的才走一次透明面板。
    但真机实测 **Keep 对"已下载"的课是幂等的，不会补写 download.db** ——
    所以这类课仍然不会出现在「下载课程」页，只能从详情页点进去练。
14. **「下载课程」页的体积是 Keep 自己算的**：`screenVideo` 类课程（整段一镜到底那种）
    永远显示 0 M，但实际已下载、断网能播。也有逐动作课程训练后掉成 0 M 的情况
    （「5 分钟箭步蹲」实测过），同样不影响播放。**能不能离线练只看文件在不在。**
15. **离线入口仍是「下载课程」页**：「练过」列表是接口数据，断网时 Keep 自己显示
    「网络未连接」，模块没有可展示的本地数据，所以只能改道「已下载课程」这个纯本地页。
    只要课都下载了，这个页面就等价于你的练过列表。
16. **配置最多 15 秒生效，不用重启 Keep**：设置页保存后，Keep 侧每次页面切换或
    1.2 秒一次的音量轮询都会检查配置文件（内部 15 秒节流）。
17. **设置页需要 root**：它用 `su` 写 `/data/adb/keepassist.conf`，首次会弹 Magisk 授权框。
    不给 root 的话设置页只能读不能存，但模块本身照旧工作（用默认配置）。
18. **后台不抢页面，所以补下会晚一点**：练完立刻切去做别的事，那门课会留在队列里，
    等你下次回到课程列表、手停 8 秒后才补。这是刻意换来的「不打断你」。
19. **长按移除不可撤销（界面里没有恢复入口）**：隐藏记录在
    `keepassist.xml` 的 `hidden_courses`，要恢复只能清掉这个键（会连带清空待下载队列和检查记录）。
    Keep 里的训练数据完全不受影响，重新练一次也不会让它回来——隐藏是按课程名记的。
20. **2 秒是"无操作"的 2 秒**：到点后还要加上 Keep 自己启动训练页的时间
    （实测 1~1.5 秒），所以从点课程到看见训练画面大约 3~3.5 秒。这个时间模块控制不了。
21. **设置页是新写的自绘界面**：不跟随系统深色模式（固定浅紫卡片）；
    开关是自绘的，无障碍读屏只能读到「View」。
22. **权限弹窗不再自动处理**：含"权限/允许/拒绝/授权/后台运行/隐私"的对话框
    一律留给你自己点。这是刻意的 —— 误点一次就把 Keep 的功能永久关掉了。

## 常见问题

**Q：连不上某个已保存的 Wi-Fi，是不是这个模块导致的？**

不是。模块只运行在 Keep 应用进程内，源码里对网络的唯一操作是通过
`ConnectivityManager` **只读**查询「当前是不是 Wi-Fi / 有没有外网」，用来决定课程要不要自动下载；
`AndroidManifest.xml` 没有申请任何权限，也没有 `WifiManager` 的任何写操作。

以 2026-11-22 的一次排查为例：手机连 `1301`（密码一直保存着）时四次握手每次都
`COMPLETED`（说明密码正确），随后 0.2~0.6 秒收到 `reasonCode:1 locallyGenerated:false`
的断连 —— 这是路由器主动发 deauth 把手机踢掉，手机连 IP 都没拿到（DHCP 未完成）。
系统把它记为 `DISCONNECTION_NON_LOCAL`，并因连续失败临时禁用自动重连。
处理办法是重启路由器 / 检查路由器 DHCP 与接入控制，或在 Wi-Fi 列表里手动点一下该网络。

**Q：Vector 模块列表里的图标还是旧的？**

管理器把图标缓存在自己的进程内存里。覆盖安装后关掉 Vector 管理器再打开（或重启手机）即可，
与 APK 本身无关。

---

## 卸载

```sh
adb uninstall com.shellz.keepassist
```

顺便撤销给 Keep 的 root 权限：

```sh
su -c "magisk --sqlite 'DELETE FROM policies WHERE uid=10467'"
```

（不撤销也能用，只是 Keep 会一直保留 root 权限。）
