package com.shellz.keepassist;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Keep 助手的设置界面。
 *
 * 模块本体跑在 Keep 进程里，配置只能放在一个两边都能读到的地方，
 * 所以用 /data/adb/keepassist.conf（root 读写）。这里通过 su 读写它，
 * Keep 进程侧的 MainHook.Cfg 也是通过 su 读同一个文件。
 *
 * 改完需要强制停止 Keep 再打开才会生效（配置在进程启动时加载，之后 15 秒刷新一次）。
 */
public class SettingsActivity extends Activity {

    static final String CONF = "/data/adb/keepassist.conf";

    // 视觉：淡紫渐变 + 白色圆角卡片
    static final int C_BG_TOP     = 0xFFFBF9FF;
    static final int C_BG_BOTTOM  = 0xFFF1EBFF;
    static final int C_CARD       = 0xFFFFFFFF;
    static final int C_ACCENT     = 0xFF6B4EFF;
    static final int C_ACCENT_2   = 0xFF9B7BFF;
    static final int C_ICON_BG    = 0xFFEFE8FF;
    static final int C_TEXT       = 0xFF1A1A1A;
    static final int C_SUB        = 0xFF8A8A8A;
    static final int C_TRACK      = 0xFFDCD2FF;

    Pill swAutoGo, swVol, swSleep, swAutoDl, swWifi, swVolFollow;
    Pill swHide, swCount, swWant;
    SeekBar sbDelay, sbSleep, sbVol;
    EditText etDays;
    TextView tvDelay, tvSleep, tvVol, tvStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setStatusBarColor(C_BG_TOP);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {}
        setContentView(buildUi());
        load();
    }

    // ------------------------------------------------------------ 视觉工具

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    TextView icon(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(C_ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(C_ICON_BG, 14));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return t;
    }

    /** 自绘胶囊开关：系统 Switch 的 track tint 在 MIUI 上不生效，而且圆点太大看不出状态 */
    class Pill extends View implements View.OnClickListener {
        boolean checked;
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF rect = new android.graphics.RectF();

        Pill(boolean on) {
            super(SettingsActivity.this);
            checked = on;
            setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(28)));
            setClickable(true);
            setOnClickListener(this);
        }

        public void onClick(View v) {
            checked = !checked;
            invalidate();
        }

        boolean isChecked() { return checked; }

        void setChecked(boolean b) {
            if (checked != b) { checked = b; invalidate(); }
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            paint.setColor(checked ? C_ACCENT : 0xFFDCDAE4);
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, r, r, paint);
            paint.setColor(Color.WHITE);
            float cr = r - dp(3);
            float cx = checked ? w - r : r;
            c.drawCircle(cx, r, cr, paint);
        }
    }

    Pill toggle(boolean on) {
        return new Pill(on);
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(15), dp(16), dp(15));
        c.setBackground(round(C_CARD, 20));
        c.setElevation(dp(1.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    /** 卡片头：图标 + 标题/说明 + 右侧开关 */
    LinearLayout head(String ic, String title, String desc, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(icon(ic));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        col.addView(text(title, 15.5f, C_TEXT, true));
        if (desc != null) {
            TextView d = text(desc, 12.5f, C_SUB, false);
            d.setPadding(0, dp(3), 0, 0);
            col.addView(d);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(col, clp);
        if (right != null) row.addView(right);
        return row;
    }

    SeekBar slider(int max, int progress) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(progress);
        sb.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        sb.setPadding(dp(2), dp(8), dp(2), dp(4));
        return sb;
    }

    View groupLabel(String s) {
        TextView t = text(s, 12f, C_SUB, false);
        t.setPadding(dp(6), dp(14), 0, dp(8));
        return t;
    }

    // ------------------------------------------------------------ 界面

    View buildUi() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_BG_TOP, C_BG_BOTTOM, 0xFFFFFFFF}));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        sc.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 顶部标题
        TextView kicker = text("KEEP 助手  ·  设置", 11.5f, C_ACCENT, false);
        kicker.setLetterSpacing(0.12f);
        root.addView(kicker);
        TextView h1 = text("设置", 30f, C_TEXT, true);
        h1.setPadding(0, dp(6), 0, dp(4));
        root.addView(h1);
        root.addView(text("保存后最多 15 秒生效，不用重启 Keep。", 12.5f, C_SUB, false));

        // ---------------- 训练
        root.addView(groupLabel("训练"));

        swAutoGo = toggle(true);
        LinearLayout c1 = card();
        c1.addView(head("GO", "详情页自动开练",
                "进课程后无操作自动点 GO，碰一下屏幕本次取消", swAutoGo));
        LinearLayout delayRow = new LinearLayout(this);
        delayRow.setOrientation(LinearLayout.HORIZONTAL);
        delayRow.setGravity(Gravity.CENTER_VERTICAL);
        delayRow.setPadding(0, dp(6), 0, 0);
        tvDelay = text("2 秒", 13f, C_ACCENT, true);
        sbDelay = slider(9, 1);
        sbDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                tvDelay.setText((p + 1) + " 秒");
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(8);
        slp.rightMargin = dp(8);
        delayRow.addView(sbDelay, slp);
        delayRow.addView(tvDelay);
        c1.addView(delayRow);
        root.addView(c1);

        swVol = toggle(true);
        LinearLayout c2 = card();
        c2.addView(head("VOL", "进入 Keep 自动设定音量",
                "训练语音走媒体音量，默认压到 0（静音）", swVol));

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.setPadding(0, dp(6), 0, 0);
        tvVol = text("0%", 13f, C_ACCENT, true);
        sbVol = slider(100, 0);
        sbVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                tvVol.setText(p + "%");
                // 拖动滑块本身就是"我要固定这个值"，自动关掉跟随
                if (fromUser && swVolFollow.isChecked()) swVolFollow.setChecked(false);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams vsp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vsp.leftMargin = dp(8);
        vsp.rightMargin = dp(8);
        volRow.addView(sbVol, vsp);
        volRow.addView(tvVol);
        c2.addView(volRow);

        // 滑块和下面这行之间加一条细分割线，否则两组控件会黏在一起
        View divider = new View(this);
        divider.setBackgroundColor(0xFFF0EDF7);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(12);
        divider.setLayoutParams(dlp);
        c2.addView(divider);

        LinearLayout followRow = new LinearLayout(this);
        followRow.setOrientation(LinearLayout.HORIZONTAL);
        followRow.setGravity(Gravity.CENTER_VERTICAL);
        followRow.setPadding(0, dp(14), 0, dp(2));
        LinearLayout followCol = new LinearLayout(this);
        followCol.setOrientation(LinearLayout.VERTICAL);
        followCol.addView(text("跟随进入时的音量", 14f, C_TEXT, true));
        followCol.addView(text("打开后不固定，进来时是多少就保持多少", 12f, C_SUB, false));
        followRow.addView(followCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        swVolFollow = toggle(false);
        swVolFollow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                swVolFollow.setChecked(!swVolFollow.isChecked());
                sbVol.setEnabled(!swVolFollow.isChecked());
                tvVol.setTextColor(swVolFollow.isChecked() ? C_SUB : C_ACCENT);
            }
        });
        followRow.addView(swVolFollow);
        c2.addView(followRow);
        root.addView(c2);

        swSleep = toggle(true);
        LinearLayout c3 = card();
        c3.addView(head("OFF", "训练中自动息屏",
                "到时间没操作就熄屏省电，拉到底关闭", swSleep));
        LinearLayout sleepRow = new LinearLayout(this);
        sleepRow.setOrientation(LinearLayout.HORIZONTAL);
        sleepRow.setGravity(Gravity.CENTER_VERTICAL);
        sleepRow.setPadding(0, dp(6), 0, 0);
        tvSleep = text("7 秒", 13f, C_ACCENT, true);
        sbSleep = slider(60, 7);
        sbSleep.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                tvSleep.setText(p == 0 ? "关闭" : (p + " 秒"));
                swSleep.setChecked(p > 0);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp2.leftMargin = dp(8);
        slp2.rightMargin = dp(8);
        sleepRow.addView(sbSleep, slp2);
        sleepRow.addView(tvSleep);
        c3.addView(sleepRow);
        root.addView(c3);

        // ---------------- 下载
        root.addView(groupLabel("下载"));

        swAutoDl = toggle(true);
        LinearLayout c4 = card();
        c4.addView(head("DL", "练过的课自动下载",
                "练完一次就存到本地，断网也能练", swAutoDl));
        root.addView(c4);

        swWifi = toggle(true);
        LinearLayout c5 = card();
        c5.addView(head("WI", "只在 Wi-Fi 下自动下载",
                "关掉后移动网络也会下整门课，注意流量", swWifi));
        root.addView(c5);

        LinearLayout c6 = card();
        LinearLayout dayRow = new LinearLayout(this);
        dayRow.setOrientation(LinearLayout.HORIZONTAL);
        dayRow.setGravity(Gravity.CENTER_VERTICAL);
        dayRow.addView(icon("7D"));
        LinearLayout dayCol = new LinearLayout(this);
        dayCol.setOrientation(LinearLayout.VERTICAL);
        dayCol.setPadding(dp(12), 0, dp(8), 0);
        dayCol.addView(text("下载状态复检", 15.5f, C_TEXT, true));
        dayCol.addView(text("超过这个天数会重新确认一次，0 = 只确认一次", 12.5f, C_SUB, false));
        LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dayRow.addView(dayCol, dclp);

        etDays = new EditText(this);
        etDays.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDays.setText("7");
        etDays.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        etDays.setTextColor(C_ACCENT);
        etDays.setGravity(Gravity.CENTER);
        etDays.setBackground(round(C_ICON_BG, 12));
        etDays.setPadding(dp(6), dp(6), dp(6), dp(6));
        dayRow.addView(etDays, new LinearLayout.LayoutParams(dp(62), dp(42)));
        TextView unit = text("天", 13f, C_SUB, false);
        unit.setPadding(dp(6), 0, 0, 0);
        dayRow.addView(unit);
        c6.addView(dayRow);
        root.addView(c6);

        // ---------------- 列表（放在最后，不占关键开关的位置）
        root.addView(groupLabel("列表"));

        swHide = toggle(true);
        LinearLayout c7 = card();
        c7.addView(head("RM", "长按课程移除",
                "在「练过 / 想练」里按住课程 0.55 秒，确认后只在列表里隐藏", swHide));
        root.addView(c7);

        swCount = toggle(true);
        LinearLayout c8 = card();
        c8.addView(head("NO", "移除后同步课程数量",
                "把顶部「N 节课程」改成实际的剩余数量", swCount));
        root.addView(c8);

        swWant = toggle(true);
        LinearLayout c9 = card();
        c9.addView(head("WT", "打开课程时自动加入「想练」",
                "进课程自动点一次「想练」；已想练（绿色）不会重复点", swWant));
        root.addView(c9);

        // ---------------- 按钮
        TextView save = text("保存", 16f, Color.WHITE, true);
        save.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{C_ACCENT, C_ACCENT_2});
        bg.setCornerRadius(dp(26));
        save.setBackground(bg);
        save.setPadding(0, dp(15), 0, dp(15));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(18);
        save.setLayoutParams(blp);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        root.addView(save);

        TextView reset = text("恢复默认", 13.5f, C_SUB, false);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, dp(16), 0, dp(8));
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyValues(true, 2, 7, 7);
                tvStatus.setText("已恢复默认值，点「保存」写入。");
            }
        });
        root.addView(reset);

        tvStatus = text("", 12.5f, C_SUB, false);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(6), 0, 0);
        root.addView(tvStatus);

        return sc;
    }

    // ------------------------------------------------------------ 读写配置

    void load() {
        tvStatus.setText("正在读取当前配置…");
        new Thread(new Runnable() {
            public void run() {
                final String s = readConf();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (s == null) {
                            tvStatus.setText("读取失败：没有拿到 root 权限。请在 Magisk 里允许「Keep 助手」。");
                            return;
                        }
                        applyValues(false, 2, 7, 7);
                        parseInto(s);
                        tvStatus.setText(s.trim().length() == 0
                                ? "当前是默认配置（配置文件尚未创建）。"
                                : "已读取当前配置。");
                    }
                });
            }
        }, "KeepAssist-settings-load").start();
    }

    /** reset=true 时全部回到默认值 */
    void applyValues(boolean reset, int delay, int sleep, int days) {
        if (reset) {
            swAutoGo.setChecked(true);
            swVol.setChecked(true);
            swVolFollow.setChecked(false);
            sbVol.setProgress(0);
            sbVol.setEnabled(true);
            tvVol.setText("0%");
            tvVol.setTextColor(C_ACCENT);
            swSleep.setChecked(true);
            swAutoDl.setChecked(true);
            swWifi.setChecked(true);
            swHide.setChecked(true);
            swCount.setChecked(true);
            swWant.setChecked(true);
            sbDelay.setProgress(delay - 1);
            sbSleep.setProgress(sleep);
            etDays.setText(String.valueOf(days));
            tvDelay.setText(delay + " 秒");
            tvSleep.setText(sleep + " 秒");
        }
    }

    void parseInto(String s) {
        if (s == null) return;
        for (String line : s.split("\n")) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            try {
                if ("auto_go".equals(k)) swAutoGo.setChecked(!"0".equals(v));
                else if ("auto_go_delay".equals(k)) {
                    int d = clamp(Integer.parseInt(v), 1, 10);
                    sbDelay.setProgress(d - 1);
                    tvDelay.setText(d + " 秒");
                } else if ("sleep_sec".equals(k)) {
                    int d = clamp(Integer.parseInt(v), 0, 60);
                    sbSleep.setProgress(d);
                    swSleep.setChecked(d > 0);
                    tvSleep.setText(d == 0 ? "关闭" : (d + " 秒"));
                } else if ("vol_lock".equals(k)) swVol.setChecked(!"0".equals(v));
                else if ("vol_target".equals(k)) {
                    int t = clamp(Integer.parseInt(v), -1, 150);
                    boolean follow = t < 0;
                    swVolFollow.setChecked(follow);
                    sbVol.setEnabled(!follow);
                    int pct = follow ? 0 : clamp(Math.round(t / 1.5f), 0, 100);
                    sbVol.setProgress(pct);
                    tvVol.setText(pct + "%");
                    tvVol.setTextColor(follow ? C_SUB : C_ACCENT);
                }
                else if ("auto_download".equals(k)) swAutoDl.setChecked(!"0".equals(v));
                else if ("wifi_only".equals(k)) swWifi.setChecked(!"0".equals(v));
                else if ("checked_days".equals(k)) etDays.setText(String.valueOf(clamp(Integer.parseInt(v), 0, 365)));
                else if ("hide_course".equals(k)) swHide.setChecked(!"0".equals(v));
                else if ("sync_count".equals(k)) swCount.setChecked(!"0".equals(v));
                else if ("auto_want".equals(k)) swWant.setChecked(!"0".equals(v));
            } catch (Throwable ignored) {}
        }
    }

    int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    int days() {
        try { return clamp(Integer.parseInt(etDays.getText().toString().trim()), 0, 365); }
        catch (Throwable t) { return 7; }
    }

    void save() {
        final String content = "# Keep 助手配置（设置界面生成）\n"
                + "auto_go=" + (swAutoGo.isChecked() ? 1 : 0) + "\n"
                + "auto_go_delay=" + (sbDelay.getProgress() + 1) + "\n"
                + "sleep_sec=" + (swSleep.isChecked() ? Math.max(1, sbSleep.getProgress()) : 0) + "\n"
                + "vol_lock=" + (swVol.isChecked() ? 1 : 0) + "\n"
                + "vol_target=" + (swVolFollow.isChecked() ? -1
                        : clamp(Math.round(sbVol.getProgress() * 1.5f), 0, 150)) + "\n"
                + "auto_download=" + (swAutoDl.isChecked() ? 1 : 0) + "\n"
                + "wifi_only=" + (swWifi.isChecked() ? 1 : 0) + "\n"
                + "checked_days=" + days() + "\n"
                + "hide_course=" + (swHide.isChecked() ? 1 : 0) + "\n"
                + "sync_count=" + (swCount.isChecked() ? 1 : 0) + "\n"
                + "auto_want=" + (swWant.isChecked() ? 1 : 0) + "\n";

        tvStatus.setText("正在保存…");
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = writeConf(content);
                runOnUiThread(new Runnable() {
                    public void run() {
                        tvStatus.setText(ok
                                ? "已保存。Keep 里最多 15 秒后生效（不用重启）。"
                                : "保存失败：没有 root 权限，或 /data/adb 不可写。");
                    }
                });
            }
        }, "KeepAssist-settings-save").start();
    }

    /** 返回 null = 拿不到 root；返回 "" = 文件不存在/为空 */
    String readConf() {
        // su 有可能卡在 Magisk 授权框上。老实现会一直等，界面永远停在"正在读取"。
        final Process[] proc = new Process[1];
        final String[] res = new String[]{null};
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(
                            new String[]{"su", "-c", "cat " + CONF + " 2>/dev/null"});
                    proc[0] = p;
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    r.close();
                    p.waitFor();
                    res[0] = sb.toString();
                } catch (Throwable ignored) {}
            }
        }, "KeepAssist-conf-read");
        t.setDaemon(true);
        t.start();
        try { t.join(4000); } catch (Throwable ignored) {}
        if (t.isAlive()) {
            try { if (proc[0] != null) proc[0].destroy(); } catch (Throwable ignored) {}
            return null;
        }
        return res[0];
    }

    boolean suAlive() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            r.close();
            p.waitFor();
            return s != null && s.contains("uid=0");
        } catch (Throwable t) { return false; }
    }

    boolean writeConf(String content) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "cat > " + CONF + " && chmod 644 " + CONF});
            OutputStream os = p.getOutputStream();
            os.write(content.getBytes("UTF-8"));
            os.flush();
            os.close();
            return p.waitFor() == 0;
        } catch (Throwable t) { return false; }
    }
}
