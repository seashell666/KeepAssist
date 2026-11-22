#!/system/bin/sh
SKIPUNZIP=0

ui_print "***********************************"
ui_print "        Keep 助手 (KeepAssist)      "
ui_print "***********************************"
ui_print "- 正在安装 Xposed 模块 APK ..."

pm install -r "$MODPATH/KeepAssist.apk"
if [ $? -eq 0 ]; then
  ui_print "  安装成功"
else
  ui_print "  ! 自动安装失败，请手动安装："
  ui_print "    $MODPATH/KeepAssist.apk"
fi

# 训练中「7 秒无操作自动息屏」需要 root 注入 SLEEP 键：
# 模块跑在 Keep 进程里，没有 DEVICE_POWER 权限，PowerManager.goToSleep 会被拒。
# 在这里直接写 Magisk 授权表（policy=2 = 允许），避免息屏时弹授权框。
ui_print "- 配置 Keep 的 root 权限（自动息屏需要）..."
KUID=$(stat -c %u /data/data/com.gotokeep.keep 2>/dev/null)
case "$KUID" in
  ''|0) ui_print "  ! 未找到已安装的 Keep，跳过（装好 Keep 后重刷本模块即可）" ;;
  *)
    magisk --sqlite "INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES ($KUID,2,0,1,1)" >/dev/null 2>&1
    ui_print "  已授予 Keep (uid $KUID) root 权限"
    ;;
esac

ui_print "- 下一步（必须手动完成）："
ui_print "  1. 打开 Vector / LSPosed 管理器"
ui_print "  2. 在「模块」里启用「Keep 助手」"
ui_print "  3. 作用域勾选 Keep (com.gotokeep.keep)"
ui_print "  4. 强制停止 Keep 后重新打开"
ui_print "- 想调开关：打开 Keep →「我的课程」→ 按住顶部标题栏 0.7 秒"
ui_print "  （桌面不显示图标；首次会要一次 root）"
ui_print "- 完成，祝训练愉快！"
