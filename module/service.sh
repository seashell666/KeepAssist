#!/system/bin/sh
# 开机兜底：若模块 APK 被卸载则自动重装
MODDIR=${0%/*}

( sleep 90
  if ! pm list packages 2>/dev/null | grep -q "com.shellz.keepassist"; then
    pm install -r "$MODDIR/KeepAssist.apk" >/dev/null 2>&1
  fi
) &
