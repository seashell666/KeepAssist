# Keep 助手 v2.6

`versionName=2.6` · `versionCode=17` · 2026-11-27

这一版把「训练中点停止还要再确认一次」砍掉了：点停止（或按返回）就直接结束训练，不再弹「训练时间过短，无法保存记录，再坚持一下吧？」或「确定结束本次训练并保存记录吗？」。

## 修了什么

### 1. 点停止直接结束训练（不再弹确认框）

**原来的问题**：训练页点左下角红色「停止」按钮后，Keep 会弹一个确认框：

- 「训练时间过短，无法保存记录，再坚持一下吧？」（结束训练 / 再练一会）
- 「确定结束本次训练并保存记录吗？」（确定 / 取消）

必须再点一次才真的结束。

**现在的做法**：新增 `handleStopConfirm()`，挂在 `Dialog.show()` 返回的**同一帧**里：只要当前在训练页、弹窗文本命中这两类确认框，就立刻找到并触发「结束训练 / 确定」按钮的点击回调。弹窗来不及绘制，视觉上完全不出现（不是模拟点屏幕，是代码层直接调按钮）。

- 只对训练页的「训练时间过短」「结束本次训练」两类确认框生效
- 权限框、引导框、`还有训练未完成` 提示一律不碰
- 日志标记：`training stop -> auto-confirm (dialog suppressed)`

### 2. 实测

- 跳绳课训练 4 秒（极短）按返回 → 弹窗被同步确认 → `training stop -> auto-confirm (dialog suppressed)` → 直接回课程详情页
- 全程截屏无弹窗痕迹

## 升级方法

直接覆盖安装，不用卸载（签名与 v2.5 一致）：

1. 安装 `KeepAssist-v2.6.apk`（或刷 `KeepAssist-Magisk-v2.6.zip`）
2. Vector / LSPosed 管理器里把「Keep 助手」开关**关掉再打开**，或重启手机
3. 打开 Keep → 冷启动直达「我的课程」
4. 长按标题栏 0.7 秒能进设置页，说明模块已加载

## 文件校验

| 文件 | 大小 | SHA-256 |
|---|---|---|
| KeepAssist-v2.6.apk | 161,081 B | `27310ea6c1fa1ac5638cf71beebaa4f721ed6c7246ac4c40a1da79a55f8347c0` |
| KeepAssist-Magisk-v2.6.zip | 154,363 B | `a2af4d08bd120a35259437022ba7d9a3b36c9b6c99d2bb1ec8a90ceec51b3ec9` |

## 已验证清单

- [x] 训练页点停止/按返回 → 确认框零闪现，直接结束
- [x] 弹窗未显示（日志 `dialog suppressed`）
- [x] 结束后正常回课程详情 → 我的课程列表
- [x] v2.5 已修的假息屏、下载页长按不受影响

## 兼容性

- Keep `com.gotokeep.keep` 9.1.110（versionCode 50223）
- 小米 11（M2011K2C）/ Android 13 / MIUI 14 / Magisk 28.1 / Vector v2.2
- 需要 root 与 Xposed 框架（Vector / LSPosed），作用域仅限 Keep
