package com.shellz.keepassist;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keep 助手 —— 基于 Xposed 框架（Vector / LSPosed）
 *
 * 全部 hook 点都在系统框架类上（Activity / Dialog / PopupWindow），
 * 不依赖 Keep 内部被 SecNeo 加固混淆的类名，因此对版本升级最稳。
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String KEEP   = "com.gotokeep.keep";
    static final String SPLASH = "com.gotokeep.keep.splash.SplashActivity";
    static final String MAIN   = "com.gotokeep.keep.refactor.business.main.activity.MainActivity";
    static final String COURSE = "com.gotokeep.keep.fd.business.mycourse.activity.MyCourseActivity";
    /** 运动总结页：训练结束后自动弹出，也可从日程翻历史进入 */
    static final String SUMMARY = "com.gotokeep.keep.variplay.business.summary.activity.VpSummaryActivity";
    /** 日程页：训练结束后 Keep 默认把用户丢到这里 */
    static final String CALENDAR = "com.gotokeep.keep.km.business.aicanlendar.CalendarActivity";
    /** 课程详情页：从「我的课程」点某个课程进来，底部有绿色 GO */
    static final String DETAIL   = "com.gotokeep.keep.wt.business.course.detail8.activity.CourseDetail8Activity";
    /** 训练页（训练引擎宿主）：点 GO 之后进来的这一页 */
    static final String TRAINING = "com.keep.trainingengine.host.TrainingEngineActivity";
    /**
     * 已下载课程列表（「我的课程 → 课程 → … → 下载训练设置 → 管理已下载课程」）。
     * 这是 Keep 里唯一一个纯本地、不依赖网络的课程列表页 —— 离线时的入口。
     */
    static final String DOWNLOADS = "com.gotokeep.keep.wt.business.course.detail.activity.CourseDownloadActivity";
    static final String TAG    = "KeepAssist";

    /** 详情页停留多久没触摸就自动点 GO（默认值，实际以 Cfg 为准） */
    static final long DETAIL_IDLE_MS = 2000;
    /** 训练中多久没触摸就自动息屏（默认值，实际以 Cfg 为准） */
    static final long TRAIN_IDLE_MS  = 7000;

    static long detailIdleMs() { return Cfg.goDelayMs > 0 ? Cfg.goDelayMs : DETAIL_IDLE_MS; }
    /** 返回 0 表示用户关掉了自动息屏 */
    static long trainingIdleMs() { return Cfg.sleepSec <= 0 ? 0 : Cfg.sleepSec * 1000L; }

    static Handler  sMain;
    static Context  sCtx;
    static Activity sCur;
    static long     sBootT = 0;
    static volatile boolean sColdNavDone = false;
    /**
     * 冷启动这次我们本该把用户送去的页面（在线 = 我的课程，离线 = 已下载课程）。
     * 冷启动途中 Keep 自己可能弹出「你还有训练未完成」之类的窗口，用户在里面点完
     * （例如点「退出」）会被带回 MainActivity，把我们的跳转吞掉 ——
     * 记下目标页面，弹层一关发现不在目标页就补一次跳转。
     */
    static volatile String sColdNavTarget = null;
    /** 冷启动跳转发生的时刻。重锚只在 20 秒内有效，过了就当用户自己在用，别打扰。 */
    static volatile long sColdNavT = 0;

    static void markColdNav(String target) {
        sColdNavTarget = target;
        sColdNavT = SystemClock.uptimeMillis();
        sMainBounced = false;   // 新一次跳转，允许再顶一次
    }
    /** 冷启动后 Keep 自己又把 MainActivity 压上来，已经顶掉过一次（只顶一次，避免死循环） */
    static volatile boolean sMainBounced = false;

    /** 训练收尾看门狗：按住「完成」之后的一段时间内，一切岔路都拉回我的课程 */
    static volatile boolean sWrapUp = false;
    static long sWrapUpUntil = 0;

    /** 训练页是否在前台、以及它是什么时候退出的（用来兜底判断"刚练完"） */
    static boolean sInTraining = false;
    static long sTrainingEnd = 0;
    /** 刚练完的是哪门课。只有同一门课才需要长时间禁止自动开练。 */
    static String sTrainingEndCid = null;

    /** 无操作计时：最后一次触摸的时刻 + 当前待执行的空闲任务（详情页/训练页共用） */
    static long sLastTouch = 0;
    static Runnable sIdleTask;
    /** 这一次进入详情页是否已经点过 GO，避免重复触发 */
    static boolean sGoTapped = false;
    /**
     * 这次进入详情页，用户已经动过手 → 本次进入期间自动开练彻底作废。
     * 注意跟 sGoTapped 不一样：那个是"我已经替他点过了"，这个是"他自己接手了"。
     */
    static volatile boolean sUserTookOver = false;
    /** 进入详情页的时刻，用来过滤页面切换瞬间的残留触摸 */
    static long sDetailEnterT = 0;
    /**
     * 详情页上正在显示的 Dialog（右上角「…」的更多选项、下载设置面板等）。
     * 这些面板是独立窗口（ty=APPLICATION + DIM_BEHIND），里面的点击不会经过
     * Activity 的 DecorView，所以"多久没摸屏幕"根本感知不到 ——
     * 只能靠 Dialog 生命周期判断"用户正在操作"，有弹层就绝不自动开练。
     */
    static final java.util.HashSet<Dialog> sOpenDialogs = new java.util.HashSet<Dialog>();
    /** 最近一次 show 的弹层（HashSet 无序，下载自动化必须拿"最新那个"） */
    static Dialog sLastDialog = null;
    /** 最近一次弹层关闭的时刻（判断"刚被弹层挤回来"的签名） */
    static volatile long sDlgClosedT = 0;
    /** 已经发过息屏指令，防止 7 秒到点后重复注入 */
    static boolean sSleeping = false;

    // ---- 训练结束自动亮屏
    /** 上次亮屏的时刻，避免连续几次触发重复注入按键 */
    static long sLastWake = 0;

    // ---- 媒体音量锁：进入 Keep 前台时以当时的音量为基准，会话内被改动就拉回来
    /** 当前会话的音量基准，-1 表示不在会话里 */
    static int  sVolBaseline = -1;
    static Runnable sVolTask;
    /** 音量轮询间隔 */
    static final long VOL_POLL_MS = 1200;
    /** 上次用 root 设音量的时刻，用来给 root 兜底节流 */
    static long sVolLastRoot = 0;
    /** 待判定的"Keep 是否真的到后台了"（避免页面切换的 100ms 空档误判） */
    static Runnable sVolIdleCheck;
    /** 音量锁被配置关掉时，日志只打一次 */
    static boolean sVolOffLogged = false;

    // ---- 离线 / 自动下载
    /** 进详情页时从 Intent 里拿到的课程 id（collectionId） */
    static String sDetailCourseId = null;
    /** 最近一次读到课程 id 的时刻：从日程直接开练时，用它拒绝继承很久以前的课 */
    static volatile long sDetailCidT = 0;
    /** 当前详情页实例，用来区分「同一次进入」和「新一次进入」（防止 re-resume 复活自动开练） */
    static java.lang.ref.WeakReference<Activity> sDetailAct = null;
    /** 正在练的课程 id（训练开始时从详情页继承） */
    static String sTrainingCourseId = null;
    /** 练过但还没下载的课程 id，持久化，重启后仍然有效 */
    static final java.util.HashSet<String> sNeedDownload = new java.util.HashSet<String>();
    static boolean sNeedLoaded = false;
    /** 自动下载机器人正在详情页上操作：此时禁止自动点 GO（会抢走页面、下载中断） */
    static volatile boolean sDlBusy = false;
    /** 正在处理哪门课的下载（详情页会 resume 两次，不去重就会点两遍"全部下载"） */
    static volatile String sDlRunningCid = null;
    /**
     * 训练结束后要立刻下好的课。
     * 流程：训练结束 → 记住 id → 收尾流程走完 → 自动打开该课详情页
     *      → 机器人点「…→下载→全部下载」 → 下完自动退回课程页。
     */
    static volatile String sAutoDlCid = null;
    /** 这个详情页是我们为了下载而自己打开的（下完要负责关掉，别把用户困住） */
    static volatile boolean sAutoDlOpened = false;
    /** 「队列泵」在跑：正在按顺序把待下载的课一门门下掉 */
    static volatile boolean sPumping = false;
    /** 每门课失败次数，超过上限就不再折腾（内存态，重启重来） */
    static final java.util.HashMap<String, Integer> sPumpTries = new java.util.HashMap<String, Integer>();
    static final int PUMP_MAX_TRIES = 2;
    static long sPumpArmT = 0;
    static int sPumpProbe = 0;
    /** 点「全部下载」之前下载库的快照，用来判断任务到底有没有真的开始 */
    static String sDlDbBefore = null;
    /**
     * 已经确认过下载状态的课程 → 确认时刻（墙钟毫秒）。
     * 不能是"终身信任"：用户在 Keep 里手动删掉下载、或者任务派发后被取消，
     * 永久记住就会导致这门课再也不补 —— 所以按 Cfg.checkDays 过期复检。
     */
    static final java.util.HashMap<String, Long> sDlChecked = new java.util.HashMap<String, Long>();
    static boolean sCheckedLoaded = false;
    /** 派发后二次确认：每门课最多补一次，避免来回重下 */
    static final java.util.HashMap<String, Integer> sVerifyTries = new java.util.HashMap<String, Integer>();
    /** 点「全部下载」时课程目录的体积，用于派发后确认真的在下载 */
    static long sDlSizeBefore = 0;
    /** 这次机器人干活时，右上角面板显示的是不是「已下载」 */
    static volatile boolean sSawDownloaded = false;
    /** 已经点过「全部下载」，任务交给 Keep 下载器了 —— 这时候可以立刻点 GO */
    static volatile boolean sDlDispatched = false;
    /** 详情页已经挂好计时，到点时需要先跑一次下载确认再点 GO */
    static volatile boolean sPendingDl = false;
    /** 已 resumed 的页面集合：空了就说明 Keep 整体到了后台，会话结束 */
    static final java.util.HashSet<Activity> sResumed = new java.util.HashSet<Activity>();

    // ================= 可配置参数 =================
    /**
     * 配置文件放在 /data/adb/keepassist.conf（只有 root 能读写，和模块 APK 的 prefs 不冲突）。
     * 模块跑在 Keep 进程里，跨 uid 读模块自己的 SharedPreferences 在 Android 13 上不可靠，
     * 而 Keep 进程本来就有 root（息屏/亮屏都在用），用 su 读一个文本文件最稳。
     *
     * 键值都是 key=value，缺省即下面这些默认值（与 v1.7 的行为完全一致）：
     *   auto_go=1          详情页自动开练
     *   auto_go_delay=2    进详情页后多少秒无操作才开练
     *   sleep_sec=7        训练中多少秒无操作息屏（0 = 关闭）
     *   vol_lock=1         保持打开 Keep 时的媒体音量
     *   auto_download=1    练过的课自动下载
     *   wifi_only=1        只在 Wi-Fi 下自动下载（避免吃蜂窝流量）
     *   checked_days=7     下载状态复检周期（天，0 = 只在首次检查）
     *
     * 设置界面在模块 APK 里（桌面上的「Keep 助手」图标），它用 su 写这个文件。
     */
    static final String CFG_FILE = "/data/adb/keepassist.conf";

    static final class Cfg {
        static volatile boolean autoGo     = true;
        static volatile int     goDelayMs  = 2000;
        static volatile int     sleepSec   = 7;
        static volatile boolean volLock    = true;
        /**
         * 进入 Keep 后要把媒体音量设成多少。
         *   0..150 = 固定值（0 就是静音，默认）
         *   -1     = 跟随「进入 Keep 那一刻」的音量（v1.4 的老行为）
         * 老版本只有 vol_lock，语义是"保持当时音量"，结果你在外面把音量调到 55
         * 再进 Keep，它会把 55 锁住 —— 训练语音照样大声。用户要的是"别吵到我"，
         * 所以默认改成 0。
         */
        static volatile int     volTarget  = 0;
        static volatile boolean autoDl     = true;
        static volatile boolean wifiOnly   = true;
        static volatile int     checkDays  = 7;
        /** 长按课程从列表移除（总开关） */
        static volatile boolean hideCourse = true;
        /** 移除后同步顶部的「N 节课程」数量 */
        static volatile boolean syncCount  = true;
        /** 打开课程详情时自动点「想练」（页面没有这个按钮就不动） */
        static volatile boolean autoWant   = false;
        static volatile long    lastLoad   = 0;
        static volatile boolean loading    = false;

        /** 15 秒内只读一次，避免每次进页面都 spawn 一个 su */
        static void refresh() {
            long now = SystemClock.uptimeMillis();
            if (loading || now - lastLoad < 15000) return;
            lastLoad = now;
            loading = true;
            new Thread(new Runnable() {
                public void run() {
                    try { parse(readRaw()); }
                    catch (Throwable t) { log("cfg load fail " + t); }
                    finally { loading = false; }
                }
            }, "KeepAssist-cfg").start();
        }

        static String readRaw() {
            // su 有可能卡住（Magisk 弹授权框、或 su 二进制异常）。
            // 老代码直接 readLine + waitFor，一旦卡住这个线程永久阻塞，
            // 而 loading 会一直是 true —— 配置从此再也刷新不了，改设置像没生效。
            final Process[] proc = new Process[1];
            final String[] out = new String[]{""};
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        Process p = Runtime.getRuntime().exec(
                                new String[]{"su", "-c", "cat " + CFG_FILE + " 2>/dev/null"});
                        proc[0] = p;
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                        r.close();
                        p.waitFor();
                        out[0] = sb.toString();
                    } catch (Throwable ignored) {}
                }
            }, "KeepAssist-cfg-read");
            t.setDaemon(true);
            t.start();
            try { t.join(2500); } catch (Throwable ignored) {}
            if (t.isAlive()) {
                try { if (proc[0] != null) proc[0].destroy(); } catch (Throwable ignored) {}
                log("cfg: read timeout (su not responding)");
                return "";
            }
            return out[0];
        }

        static void parse(String s) {
            if (s == null || s.length() == 0) return;
            for (String line : s.split("\n")) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                try {
                    if ("auto_go".equals(k))            autoGo    = !"0".equals(v);
                    else if ("auto_go_delay".equals(k)) goDelayMs = clamp(Integer.parseInt(v) * 1000, 1000, 60000);
                    else if ("sleep_sec".equals(k))     sleepSec  = clamp(Integer.parseInt(v), 0, 600);
                    else if ("vol_lock".equals(k))      volLock   = !"0".equals(v);
                    else if ("vol_target".equals(k))    volTarget = clamp(Integer.parseInt(v), -1, 150);
                    else if ("auto_download".equals(k)) autoDl    = !"0".equals(v);
                    else if ("wifi_only".equals(k))     wifiOnly  = !"0".equals(v);
                    else if ("checked_days".equals(k))  checkDays = clamp(Integer.parseInt(v), 0, 365);
                    else if ("hide_course".equals(k))   hideCourse = !"0".equals(v);
                    else if ("sync_count".equals(k))    syncCount  = !"0".equals(v);
                    else if ("auto_want".equals(k))     autoWant   = !"0".equals(v);
                } catch (Throwable ignored) {}
            }
            // 只在配置真的变了才写日志。这里 15 秒被调用一次，无条件 log 会让
            // 512KB 的日志被"cfg: ..."刷满，把真正的关键行挤掉。
            String fp = autoGo + "/" + goDelayMs + "/" + sleepSec + "/" + volLock
                    + "/" + volTarget + "/" + autoDl + "/" + wifiOnly + "/" + checkDays
                    + "/" + hideCourse + "/" + syncCount + "/" + autoWant;
            if (fp.equals(sLastCfg)) return;
            sLastCfg = fp;
            log("cfg: autoGo=" + autoGo + " delay=" + goDelayMs + "ms sleep=" + sleepSec
                    + "s volLock=" + volLock + " volTarget=" + volTarget
                    + " autoDl=" + autoDl + " wifiOnly=" + wifiOnly
                    + " checkedDays=" + checkDays
                    + " hide=" + hideCourse + " syncCount=" + syncCount + " autoWant=" + autoWant);
        }

        static String sLastCfg = "";

        static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

        static String dump() {
            return "auto_go=" + (autoGo ? 1 : 0)
                    + "\nauto_go_delay=" + (goDelayMs / 1000)
                    + "\nsleep_sec=" + sleepSec
                    + "\nvol_lock=" + (volLock ? 1 : 0)
                    + "\nvol_target=" + volTarget
                    + "\nauto_download=" + (autoDl ? 1 : 0)
                    + "\nwifi_only=" + (wifiOnly ? 1 : 0)
                    + "\nchecked_days=" + checkDays
                    + "\nhide_course=" + (hideCourse ? 1 : 0)
                    + "\nsync_count=" + (syncCount ? 1 : 0)
                    + "\nauto_want=" + (autoWant ? 1 : 0);
        }
    }

    static int  ID_SKIP = 0, ID_TEXT_NAME = 0, ID_NAME = 0;
    static boolean sIdsReady = false;

    /** 广告 SDK 的 Activity 前缀，命中后直接阻断跳转 */
    static final String[] AD_PKGS = {
            "com.qq.e.", "com.tencent.gdt", "com.bykv.", "com.bytedance.sdk",
            "com.ss.android.", "com.noah.adn.", "com.liangyuji.ad.", "com.mbridge.",
            "com.kwad.", "com.baidu.mobads", "com.huawei.hms.ads", "com.sigmob."
    };

    /** 倒计时式关闭按钮，如「1s 后可关闭」「5 秒后可关闭」 */
    static final Pattern COUNTDOWN = Pattern.compile("\\d+\\s*[sS秒]\\s*后可关");
    /** 体积文案，如「6.62M」「492.49K」——用来判断下载面板里到底有没有课程条目 */
    static final Pattern SIZE = Pattern.compile("\\d+(?:\\.\\d+)?\\s*[KMG](?![A-Za-z])");

    interface Match { boolean m(View v); }
    interface Act   { void a(View v); }

    // ---------------------------------------------------------------- 入口

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!KEEP.equals(lp.packageName)) return;
        sBootT = SystemClock.uptimeMillis();
        log("=== loaded, first=" + lp.isFirstApplication + " ===");
        Cfg.refresh();
        hookCreate();
        hookResume();
        hookBack();
        hookNewIntent();
        hookDialog();
        hookPopup();
        hookStartActivity();
        hookTouch();
        hookPause();
        hookIntentLog();
    }

    // ------------------------------------------------- 开屏即直达（核心加速）

    /**
     * 开屏页一被创建就跳「我的课程」，完全不经过首页与广告位。
     * 实测 MyCourseActivity 可独立启动，不依赖 Splash/Main 的初始化。
     */
    void hookCreate() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    final Activity a = (Activity) p.thisObject;
                    if (!KEEP.equals(a.getPackageName())) return;
                    String cn = a.getClass().getName();
                    if (!SPLASH.equals(cn)) return;
                    ensureMain();
                    sCtx = a.getApplicationContext();
                    sMain.postDelayed(new Runnable() {
                        public void run() {
                            try {
                                if (a.isFinishing()) return;
                                // 离线时「练过」列表只剩"网络未连接"，直接去本地已下载课程页
                                String target = isOnline() ? COURSE : DOWNLOADS;
                                Intent i = new Intent();
                                i.setClassName(KEEP, target);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                a.startActivity(i);
                                a.overridePendingTransition(0, 0);
                                a.finish();
                                sColdNavDone = true;   // 已直达目标页，MainActivity 不再自动导航
                                markColdNav(target);
                                log("JUMP splash -> " + (isOnline() ? "course" : "downloads(offline)")
                                        + " @" + el(sBootT) + "ms");
                            } catch (Throwable t) { log("jump fail " + t); }
                        }
                    }, 10);
                }
            });
        } catch (Throwable t) { log("hookCreate " + t); }
    }

    // ------------------------------------------------------- Activity 生命周期

    /** Keep 的课程 id 是 24 位 hex（Mongo ObjectId 风格） */
    static final Pattern HEX24 = Pattern.compile("^[0-9a-fA-F]{24}$");

    /**
     * 从 Activity 的启动 Intent 里取课程 id。
     *
     * 为什么每次进详情页都要重读：进训练页时我们会把 sDetailCourseId 清掉，
     * 而训练中途退出回到的是**同一个详情页实例**（onCreate 不会再跑），
     * 如果只靠 onCreate 那一次，第二次点 GO 就继承不到 id，练完不会进下载队列。
     *
     * 训练页的 Intent 参数名不确定（被加固），所以除了已知 key，还会扫一遍
     * Bundle 里所有 24 位 hex 的字符串值，命中的 key 会写进日志便于核对。
     */
    String intentCid(Activity a, String why, boolean scan) {
        try {
            Intent it = a.getIntent();
            if (it == null) return null;
            Bundle b = it.getExtras();
            if (b == null) return null;
            String[] keys = scan
                    ? new String[]{"collectionId", "courseId", "planId", "workoutId", "id"}
                    : new String[]{"collectionId", "courseId"};
            for (String k : keys) {
                Object v = null;
                try { v = b.get(k); } catch (Throwable ignored) {}
                if (v instanceof String && HEX24.matcher((String) v).matches()) {
                    log("cid(" + why + ") <- " + k + "=" + v);
                    return (String) v;
                }
            }
            if (!scan) return null;
            for (String k : b.keySet()) {
                Object v = null;
                try { v = b.get(k); } catch (Throwable ignored) {}
                if (v instanceof String && HEX24.matcher((String) v).matches()) {
                    log("cid(" + why + ") <- scan " + k + "=" + v);
                    return (String) v;
                }
            }
        } catch (Throwable t) { log("cid(" + why + ") fail " + t); }
        return null;
    }

    String intentCid(Activity a, String why) {
        return intentCid(a, why, true);
    }

    /**
     * 诊断用：把详情页/训练页的 Intent 参数写进日志。
     * 自动下载需要知道课程 id，而它是从这里传进去的（界面文案拿不到 id）。
     */
    void hookIntentLog() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    if (!KEEP.equals(a.getPackageName())) return;
                    String cn = a.getClass().getName();
                    if (!DETAIL.equals(cn) && !TRAINING.equals(cn) && !SUMMARY.equals(cn)) return;
                    try {
                        Intent it = a.getIntent();
                        if (it == null) return;
                        StringBuilder sb = new StringBuilder("intent ");
                        sb.append(DETAIL.equals(cn) ? "DETAIL" : (TRAINING.equals(cn) ? "TRAINING" : "SUMMARY"));
                        String ds = it.getDataString();
                        if (ds != null) sb.append(" data=").append(ds);
                        Bundle b = it.getExtras();
                        if (b != null) {
                            if (DETAIL.equals(cn)) {
                                // 详情页：collectionId 是权威来源，同时记下时刻用于"刚看过这门课"的判断
                                String cid = intentCid(a, "detail");
                                if (cid != null) {
                                    sDetailCourseId = cid;
                                    sDetailCidT = SystemClock.uptimeMillis();
                                    sb.append(" <<cid=").append(cid).append(">>");
                                }
                            } else if (TRAINING.equals(cn)) {
                                // 训练页：参数名不确定，但只接受 collectionId / courseId 这两个明确的名字。
                                // 不做全量扫描 —— 训练页 Bundle 里可能有 userId 之类的 24 位 hex，
                                // 认错就会去下载另一门课。
                                sTrainingCourseId = null;
                                String tcid = intentCid(a, "training", false);
                                if (tcid != null) {
                                    sTrainingCourseId = tcid;
                                    sb.append(" <<tcid=").append(tcid).append(">>");
                                }
                            }
                            for (String k : b.keySet()) {
                                String v;
                                try { v = String.valueOf(b.get(k)); } catch (Throwable t) { v = "?"; }
                                if (v.length() > 100) v = v.substring(0, 100) + "...";
                                sb.append(" | ").append(k).append("=").append(v);
                            }
                        }
                        log(sb.toString());
                    } catch (Throwable t) { log("intent log fail " + t); }
                }
            });
        } catch (Throwable t) { log("hookIntentLog " + t); }
    }

    void hookResume() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    String cn = a.getClass().getName();
                    if (!KEEP.equals(a.getPackageName())) return;
                    ensureMain();
                    sCtx = a.getApplicationContext();
                    sCur = a;
                    Cfg.refresh();     // 15 秒节流，改了配置最多等这么久生效
                    markResumed(a);
                    if (sWrapUp) checkWake("wrapup-resume");

                    // 队列泵为了下载打开了某门课的详情页，但那一页落到了别的地方
                    // （实测：cid 对应的是一门付费课时，详情页会直接跳 CompleteSaleActivity）。
                    // 绝不能把用户留在这种页面上 —— 立刻收工并关掉。
                    if (sAutoDlOpened && !DETAIL.equals(cn) && !COURSE.equals(cn)
                            && !MAIN.equals(cn) && !DOWNLOADS.equals(cn)
                            && !SPLASH.equals(cn)) {
                        log("dl: auto-opened landed on " + cn + " -> close");
                        if (sDlBusy) dlDone("wrong-page");
                        else { sAutoDlOpened = false; sAutoDlCid = null; }
                        try {
                            a.finish();
                            a.overridePendingTransition(0, 0);
                        } catch (Throwable ignored) {}
                        return;
                    }

                    // 训练页进出记录：这是判断"刚练完"最硬的信号，不依赖任何界面文案
                    // 注意 TrainingEngineActivity 里并不含 "TrainingActivity" 这个子串，
                    // 必须单独判断，否则训练结束信号永远不触发
                    if (cn.contains("TrainingActivity") || cn.contains("TrainingEngineActivity")
                            || cn.contains("training.core")) {
                        sInTraining = true;
                        // 用户自己点了 GO 走了 → 机器人收工，别在不可见的页面上瞎点
                        if (sDlBusy) dlDone("training-started");
                        // 训练开始时把详情页的课程 id 继承下来，练完要按它去下载。
                        // 时间窗收到 5 分钟：之前是 30 分钟，结果"20 分钟前看过 A 课、
                        // 现在从日程直接练 B 课"会把 A 记进下载队列，去下错的课。
                        // 5 分钟足够覆盖「点进详情页 → 2 秒开练」，超了就宁可不记。
                        long sinceDetail = sDetailCidT == 0 ? Long.MAX_VALUE
                                : SystemClock.uptimeMillis() - sDetailCidT;
                        if (sDetailCourseId != null && sinceDetail < 5 * 60 * 1000L) {
                            sTrainingCourseId = sDetailCourseId;
                        } else if (sDetailCourseId != null) {
                            log("training: detail cid stale (" + (sinceDetail / 60000) + "min), ignore");
                        }
                        // 注意：这里**不能**把 sDetailCourseId 清空。
                        // 训练中途退出回到的是同一个详情页实例，onCreate 不会重跑，
                        // 一旦清空，第二次点 GO 就没法继承 id，练完不会进下载队列
                        // （日志表现就是 `dl: training course id unknown, skip`）。
                        // sDetailCourseId 的生命周期改由 onDetail() 每次从 Intent 重读来维护。
                        if (sTrainingCourseId != null) log("training cid=" + sTrainingCourseId);
                        else log("training cid unknown (no detail id / no intent id)");
                    } else if (sInTraining) {
                        sInTraining = false;
                        sTrainingEnd = SystemClock.uptimeMillis();
                        sTrainingEndCid = sTrainingCourseId;
                        log("training ended -> next=" + cn);
                        markTrainedCourseForDownload();
                        // 训练中我们可能已经把屏幕睡掉了，练完必须点亮，否则用户看不到总结页。
                        // 实测：黑屏状态下 Keep 启动新页面，onResume 照样回调，所以这里可靠。
                        wakeScreen("training-end");
                    }
                    // 停在课程列表（练过 / 已下载课程）时，把断网期间攒下的待下载课补上
                    if (COURSE.equals(cn) || DOWNLOADS.equals(cn)) armPump(5000);
                    if (SPLASH.equals(cn)) {
                        onSplash(a);
                    } else if (COURSE.equals(cn)) {
                        onCourse(a);
                    } else if (DETAIL.equals(cn)) {
                        onDetail(a);
                    } else if (TRAINING.equals(cn)) {
                        onTraining(a);
                    } else if (SUMMARY.equals(cn)) {
                        onSummary(a);
                    } else if (sWrapUp) {
                        // 收尾期：主页（日程 tab）、分享页、日程页一律拉回我的课程。
                        // 但用户自己点进详情页不算岔路 —— 那是他在找下一门课练。
                        if (DETAIL.equals(cn)) {
                            sWrapUp = false;
                            log("wrapup off (user opened detail)");
                        } else {
                            log("wrapup: side page " + cn);
                            wrapJumpBack(a, cn);
                        }
                    } else if (MAIN.equals(cn)) {
                        onMain(a);
                    } else {
                        log("resume: " + cn);
                        scanActivityAd(a, cn);
                    }
                }
            });
        } catch (Throwable t) { log("hookResume fail " + t); }
    }

    /** 进程已在后台时，从桌面再点图标也落到课程页 */
    void hookNewIntent() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    if (!KEEP.equals(a.getPackageName())) return;
                    if (!MAIN.equals(a.getClass().getName())) return;
                    Intent it = (Intent) p.args[0];
                    if (it == null) return;
                    if (!Intent.ACTION_MAIN.equals(it.getAction())) return;
                    if (!it.hasCategory(Intent.CATEGORY_LAUNCHER)) return;
                    log("launcher re-entry -> course");
                    try {
                        Intent i = new Intent();
                        i.setClassName(KEEP, COURSE);
                        a.startActivity(i);
                    } catch (Throwable t) { log("re-entry fail " + t); }
                }
            });
        } catch (Throwable t) { log("hookNewIntent " + t); }
    }

    /**
     * 课程页按返回时回到 App 主页，避免直接退出 App。
     *
     * 为什么以前会退出：冷启动是用 FLAG_ACTIVITY_CLEAR_TASK 跳过来的，任务栈里
     * 就只剩下 MyCourseActivity（MainActivity 已被清掉）。这时按返回 / 从左边缘右滑，
     * Keep 自己把这个唯一页面 finish 掉 → 任务空了 → 系统直接退回桌面。
     *
     * 为什么以前"翻一下 tab 再返回"就正常：翻 tab 会在 Keep 内部压入返回栈，
     * 第一次返回被它自己消费掉了，第二次才真正 finish —— 看起来就像"好了"。
     *
     * 所以两处都要拦：
     *  1) Activity.finish()：不管返回键、手势还是 Keep 自己调的，只要是课程页且
     *     已经是任务栈根（下面没有别的页面），先把 MainActivity 顶上去再放行；
     *  2) onBackPressed：被子类重写且不调 super 时上面那条收不到，这里吃掉这次返回。
     */
    static volatile boolean sBackRedirect = false;

    void hookBack() {
        // (1) finish() 兜底：覆盖返回键、手势返回、Keep 自己 finish 的所有路径
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "finish", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    if (a == null || !KEEP.equals(a.getPackageName())) return;
                    if (!COURSE.equals(a.getClass().getName())) return;
                    if (sBackRedirect) return;
                    boolean root;
                    try { root = a.isTaskRoot(); } catch (Throwable t) { root = false; }
                    if (!root) return;   // 下面还有 Main，正常 finish 就行
                    sBackRedirect = true;
                    sColdNavTarget = MAIN;   // 别让 20 秒内的"重锚"把用户又拉回课程页
                    log("back: course is task root -> start main");
                    try {
                        Intent i = new Intent();
                        i.setClassName(KEEP, MAIN);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(i);
                    } catch (Throwable t) { log("back start main fail " + t); }
                    sMain.postDelayed(new Runnable() {
                        public void run() { sBackRedirect = false; }
                    }, 2000);
                }
            });
        } catch (Throwable t) { log("hookFinish " + t); }

        // (2) onBackPressed：被重写不调 super 的情况
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onBackPressed", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    if (!KEEP.equals(a.getPackageName())) return;
                    if (!COURSE.equals(a.getClass().getName())) return;
                    boolean root;
                    try { root = a.isTaskRoot(); } catch (Throwable t) { root = false; }
                    if (!root) return;   // 正常返回上一层
                    sBackRedirect = true;
                    sColdNavTarget = MAIN;
                    try {
                        Intent i = new Intent();
                        i.setClassName(KEEP, MAIN);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(i);
                        p.setResult(null);   // 吃掉这次返回，由我们自己收尾
                        // 主页已经在下面了，把课程页关掉，用户才真的"回到主页"。
                        // finish 钩子看到 sBackRedirect 会跳过重复处理。
                        a.finish();
                        log("back: course -> main (consumed)");
                    } catch (Throwable t) { log("back fail " + t); }
                    sMain.postDelayed(new Runnable() {
                        public void run() { sBackRedirect = false; }
                    }, 3000);
                }
            });
        } catch (Throwable t) { log("hookBack " + t); }
    }

    /** 开屏页：广告一出现立刻点掉「跳过」 */
    void onSplash(final Activity a) {
        initIds(a);
        final long t0 = SystemClock.uptimeMillis();
        sMain.post(new Runnable() {
            public void run() {
                if (sCur != a || a.isFinishing()) return;
                View skip = a.findViewById(ID_SKIP);
                if (skip != null && skip.isShown()) {
                    boolean ok = clickDeep(skip);
                    log("splash skip ok=" + ok + " @" + el(t0) + "ms");
                    if (ok) return;
                }
                if (el(t0) < 6000) sMain.postDelayed(this, 40);
            }
        });
    }

    /** 主页：冷启动后自动走到「我的 → 我的课程 → 练过」 */
    void onMain(final Activity a) {
        initIds(a);
        {
            Intent di = null;
            try { di = a.getIntent(); } catch (Throwable ignored) {}
            log("main: navDone=" + sColdNavDone + " target="
                    + (sColdNavTarget == null ? "-" : sColdNavTarget.substring(sColdNavTarget.lastIndexOf('.') + 1))
                    + " t=" + el(sBootT) + "ms act=" + (di == null ? "null" : String.valueOf(di.getAction())));
        }
        // 桌面 launcher 进入（含热启动复用实例）→ 转课程页。
        // 用 setIntent 消费掉 launcher intent，避免用户从课程页返回后又被弹回去。
        try {
            Intent it = a.getIntent();
            if (it != null && Intent.ACTION_MAIN.equals(it.getAction())
                    && it.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                a.setIntent(new Intent());
                sColdNavDone = true;
                final String target = isOnline() ? COURSE : DOWNLOADS;
                markColdNav(target);
                log("launcher entry -> " + (isOnline() ? "course" : "downloads(offline)")
                        + " @" + el(sBootT) + "ms");
                sMain.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            Intent i = new Intent();
                            i.setClassName(KEEP, target);
                            a.startActivity(i);
                        } catch (Throwable t) { log("launcher jump fail " + t); }
                    }
                }, 20);
                return;
            }
        } catch (Throwable t) { log("main intent " + t); }

        if (sColdNavDone) {
            // Keep 的 Splash 在 finish 之后仍会把它自己的「进主页」流程跑完，
            // 几百毫秒后把 MainActivity 压在已下载课程页上面。此时直接 finish 掉主页，
            // 下层的已下载课程页就露出来了（比再跳一次更干净，没有闪屏）。
            // 用户主动返回进主页时，这个 MainActivity 是我们自己拉起来的，
            // 不能当成"Keep 又把主页顶上来"再 finish 掉（以前就是这里把返回吃掉，
            // 表现成按返回没反应 / 直接退出 App）。
            if (sColdNavTarget != null && !MAIN.equals(sColdNavTarget) && !sMainBounced
                    && !sBackRedirect
                    && SystemClock.uptimeMillis() - sColdNavT < 4000) {
                sMainBounced = true;
                log("main: rogue start over target -> finish main");
                try {
                    a.finish();
                    a.overridePendingTransition(0, 0);
                } catch (Throwable t) { log("finish main fail " + t); }
                return;
            }
            // 兜底：弹层没走 Dialog.dismiss（页面直接 finish）时这里补一次。
            // 只有"刚刚关过弹层"才动手，否则用户按返回键回到主页也会被拽回去。
            if (SystemClock.uptimeMillis() - sDlgClosedT < 3000) {
                final Activity ma = a;
                sMain.postDelayed(new Runnable() {
                    public void run() { reanchorIfKicked(ma, "main-resume"); }
                }, 600);
            }
            return;
        }
        sColdNavDone = true;
        // 非 launcher 进入（其它 App 拉起等）也走同一套离线路由：
        // 没网时「我的 → 我的课程 → 练过」这条链路点不出内容，直接去已下载课程页。
        if (!isOnline()) {
            log("main: offline -> jump downloads @" + el(sBootT) + "ms");
            markColdNav(DOWNLOADS);
            jumpTo(a, DOWNLOADS, "offline-main");
            return;
        }
        final long t0 = SystemClock.uptimeMillis();
        log("main resumed, start nav @" + el(t0) + "ms");
        navStep(a, 0, t0);
    }

    void navStep(final Activity a, final int step, final long t0) {
        final String[] want = {"我的", "我的课程", "练过"};
        waitView(a, 6000, new Match() {
            public boolean m(View v) {
                if (!(v instanceof TextView)) return false;
                String tx = String.valueOf(((TextView) v).getText());
                if (step == 1) return "我的课程".equals(tx) && "name".equals(idName(v));
                if (step == 2) return "练过".equals(tx) && v.isClickable();
                return want[step].equals(tx);
            }
        }, new Act() {
            public void a(View v) {
                boolean ok = clickDeep(v);
                log("nav" + (step + 1) + " [" + want[step] + "] ok=" + ok + " @" + el(t0) + "ms");
                if (step < 2) {
                    final int next = step + 1;
                    sMain.postDelayed(new Runnable() {
                        public void run() { navStep(a, next, t0); }
                    }, 100);
                }
            }
        });
    }

    /** 我的课程页：切到「练过」 */
    void onCourse(final Activity a) {
        // 没网时「练过」列表是空的（"网络未连接 / 请检查你的网络设置后刷新"），
        // 改去本地已下载课程页，那里能直接点进课程开练。
        if (!isOnline()) {
            log("course: offline -> jump downloads");
            markColdNav(DOWNLOADS);
            jumpTo(a, DOWNLOADS, "offline");
            return;
        }
        initIds(a);
        installTouchSpy(a);
        armHiddenScan(a);
        // 顶部标题「我的课程」是打开 Keep 助手的隐藏入口，先把它找出来记着
        sMain.postDelayed(new Runnable() {
            public void run() { cacheCourseTitle(a); }
        }, 350);
        final long t0 = sBootT;
        log("course resumed @" + el(t0) + "ms, tap 练过");
        waitView(a, 5000, new Match() {
            public boolean m(View v) {
                return v instanceof TextView
                        && "练过".equals(String.valueOf(((TextView) v).getText()))
                        && v.isClickable()
                        && v.isShown();
            }
        }, new Act() {
            public void a(View v) {
                clickDeep(v);
                log("course: 练过 tapped @" + el(t0) + "ms");
            }
        });
    }

    // ------------------------------------------- 无操作自动化（详情页 / 训练页）

    /**
     * 记录"最后一次屏幕操作"的时刻。
     *
     * 踩过的坑（都实测过，别再回头试）：
     *  - hook Activity.dispatchTouchEvent：Keep 的页面重写了它且不调 super，收不到；
     *  - hook ViewRootImpl.dispatchInputEvent：hookAllMethods 找不到方法，一条都没命中；
     *  - 给 DecorView 挂 OnTouchListener：DecorView 重写了 dispatchTouchEvent，
     *    直接把事件交给 Window.Callback(Activity)，根本不走 View 的 listener 逻辑。
     *
     * 所以最终做法是 installTouchSpy 里按“实例的真实类”去 hook，见那个方法。
     */
    void hookTouch() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent",
                    MotionEvent.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Activity a = (Activity) p.thisObject;
                            if (a == null) return;
                            String cn = a.getClass().getName();
                            if (!DETAIL.equals(cn) && !TRAINING.equals(cn)) return;
                            MotionEvent e = (MotionEvent) p.args[0];
                            if (e == null) return;
                            int act = e.getActionMasked();
                            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                                onUserTouch(cn);
                            }
                        }
                    });
            log("hookTouch(Activity) installed");
        } catch (Throwable t) { log("hookTouch " + t); }
    }

    /** 页面一离开（返回、切后台、跳别的页面）就把计时停掉，避免在别的页面上误动作 */
    void hookPause() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    if (a == null) return;
                    String cn = a.getClass().getName();
                    if (KEEP.equals(a.getPackageName())) markPaused(a);
                    if (DETAIL.equals(cn) || TRAINING.equals(cn)) {
                        cancelIdle(cn.substring(cn.lastIndexOf('.') + 1));
                    }
                }
            });
        } catch (Throwable t) { log("hookPause " + t); }
    }

    /**
     * 只更新时间戳，别的什么都不做。
     * 滑动时 ACTION_MOVE 每秒会来几十次，这里绝对不能重挂 Handler 或写日志，
     * 否则训练时的手势会明显发涩。续期交给 armIdle 里的任务自己处理。
     *
     * 但详情页有个例外：**碰一下就算接管**。
     * 老逻辑是"碰屏幕就把 2 秒往后推"，结果你滑几下、停手 2 秒，照样被推进训练。
     * 用户的诉求很明确 —— 进页面后只要动过一次手，这次就不许替我点 GO。
     */
    void onUserTouch(String cn) {
        sLastTouch = SystemClock.uptimeMillis();
        if (!DETAIL.equals(cn) || sUserTookOver) return;
        // 页面刚进来的那一瞬间，事件可能还是上一个页面残留的，给 400ms 宽限
        if (SystemClock.uptimeMillis() - sDetailEnterT < 400) return;
        sUserTookOver = true;
        cancelIdle("user touched detail");
        log("auto-GO cancelled: user is operating");
    }

    // ================= 课程列表：长按移除 =================
    // 用户要求：在「练过」列表长按某门课，弹窗确认后把它从列表里隐藏。
    // 列表是接口数据，模块不改服务器记录，只在本地把匹配的 item 设为 GONE。

    /** 被用户移除的课程。key = "tab|课程标题"（练过/想练同名课互不影响） */
    static final java.util.LinkedHashSet<String> sHiddenCourses = new java.util.LinkedHashSet<String>();
    static boolean sHiddenLoaded = false;
    static float sDownX, sDownY;
    static long sDownT = 0;
    static Runnable sLongTask = null;
    static int sHiddenScanStep = 0;
    static final long LONG_PRESS_MS = 550;
    static final float LONG_SLOP = 32f;

    static String hiddenKey(String tab, String title) { return tab + "|" + title; }

    boolean isHidden(String tab, String title) {
        return sHiddenCourses.contains(hiddenKey(tab, title));
    }

    void loadHidden() {
        if (sHiddenLoaded) return;
        sHiddenLoaded = true;
        try {
            android.content.SharedPreferences p = dlPrefs();
            if (p == null) return;
            String s = p.getString("hidden_courses", "");
            for (String x : s.split("\n")) {
                x = x.trim();
                if (x.length() == 0) continue;
                // 兼容旧数据：没有 "tab|" 前缀的，当作「练过」里的
                if (x.indexOf('|') < 0) x = hiddenKey(TAB_DONE, x);
                sHiddenCourses.add(x);
            }
            if (!sHiddenCourses.isEmpty()) log("hidden: " + sHiddenCourses.size() + " entries");
        } catch (Throwable t) { log("hidden load fail " + t); }
    }

    void saveHidden() {
        try {
            android.content.SharedPreferences p = dlPrefs();
            if (p == null) return;
            StringBuilder sb = new StringBuilder();
            for (String x : sHiddenCourses) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(x);
            }
            p.edit().putString("hidden_courses", sb.toString()).apply();
            log("hidden saved (" + sHiddenCourses.size() + ")");
        } catch (Throwable t) { log("hidden save fail " + t); }
    }

    /** 课程页的触摸：按住 550ms 不动 = 长按 */
    void handleCourseTouch(MotionEvent e) {
        if (e == null) return;
        int act = e.getActionMasked();
        float x = e.getRawX(), y = e.getRawY();

        // 标题栏长按（0.7 秒）是打开 Keep 助手的隐藏入口，和列表项长按是两回事
        if (act == MotionEvent.ACTION_DOWN) {
            if (sTitlePressing) endTitlePress();
            if (hitCourseTitle(x, y)) {
                startTitlePress(x, y);
                return;
            }
        }
        if (sTitlePressing) {
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL
                    || Math.abs(x - sTitleDownX) > LONG_SLOP
                    || Math.abs(y - sTitleDownY) > LONG_SLOP) {
                endTitlePress();
            }
            return;
        }

        if (act == MotionEvent.ACTION_DOWN) {
            sDownX = x; sDownY = y; sDownT = SystemClock.uptimeMillis();
            if (sLongTask != null) sMain.removeCallbacks(sLongTask);
            final float dx = x, dy = y;
            sLongTask = new Runnable() {
                public void run() {
                    sLongTask = null;
                    onCourseLongPress(dx, dy);
                }
            };
            sMain.postDelayed(sLongTask, LONG_PRESS_MS);
            return;
        }
        if (Math.abs(x - sDownX) > LONG_SLOP || Math.abs(y - sDownY) > LONG_SLOP) {
            if (sLongTask != null) { sMain.removeCallbacks(sLongTask); sLongTask = null; }
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            if (sLongTask != null) { sMain.removeCallbacks(sLongTask); sLongTask = null; }
            // 抬手后可能是切了 tab（练过 / 想练）。等列表刷出来再按当前 tab 重新应用一次，
            // 否则"在练过删的课"会在切到想练后仍被隐藏（旧实现是全局按标题隐藏）。
            final Activity ca = sCur;
            if (ca != null && COURSE.equals(ca.getClass().getName())) {
                sMain.postDelayed(new Runnable() {
                    public void run() {
                        if (sCur != ca) return;
                        applyHidden(ca);
                        if (Cfg.syncCount) syncCountLabel(ca);
                    }
                }, 420);
            }
        }
    }

    // ---------------------------- 标题栏长按 → 打开 Keep 助手
    // 需求：在「我的课程」页面按住顶部标题，标题变红，0.7 秒后打开模块的设置页。
    // 桌面上虽然还有图标，但这个入口是给"不想在桌面留图标"的场景准备的。

    static java.lang.ref.WeakReference<TextView> sCourseTitle = null;
    static int  sTitleOrigColor = 0;
    static Runnable sTitleTask = null;
    static boolean sTitlePressing = false;
    static float sTitleDownX, sTitleDownY;
    static final long TITLE_LONG_MS = 700;
    static final int  TITLE_PRESS_COLOR = 0xFFD2525E;

    /** 找到顶部那个「我的课程」标题并记住它（换 tab / 重进页面会重建，所以要刷新） */
    void cacheCourseTitle(Activity a) {
        try {
            View root = decor(a);
            if (root == null) return;
            List<TextView> tvs = new ArrayList<TextView>();
            collect(root, tvs);
            int screenH = a.getResources().getDisplayMetrics().heightPixels;
            int[] loc = new int[2];
            TextView best = null;
            for (int i = 0; i < tvs.size(); i++) {
                TextView t = tvs.get(i);
                if (!"我的课程".equals(String.valueOf(t.getText()))) continue;
                if (!t.isShown() || t.getHeight() <= 0) continue;
                try { t.getLocationOnScreen(loc); } catch (Throwable ignored) { continue; }
                if (loc[1] < 0 || loc[1] > screenH * 0.18f) continue;
                best = t;   // 取最靠上的那个
            }
            if (best == null) {
                sCourseTitle = null;
                return;
            }
            sCourseTitle = new java.lang.ref.WeakReference<TextView>(best);
            sTitleOrigColor = best.getCurrentTextColor();
            log("title hook: 我的课程 @" + best.getWidth() + "x" + best.getHeight()
                    + " color=" + Integer.toHexString(sTitleOrigColor));
        } catch (Throwable t) { log("cacheCourseTitle fail " + t); }
    }

    boolean hitCourseTitle(float x, float y) {
        Activity a = sCur;
        if (a == null) return false;
        TextView t = sCourseTitle == null ? null : sCourseTitle.get();
        // 缓存失效（页面重建 / 还没渲染完）时现场再找一次
        if (t == null || !t.isShown()) {
            cacheCourseTitle(a);
            t = sCourseTitle == null ? null : sCourseTitle.get();
        }
        if (t == null || !t.isShown()) return false;

        // 「整条标题栏」：x 不做限制（左右留白也能按），y 取标题所在标题栏容器的高度。
        // 往上找 2 层父容器，挑第一个高度合理（< 12% 屏高）的当作标题栏；
        // 找不到就退化成标题自身上下放宽。
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        View bar = null;
        View v = t;
        for (int i = 0; i < 2; i++) {
            android.view.ViewParent p = v.getParent();
            if (!(p instanceof View)) break;
            v = (View) p;
            if (v.getVisibility() == View.VISIBLE && v.getHeight() > 0
                    && v.getHeight() < screenH * 0.12f) {
                bar = v;   // 继续往上找，取仍然合理的最高一层
            }
        }

        int[] loc = new int[2];
        int top, bottom;
        try {
            if (bar != null) {
                bar.getLocationOnScreen(loc);
                top = loc[1];
                bottom = loc[1] + bar.getHeight();
            } else {
                t.getLocationOnScreen(loc);
                int padY = dp(a, 22);
                top = loc[1] - padY;
                bottom = loc[1] + t.getHeight() + padY;
            }
        } catch (Throwable e) { return false; }

        if (top < 0) top = 0;
        int limit = (int) (screenH * 0.18f);   // 不吃到下面列表第一项
        if (bottom > limit) bottom = limit;
        if (bottom <= top) return false;
        return y >= top && y <= bottom;
    }

    void startTitlePress(float x, float y) {
        final TextView t = sCourseTitle == null ? null : sCourseTitle.get();
        if (t == null) return;
        sTitlePressing = true;
        sTitleDownX = x;
        sTitleDownY = y;
        try {
            t.setTextColor(TITLE_PRESS_COLOR);
        } catch (Throwable ignored) {}
        if (sTitleTask != null) sMain.removeCallbacks(sTitleTask);
        sTitleTask = new Runnable() {
            public void run() {
                sTitleTask = null;
                if (!sTitlePressing) return;
                log("title long press -> open KeepAssist");
                openKeepAssist("title-long-press");
                endTitlePress();
            }
        };
        sMain.postDelayed(sTitleTask, TITLE_LONG_MS);
    }

    void endTitlePress() {
        sTitlePressing = false;
        if (sTitleTask != null) { sMain.removeCallbacks(sTitleTask); sTitleTask = null; }
        TextView t = sCourseTitle == null ? null : sCourseTitle.get();
        if (t != null) {
            try { t.setTextColor(sTitleOrigColor); } catch (Throwable ignored) {}
        }
    }

    /** 拉起模块自己的设置页（隐藏入口 → 桌面图标之外的第二个入口） */
    void openKeepAssist(String why) {
        // 直接从 Keep 进程 startActivity 会被 MIUI 的「应用链式启动」拦一次：
        // 「Keep 想要打开 Keep 助手，是否允许？」—— 长按 0.7 秒的体验就断了。
        // 走 root 的 am start 不受这个限制，也不用用户先点过"始终允许"。
        rootCmd("am start -n com.shellz.keepassist/.SettingsActivity", "openKa");
        log("open KeepAssist <- " + why);
        // 兜底：su 不可用（被拒绝）时退回普通 startActivity，最多让用户确认一次
        sMain.postDelayed(new Runnable() {
            public void run() {
                if (!keepInForeground()) return;   // 已经切过去了
                try {
                    Context c = ctxOrNull();
                    if (c == null) return;
                    Intent i = new Intent();
                    i.setClassName("com.shellz.keepassist",
                            "com.shellz.keepassist.SettingsActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    c.startActivity(i);
                    log("open KeepAssist fallback <- startActivity");
                } catch (Throwable t) { log("openKeepAssist fallback fail " + t); }
            }
        }, 1600);
    }

    void onCourseLongPress(final float x, final float y) {
        Activity a = sCur;
        if (a == null || !COURSE.equals(a.getClass().getName())) return;
        if (!Cfg.hideCourse) { log("long press ignored (hide_course=0)"); return; }
        View root = decor(a);
        if (root == null) return;
        View item = findListItem(root, x, y);
        if (item == null) { log("long press: no item at " + (int) x + "," + (int) y); return; }
        final String title = itemTitle(item);
        if (title == null || title.length() < 2) { log("long press: no title"); return; }
        // 「练过」和「想练」里可能有同名课程，移除必须只作用在当前这个 tab
        final String tab = currentTab(root);
        int[] ip = new int[2];
        try { item.getLocationOnScreen(ip); } catch (Throwable ignored) {}
        log("long press @" + (int) x + "," + (int) y + " -> " + title + " tab=" + tab
                + " item@" + ip[0] + "," + ip[1] + " h=" + item.getHeight());
        sMain.post(new Runnable() {
            public void run() { showRemoveDialog(title, tab); }
        });
    }

    // ---------------------------- tab 识别
    // 「练过」顶部计数是「17节课程」，「想练」是「2 节想练的课程」。
    // 用这个文案判断当前在哪个列表，比去猜 tab 的选中态稳得多。
    static final String TAB_DONE = "done";   // 练过
    static final String TAB_WANT = "want";   // 想练
    static final String TAB_OTHER = "other";

    static final java.util.regex.Pattern COUNT_PAT =
            java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*(节.*课程)\\s*$");

    /** 找顶部「N 节课程 / N 节想练的课程」那个计数 TextView */
    TextView findCountLabel(View root) {
        final TextView[] found = {null};
        findCountLabelIn(root, found, 0);
        return found[0];
    }

    void findCountLabelIn(View v, TextView[] out, int depth) {
        if (depth > 30 || out[0] != null) return;
        if (v instanceof TextView) {
            String t = String.valueOf(((TextView) v).getText()).trim();
            if (t.length() < 3 || t.length() > 20) return;
            if (COUNT_PAT.matcher(t).matches()) { out[0] = (TextView) v; return; }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findCountLabelIn(g.getChildAt(i), out, depth + 1);
        }
    }

    String currentTab(View root) {
        TextView t = findCountLabel(root);
        if (t == null) return TAB_OTHER;
        String s = String.valueOf(t.getText());
        return s.contains("想练") ? TAB_WANT : TAB_DONE;
    }

    int dp(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 和设置页一致的紫色圆角弹窗，别用系统默认的直角 AlertDialog */
    void showRemoveDialog(final String title, final String tab) {
        final Activity a = sCur;
        if (a == null || a.isFinishing()) return;
        try {
            final Dialog d = new Dialog(a);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(a, 22);
            box.setPadding(pad, pad, pad, dp(a, 14));

            TextView h = new TextView(a);
            h.setText(TAB_WANT.equals(tab) ? "从「想练」里移除这门课？" : "从「练过」里移除这门课？");
            h.setTextSize(17);
            h.setTextColor(0xFF1A1A1A);
            h.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            box.addView(h);

            TextView name = new TextView(a);
            name.setText(title);
            name.setTextSize(14);
            name.setTextColor(0xFF6B4EFF);
            name.setPadding(0, dp(a, 10), 0, dp(a, 4));
            box.addView(name);

            TextView desc = new TextView(a);
            desc.setText("只在这个列表里隐藏，Keep 里的训练记录不会删除。");
            desc.setTextSize(13);
            desc.setTextColor(0xFF8A8A8A);
            desc.setLineSpacing(dp(a, 2), 1f);
            desc.setPadding(0, dp(a, 2), 0, dp(a, 18));
            box.addView(desc);
            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView cancel = new TextView(a);
            cancel.setText("取消");
            cancel.setTextSize(15);
            cancel.setTextColor(0xFF8A8A8A);
            cancel.setGravity(android.view.Gravity.CENTER);
            cancel.setPadding(0, dp(a, 12), 0, dp(a, 12));
            cancel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { d.dismiss(); }
            });
            btns.addView(cancel, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final TextView remove = new TextView(a);
            remove.setText("移除");
            remove.setTextSize(15);
            remove.setTextColor(0xFFFFFFFF);
            remove.setGravity(android.view.Gravity.CENTER);
            remove.setPadding(0, dp(a, 12), 0, dp(a, 12));
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF6B4EFF);
            bg.setCornerRadius(dp(a, 22));
            remove.setBackground(bg);
            remove.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    loadHidden();
                    sHiddenCourses.add(hiddenKey(tab, title));
                    saveHidden();
                    Activity cur = sCur;
                    if (cur != null) {
                        applyHidden(cur);
                        if (Cfg.syncCount) syncCountLabel(cur);
                    }
                    log("hidden by user: " + tab + "|" + title);
                    d.dismiss();
                }
            });
            btns.addView(remove, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(btns);

            d.setContentView(box);
            Window w = d.getWindow();
            if (w != null) {
                android.graphics.drawable.GradientDrawable winBg =
                        new android.graphics.drawable.GradientDrawable();
                winBg.setColor(0xFFFFFFFF);
                winBg.setCornerRadius(dp(a, 22));
                w.setBackgroundDrawable(winBg);
            }
            d.show();
            if (d.getWindow() != null) {
                int screenW = a.getResources().getDisplayMetrics().widthPixels;
                d.getWindow().setLayout((int) (screenW * 0.86f),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable t) { log("remove dialog fail " + t); }
    }

    View decor(Activity a) {
        try {
            Window w = a.getWindow();
            return w == null ? null : w.getDecorView();
        } catch (Throwable t) { return null; }
    }

    boolean hitRect(View v, float x, float y) {
        try {
            int[] p = new int[2];
            v.getLocationOnScreen(p);
            return x >= p[0] && x <= p[0] + v.getWidth()
                    && y >= p[1] && y <= p[1] + v.getHeight();
        } catch (Throwable t) { return false; }
    }

    /**
     * 找到触摸点下面「某一行课程」的 View。
     *
     * 踩坑记录：Keep 用的是自定义的 PullRecyclerView，它把**所有课程行装在同一个
     * 直接子 View 里**（实测那一个子 View 高 2376px，包含 5 门课）。所以
     * "返回 RecyclerView 的直接子 View"永远只能拿到整个列表，标题也就永远是第一门
     * —— 这就是"长按哪门都删第一门"的真正原因。
     *
     * 正确做法：在命中点下面往下递归，收集所有"可点击 + 含课程标题"的 View，
     * 取高度最小的那个（最贴近单行）。容器高度很大，会被自然排除。
     */
    View findListItem(View v, float x, float y) {
        View[] best = {null};
        int[] bestH = {Integer.MAX_VALUE};
        collectItemViews(v, x, y, best, bestH, 0);
        return best[0];
    }

    void collectItemViews(View v, float x, float y, View[] best, int[] bestH, int depth) {
        if (depth > 40 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!c.isShown() || c.getHeight() <= 0 || !hitRect(c, x, y)) continue;
            if (c.isClickable()) {
                TextView title = findTitleTextView(c);
                if (title != null) {
                    int h = c.getHeight();
                    if (h < bestH[0]) { bestH[0] = h; best[0] = c; }
                }
            }
            collectItemViews(c, x, y, best, bestH, depth + 1);
        }
    }

    /** 优先用 Keep 自己的 textName（课程名），找不到再退回"最靠上的 TextView" */
    TextView findTitleTextView(View item) {
        final TextView[] found = {null};
        findTitleIn(item, found, 0);
        return found[0];
    }

    void findTitleIn(View v, TextView[] out, int depth) {
        if (depth > 20 || out[0] != null) return;
        if (v instanceof TextView) {
            if ("textName".equals(idName(v)) && v.isShown()) { out[0] = (TextView) v; return; }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findTitleIn(g.getChildAt(i), out, depth + 1);
        }
    }

    /** item 里最靠上的文本就是课程标题（下面是「K1 零基础 2 分钟」「1 天前练过」） */
    String itemTitle(View item) {
        TextView tv = findTitleTextView(item);
        if (tv == null) {
            final TextView[] best = {null};
            final int[] bestY = {Integer.MAX_VALUE};
            collectTitle(item, best, bestY);
            tv = best[0];
        }
        if (tv == null) return null;
        String t = String.valueOf(tv.getText()).trim();
        return t.length() == 0 ? null : t;
    }

    void collectTitle(View v, TextView[] best, int[] bestY) {
        if (!v.isShown()) return;
        if (v instanceof TextView) {
            String t = String.valueOf(((TextView) v).getText()).trim();
            if (t.length() >= 2 && best[0] != (TextView) v) {
                try {
                    int[] p = new int[2];
                    v.getLocationOnScreen(p);
                    if (p[1] < bestY[0]) { bestY[0] = p[1]; best[0] = (TextView) v; }
                } catch (Throwable ignored) {}
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTitle(g.getChildAt(i), best, bestY);
        }
    }

    void applyHidden(Activity a) {
        loadHidden();
        View root = decor(a);
        if (root == null) return;
        String tab = currentTab(root);
        applyHiddenIn(root, 0, tab);
    }

    /**
     * 隐藏「当前 tab 里被移除的课」。
     *
     * 关键：RecyclerView 会复用 item View，而 LayoutParams 是跟着 View 走的。
     * 旧实现只把命中的 item 压成 height=0，这个 0 会留在被复用的 View 上 ——
     * 于是"删掉第 3 门"之后，第 3 个 View 被回收去显示别的课，那门课也变 0 高，
     * 看起来就是"删 A 结果 B 消失"、以及删完还显示"5 节课程"。
     * 所以每轮扫描对**没被移除的 item 也要主动恢复**成 WRAP_CONTENT + VISIBLE。
     */
    void applyHiddenIn(View v, int depth, final String tab) {
        if (depth > 40 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        int screenH = g.getResources().getDisplayMetrics().heightPixels;
        int rowMax = (int) (screenH * 0.40f);
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!c.isShown() || c.getHeight() <= 0) continue;
            // 只把"一行课程"当处理对象：可点击 + 有 textName + 高度不像整个列表
            // （PullRecyclerView 的唯一子 View 高 2376px，装了 5 门课，必须排除）
            if (c.isClickable() && c.getHeight() <= rowMax) {
                TextView tv = findTitleTextView(c);
                if (tv != null) {
                    String t = String.valueOf(tv.getText()).trim();
                    boolean hide = t.length() > 0 && isHidden(tab, t);
                    ViewGroup.LayoutParams lp = c.getLayoutParams();
                    if (hide) {
                        if (c.getVisibility() != View.GONE) c.setVisibility(View.GONE);
                        if (lp != null && lp.height != 0) {
                            lp.height = 0;
                            c.setLayoutParams(lp);
                        }
                    } else {
                        // RecyclerView 复用会把 0 高度带回来，必须主动还原
                        if (c.getVisibility() != View.VISIBLE) c.setVisibility(View.VISIBLE);
                        if (lp != null && lp.height == 0) {
                            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                            c.setLayoutParams(lp);
                        }
                    }
                }
            }
            applyHiddenIn(c, depth + 1, tab);
        }
    }

    /**
     * 顶部「N 节课程 / N 节想练的课程」的计数同步。
     * Keep 的列表数据来自接口，我们只在本地隐藏 item，它自己的计数不会变，
     * 所以要按"原始数 - 当前 tab 已移除数"重写这个 TextView。
     * 原始数在每个页面实例第一次看到时记下来，避免反复减。
     */
    static java.lang.ref.WeakReference<Activity> sCountAct = null;
    static int sCountOrig = -1;
    static String sCountSuffix = null;
    static String sCountTab = null;

    void syncCountLabel(Activity a) {
        View root = decor(a);
        if (root == null) return;
        TextView lab = findCountLabel(root);
        if (lab == null) return;
        String tab = currentTab(root);
        String cur = String.valueOf(lab.getText()).trim();
        java.util.regex.Matcher m = COUNT_PAT.matcher(cur);
        if (!m.matches()) return;

        Activity prev = sCountAct == null ? null : sCountAct.get();
        if (prev != a || sCountTab == null || !sCountTab.equals(tab)) {
            // 换页面 / 换 tab：以当前显示的数为基准
            sCountAct = new java.lang.ref.WeakReference<Activity>(a);
            sCountTab = tab;
            sCountOrig = Integer.parseInt(m.group(1));
            sCountSuffix = m.group(2);
        }
        int hidden = 0;
        String prefix = tab + "|";
        for (String k : sHiddenCourses) if (k.startsWith(prefix)) hidden++;
        int shown = sCountOrig - hidden;
        if (shown < 0) shown = 0;
        String want = shown + (sCountSuffix == null ? "节课程" : sCountSuffix);
        if (!want.equals(cur)) {
            lab.setText(want);
            log("count: " + cur + " -> " + want + " (hidden=" + hidden + ", tab=" + tab + ")");
        }
    }

    /** 列表会滚动复用，隐藏过的 item 重新绑定时要再压一次。7 秒内持续扫 */
    void armHiddenScan(final Activity a) {
        loadHidden();
        sHiddenScanStep = 0;
        sMain.post(new Runnable() {
            public void run() {
                Activity cur = sCur;
                if (cur == null || !COURSE.equals(cur.getClass().getName())) return;
                applyHidden(cur);
                if (Cfg.syncCount) syncCountLabel(cur);
                if (++sHiddenScanStep < 12) sMain.postDelayed(this, 600);
            }
        });
    }

    /** 已经挂过触摸监听的类，避免同一页面反复进入时重复 hook */
    static final java.util.HashSet<String> sTouchHooked = new java.util.HashSet<String>();

    /**
     * 在根 View（DecorView）自己的 dispatchTouchEvent 上挂触摸监听。
     *
     * 排查过程（都实测过，别再走回头路）：
     *  - hook Activity.dispatchTouchEvent：一条 touch DOWN 都没有。原因是
     *    DecorView 会把事件交给 Window.Callback(Activity) 或 super(View树)，
     *    Keep 这里走的是后者，Activity 那条分支根本没被调用。
     *  - hook ViewRootImpl.dispatchInputEvent：hookAllMethods 找不到方法。
     *  - 给 DecorView setOnTouchListener：不走 View 的 listener 逻辑。
     *  - 按实例类 hookActivity：类没重写，沿继承链只找到 Activity 本身，同样收不到。
     *
     * DecorView.dispatchTouchEvent 是所有窗口内触摸的必经之路，这里才靠谱。
     * 它是同一个类实例，所以用 decorView 实例比对来区分具体是哪个页面。
     */
    /**
     * PopupWindow 的触摸监听。
     *
     * 详情页上面盖着一层 Keep 自己的 PopupWindow（dumpsys 里能看到
     * `Window #14 ... PopupWindow`，isVisible=true），触摸事件被它先吃掉，
     * 根本到不了 Activity 的 DecorView —— 这就是前面怎么 hook 都收不到的原因。
     * PopupWindow 的根 View 是 PopupDecorView，它才是那个窗口的触摸入口。
     */
    void installPopupTouchHook() {
        if (sTouchHooked.contains("@@popup")) return;
        sTouchHooked.add("@@popup");
        try {
            Class<?> pdv = XposedHelpers.findClass("android.widget.PopupWindow$PopupDecorView", null);
            XposedBridge.hookAllMethods(pdv, "dispatchTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Activity cur = sCur;
                    if (cur == null) return;
                    String cn = cur.getClass().getName();
                    if (!DETAIL.equals(cn) && !TRAINING.equals(cn) && !COURSE.equals(cn)) return;
                    MotionEvent e = (MotionEvent) p.args[0];
                    if (e == null) return;
                    int act = e.getActionMasked();
                    if (COURSE.equals(cn)) handleCourseTouch(e);
                    if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                        onUserTouch(cn);
                    }
                }
            });
            log("touch hook on PopupDecorView");
        } catch (Throwable t) { log("popup touch hook fail " + t); }
    }

    void installTouchSpy(final Activity a) {
        installPopupTouchHook();
        installDecorTouchHook(a);
    }

    void installDecorTouchHook(final Activity a) {
        View decor = null;
        try {
            decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
        } catch (Throwable ignored) {}
        if (decor == null) { log("touch hook: no decorView"); return; }

        final String decorCls = decor.getClass().getName();
        if (sTouchHooked.contains(decorCls)) return;
        sTouchHooked.add(decorCls);

        final XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity cur = sCur;
                if (cur == null) return;
                try {
                    if (p.thisObject != cur.getWindow().getDecorView()) return;
                } catch (Throwable t) { return; }
                MotionEvent e = (MotionEvent) p.args[0];
                if (e == null) return;
                int act = e.getActionMasked();
                String cn = cur.getClass().getName();
                if (COURSE.equals(cn)) handleCourseTouch(e);
                if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                    onUserTouch(cn);
                }
            }
        };

        Class<?> c = decor.getClass();
        while (c != null && c != Object.class) {
            java.util.Set<XC_MethodHook.Unhook> r = XposedBridge.hookAllMethods(c, "dispatchTouchEvent", hook);
            if (!r.isEmpty()) {
                log("touch hook on " + c.getName());
                return;
            }
            c = c.getSuperclass();
        }
        log("touch hook: decor dispatchTouchEvent not found");
    }

    void onDetail(final Activity a) {
        initIds(a);
        // 课程 id 每次都从 Intent 重读：训练中途退回的是同一个详情页实例，
        // onCreate 不会再跑，只靠那一次会让"第二次点 GO"丢失 id、练完不进下载队列。
        String cid = intentCid(a, "detail");
        if (cid != null) {
            sDetailCourseId = cid;
            sDetailCidT = SystemClock.uptimeMillis();
        }

        // —— 只有「新的一次进入」才重置状态 ——
        // 同一个详情页会因为弹层、切后台、系统权限框而再次 onResume。
        // 老代码在这里无条件把 sUserTookOver 置回 false，等于偷偷撤销了"一碰作废"：
        // 你碰过屏幕 → 切一下后台再回来 → 2 秒后照样被推进训练。
        long now = SystemClock.uptimeMillis();
        Activity prev = sDetailAct == null ? null : sDetailAct.get();
        boolean sameVisit = prev == a && now - sDetailEnterT < 120000;
        sDetailAct = new java.lang.ref.WeakReference<Activity>(a);
        if (sameVisit) {
            if (sUserTookOver || sGoTapped) {
                log("detail re-resume (same visit) -> keep takeover/goTapped");
                sLastTouch = now;
                return;
            }
        } else {
            sGoTapped = false;
            sUserTookOver = false;
        }

        sPendingDl = false;
        sDetailEnterT = now;
        sLastTouch = now;
        installTouchSpy(a);
        armAutoWant(a);

        // 刚练完回到详情页（点红色方块结束、或从总结页返回）绝不能再自动开练，
        // 否则就成"练完→回详情页→2 秒→又被推进训练"的死循环。
        // 但"练完"不能无限扩大：只有**同一门课**才在 60 秒内禁止；
        // 换一门课练（练完马上找下一门）8 秒后照常自动开练，不用干等一分钟。
        long sinceEnd = sTrainingEnd == 0 ? Long.MAX_VALUE
                : SystemClock.uptimeMillis() - sTrainingEnd;
        boolean sameAsJustTrained = sTrainingEndCid != null
                && sTrainingEndCid.equals(sDetailCourseId);
        long endGuard = sameAsJustTrained ? 60000L : 8000L;
        if (sinceEnd < endGuard) {
            sUserTookOver = true;
            log("detail: just trained " + (sinceEnd / 1000) + "s ago"
                    + (sameAsJustTrained ? " (same course)" : " (other course)")
                    + " -> auto-GO off");
            maybeAutoDownload(a);
            return;
        }

        // 为了下载而自己打开的详情页：绝不自动开练，下完就走
        if (sAutoDlCid != null && sAutoDlCid.equals(sDetailCourseId)) {
            sUserTookOver = true;
            log("detail: auto-download page -> auto-GO off");
            maybeAutoDownload(a);
            return;
        }

        if (!Cfg.autoGo) {
            sUserTookOver = true;
            log("detail: auto-GO disabled by config");
            maybeAutoDownload(a);
            return;
        }

        log("detail resumed -> auto-GO armed (" + (detailIdleMs() / 1000) + "s)");
        armIdle(detailIdleMs(), false);

        // 这里**不再**提前跑下载机器人。
        // v1.9 的「下载和倒计时并行」其实还是串行的：2 秒到点时面板才刚点开，
        // 自动开练要等任务派发（0.9~1.5 秒）+ 点 GO + 训练页启动（1.5 秒），
        // 实测 4.5~6 秒才真正进训练。用户要的是"2 秒就进去"，下载完全可以晚点做。
        // 现在的顺序是：2 秒 → 进训练（训练时 Keep 自己边播边缓存）
        // → 练完入队 → 回到列表页时由队列泵补下并核对 download.db。
    }

    // ---------------------------- 自动加入「想练」
    /**
     * 详情页底部左侧有一个「想练」图标。用户希望"点进课程就自动进想练列表"，
     * 所以新一次进入详情时看一眼那个按钮：文案正好是「想练」（未加入）才点，
     * 已经是「已想练 / 取消想练」就跳过，免得把用户已经加过的取消掉。
     * 同一门课在一次进程里只尝试一次。
     */
    static String sWantDoneCid = null;

    void armAutoWant(final Activity a) {
        if (!Cfg.autoWant) return;
        final String cid = sDetailCourseId;
        if (cid != null && cid.equals(sWantDoneCid)) return;
        sMain.postDelayed(new Runnable() {
            public void run() {
                if (sCur != a || a.isFinishing()) return;
                View root = decor(a);
                if (root == null) return;
                View btn = findWantButton(root, a, 0);
                if (btn == null) { log("auto-want: button not found"); return; }
                String label = wantLabel(btn);
                if (!"想练".equals(label)) {
                    log("auto-want: already '" + label + "', skip");
                    sWantDoneCid = cid;
                    return;
                }
                boolean ok = clickDeep(btn);
                log("auto-want: click '" + label + "' ok=" + ok);
                if (ok) sWantDoneCid = cid;
            }
        }, 900);
    }

    /** 只在屏幕下半部分找「想练」，避免命中正文里同名的推荐文案 */
    View findWantButton(View v, Activity a, int depth) {
        if (depth > 30 || !(v instanceof ViewGroup)) return null;
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            String label = wantLabel(c);
            if (label != null && c.isShown()) {
                int[] p = new int[2];
                try { c.getLocationOnScreen(p); } catch (Throwable ignored) { p[1] = 0; }
                if (p[1] > screenH * 0.55f) {
                    View clickable = nearestClickable(c);
                    if (clickable != null) return clickable;
                }
            }
            View r = findWantButton(c, a, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    String wantLabel(View v) {
        if (v instanceof TextView) {
            String t = String.valueOf(((TextView) v).getText()).trim();
            if ("想练".equals(t) || "加入想练".equals(t) || "已想练".equals(t)
                    || "取消想练".equals(t)) return t;
        }
        try {
            CharSequence d = v.getContentDescription();
            if (d != null) {
                String s = String.valueOf(d).trim();
                if (s.contains("加入想练") || s.equals("想练")) return "想练";
            }
        } catch (Throwable ignored) {}
        return null;
    }

    View nearestClickable(View v) {
        View cur = v;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return v;
    }

    void onTraining(final Activity a) {
        initIds(a);
        sSleeping = false;
        sLastTouch = SystemClock.uptimeMillis();
        installTouchSpy(a);
        // 训练是唯一真正会出声的环节（教练语音 / 节拍 / 提示音全走 STREAM_MUSIC）。
        // 进训练页这一刻就强制压到目标音量，不等 1.2 秒的轮询。
        if (Cfg.volLock && Cfg.volTarget >= 0 && mediaVol() != Cfg.volTarget) {
            log("training: apply vol target " + Cfg.volTarget);
            applyMediaVol(Cfg.volTarget);
        }
        long ms = trainingIdleMs();
        if (ms <= 0) {
            log("training: auto-sleep disabled by config");
            return;
        }
        log("training resumed -> auto-sleep armed (" + (ms / 1000) + "s)");
        armIdle(ms, true);
    }

    void cancelIdle(String why) {
        if (sIdleTask != null) {
            sMain.removeCallbacks(sIdleTask);
            sIdleTask = null;
            log("idle timer off (" + why + ")");
        }
    }

    /**
     * 挂一个「空闲到期」任务。
     * 到点时先看距上次触摸够不够久：不够就把自己往后顺延剩余时间再检查，
     * 而不是让每次触摸都来重挂任务（滑动时一秒几十次，那样会卡）。
     */
    void armIdle(final long delayMs, final boolean sleepMode) {
        if (sIdleTask != null) sMain.removeCallbacks(sIdleTask);
        sIdleTask = new Runnable() {
            public void run() {
                long dt = SystemClock.uptimeMillis() - sLastTouch;
                if (dt < delayMs - 60) {
                    sMain.postDelayed(this, Math.max(80, delayMs - dt));
                    return;
                }

                Activity a = sCur;
                if (a == null) {
                    // 页面刚好在重建（配置变更、进程刚起来）时 sCur 可能是 null。
                    // 直接 return 会让计时永久丢失，稍后再看一眼。
                    sMain.postDelayed(this, 400);
                    return;
                }
                String cn = a.getClass().getName();

                if (sleepMode) {
                    if (!TRAINING.equals(cn) || sSleeping) return;
                    sSleeping = true;
                    log("TRAINING idle " + (delayMs / 1000) + "s -> screen off");
                    screenOff();
                } else {
                    if (!DETAIL.equals(cn) || sGoTapped || sUserTookOver) return;
                    // 页面上一旦有弹层（右上角「…」的更多选项、下载面板等），
                    // 说明用户在操作别的事情，绝不能在这时候替他点 GO。
                    // 下载机器人自己开的面板除外 —— 那是我们自己的窗口，不算用户操作。
                    if (!sDlBusy && anyDialogShowing()) { log("auto-GO hold (dialog open)"); return; }
                    if (!screenIsOn()) { log("auto-GO hold (screen off)"); return; }
                    // 只有在下载机器人正好在跑（队列泵 / 已经点开的面板）时才让一让，
                    // 它收工后会重新挂计时。正常进详情页这条路不会有 sDlBusy，
                    // 所以 2 秒就是 2 秒，不再被下载拖成 5 秒。
                    if (sDlBusy) { log("auto-GO hold (auto-download running)"); return; }
                    sGoTapped = true;
                    tapGoRetry(a, 0);
                }
            }
        };
        sMain.postDelayed(sIdleTask, delayMs);
    }

    /**
     * 等下载任务派发，最多 2.5 秒。
     *
     * 用户的核心抱怨是「要五六秒才进训练」。老逻辑是等下载机器人整条链路跑完
     * （点「…」→「下载」→「全部下载」→ 轮询数据库 1.2 秒）再重新挂 2 秒计时，
     * 所以 2 + 4 = 6 秒。
     *
     * 现在：只要任务已经派给 Keep 下载器（sDlDispatched），立刻点 GO；
     * 没派发就每 500ms 看一眼，最多等 2.5 秒。下载留在队列里之后补。
     */
    void waitDlThenGo(final Activity a, final int step) {
        sMain.postDelayed(new Runnable() {
            public void run() {
                Activity cur = sCur;
                if (cur == null || !DETAIL.equals(cur.getClass().getName())) return;
                if (sUserTookOver || sGoTapped) { log("dl: GO cancelled during wait"); return; }
                if (!sDlBusy || sDlDispatched || step >= 10) {
                    log("dl: GO now (dispatched=" + sDlDispatched + ", waited=" + (step * 300) + "ms)");
                    // 面板还开着就先关掉：否则 tapGo 会被 anyDialogShowing 拦下
                    if (sDlBusy) {
                        dismissDialogs();
                        dlDone(sDlDispatched ? "dispatched" : "go-timeout");
                    }
                    sGoTapped = true;
                    // 面板刚关时详情页还在恢复布局，立刻 performClick 有时不跳转，
                    // 等 120ms 再点，少一次 800ms 的重试。
                    sMain.postDelayed(new Runnable() {
                        public void run() { tapGoRetry(a, 0); }
                    }, 250);
                    return;
                }
                waitDlThenGo(a, step + 1);
            }
        }, 300);
    }

    /**
     * 点 GO，并确认真的跳走了。
     * 页面可能还没渲染完（骨架屏、图片加载中）导致按钮找不到、坐标也点空，
     * 所以 1.2 秒后如果还停在详情页就重来一次，最多 3 次；
     * 期间用户自己碰了屏幕则放弃，不跟用户抢。
     */
    void tapGoRetry(final Activity a, final int attempt) {
        if (attempt >= 3) { log("auto-GO give up after " + attempt + " tries"); return; }
        boolean ok = tapGo(a);
        log("DETAIL auto-GO try#" + (attempt + 1) + " ok=" + ok);

        sMain.postDelayed(new Runnable() {
            public void run() {
                Activity cur = sCur;
                if (cur == null) return;
                if (!DETAIL.equals(cur.getClass().getName())) {
                    log("auto-GO confirmed (left detail)");
                    return;
                }
                if (SystemClock.uptimeMillis() - sLastTouch < 1400) {
                    log("auto-GO cancelled by touch");
                    return;
                }
                if (anyDialogShowing()) {
                    log("auto-GO aborted (dialog opened)");
                    return;
                }
                tapGoRetry(cur, attempt + 1);
            }
        }, 1500);
    }

    /**
     * 点课程详情页的 GO。
     * 页面里有两个 Detail8GoCellView：一个是真正显示在底部的，另一个是
     * floatingGoCellView 副本（GONE）。只认 isShown 的那个，再取它内部
     * 可点击的 startContainer（实测 clickable=true）。
     */
    boolean tapGo(Activity a) {
        final int idStart = a.getResources().getIdentifier("startContainer", "id", KEEP);
        try {
            View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (root != null) {
                View cell = findView(root, new Match() {
                    public boolean m(View v) {
                        return v.getClass().getName().contains("Detail8GoCellView") && v.isShown();
                    }
                });
                if (cell != null) {
                    View btn = idStart == 0 ? null : findView(cell, new Match() {
                        public boolean m(View v) { return v.getId() == idStart; }
                    });
                    if (btn != null && btn.isShown() && btn.isClickable()) {
                        boolean ok = btn.performClick();
                        log("  tapGo performClick -> " + ok);
                        if (ok) return true;
                    }
                } else {
                    log("  tapGo: GoCell not found");
                }
            }
        } catch (Throwable t) { log("tapGo find " + t); }

        // 兜底：按屏幕比例直接注入点击（GO 圆心实测在宽 50%、高 88.1%）
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        log("  tapGo fallback by coords");
        return dispatchTapAt(a, dm.widthPixels * 0.5f, dm.heightPixels * 0.881f);
    }

    /**
     * 息屏。模块跑在 Keep 进程里（普通 uid），没有 DEVICE_POWER 权限，
     * PowerManager.goToSleep 会被拒，只能借 root 注入 KEYCODE_SLEEP(223)。
     * 必须放子线程：waitFor 会阻塞，主线程卡住就整个 UI 冻了。
     */
    void screenOff() {
        rootCmd("input keyevent 223", "screenOff");
    }

    /** 统一的 root 命令执行。必须放子线程：waitFor 会阻塞，主线程卡住整个 UI 就冻了 */
    void rootCmd(final String cmd, final String tag) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    int rc = p.waitFor();
                    log(tag + " su rc=" + rc);
                } catch (Throwable t) { log(tag + " su fail " + t); }
            }
        }, "KeepAssist-" + tag).start();
    }

    Context ctxOrNull() {
        if (sCtx != null) return sCtx;
        Activity a = sCur;
        return a == null ? null : a.getApplicationContext();
    }

    boolean screenIsOn() {
        try {
            Context c = ctxOrNull();
            if (c == null) return true;
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) { return true; }
    }

    // --------------------------------------------------- 训练结束自动亮屏

    /**
     * 把屏幕点亮。
     *
     * 训练中我们 7 秒无操作会主动息屏，练完如果屏幕还是黑的，用户既看不到总结页，
     * 自动点「完成」也等于在暗处操作。模块没有 DEVICE_POWER 权限，
     * 只能借 root 注入 KEYCODE_WAKEUP(224)——它只会唤醒，不会误关亮着的屏幕。
     * 后面的 dismiss-keyguard 是兜底：个别机型灭屏久了会把锁屏拉起来。
     */
    void wakeScreen(String why) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastWake < 3000) { log("wake skip (just woke) <- " + why); return; }
        if (screenIsOn()) { log("wake: screen already on <- " + why); return; }
        sLastWake = now;
        log("wake screen <- " + why);
        rootCmd("input keyevent KEYCODE_WAKEUP; sleep 0.5; wm dismiss-keyguard", "wake");
    }

    /** 只在屏幕是灭的时候补一次点亮（收尾期反复调用也不会重复发按键） */
    void checkWake(String why) {
        if (screenIsOn()) return;
        wakeScreen(why);
    }

    // --------------------------------------------- 媒体音量锁（保持打开 Keep 时的音量）

    /**
     * 记录 Keep 前台的页面集合。
     * 集合从空变非空 = Keep 刚被打开 → 以此刻的音量为基准；
     * 从非空变空 = Keep 到后台了 → 会话结束，下次进来重新取基准。
     */
    void markResumed(Activity a) {
        if (sMain == null) ensureMain();
        // 页面切换时旧页面的 onPause 可能比新页面的 onResume 早 100ms 左右，
        // 集合会瞬间变空。这里取消掉待判定的"会话结束"，避免基准被无谓重取。
        if (sVolIdleCheck != null) { sMain.removeCallbacks(sVolIdleCheck); sVolIdleCheck = null; }
        boolean first;
        synchronized (sResumed) {
            first = sResumed.isEmpty();
            sResumed.add(a);
        }
        if (first && sVolBaseline < 0) startVolSession();
        // 已经在会话里：如果目标音量跟当前不一样，立刻纠正一次。
        // （训练页 resume 时特别重要 —— 有的训练会在开始那一刻自己把音量顶上去）
        else if (first && Cfg.volTarget >= 0 && mediaVol() != Cfg.volTarget) {
            log("vol fix on resume -> " + Cfg.volTarget);
            applyMediaVol(Cfg.volTarget);
        }
    }

    void markPaused(Activity a) {
        boolean empty;
        synchronized (sResumed) {
            sResumed.remove(a);
            empty = sResumed.isEmpty();
        }
        if (!empty) return;
        if (sMain == null) { stopVolSession(); return; }
        if (sVolIdleCheck != null) sMain.removeCallbacks(sVolIdleCheck);
        sVolIdleCheck = new Runnable() {
            public void run() {
                sVolIdleCheck = null;
                boolean stillEmpty;
                synchronized (sResumed) { stillEmpty = sResumed.isEmpty(); }
                if (!stillEmpty) return;
                // 息屏也会走 onPause，但 Keep 并没有退到后台 —— 训练页 7 秒自动息屏
                // 就是这个路径。老代码在这里结束会话，结果黑屏期间音量锁失效：
                // 训练自己把音量顶上去、或你按到音量键，都会被保留下来。
                // 屏幕是黑的就保持锁，亮屏后 onResume 会继续用同一个目标值。
                if (!screenIsOn()) {
                    log("vol session kept (screen off, not background)");
                    return;
                }
                stopVolSession();
            }
        };
        sMain.postDelayed(sVolIdleCheck, 900);
    }

    int mediaVol() {
        try {
            Context c = ctxOrNull();
            if (c == null) return -1;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            return am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) { return -1; }
    }

    /**
     * 设媒体音量。先走 AudioManager（Keep 自己就有 MODIFY_AUDIO_SETTINGS，实测已授予），
     * 设完再读一次确认真的生效；没生效才退到 root 的 cmd media_session。
     * root 分支做 5 秒节流，避免轮询每 1.2 秒就 spawn 一个 root shell。
     */
    void applyMediaVol(final int v) {
        try {
            Context c = ctxOrNull();
            if (c != null) {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            }
        } catch (Throwable t) { log("vol set api fail " + t); }

        int after = mediaVol();
        if (after == v) { log("vol -> " + v + " (api)"); return; }

        long now = SystemClock.uptimeMillis();
        if (now - sVolLastRoot < 5000) { log("vol api still " + after + ", root throttled"); return; }
        sVolLastRoot = now;
        log("vol api no effect (now=" + after + ") -> root set " + v);
        rootCmd("cmd media_session volume --stream 3 --set " + v, "volSet");
    }

    void startVolSession() {
        if (sMain == null) return;
        if (!Cfg.volLock) {
            // 关掉音量锁时只打一次日志，不要每次页面切换都刷
            if (!sVolOffLogged) { sVolOffLogged = true; log("vol: lock disabled by config"); }
            return;
        }
        stopVolWatch();
        if (Cfg.volTarget >= 0) {
            // 固定目标值：不读当前音量，直接设成配置值（默认 0 = 静音）。
            // 这样无论你进 Keep 前把音量调到多大，进来都被压到目标值。
            sVolBaseline = Cfg.volTarget;
            log("vol session start, target=" + sVolBaseline);
            if (mediaVol() != sVolBaseline) applyMediaVol(sVolBaseline);
        } else {
            sVolBaseline = mediaVol();
            if (sVolBaseline < 0) { log("vol: unavailable, skip"); return; }
            log("vol session start, baseline=" + sVolBaseline);
        }
        sVolTask = new Runnable() {
            public void run() {
                // 设置页把目标音量改了（配置 15 秒内会刷新进来），当前会话跟着改，
                // 不需要退出 Keep 重新进。老代码只在会话开始时取一次值。
                if (!Cfg.volLock) { stopVolSession(); return; }
                // 让配置文件有机会在不重启 Keep 的情况下被读到（内部 15 秒节流）。
                // 用户改完设置停在列表页不动时不会有 onResume，只能靠这里。
                Cfg.refresh();
                int base = sVolBaseline;
                if (base < 0) return;
                if (Cfg.volTarget >= 0 && Cfg.volTarget != base) {
                    log("vol target changed " + base + " -> " + Cfg.volTarget);
                    sVolBaseline = Cfg.volTarget;
                    base = Cfg.volTarget;
                }
                int cur = mediaVol();
                if (cur >= 0 && cur != base) {
                    log("vol drifted " + cur + " -> restore " + base);
                    applyMediaVol(base);
                }
                // 训练是唯一会连续出声的环节，Keep 也可能在某个动作节点自己顶音量。
                // 1.2 秒的窗口足够你听到半句教练语音，所以训练期间改 300ms 轮询
                // （只是读一次 int + 定时器，几乎不耗电）。
                long next = sInTraining ? 300 : VOL_POLL_MS;
                if (sVolBaseline >= 0) sMain.postDelayed(this, next);
            }
        };
        sMain.postDelayed(sVolTask, VOL_POLL_MS);
    }

    void stopVolWatch() {
        if (sVolTask != null && sMain != null) sMain.removeCallbacks(sVolTask);
        sVolTask = null;
    }

    void stopVolSession() {
        if (sVolBaseline >= 0) log("vol session end (baseline=" + sVolBaseline + ")");
        sVolBaseline = -1;
        stopVolWatch();
    }

    // ------------------------------------------------------- 训练收尾链路

    /**
     * 运动总结页。两种来源必须区分：
     *  - 刚练完自动弹出：左上角是文字「完成」→ 自动点掉并接管后续跳转
     *  - 从日程/列表翻历史记录：左上角是返回箭头 → 什么都不做，别打扰用户
     */
    void onSummary(final Activity a) {
        initIds(a);
        // 总结页出现时如果屏幕还是黑的（训练中被我们睡掉了），先点亮
        checkWake("summary-resume");
        probeSummary(a, 0);
    }

    void probeSummary(final Activity a, final int attempt) {
        sMain.postDelayed(new Runnable() {
            public void run() {
                try {
                    if (a.isFinishing() || sCur != a) return;
                    View done = findTopLeftDone(a);
                    // 兜底信号：训练页刚退出 20s 内出现的总结页，一定是"刚练完"，
                    // 就算按钮结构变了没匹配到「完成」也要点掉
                    boolean afterTraining = sTrainingEnd > 0
                            && SystemClock.uptimeMillis() - sTrainingEnd < 20000;
                    if (done != null || afterTraining) {
                        log("SUMMARY(complete) -> " + (done != null ? "text-match" : "after-training")
                                + " @" + el(sBootT) + "ms");
                        startWrapUp();
                        tapDone(a, done);
                        sMain.postDelayed(new Runnable() {
                            public void run() { wrapTick(0); }
                        }, 300);
                        return;
                    }
                    if (attempt < 12) {
                        probeSummary(a, attempt + 1);
                    } else {
                        log("SUMMARY(browse history) -> leave alone");
                    }
                } catch (Throwable t) { log("probeSummary " + t); }
            }
        }, attempt == 0 ? 260 : 320);
    }

    /** 找左上角那个「完成」按钮：必须真可见、在左侧顶部、且可点击，避免误命中隐藏控件或正文 */
    View findTopLeftDone(Activity a) {
        View root = null;
        try {
            Window w = a.getWindow();
            if (w != null) root = w.getDecorView();
        } catch (Throwable ignored) {}
        if (root == null) return null;

        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        List<TextView> tvs = new ArrayList<TextView>();
        collect(root, tvs);
        int[] loc = new int[2];
        for (int i = 0; i < tvs.size(); i++) {
            TextView t = tvs.get(i);
            if (!"完成".equals(String.valueOf(t.getText()))) continue;
            int[] p0 = new int[2];
            try { t.getLocationOnScreen(p0); } catch (Throwable ignored) {}
            log("  done? shown=" + t.isShown() + " vis=" + t.getVisibility()
                    + " " + t.getWidth() + "x" + t.getHeight() + " @" + p0[0] + "," + p0[1]
                    + " click=" + canClick(t));
            if (!t.isShown() || t.getVisibility() != View.VISIBLE) continue;
            if (t.getWidth() <= 0 || t.getHeight() <= 0) continue;
            try { t.getLocationOnScreen(loc); } catch (Throwable e) { continue; }
            if (loc[1] < 0 || loc[1] > screenH * 0.12f) continue;
            if (loc[0] < 0 || loc[0] > screenW * 0.45f) continue;
            log("  完成 candidate x=" + loc[0] + " y=" + loc[1] + " w=" + t.getWidth());
            return t;
        }
        return null;
    }

    /**
     * 点掉左上角的「完成」。
     * 这页的「完成」只是个 TextView，祖先链上没有任何 clickable，
     * 真正接收点击的是和它重叠的兄弟按钮 imgLeftClick —— 必须按 id 取那个。
     */
    boolean tapDone(Activity a, View doneView) {
        try {
            int id = a.getResources().getIdentifier("imgLeftClick", "id", KEEP);
            if (id != 0) {
                View btn = a.findViewById(id);
                if (btn != null && btn.isEnabled()) {
                    boolean ok = btn.performClick();
                    log("tapDone imgLeftClick.performClick -> " + ok);
                    if (ok) return true;
                }
            }
        } catch (Throwable t) { log("tapDone byId " + t); }

        if (doneView != null && canClick(doneView)) {
            boolean ok = clickDeep(doneView);
            log("tapDone clickDeep -> " + ok);
            if (ok) return true;
        }

        if (doneView != null) return dispatchTap(a, doneView);

        // 连按钮都找不到：按布局比例点左上角
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        return dispatchTapAt(a, dm.widthPixels * 0.055f, dm.heightPixels * 0.085f);
    }

    /** 坐标注入：直接用屏幕坐标往 decorView 派发一组按下/抬起 */
    boolean dispatchTap(Activity a, View target) {
        try {
            int[] t = new int[2];
            target.getLocationOnScreen(t);
            return dispatchTapAt(a, t[0] + target.getWidth() / 2f, t[1] + target.getHeight() / 2f);
        } catch (Throwable t) { log("dispatchTap " + t); return false; }
    }

    boolean dispatchTapAt(Activity a, float screenX, float screenY) {
        try {
            View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (root == null) return false;
            int[] r = new int[2];
            root.getLocationOnScreen(r);
            float x = screenX - r[0];
            float y = screenY - r[1];
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 70, MotionEvent.ACTION_UP, x, y, 0);
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(up);
            down.recycle(); up.recycle();
            log("tapDone dispatchTap @" + (int) screenX + "," + (int) screenY);
            return true;
        } catch (Throwable t) { log("dispatchTapAt " + t); return false; }
    }

    void startWrapUp() {
        sWrapUp = true;
        sWrapUpUntil = SystemClock.uptimeMillis() + 25000;
        log("WRAPUP on");
        checkWake("wrapup-start");
    }

    /**
     * 收尾看门狗：每 220ms 校一次当前页面。
     * 目标页之外的一律拉回「我的课程」，直到稳定落在课程页。
     */
    void wrapTick(final int stable) {
        if (!sWrapUp) return;
        if (SystemClock.uptimeMillis() > sWrapUpUntil) {
            sWrapUp = false;
            log("WRAPUP timeout");
            return;
        }
        // 你已经切走了（微信、桌面、别的 App）：立刻收工，绝不把你拉回 Keep。
        // 老代码只看 sCur，而 sCur 是"最后一个 resume 的 Keep 页面"，切后台也不会变，
        // 于是练完 25 秒内切去微信，会被这里每 220ms 一次的 startActivity 顶回前台。
        if (!keepInForeground()) {
            sWrapUp = false;
            log("WRAPUP off (keep left foreground)");
            return;
        }
        final Activity a = sCur;
        if (a == null) {
            sMain.postDelayed(new Runnable() {
                public void run() { wrapTick(stable); }
            }, 220);
            return;
        }
        String cn = a.getClass().getName();
        // 用户在收尾窗口里自己点进了一门课的详情页（想练下一门）：
        // 立刻停掉收尾，绝不能把他弹回列表 —— 那会表现为"课程点不进去"。
        if (DETAIL.equals(cn) && SystemClock.uptimeMillis() - sLastTouch < 5000) {
            sWrapUp = false;
            log("WRAPUP off (user opened a course)");
            return;
        }
        if (COURSE.equals(cn)) {
            if (stable == 0) ensureLianguo(a);
            if (stable >= 2) {
                // 已稳定落在课程页：停掉轮询，但把拦截窗口再延 5 秒，
                // 挡住 Keep 延迟发起的日程页/分享页跳转
                sWrapUpUntil = SystemClock.uptimeMillis() + 8000;
                log("WRAPUP settled -> guard 8s");
                sMain.postDelayed(new Runnable() {
                    public void run() {
                        if (sWrapUp && SystemClock.uptimeMillis() >= sWrapUpUntil) {
                            sWrapUp = false;
                            log("WRAPUP guard off");
                        }
                    }
                }, 8200);
                return;
            }
            sMain.postDelayed(new Runnable() {
                public void run() { wrapTick(stable + 1); }
            }, 260);
            return;
        }
        log("wrapup tick: " + cn + " -> pull back to course");
        wrapJumpBack(a, cn);
        sMain.postDelayed(new Runnable() {
            public void run() { wrapTick(0); }
        }, 240);
    }

    /** 从岔路页面跳回我的课程；CLEAR_TOP 会把压在上面的总结页/日程页一起清掉 */
    void wrapJumpBack(Activity a, String cn) {
        try {
            // 收尾只处理"人还在 Keep 里"的情况。切走就别抢前台。
            if (!keepInForeground()) { log("wrapup: keep in background, skip jump"); return; }
            // 离线时课程页也是空的，收尾同样改去本地已下载课程页
            String target = isOnline() ? COURSE : DOWNLOADS;
            Intent i = new Intent();
            i.setClassName(KEEP, target);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
            a.overridePendingTransition(0, 0);
            log("wrapup: " + cn + " -> " + (isOnline() ? "course" : "downloads(offline)"));
        } catch (Throwable t) { log("wrapJumpBack " + t); }
    }

    /** 当前是否有可用网络（只看有没有网，不管能不能连上服务器） */
    boolean isOnline() {
        try {
            Context c = ctxOrNull();
            if (c == null) return true;
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) return false;
            if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
            // 还要 VALIDATED：连着酒店/商场那种"连上了但没登录"的 Wi-Fi 时，
            // INTERNET 仍然是 true，Keep 却根本连不上服务器。老逻辑会把用户送到
            // 一个空的「练过」列表，下载也必然失败。这种情况按离线处理才对。
            return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) { return true; }
    }

    /**
     * 网络是否被系统「验证过」。
     * 连上酒店/机场那种需要登录的门户 Wi-Fi 时，INTERNET 依然是 true，
     * 但 VALIDATED 是 false —— 这时候去下载必然失败，不如再等等。
     */
    boolean networkValidated() {
        try {
            Context c = ctxOrNull();
            if (c == null) return true;
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) { return true; }
    }

    boolean isWifi() {
        try {
            Context c = ctxOrNull();
            if (c == null) return true;
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) { return false; }
    }

    /** 自动下载的网络门槛：在线 + 系统验证过 + （配置为仅 Wi-Fi 时）确实在 Wi-Fi 上 */
    boolean canDownloadNow() {
        if (!isOnline()) return false;
        if (!networkValidated()) return false;
        if (Cfg.wifiOnly && !isWifi()) return false;
        return true;
    }

    /**
     * Keep 是否真的在前台。
     * 只有「有页面处于 resumed 状态」+「栈顶还是 Keep 的页面」才算。
     * 用来拦住"练完 6 秒后把用户从别的 App 拉回来下载"。
     */
    boolean keepInForeground() {
        try {
            synchronized (sResumed) {
                if (sResumed.isEmpty()) return false;
            }
            Activity a = sCur;
            if (a == null || a.isFinishing()) return false;
            return KEEP.equals(a.getPackageName());
        } catch (Throwable t) { return false; }
    }

    void jumpTo(Activity a, String cls, String why) {
        try {
            Intent i = new Intent();
            i.setClassName(KEEP, cls);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
            a.overridePendingTransition(0, 0);
            log("jump(" + why + ") -> " + cls.substring(cls.lastIndexOf('.') + 1));
        } catch (Throwable t) { log("jumpTo fail " + t); }
    }

    /** 确保停在「练过」这个 tab 上 */
    void ensureLianguo(final Activity a) {
        View root = null;
        try {
            Window w = a.getWindow();
            if (w != null) root = w.getDecorView();
        } catch (Throwable ignored) {}
        if (root == null) return;
        View tab = findView(root, new Match() {
            public boolean m(View v) {
                return v instanceof TextView
                        && "练过".equals(String.valueOf(((TextView) v).getText()))
                        && v.isShown();
            }
        });
        if (tab != null && !tab.isSelected()) {
            clickDeep(tab);
            log("ensure 练过");
        }
    }

    /** 训练收尾期间，日程/分享/主页这类岔路一律拦截，不让它起来 */
    static boolean isWrapUpBlocked(String cn) {
        return MAIN.equals(cn) || cn.contains("Calendar") || cn.contains("Share")
                || cn.contains("share") || cn.contains("Poster") || cn.contains("Social")
                || cn.contains(".km.business.aicanlendar.");
    }


    void scanActivityAd(final Activity a, final String cn) {
        sMain.postDelayed(new Runnable() {
            public void run() {
                try {
                    if (a.isFinishing() || sCur != a) return;
                    View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
                    if (root == null) return;
                    List<TextView> tvs = new ArrayList<TextView>();
                    collect(root, tvs);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < tvs.size(); i++) {
                        String s = String.valueOf(tvs.get(i).getText());
                        if (s.length() > 0) sb.append(s).append(" | ");
                    }
                    String all = sb.toString();
                    if (all.length() == 0) return;

                    boolean hasAd = all.contains("广告") || all.contains("赞助") || all.contains("后可关");
                    boolean hasClose = all.contains("关闭") || all.contains("跳过");
                    if (hasAd && (hasClose || COUNTDOWN.matcher(all).find())) {
                        log("AD ACTIVITY: " + cn + " => "
                                + (all.length() > 220 ? all.substring(0, 220) : all));
                        View c = findByTextContains(root, "后可关");
                        if (c == null) c = findByCountdown(root);
                        if (c == null) c = findCloseLike(root);
                        if (c != null && canClick(c)) clickDeep(c);
                        else a.finish();
                        sMain.postDelayed(new Runnable() {
                            public void run() { navToCourse(); }
                        }, 200);
                    }
                } catch (Throwable t) { log("scanActivityAd " + t); }
            }
        }, 600);
    }

    void navToCourse() {
        final Activity a = sCur;
        if (a == null) return;
        sMain.postDelayed(new Runnable() {
            public void run() {
                if (COURSE.equals(a.getClass().getName())) {
                    onCourse(a);
                } else {
                    try {
                        Intent i = new Intent();
                        i.setClassName(KEEP, COURSE);
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        a.startActivity(i);
                        log("relaunch course after ad");
                    } catch (Throwable t) { log("navToCourse " + t); }
                }
            }
        }, 260);
    }

    // ------------------------------------------------------------- 弹窗处理

    void hookDialog() {
        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    final Dialog d = (Dialog) p.thisObject;
                    // 下载机器人要靠 Keep 自己的面板派发任务，但用户不该看见这一套。
                    // 这里无条件先压成透明：此时 Dialog 的内容树还没量过，
                    // 靠 findViewById 判断不一定来得及 —— 宁可先隐形。
                    // 紧接着的 after 会判断它到底是不是下载面板，不是就恢复可见，
                    // 所以权限框/错误提示最多闪一帧，不会变成"看不见又点不动"的窗口。
                    if (sDlBusy) hidePanel(d);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    final Dialog d = (Dialog) p.thisObject;
                    ensureMain();
                    if (sDlBusy && isDownloadPanel(d)) hidePanel(d);   // 确实是下载面板：再压一次保险
                    else showPanel(d);   // 不是下载面板（或机器人已收工）：把误隐藏的恢复可见
                    noteDialog(d, true);
                    sMain.postDelayed(new Runnable() {
                        public void run() { handleDialog(d, "dialog"); }
                    }, 80);
                    sMain.postDelayed(new Runnable() {
                        public void run() { handleDialog(d, "dialog+"); }
                    }, 700);
                }
            });
            XposedHelpers.findAndHookMethod(Dialog.class, "dismiss", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    noteDialog((Dialog) p.thisObject, false);
                }
            });
        } catch (Throwable t) { log("hookDialog " + t); }
        log("hookDialog installed");
    }

    /** 弹层关闭后如果还停在详情页、而且没在等别的弹层，就重新挂 2 秒计时 */
    void noteDialog(Dialog d, boolean shown) {
        boolean anyOpen;
        synchronized (sOpenDialogs) {
            if (shown) { sOpenDialogs.add(d); sLastDialog = d; } else { sOpenDialogs.remove(d); }
            anyOpen = !sOpenDialogs.isEmpty();
        }
        if (!shown) sDlgClosedT = SystemClock.uptimeMillis();
        Activity a = sCur;
        String cn = a == null ? "" : a.getClass().getName();
        if (shown) {
            if (DETAIL.equals(cn)) {
                // 下载机器人自己开的面板（「…」和下载设置）不算"用户在操作"，
                // 否则每次下载都会把 2 秒计时取消掉，自动开练永远等不到。
                if (sDlBusy) return;
                log("dialog opened on detail -> auto-GO cancelled");
                cancelIdle("dialog opened");
            }
            return;
        }
        // 冷启动途中被 Keep 自己的弹层打断（点完弹层会退回主页），补一次跳转。
        if (!anyOpen) {
            final Activity ca = a;
            if (ca != null) {
                sMain.postDelayed(new Runnable() {
                    public void run() { reanchorIfKicked(ca, "dialog-closed"); }
                }, 350);
            }
        }
        if (anyOpen || sGoTapped || sUserTookOver || sDlBusy || !DETAIL.equals(cn)) return;
        if (!Cfg.autoGo) return;
        log("dialog closed -> auto-GO re-armed (" + (detailIdleMs() / 1000) + "s)");
        sLastTouch = SystemClock.uptimeMillis();
        armIdle(detailIdleMs(), false);
    }

    /**
     * 冷启动后本应停在 sColdNavTarget，但如果被 Keep 的弹层挤回 MainActivity，
     * 这里补一次跳转。只在"当前是 MainActivity"时动手 —— 页面上其它任何地方
     * 都说明用户自己在走，绝不打扰。
     */
    void reanchorIfKicked(Activity a, String why) {
        String target = sColdNavTarget;
        if (target == null) return;
        // 只在冷启动后的短窗口内管这件事。用户后来自己回主页、自己开关弹层，
        // 属于正常使用，不能被强行拽走。
        if (SystemClock.uptimeMillis() - sColdNavT > 20000) { sColdNavTarget = null; return; }
        Activity cur = sCur != null ? sCur : a;
        if (cur == null) return;
        String cn = cur.getClass().getName();
        if (target.equals(cn)) return;
        if (!MAIN.equals(cn)) return;
        log(why + ": kicked back to main -> reanchor "
                + (isOnline() ? "course" : "downloads"));
        jumpTo(cur, target, "reanchor");
    }

    boolean anyDialogShowing() {
        synchronized (sOpenDialogs) {
            java.util.Iterator<Dialog> it = sOpenDialogs.iterator();
            while (it.hasNext()) {
                Dialog d = it.next();
                boolean on = false;
                try { on = d.isShowing(); } catch (Throwable ignored) {}
                if (!on) it.remove();   // dismiss 没被回调（页面直接 finish）时兜底清理
            }
            return !sOpenDialogs.isEmpty();
        }
    }

    /**
     * 把一个 View 树按「可点控件」dump 到日志：class / id / text / 屏幕坐标。
     * 弹层里的按钮既没有稳定文案也不一定 clickable，只能靠 dump 出来的结构定位。
     */
    void dumpViewTree(View v, String tag, int depth) {
        if (v == null || depth > 24) return;
        try {
            int[] p = new int[2];
            v.getLocationOnScreen(p);
            String id = "";
            try { id = v.getId() == View.NO_ID ? "" : idName(v); } catch (Throwable ignored) {}
            String txt = "";
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) txt = cs.toString().replace('\n', ' ');
                if (txt.length() > 30) txt = txt.substring(0, 30) + "..";
            }
            boolean click = v.isClickable();
            if (click || id.length() > 0 || txt.length() > 0) {
                log(tag + " " + depth + " " + v.getClass().getSimpleName()
                        + (id.isEmpty() ? "" : " #" + id)
                        + (txt.isEmpty() ? "" : " \"" + txt + "\"")
                        + (click ? " [click]" : "")
                        + " [" + p[0] + "," + p[1] + "][" + (p[0] + v.getWidth()) + "," + (p[1] + v.getHeight()) + "]"
                        + (v.isShown() ? "" : " hidden"));
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), tag, depth + 1);
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------- 练过的课程自动下载（为离线可用）

    File dlStateFile() {
        File dir = sCtx != null ? sCtx.getFilesDir() : new File("/data/data/" + KEEP + "/files");
        return new File(dir, "keepassist.dl");
    }

    /**
     * 待下载队列存在 SharedPreferences 里。
     * 不能用 files 下的普通文件：root 建的文件属主是 root，
     * 应用进程（uid 10467）写会直接 EACCES。
     */
    android.content.SharedPreferences dlPrefs() {
        Context c = ctxOrNull();
        return c == null ? null : c.getSharedPreferences("keepassist", Context.MODE_PRIVATE);
    }

    void loadNeedDownload() {
        if (sNeedLoaded) return;
        sNeedLoaded = true;
        try {
            android.content.SharedPreferences p = dlPrefs();
            if (p == null) return;
            String v = p.getString("need_download", "");
            for (String x : v.split(",")) {
                x = x.trim();
                if (x.length() > 0) sNeedDownload.add(x);
            }
            if (!sNeedDownload.isEmpty()) log("dl: loaded queue " + sNeedDownload);
        } catch (Throwable t) { log("dl load fail " + t); }
    }

    void saveNeedDownload() {
        try {
            android.content.SharedPreferences p = dlPrefs();
            if (p == null) return;
            StringBuilder sb = new StringBuilder();
            for (String s : sNeedDownload) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
            p.edit().putString("need_download", sb.toString()).apply();
        } catch (Throwable t) { log("dl save fail " + t); }
    }

    /** 训练结束：把这门课记进「待下载」，并安排立刻下好 */
    void markTrainedCourseForDownload() {
        String cid = sTrainingCourseId;
        sTrainingCourseId = null;
        if (cid == null) { log("dl: training course id unknown, skip"); return; }
        loadNeedDownload();
        if (!sNeedDownload.add(cid)) {
            log("dl: already queued " + cid);
        } else {
            saveNeedDownload();
            log("dl: queued " + cid);
        }
        // 统一走队列泵：有网就补，没网就等回到列表页时补。
        // 15 秒而不是 7 秒：补下必须打开详情页，太早会"页面自己跳走"。
        // 用户明确讨厌这个观感，所以把动作推到他已经停下来之后。
        sPumpArmT = 0;
        armPump(15000);
    }

    /**
     * 旧的「练完 6 秒后自动打开详情页」入口已合并进队列泵（armPump）：
     * 现在统一由 pumpStep 在「Keep 在前台 + 停在列表页 + 屏幕亮 + 网络允许 + 手停 4 秒」时打开。
     * 少一条独立路径就少一处会抢前台的代码（v1.8 之前它会把你从微信拉回 Keep）。
     */

    /** 下完（或失败）后把"为了下载而打开的详情页"关掉，退回课程页 */
    void closeAutoDetail(String why) {
        sAutoDlCid = null;
        boolean wasOpen = sAutoDlOpened;
        sAutoDlOpened = false;
        Activity a = sCur;
        if (wasOpen && a != null) {
            String cn = a.getClass().getName();
            // 不一定停在详情页：cid 失效/课程变成付费课时，详情页会直接跳到
            // 购买页（CompleteSaleActivity）之类的页面，同样要关掉退回列表，
            // 否则用户会被留在一个他根本没打算打开的页面里。
            if (!COURSE.equals(cn) && !MAIN.equals(cn) && !DOWNLOADS.equals(cn)) {
                log("dl: closing auto-opened page (" + why + ") " + cn);
                try {
                    a.finish();
                    a.overridePendingTransition(0, 0);
                } catch (Throwable t) { log("closeAutoDetail fail " + t); }
            }
        }
        // 队列泵：还有没下完的课就接着下一门
        if (sPumping) pumpNext();
    }

    // ================= 「待下载队列泵」 =================
    // 断网时练完的课没法缓存（Keep 是训练时边播边存），只能排队等联网。
    // 等用户停在课程列表、屏幕亮着、网回来了，就一门一门自动补下，下完退回列表继续下一门。
    void armPump(long delay) {
        if (sPumping) return;
        if (!Cfg.autoDl) return;
        loadNeedDownload();
        if (sNeedDownload.isEmpty()) return;
        // 提前把下载库缓存读进来，免得泵真的开始时还没准备好
        loadDbCidsAsync();
        long now = SystemClock.uptimeMillis();
        if (now - sPumpArmT < 8000) return;   // 页面切换会连续 resume，别重复挂
        sPumpArmT = now;
        log("pump: armed in " + delay + "ms, queue=" + sNeedDownload.size());
        sMain.postDelayed(new Runnable() { public void run() { pumpStep(); } }, delay);
    }

    void pumpNext() {
        sPumping = false;
        sMain.postDelayed(new Runnable() { public void run() { pumpStep(); } }, 1600);
    }

    void bumpPumpTry(String cid) {
        Integer n = sPumpTries.get(cid);
        int v = (n == null ? 0 : n) + 1;
        sPumpTries.put(cid, v);
        log("pump: " + cid + " failed x" + v);
    }

    void pumpStep() {
        if (sPumping) return;
        loadNeedDownload();
        if (sNeedDownload.isEmpty()) { log("pump: queue empty, done"); return; }
        // 先确保「下载库里已有哪些课」这份缓存加载完，否则会把已经下过的课
        // 当成没下过、去打开一次详情页（用户能看到列表「跳一下」）。
        loadDbCidsAsync();
        if (sDbCids.isEmpty() && SystemClock.uptimeMillis() - sDbLoadT < 3000) {
            log("pump: waiting download.db cache");
            sMain.postDelayed(new Runnable() { public void run() { pumpStep(); } }, 900);
            return;
        }
        if (!isOnline()) { log("pump: offline, keep in queue"); return; }
        if (!canDownloadNow()) {
            log("pump: cellular or unvalidated, keep in queue (wifi_only=" + (Cfg.wifiOnly ? 1 : 0) + ")");
            return;
        }
        if (!screenIsOn()) {
            if (sPumpProbe < 20) {
                sPumpProbe++;
                log("pump: screen off, retry " + sPumpProbe);
                sMain.postDelayed(new Runnable() { public void run() { pumpStep(); } }, 4000);
            }
            return;
        }
        if (sInTraining || sWrapUp) {
            if (sPumpProbe < 12) {
                sPumpProbe++;
                log("pump: busy (" + (sInTraining ? "training" : "wrapup") + "), retry " + sPumpProbe);
                sMain.postDelayed(new Runnable() { public void run() { pumpStep(); } }, 3000);
            } else {
                sPumpProbe = 0;
                log("pump: busy too long, stays in queue");
            }
            return;
        }
        // 后台不抢：sCur 只是"最后一个 resume 的 Keep 页面"，Keep 退到后台它也不会变。
        // 不加这一条，队列泵会在你刷微信的时候把 Keep 顶到前台来下载。
        if (!keepInForeground()) {
            log("pump: keep in background, stay in queue");
            return;
        }
        Activity a = sCur;
        if (a == null) return;
        String cn = a.getClass().getName();
        if (DETAIL.equals(cn)) { log("pump: already on detail, skip"); return; }
        if (!COURSE.equals(cn) && !MAIN.equals(cn) && !DOWNLOADS.equals(cn)) {
            if (sPumpProbe < 5) {
                sPumpProbe++;
                log("pump: not on a list page (" + cn + "), retry " + sPumpProbe);
                sMain.postDelayed(new Runnable() { public void run() { pumpStep(); } }, 3000);
            } else {
                log("pump: gave up looking for a list page");
                sPumpProbe = 0;
            }
            return;
        }
        sPumpProbe = 0;
        // 用户刚动过屏幕就别抢页面。补下要打开详情页，是肉眼可见的跳转，
        // 所以要求连续 8 秒没触摸才动手（v1.9 是 4 秒，仍然会在你刚放下手时跳走）。
        if (SystemClock.uptimeMillis() - sLastTouch < 8000) {
            log("pump: user active, retry in 3s");
            sMain.postDelayed(new Runnable() { public void run() { pumpStep(); } }, 3000);
            return;
        }
        String cid = null;
        java.util.ArrayList<String> already = new java.util.ArrayList<String>();
        for (String s : sNeedDownload) {
            Integer n = sPumpTries.get(s);
            if (n != null && n >= PUMP_MAX_TRIES) continue;
            // 本地已经完整下过：不必打开详情页走一遍面板（那会肉眼可见地跳一次页）
            if (courseDownloadedOnDisk(s)) { already.add(s); continue; }
            cid = s;
            break;
        }
        for (String s : already) { dequeueCid(s); markChecked(s); }
        if (cid == null) {
            if (!already.isEmpty()) { sMain.postDelayed(new Runnable() { public void run() { pumpStep(); } }, 300); return; }
            log("pump: all remaining tried out, stop"); return;
        }
        sPumping = true;
        sAutoDlCid = cid;
        try {
            Intent i = new Intent();
            i.setClassName(KEEP, DETAIL);
            i.putExtra("collectionId", cid);
            a.startActivity(i);
            sAutoDlOpened = true;
            log("pump: open " + cid + " (" + sNeedDownload.size() + " left)");
            sMain.postDelayed(new Runnable() {
                public void run() { closeAutoDetail("pump-watchdog"); }
            }, 40000);
        } catch (Throwable t) {
            log("pump: open fail " + t);
            sPumping = false;
            sAutoDlCid = null;
            bumpPumpTry(cid);
            pumpNext();
        }
    }

    void loadChecked() {
        if (sCheckedLoaded) return;
        sCheckedLoaded = true;
        try {
            android.content.SharedPreferences p = dlPrefs();
            if (p == null) return;
            String s = p.getString("dl_checked", "");
            if (s.length() == 0) return;
            long now = System.currentTimeMillis();
            for (String x : s.split(",")) {
                x = x.trim();
                if (x.length() == 0) continue;
                int at = x.lastIndexOf('@');
                if (at > 0) {
                    String cid = x.substring(0, at);
                    long t = now;
                    try { t = Long.parseLong(x.substring(at + 1)); } catch (Throwable ignored) {}
                    sDlChecked.put(cid, t);
                } else {
                    // 旧格式（只有 id，v1.7 及以前）：不知道是什么时候确认的，按"已过期"处理。
                    // 目的：升级后第一次进这门课会进一次下载面板，把 v1.7 漏掉的
                    // 「练过却不在下载课程页」的历史课补上。之后就会写成带时间戳的新格式。
                    sDlChecked.put(x, 0L);
                }
            }
            log("dl: loaded " + sDlChecked.size() + " checked");
        } catch (Throwable t) { log("checked load fail " + t); }
    }

    void saveChecked() {
        try {
            android.content.SharedPreferences p = dlPrefs();
            if (p == null) return;
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Long> e : sDlChecked.entrySet()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(e.getKey()).append('@').append(e.getValue());
            }
            p.edit().putString("dl_checked", sb.toString()).apply();
        } catch (Throwable t) { log("checked save fail " + t); }
    }

    /** 最近确认过、且没过复检周期 → 不用再折腾（否则每次进详情页都多等两秒） */
    boolean checkedFresh(String cid) {
        Long t = sDlChecked.get(cid);
        if (t == null) return false;
        if (Cfg.checkDays <= 0) return true;
        long age = System.currentTimeMillis() - t;
        if (age < Cfg.checkDays * 86400000L) return true;
        log("dl: check expired for " + cid + " (" + (age / 86400000L) + "d)");
        return false;
    }

    void markChecked(String cid) {
        if (cid == null) return;
        loadChecked();
        sDlChecked.put(cid, System.currentTimeMillis());
        saveChecked();
    }

    /** 课程本地目录够大就认为"已经下过"（1.86M 的课实测 81KB 只是训练缓存） */
    static final long DL_ENOUGH_BYTES = 512 * 1024;

    /**
     * Keep 下载库里的课程 id 前缀集合。
     * download_data.workoutId 和 collectionId 不是同一个值（实测末几位不同，
     * 例如 course_639072a419f55b0001067441 对应 workoutId 639072a419f55b0001067440），
     * 所以只比对前 20 位。用内存集合是因为要在主线程判断，不能每次都开库。
     */
    static final java.util.HashSet<String> sDbCids = new java.util.HashSet<String>();
    static volatile boolean sDbLoaded = false;
    static long sDbLoadT = 0;

    static String cidKey(String cid) {
        return cid == null || cid.length() < 20 ? null : cid.substring(0, 20);
    }

    /** 后台刷新「下载库里有哪些课」，60 秒一次；主线程只读内存集合 */
    void loadDbCidsAsync() {
        long now = SystemClock.uptimeMillis();
        if (sDbLoaded && now - sDbLoadT < 60000) return;
        sDbLoaded = true;
        sDbLoadT = now;
        new Thread(new Runnable() {
            public void run() {
                java.util.HashSet<String> tmp = new java.util.HashSet<String>();
                try {
                    java.io.File f = new java.io.File(
                            "/data/data/com.gotokeep.keep/databases/download.db");
                    if (f.exists()) {
                        android.database.sqlite.SQLiteDatabase db =
                                android.database.sqlite.SQLiteDatabase.openDatabase(
                                        f.getAbsolutePath(), null,
                                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                        android.database.Cursor c = db.rawQuery(
                                "select workoutId from download_data", null);
                        while (c.moveToNext()) {
                            String w = c.getString(0);
                            String k = cidKey(w);
                            if (k != null) tmp.add(k);
                        }
                        c.close();
                        db.close();
                    }
                } catch (Throwable t) { log("dldb list fail " + t); }
                final java.util.HashSet<String> got = tmp;
                sMain.post(new Runnable() {
                    public void run() {
                        sDbCids.clear();
                        sDbCids.addAll(got);
                        log("dl: download.db has " + sDbCids.size() + " courses");
                    }
                });
            }
        }, "KeepAssist-db-cids").start();
    }

    boolean courseDownloadedOnDisk(String cid) {
        if (cid == null) return false;
        loadDbCidsAsync();
        String k = cidKey(cid);
        if (k != null && sDbCids.contains(k)) {
            // 以 Keep 自己的下载库为准。注意不要再去校验 course_<cid> 目录：
            // Keep 的视频主要落在 Movies/MultiVideos，course_ 目录只是索引缓存，
            // 拿它判断"文件还在不在"会误判成没下载，反而每次重复下一遍。
            log("dl: already in download.db -> " + cid);
            return true;
        }
        long n = courseSize(cid);
        if (n <= DL_ENOUGH_BYTES) return false;
        log("dl: on-disk already " + n + " bytes for " + cid);
        return true;
    }

    /** 详情页 resume 时调用：练过但没下载的课程，自动走一遍「… → 下载 → 全部下载」 */
    void maybeAutoDownload(final Activity a) {
        if (!Cfg.autoDl) { log("dl: disabled by config"); return; }
        loadNeedDownload();
        loadChecked();
        final String cid = sDetailCourseId;
        if (cid == null) return;
        // 本地已经有一份完整课程：一个 UI 都不点，直接记成已确认。
        // 用户明确说过"已经下载过的课不要再走一遍右上角三个点"。
        if (courseDownloadedOnDisk(cid)) {
            dequeueCid(cid);
            markChecked(cid);
            if (sAutoDlCid != null) {
                sMain.postDelayed(new Runnable() {
                    public void run() { closeAutoDetail("already-on-disk"); }
                }, 60);
            }
            return;
        }
        boolean queued = sNeedDownload.contains(cid);
        // 最近确认过（且没过复检周期）的课就不再折腾，否则每次进详情页都要多等两秒
        if (!queued && checkedFresh(cid)) return;
        if (!isOnline()) {
            if (queued) log("dl: offline, postpone " + cid);
            return;
        }
        // 只吃 Wi-Fi：进详情页 2 秒不动就会下**整门课**，大课几百兆，别悄悄走蜂窝
        if (!canDownloadNow()) {
            log("dl: cellular, postpone (wifi_only=1) " + cid);
            return;
        }
        if (!queued) {
            // 第一次进这门课的详情页：先确保它下好，再自动开练 —— 这就是
            // 「点 GO 的同时一边训练一边下载」。任务派发后 Keep 自己在后台跑，
            // 训练开始了也不影响。
            sNeedDownload.add(cid);
            saveNeedDownload();
            log("dl: first visit, queued " + cid);
        }
        // 详情页会 onResume 两次（新旧实例各一次），没有这个守卫就会点两遍
        if (sDlBusy && cid.equals(sDlRunningCid)) {
            log("dl: already running for " + cid + ", skip");
            return;
        }
        log("dl: start for " + cid);
        sDlRunningCid = cid;
        sSawDownloaded = false;
        sDlDispatched = false;
        // 息屏状态下页面不渲染，点「…」必然落空 —— 先把屏幕点亮
        if (!screenIsOn()) wakeScreen("auto-download");
        sDlBusy = true;
        // 看门狗：任何一步卡住都不许把自动 GO 永久冻住
        sMain.postDelayed(new Runnable() {
            public void run() { if (sDlBusy) dlDone("watchdog"); }
        }, 25000);
        sMain.postDelayed(new Runnable() {
            public void run() { dlStep1(a); }
        }, 900);
    }

    /** 下载机器人收工：解除「不许点 GO」的锁，并把详情页的 2 秒计时重新挂上 */
    void dlDone(String why) {
        if (!sDlBusy) return;
        String ranCid = sDlRunningCid;
        sDlBusy = false;
        sDlRunningCid = null;
        log("dl: robot done (" + why + ")");
        boolean ok = "already".equals(why) || "dispatched".equals(why)
                // 用户提前点了 GO（我们不再等下载跑完），只要任务已经派出去，
                // 训练不影响 Keep 下载器继续干活 —— 这种情况算成功。
                || ("training-started".equals(why) && sDlDispatched)
                // 面板显示「已下载」、点进去也没有可下载项：确实已经下好了，
                // 只是 db 记录补不上（Keep 自己认为不需要写）。别每次进详情页都重试。
                || (sSawDownloaded && ("no-sheet".equals(why) || "no-btn".equals(why)
                    || "no-items".equals(why) || "no-dispatch".equals(why)));
        // 只有确认成功（真的开始了 / 本来就在）才出队并记入「已确认」
        if (ok) {
            String c = ranCid != null ? ranCid : sDetailCourseId;
            dequeueCid(c);
            markChecked(c);
            // 「任务派出去了」不等于「下载完了」：过一会儿再看一眼体积。
            // 已下载的课本来就不会再增长，跳过校验，否则会被误判"没下成"重新排队。
            if ("dispatched".equals(why) && !sSawDownloaded) armVerify(c);
        }
        // 是我们自己打开详情页去下载的场景（练完立刻下 / 队列泵）→ 关页退回列表
        final String autoCid = sAutoDlCid;
        if (autoCid != null) {
            if (!ok) bumpPumpTry(autoCid);
            final String reason = ok ? "done" : "fail";
            sMain.postDelayed(new Runnable() {
                public void run() { closeAutoDetail(reason); }
            }, 1500);
            return;
        }
        Activity a = sCur;
        if (a == null || !DETAIL.equals(a.getClass().getName())) return;
        if (!Cfg.autoGo) { log("auto-GO stays off (config)"); return; }
        if (sGoTapped || sUserTookOver || anyDialogShowing()) return;
        // 下载期间压掉的那段时间要补回来，而不是重新从 0 开始算 2 秒 ——
        // 否则「2 秒自动开练」实际会变成 2 秒 + 下载耗时。
        long elapsed = SystemClock.uptimeMillis() - sDetailEnterT;
        long left = detailIdleMs() - elapsed;
        if (left < 300) left = 300;
        armIdle(left, false);
        log("auto-GO re-armed after download (" + left + "ms left)");
    }

    /**
     * 派发成功的二次确认。
     * 点完「全部下载」只能证明 Keep 收下了任务，下载器失败/被取消时数据库照样有记录。
     * 90 秒后量一下这门课的本地目录：没变大就撤回「已确认」并重新入队（每门课最多补一次），
     * 免得一次失败把课永久标成"下过了"。
     */
    void armVerify(final String cid) {
        if (cid == null) return;
        final long before = sDlSizeBefore;
        sMain.postDelayed(new Runnable() {
            public void run() {
                if (!isOnline()) { log("dl: verify skipped (offline) " + cid); return; }
                long now = courseSize(cid);
                boolean grew = before > 0 && now > before + 65536;
                Integer n = sVerifyTries.get(cid);
                int tries = n == null ? 0 : n;
                log("dl: verify " + cid + " before=" + before + " now=" + now
                        + " grew=" + grew + " try=" + tries);
                if (grew) return;
                // 前后都是 0：要么这门课本来就没有独立目录（路径判断失效），
                // 要么还没开始写盘。这种情况宁可不重排，别白白再下一遍。
                if (before == 0 && now == 0) {
                    log("dl: verify inconclusive (0/0), skip " + cid);
                    return;
                }
                if (tries >= 1) { log("dl: verify failed twice, give up " + cid); return; }
                sVerifyTries.put(cid, tries + 1);
                sDlChecked.remove(cid);
                saveChecked();
                loadNeedDownload();
                if (sNeedDownload.add(cid)) saveNeedDownload();
                log("dl: verify failed -> requeued " + cid);
            }
        }, 90000);
    }

    /** 一门课在本地占多少字节（course_/plan_cache_ 两个目录） */
    long courseSize(String cid) {
        if (cid == null) return 0;
        long total = 0;
        try {
            File base = new File("/data/data/com.gotokeep.keep/files/keep");
            total += dirSize(new File(base, "course_" + cid));
            total += dirSize(new File(base, "plan_cache_" + cid));
        } catch (Throwable t) { log("courseSize fail " + t); }
        return total;
    }

    static long dirSize(File f) {
        if (f == null) return 0;
        try {
            if (!f.exists()) return 0;
            if (f.isFile()) return f.length();
            File[] kids = f.listFiles();
            if (kids == null) return 0;
            long s = 0;
            for (int i = 0; i < kids.length; i++) s += dirSize(kids[i]);
            return s;
        } catch (Throwable t) { return 0; }
    }

    /** 第一步：点右上角「…」（setContainer 才是可点的那个容器） */
    void dlStep1(final Activity a) { dlStep1(a, 0); }

    void dlStep1(final Activity a, final int attempt) {
        try {
            View v = findById(a.getWindow() == null ? null : a.getWindow().getDecorView(), "setContainer");
            if (v == null) {
                if (attempt < 2) {
                    log("dl: setContainer missing, retry #" + (attempt + 1));
                    sMain.postDelayed(new Runnable() { public void run() { dlStep1(a, attempt + 1); } }, 600);
                } else {
                    log("dl: setContainer not found");
                    dlDone("no-setContainer");
                }
                return;
            }
            v.performClick();
            log("dl: options panel opening (try " + (attempt + 1) + ")");
            sMain.postDelayed(new Runnable() { public void run() { dlStep2(attempt, 0); } }, 600);
        } catch (Throwable t) { log("dl1 fail " + t); }
    }

    /** 第二步：更多选项面板里点「下载」，已经下过就跳过 */
    void dlStep2(final int openTry, final int wait) {
        try {
            Dialog d = newestDialog();
            View root = d == null ? null : popupRoot(d);
            View lbl = root == null ? null : findById(root, "textFunc");
            if (lbl == null) {
                if (wait < 4) {
                    log("dl: wait options #" + (wait + 1));
                    sMain.postDelayed(new Runnable() { public void run() { dlStep2(openTry, wait + 1); } }, 400);
                    return;
                }
                // 面板压根没弹：多半是页面还没渲染完，点「…」落空了，整步重来一次
                Activity a = sCur;
                if (a != null && openTry < 2) {
                    log("dl: options panel not ready -> re-click (try " + (openTry + 2) + ")");
                    dlStep1(a, openTry + 1);
                } else {
                    log("dl: options panel not ready, give up");
                    dlDone("no-options");
                }
                return;
            }
            String t = lbl instanceof TextView ? String.valueOf(((TextView) lbl).getText()) : "";
            log("dl# options text=" + t);
            if (t.contains("已下载")) {
                // 这里绝对不能直接收工。
                // 详情页的「已下载」看的是**本地视频文件**，「下载课程」页看的是
                // download.db 的 download_data 表 —— 两套状态互不同步。
                // 训练时边播边存下来的文件会让这里显示「已下载」，但 db 里没有记录，
                // 结果就是"明明练过，下载课程页里却没有这门课"。
                // 所以继续点进下载设置面板：能点「全部下载」就把 db 记录补上；
                // 面板里确实没有可下载项，才当作"本来就下好了"。
                log("dl: options says 已下载, still open download sheet");
                sSawDownloaded = true;
            }
            View btn = findById(root, "imageFunc");
            if (btn == null) { log("dl: imageFunc missing"); dismissDialogs(); dlDone("no-btn"); return; }
            btn.performClick();
            log("dl: clicked download item (" + t + ")");
            sMain.postDelayed(new Runnable() { public void run() { dlStep3(0); } }, 550);
        } catch (Throwable t) { log("dl2 fail " + t); dlDone("dl2-ex"); }
    }

    /** 第三步：下载设置面板里点「全部下载」。面板是异步挂上来的，要等 */
    void dlStep3(final int attempt) {
        try {
            Dialog d = newestDialog();
            View root = d == null ? null : popupRoot(d);
            View all = root == null ? null : findById(root, "textRight");
            String t = all instanceof TextView ? String.valueOf(((TextView) all).getText()) : "";
            if (!"全部下载".equals(t)) {
                if (attempt < 6) {
                    log("dl: wait download sheet #" + (attempt + 1));
                    sMain.postDelayed(new Runnable() { public void run() { dlStep3(attempt + 1); } }, 400);
                } else {
                    log("dl: 全部下载 not found, give up");
                    dismissDialogs();
                    dlDone("no-sheet");
                }
                return;
            }
            log("dl# sheet=" + textOf(root));
            // 面板里必须真的列出课程条目（"1. xxx | 训练 | 6.62M"）。
            // 空面板（比如点到的是单个动作页）点「全部下载」什么也不会发生，
            // 不用傻等 12 秒。
            String sheetAll = textOf(root);
            boolean hasItems = sheetAll.contains("训练") && SIZE.matcher(sheetAll).find();
            if (!hasItems) {
                // 已下载的课面板本来就可能不显示体积，别傻等 5 秒
                int maxWait = sSawDownloaded ? 3 : 8;
                if (attempt < maxWait) {
                    log("dl: sheet empty, wait content #" + (attempt + 1));
                    sMain.postDelayed(new Runnable() { public void run() { dlStep3(attempt + 1); } }, 700);
                } else {
                    log("dl: sheet has no downloadable items, give up");
                    dismissDialogs();
                    dlDone("no-items");
                }
                return;
            }
            // 读 download.db 和统计目录体积都放到后台线程。
            // 这两个操作在主线程做过：Keep 自己正在写这个库时会等锁，
            // 一旦锁住就是几百毫秒甚至数秒的卡顿 —— 直接表现为"点课程会卡一下"。
            final String cidNow = sDlRunningCid != null ? sDlRunningCid : sDetailCourseId;
            new Thread(new Runnable() {
                public void run() {
                    final String db = dlDbSnapshot();
                    final long size = courseSize(cidNow);
                    sMain.post(new Runnable() {
                        public void run() {
                            if (!sDlBusy) return;
                            sDlDbBefore = db;
                            sDlSizeBefore = size;
                            log("dl: db before=" + db + " size=" + size);
                            boolean ok = all.performClick();
                            log("dl: 全部下载 clicked=" + ok);
                            if (!ok) { dlDone("click-failed"); return; }
                            afterDispatchClicked();
                        }
                    });
                }
            }, "KeepAssist-dl-db").start();
        } catch (Throwable t) { log("dl3 fail " + t); dlDone("dl3-ex"); }
    }

    /** 点完「全部下载」之后的收尾（在 sDlDbBefore / sDlSizeBefore 就绪后调用） */
    void afterDispatchClicked() {
        // 点下去就算交给 Keep 下载器了，自动开练不用再等数据库确认
        sDlDispatched = true;
        if (sSawDownloaded) {
            // 已下载的课 Keep 是幂等的，不会写数据库 —— 没必要等 4.5 秒确认。
            // 快速关掉面板，后面点 GO 也不会被弹层挡住。
            sMain.postDelayed(new Runnable() {
                public void run() {
                    if (!sDlBusy) return;
                    dismissDialogs();
                    dlDone("dispatched");
                }
            }, 400);
            return;
        }
        // 注意：这里不能出队！出队只等 dlDone 确认真的开始了（或已经是已下载）
        // 点完不能马上关面板：Keep 是在这个面板里把任务派给下载器的。
        log("dl: waiting for dispatch");
        sMain.postDelayed(new Runnable() {
            public void run() { dlWaitDispatch(0); }
        }, 800);
    }

    void dequeueCid(String cid) {
        if (cid == null) return;
        loadNeedDownload();
        if (sNeedDownload.remove(cid)) {
            saveNeedDownload();
            log("dl: dequeued " + cid);
        }
    }

    /**
     * 点完「全部下载」之后盯住面板，直到看到进度（"正在下载" / "x/y M"）才关。
     * 关太早 = Keep 还没把任务派出去，等于白点。
     */
    /**
     * 直接读 Keep 的下载库，返回 "条数:最新下载时间"。
     * 面板文案靠不住（有时点了「全部下载」面板毫无变化，但任务其实派出去了），
     * 数据库变了才是真的开始了。
     */
    String dlDbSnapshot() {
        try {
            java.io.File f = new java.io.File(
                    "/data/data/com.gotokeep.keep/databases/download.db");
            if (!f.exists()) return null;
            android.database.sqlite.SQLiteDatabase db =
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                            f.getAbsolutePath(), null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor c = db.rawQuery(
                    "select count(*), ifnull(max(downloadTime),0) from download_data", null);
            String r = null;
            if (c.moveToFirst()) r = c.getInt(0) + ":" + c.getLong(1);
            c.close();
            db.close();
            return r;
        } catch (Throwable t) { log("dldb read fail " + t); return null; }
    }

    void dlWaitDispatch(final int attempt) {
        final Dialog d = newestDialog();
        final String all = textOf(d == null ? null : popupRoot(d));
        // 数据库读取放后台：主线程 openDatabase 遇到 Keep 正在写库会等锁
        new Thread(new Runnable() {
            public void run() {
                final String dbNow = dlDbSnapshot();
                sMain.post(new Runnable() {
                    public void run() { dlDispatchCheck(attempt, all, dbNow); }
                });
            }
        }, "KeepAssist-dl-wait").start();
    }

    void dlDispatchCheck(final int attempt, String all, String dbNow) {
        try {
            boolean dbChanged = sDlDbBefore != null && dbNow != null && !dbNow.equals(sDlDbBefore);
            boolean uiStarted = all.contains("正在下载") || all.contains("排队")
                    || all.contains("等待下载") || all.contains("下载中");
            log("dl# wait" + attempt + " db=" + dbNow + " ui=" + uiStarted);
            if (dbChanged || uiStarted) {
                log("dl: dispatch confirmed -> close panel (dbChanged=" + dbChanged + ")");
                dismissDialogs();
                dlDone("dispatched");
                return;
            }
            if (attempt >= (sSawDownloaded ? 3 : 8)) {
                log("dl: dispatch unconfirmed after " + (attempt * 1500) + "ms, close anyway");
                dismissDialogs();
                dlDone("no-dispatch");
                return;
            }
            log("dl: waiting dispatch #" + (attempt + 1));
            sMain.postDelayed(new Runnable() {
                public void run() { dlWaitDispatch(attempt + 1); }
            }, 1500);
        } catch (Throwable t) {
            log("dlWait fail " + t);
            dismissDialogs();
            dlDone("ex");
        }
    }

    Dialog newestDialog() {
        Dialog d = sLastDialog;
        boolean on = false;
        try { on = d != null && d.isShowing(); } catch (Throwable ignored) {}
        if (on) return d;
        synchronized (sOpenDialogs) {
            for (Dialog x : sOpenDialogs) {
                try { if (x.isShowing()) return x; } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * 把下载机器人的面板变透明。
     *
     * 「… → 下载 → 全部下载」这三步必须走 Keep 自己的 UI，任务才会派给下载器；
     * 但用户不该在屏幕上看到面板一闪一闪。alpha=0 只影响绘制，
     * View 仍然是 VISIBLE + attached，`performClick()` 照常工作。
     * dim 也要清零，否则背景还是会变暗一下。
     */
    void hidePanel(Dialog d) {
        try {
            Window w = d.getWindow();
            if (w == null) return;
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.alpha = 0f;
            lp.dimAmount = 0f;
            lp.flags |= android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            w.setAttributes(lp);
            w.getDecorView().setAlpha(0f);
            log("dl: panel hidden");
        } catch (Throwable t) { log("hidePanel fail " + t); }
    }

    /** 这个 Dialog 是不是「…→下载」那条链路上的面板（靠 Keep 自己的控件 id 认） */
    boolean isDownloadPanel(Dialog d) {
        try {
            View root = popupRoot(d);
            if (root == null) return false;
            return findById(root, "textFunc") != null
                    || findById(root, "imageFunc") != null
                    || findById(root, "textRight") != null
                    || findById(root, "setContainer") != null;
        } catch (Throwable t) { return false; }
    }

    /** hidePanel 的逆操作，用于把误隐藏的普通弹窗恢复 */
    void showPanel(Dialog d) {
        try {
            Window w = d.getWindow();
            if (w == null) return;
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            if (lp.alpha != 0f) return;
            lp.alpha = 1f;
            w.setAttributes(lp);
            w.getDecorView().setAlpha(1f);
            log("dl: restored a non-download dialog");
        } catch (Throwable ignored) {}
    }

    void dismissDialogs() {
        java.util.ArrayList<Dialog> copy = new java.util.ArrayList<Dialog>();
        synchronized (sOpenDialogs) { copy.addAll(sOpenDialogs); }
        for (Dialog d : copy) {
            try { if (d.isShowing()) d.dismiss(); } catch (Throwable ignored) {}
        }
        if (!copy.isEmpty()) log("dl: closed " + copy.size() + " dialog(s)");
    }

    View findById(View root, String name) {
        if (root == null) return null;
        try {
            int id = root.getResources().getIdentifier(name, "id", KEEP);
            if (id == 0) return null;
            return root.findViewById(id);
        } catch (Throwable t) { return null; }
    }

    void hookPopup() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                final PopupWindow pw = (PopupWindow) p.thisObject;
                ensureMain();
                sMain.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            if (sWrapUp) {
                                log("popup wrapup-dismiss");
                                pw.dismiss();
                                return;
                            }
                            inspect(pw.getContentView(), "popup");
                        } catch (Throwable ignored) {}
                    }
                }, 80);
            }
        };
        try {
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", h);
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", h);
        } catch (Throwable t) { log("hookPopup " + t); }
    }

    static View popupRoot(Dialog d) {
        try {
            Window w = d.getWindow();
            return w == null ? null : w.getDecorView();
        } catch (Throwable t) { return null; }
    }

    /** 收尾期弹窗一律关掉；平时交给 inspect 精准识别，避免误伤正常弹窗 */
    void handleDialog(Dialog d, String tag) {
        try {
            if (!d.isShowing()) return;
            if (sWrapUp) {
                String txt = textOf(popupRoot(d));
                // 收尾期"一律关掉"也有底线：权限框和"训练未完成"提示不能关。
                if (isPermissionLike(txt) || txt.contains("还有训练未完成")) {
                    log(tag + " wrapup: keep this dialog for user");
                    return;
                }
                log(tag + " wrapup-dismiss => " + (txt.length() > 200 ? txt.substring(0, 200) : txt));
                d.dismiss();
                return;
            }
            // Keep 的「你还有训练未完成，是否继续训练？」会在冷启动时挡在前面。
            // 这是用户自己的训练进度，替他点「退出」等于把进度丢掉 —— 只记录，
            // 让人自己选；他点完弹层退回主页后由 reanchorIfKicked() 补跳转。
            View root = popupRoot(d);
            String all = textOf(root);
            if (all.contains("还有训练未完成")) {
                log(tag + " unfinished-training prompt (leave to user)");
                return;
            }
            // 详情页的「为你推荐 / 点击查看」推广弹窗：挡住 GO，还会让自动开练被取消。
            // 这类弹窗没有保留价值，直接点掉，关掉后 noteDialog 会重新挂 2 秒计时。
            Activity cur = sCur;
            String cn = cur == null ? "" : cur.getClass().getName();
            if (DETAIL.equals(cn)
                    && (all.contains("为你推荐") || all.contains("猜你喜欢") || all.contains("点击查看"))) {
                View cancel = findByTextIn(root, new String[]{"取消", "关闭", "暂不", "以后再说"});
                if (cancel != null) {
                    clickDeep(cancel);
                    log(tag + " detail promo dismissed");
                    return;
                }
            }
            inspect(root, tag);
        } catch (Throwable t) { log("handleDialog " + t); }
    }

    static String textOf(View root) {
        if (root == null) return "";
        List<TextView> tvs = new ArrayList<TextView>();
        collect(root, tvs);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tvs.size(); i++) {
            String s = String.valueOf(tvs.get(i).getText());
            if (s.length() > 0) sb.append(s).append(" | ");
        }
        return sb.toString();
    }

    /** 识别并处理弹窗 */
    /** 权限 / 授权 / 隐私确认类弹窗的特征（这类弹窗一律不替用户点） */
    static boolean isPermissionLike(String all) {
        if (all == null) return false;
        return all.contains("权限") || all.contains("允许") || all.contains("拒绝")
                || all.contains("授权") || all.contains("后台运行")
                || all.contains("隐私") || all.contains("同意并");
    }

    void inspect(View root, String tag) {
        if (root == null) return;
        List<TextView> tvs = new ArrayList<TextView>();
        collect(root, tvs);
        if (tvs.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tvs.size(); i++) {
            String s = String.valueOf(tvs.get(i).getText());
            if (s.length() > 0) sb.append(s).append(" | ");
        }
        String all = sb.toString();
        if (all.length() == 0) return;
        log(tag + " => " + (all.length() > 500 ? all.substring(0, 500) : all));

        // 权限 / 授权类弹窗：一个字都不能替用户点。
        // 这类弹窗里「以后再说」= 拒绝，「关闭」也可能是"稍后"，
        // 误点会让 Keep 丢掉存储、通知、后台运行权限 —— 后续下载和提醒直接失效，
        // 而且表面上完全看不出原因。宁可漏关一个广告，也不能点错一次权限。
        if (isPermissionLike(all)) {
            log(tag + " permission-like dialog -> leave to user");
            return;
        }

        // 训练完成后的活动/奖牌广告：带倒计时的关闭按钮（如「1s 后可关闭」），不等倒计时直接点
        if (all.contains("后可关闭") || all.contains("后可关") || all.contains("后可跳过")
                || COUNTDOWN.matcher(all).find()) {
            View c = findByTextContains(root, "后可关");
            if (c == null) c = findByTextContains(root, "后可跳");
            if (c == null) c = findByCountdown(root);
            if (c == null) c = findCloseLike(root);
            if (c != null) {
                clickDeep(c);
                log(tag + " -> ACTIVITY AD dismissed, back to course");
                navToCourse();
                // 倒计时未走完时按钮可能还不可点，稍后再补一次
                final View c2 = c;
                sMain.postDelayed(new Runnable() {
                    public void run() {
                        if (canClick(c2)) { clickDeep(c2); log("ad retry click"); }
                    }
                }, 1200);
            }
            return;
        }

        // 「继续努力」之类的结果弹窗，自动按掉
        if (all.contains("继续努力") || all.contains("下次继续")) {
            View ok = findByTextIn(root, new String[]{"确定", "好的", "知道了", "知道", "继续", "完成", "关闭"});
            if (ok != null) {
                clickDeep(ok);
                log(tag + " -> 继续努力 dismissed");
            } else {
                log(tag + " -> 继续努力 but no button found");
            }
            return;
        }

        // 通用：带明确关闭语义的按钮。
        // 这里刻意不含「以后再说 / 暂不」—— 在权限和引导流程里它们等于"拒绝/退出"，
        // 点掉会把 Keep 自己的功能关掉，用户完全不知道为什么。
        View c = findByTextIn(root, new String[]{"跳过", "关闭", "我知道了"});
        if (c != null && !all.contains("继续训练")) {
            clickDeep(c);
            log(tag + " -> generic close");
        }
    }

    // --------------------------------------------------- 广告 Activity 拦截

    void hookStartActivity() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Intent it = null;
                    int idx = 0;
                    if (p.args != null) {
                        for (int i = 0; i < p.args.length; i++) {
                            if (p.args[i] instanceof Intent) { it = (Intent) p.args[i]; idx = i; break; }
                        }
                    }
                    if (it == null || it.getComponent() == null) return;
                    String cn = it.getComponent().getClassName();
                    if (isAdClass(cn)) {
                        log("BLOCK ad activity -> " + cn);
                        p.setResult(null);
                        return;
                    }
                    // 训练收尾期：日程页/分享页不许起来，直接改道去我的课程
                    if (sWrapUp && KEEP.equals(it.getComponent().getPackageName())
                            && !COURSE.equals(cn) && !SUMMARY.equals(cn)
                            && isWrapUpBlocked(cn)) {
                        log("BLOCK wrapup detour -> " + cn);
                        Intent r = new Intent();
                        r.setClassName(KEEP, COURSE);
                        r.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        p.args[idx] = r;
                    }
                } catch (Throwable t) { log("startActivity hook " + t); }
            }
        };
        try {
            XposedBridge.hookAllMethods(Activity.class, "startActivity", h);
            XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", h);
        } catch (Throwable t) { log("hookStartActivity " + t); }
    }

    static boolean isAdClass(String cn) {
        for (int i = 0; i < AD_PKGS.length; i++) {
            if (cn.startsWith(AD_PKGS[i])) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- View 工具

    void waitView(final Activity a, final long timeout, final Match m, final Act act) {
        final long t0 = SystemClock.uptimeMillis();
        Runnable r = new Runnable() {
            public void run() {
                View root = null;
                try {
                    Window w = a.getWindow();
                    if (w != null) root = w.getDecorView();
                } catch (Throwable ignored) {}
                View found = root == null ? null : findView(root, m);
                if (found != null) {
                    act.a(found);
                    return;
                }
                if (SystemClock.uptimeMillis() - t0 < timeout && !a.isFinishing()) {
                    sMain.postDelayed(this, 55);
                } else {
                    log("waitView timeout @" + el(t0) + "ms");
                }
            }
        };
        sMain.post(r);
    }

    static View findView(View root, Match m) {
        if (m.m(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findView(g.getChildAt(i), m);
                if (r != null) return r;
            }
        }
        return null;
    }

    static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    static View findByTextContains(View root, String key) {
        final String k = key;
        return findView(root, new Match() {
            public boolean m(View v) {
                return v instanceof TextView && String.valueOf(((TextView) v).getText()).contains(k);
            }
        });
    }

    static View findByTextIn(View root, String[] keys) {
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            View v = findView(root, new Match() {
                public boolean m(View x) {
                    return x instanceof TextView && k.equals(String.valueOf(((TextView) x).getText()));
                }
            });
            if (v != null && canClick(v)) return v;
        }
        return null;
    }

    static View findCloseLike(View root) {
        return findByTextIn(root, new String[]{"关闭", "跳过", "×", "X", "我知道了", "取消"});
    }

    static View findByCountdown(View root) {
        return findView(root, new Match() {
            public boolean m(View v) {
                return v instanceof TextView
                        && COUNTDOWN.matcher(String.valueOf(((TextView) v).getText())).find();
            }
        });
    }

    static boolean canClick(View v) {
        View p = v;
        int guard = 0;
        while (p != null && guard++ < 12) {
            if (p.isClickable()) return true;
            Object par = p.getParent();
            p = (par instanceof View) ? (View) par : null;
        }
        return false;
    }

    /** 文本本身可能不可点，往上找第一个可点击的祖先 */
    static boolean clickDeep(View v) {
        View p = v;
        int guard = 0;
        while (p != null && guard++ < 12) {
            if (p.isClickable()) return p.performClick();
            Object par = p.getParent();
            p = (par instanceof View) ? (View) par : null;
        }
        return v.performClick();
    }

    static String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) { return ""; }
    }

    void initIds(Context c) {
        if (sIdsReady) return;
        try {
            ID_SKIP      = c.getResources().getIdentifier("textSkip", "id", KEEP);
            ID_TEXT_NAME = c.getResources().getIdentifier("textName", "id", KEEP);
            ID_NAME      = c.getResources().getIdentifier("name", "id", KEEP);
            sIdsReady = true;
            log("ids: skip=" + ID_SKIP + " textName=" + ID_TEXT_NAME + " name=" + ID_NAME);
        } catch (Throwable t) { log("initIds " + t); }
    }

    // ------------------------------------------------------------------ misc

    static void ensureMain() {
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
    }

    static long el(long t0) { return SystemClock.uptimeMillis() - t0; }

    static synchronized void log(String s) {
        XposedBridge.log(TAG + ": " + s);
        try {
            // sCtx 要等第一个 Activity onResume 才有，之前只能进 logcat，
            // 导致 "模块是否加载成功" 这种关键信息查不到。直接按包名拼路径即可写
            File dir = sCtx != null ? sCtx.getFilesDir() : new File("/data/data/" + KEEP + "/files");
            File f = new File(dir, "keepassist.log");
            if (f.length() > 512 * 1024) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }
}
