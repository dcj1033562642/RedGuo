package com.wodi.redguotools;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Color;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * 宿主进程内的界面控制：状态栏、底部导航栏、叠加组件的隐藏/透明度、长按入口、双击打开评论区。
 *
 * 关于状态栏：支持「隐藏系统状态栏 + 自绘底色横条」。
 * 早期版本还有「点击横条临时显示时间/电量数字」，该子功能已移除 ——
 * 现在横条只负责底色，不再承载任何数字与交互。
 *
 * 关于组件控制：不再写死「三个浮层」，而是
 *   内置槽位（Config.SLOT_*}）+ 用户自定义清单（Config.overlayList）
 * 统一走同一套匹配规则。默认什么都不启用 —— 也就是全部照常显示。
 */
public final class UiController {

    private static final String TAG = RGModule.TAG;

    private static final WeakHashMap<Activity, Boolean> ATTACHED = new WeakHashMap<>();
    private static final WeakHashMap<Activity, GestureDetector> DETECTORS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> CONSUME = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> SWALLOW = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Tap> TAPS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Bar> BARS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> LOOPS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Long> LAST_TOUCH = new WeakHashMap<>();
    private static final WeakHashMap<Activity, List<View>> IDLE_HIDDEN = new WeakHashMap<>();
    private static long LAST_IDLE_DBG;

    /** 已经为哪些页面落过「评论区入口找不到」的视图树（避免反复重写同一个文件）。 */
    private static final java.util.HashSet<String> MISS_DUMPED = new java.util.HashSet<>();

    /** 保存被我们改过的视图的原始 (visibility, alpha)，便于复原。 */
    private static final int TAG_ORIG = 0x5A5A0001;

    /** 标记「可见性是我们改成 INVISIBLE 的」—— 只有这种才允许我们再改回去。 */
    private static final int TAG_WEHID = 0x5A5A0002;

    /** 底部标签栏专用（与上面的浮层体系互不干扰，applyDesired 不认识它们）。 */
    private static final int TAG_BTAB_WEHID = 0x5A5A0004;

    /** 清屏空闲隐藏专用：INVISIBLE 是空闲定时器设的，触摸即恢复。 */
    private static final int TAG_IDLE_WEHID = 0x5A5A0005;

    /** 清屏空闲隐藏：无触摸多久后收起其余控件（仅二级播放页 + 清屏开启时）。 */
    private static final long IDLE_HIDE_MS = 10000L;

    /**
     * 导航栏隐藏态标记：用 Map 而非 View tag —— 导航栏是 SystemUI 的独立窗口，
     * 模块拿不到它的 View，没有 tag 可打。
     *
     * <p>语义：值 = 本模块是否已请求隐藏。用于避免每轮循环重复调用 hide()，
     * 也用于在 App 退到后台时决定要不要还回去。
     */
    private static final WeakHashMap<Activity, Boolean> NAV_HIDDEN_MARK = new WeakHashMap<>();

    /**
     * 整个 App 当前是否处于前台。
     *
     * <p>用来区分「App 内部切页面」与「真的离开 App」：只有后者才恢复导航栏。
     * 计数器而非布尔 —— 页面切换时新旧 Activity 的 pause/stop 会交错。
     */
    private static int sForegroundCount = 0;

    /** 本页面见过的导航栏最大高度，供隐藏后继续使用（隐藏后 insets 会归零）。 */
    private static final WeakHashMap<Activity, Integer> NAV_BASE_H = new WeakHashMap<>();

    /** 连击计数：竖屏双击、横屏三击打开评论区。 */
    private static final class Tap {
        long lastUp;
        long downTime;
        int count;
        float downX;
        float downY;
        boolean swallow;
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /*
     * 「叠加组件透明度」不再维护自己的目标清单。
     *
     * 旧实现是按三个写死的类名去 setAlpha：
     *     com.dragon.read.widget.BottomTabFrameLayout
     *     com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed
     *     com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer
     * 这三个类在红果 7.3.9.32 里已经**全部不存在**（实测视图树命中 0 次），
     * 于是那个滑块怎么拖都不会有任何效果。
     *
     * 现在它改成**系数**：乘在「组件显隐」各项与自定义清单各自的透明度上，
     * 见 applyOverlays()。这样它作用范围明确，也不会再因为宿主改类名而静默失效。
     */

    private static volatile Activity sTop;

    /** 宿主进程 Context：供无 Activity 场景读配置（PlayerTweaks hook 回调里）。 */
    private static volatile Context sAppCtx;

    public static Context appCtx() {
        return sAppCtx;
    }

    private UiController() {
    }

    public static Activity topActivity() {
        return sTop;
    }

    public static Activity activityOf(Object o) {
        return sTop;
    }

    /** 诊断用：当前栈顶 Activity 简名。 */
    public static String topActivitySimple() {
        Activity a = sTop;
        return a == null ? "?" : a.getClass().getSimpleName();
    }

    private static boolean isHost(Activity a) {
        return a != null && RGModule.HOST_PKG.equals(a.getPackageName());
    }

    /** 该 Activity 是否属于红果（供 RGModule 的生命周期钩子判断，避免误统计别家 App）。 */
    public static boolean isHostActivity(Activity a) {
        return isHost(a);
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ------------------------------------------------------------------ */
    /* 文件日志：HyperOS 把 logcat 屏蔽了，关键事件落一份文件便于 adb pull    */
    /* ------------------------------------------------------------------ */

    private static final Object LOG_LOCK = new Object();

    public static void logFile(String msg) {
        synchronized (LOG_LOCK) {
            try {
                Activity a = sTop;
                if (a == null) {
                    return;
                }
                File dir = a.getExternalFilesDir(null);
                if (dir == null) {
                    return;
                }
                File f = new File(dir, "rgtools_log.txt");
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void resetLogFile(Activity a) {
        try {
            File dir = a.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "rgtools_log.txt");
            if (f.exists() && f.length() > 512 * 1024) {
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    public static void onActivityCreated(final Activity a) {
        if (!isHost(a)) {
            return;
        }
        sTop = a;
        if (sAppCtx == null) {
            try {
                sAppCtx = a.getApplicationContext();
            } catch (Throwable ignored) {
            }
        }
        try {
            Config.init(a);
        } catch (Throwable ignored) {
        }
        if (ATTACHED.containsKey(a)) {
            return;
        }
        ATTACHED.put(a, Boolean.TRUE);
        String ps = PlayerTweaks.status();
        if (ps != null) {
            logFile("player install: " + ps);
        }
        resetLogFile(a);
        logFile("activity created: " + a.getClass().getName());
        View decor = a.getWindow().getDecorView();
        if (decor != null) {
            decor.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        applyStatusBar(a);
                        applyOverlays(a);
                    } catch (Throwable t) {
                        Log.e(TAG, "attach failed", t);
                    }
                }
            });
        }
    }

    public static void onActivityResumed(Activity a) {
        if (!isHost(a)) {
            return;
        }
        sTop = a;
        logFile("activity resumed: " + a.getClass().getName());
        // 前台计数 +1（与 RGModule 的 onStop 钩子配对），供导航栏「仅退出时恢复」判断
        onAppForeground(a);
        try {
            applyStatusBar(a);
            applyOverlays(a);
        } catch (Throwable t) {
            Log.e(TAG, "onActivityResumed failed", t);
        }
        startReapplyLoop(a);
        // 浮层往往比 Activity 晚一步 inflate，等一会儿再落一份视图树便于排查
        final WeakReference<Activity> wrTree = new WeakReference<>(a);
        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                Activity act = wrTree.get();
                if (act != null) {
                    try {
                        logTree(act);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, 1800L);
    }

    /** 已经提示过的「规则没命中」，避免 1.2 秒一次的循环把日志刷爆。 */
    private static final java.util.HashSet<String> MISS_LOGGED = new java.util.HashSet<>();

    private static void miss(String rule, Activity a) {
        String k = rule + "@" + a.getClass().getSimpleName();
        if (MISS_LOGGED.add(k)) {
            logFile("MISS " + k);
        }
    }

    private static void hit(String rule, View v, Activity a) {
        String k = rule + "@" + a.getClass().getSimpleName();
        if (MISS_LOGGED.add(k)) {
            logFile("HIT " + k + " -> " + v.getClass().getName());
        }
    }

    /**
     * 浮层往往比 Activity 晚一步 inflate，页面切换也会重建整棵树，
     * 所以在一个较低的频率上周期性重新应用一次（视图未变化时不产生副作用）。
     */
    private static void startReapplyLoop(final Activity a) {
        if (LOOPS.containsKey(a)) {
            return;
        }
        LOOPS.put(a, Boolean.TRUE);
        final WeakReference<Activity> wr = new WeakReference<>(a);
        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                Activity act = wr.get();
                if (act == null) {
                    return;
                }
                try {
                    applyOverlays(act);
                } catch (Throwable ignored) {
                }
                // 设置弹层若直接挂在 DecorView 上（不是 Dialog），靠这个循环兜住注入
                try {
                    SheetEntry.tryInject(act, act.getWindow().getDecorView());
                } catch (Throwable ignored) {
                }
                UI.postDelayed(this, 1200L);
            }
        }, 1200L);
    }

    /** 设置面板改动后重新应用。 */
    public static void refresh(Activity a) {
        try {
            applyStatusBar(a);
            applyOverlays(a);
        } catch (Throwable t) {
            Log.e(TAG, "refresh failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 状态栏：隐藏系统栏 + 自绘底色横条                                     */
    /* ------------------------------------------------------------------ */

    /**
     * 状态栏自绘载体的状态。
     *
     * <p>它只做一件事：当「隐藏系统状态栏」开启时，在窗口顶部铺一条横条来填底色，
     * 避免系统栏被隐藏后露出一条突兀的空隙。**不承载任何文字与交互** ——
     * 早期的「点击显示时间/电量数字」子功能已移除。
     */
    private static final class Bar {
        LinearLayout root;
    }

    private static Bar buildBar(final Activity a) {
        Bar b = new Bar();
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        bar.setClickable(false);
        bar.setFocusable(false);

        b.root = bar;
        bar.setTag(b);
        return b;
    }

    private static int statusBarHeight(Context c) {
        Resources r = c.getResources();
        int id = r.getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = r.getDimensionPixelSize(id);
            if (h > 0) {
                return h;
            }
        }
        return dp(c, 24);
    }

    private static void applyStatusBar(Activity a) {
        if (!isHost(a)) {
            return;
        }
        Window w = a.getWindow();
        if (w == null) {
            return;
        }
        View decor = w.getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) decor;

        Bar b = BARS.get(a);
        if (b == null || b.root.getParent() == null) {
            b = buildBar(a);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeight(a));
            lp.gravity = Gravity.TOP;
            root.addView(b.root, lp);
            BARS.put(a, b);
        }

        boolean hide = Config.hideStatusBar(a);
        setSystemBarVisible(a, !hide);
        b.root.getLayoutParams().height = statusBarHeight(a);
        render(a);
    }

    /**
     * 按配置渲染底色横条。
     *
     * <p>不隐藏系统栏时横条完全让位（{@code GONE}）；已移除原有数字绘制与定时刷新。
     */
    private static void render(Activity a) {
        Bar b = BARS.get(a);
        if (b == null) {
            return;
        }
        if (!Config.hideStatusBar(a)) {
            b.root.setVisibility(View.GONE);
            return;
        }
        b.root.setVisibility(View.VISIBLE);

        int bg = Config.statusBarBg(a);
        int color = bg == Config.BG_BLACK ? Color.BLACK
                : bg == Config.BG_WHITE ? Color.WHITE
                : Color.TRANSPARENT;
        b.root.setBackgroundColor(color);

        // 宿主可能在我们之后继续往 DecorView 加子视图，重新置顶避免被盖住
        try {
            b.root.bringToFront();
        } catch (Throwable ignored) {
        }
    }

    private static void setSystemBarVisible(Activity a, boolean visible) {
        Window w = a.getWindow();
        if (w == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController ic = w.getInsetsController();
                if (ic != null) {
                    ic.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    if (visible) {
                        ic.show(WindowInsets.Type.statusBars());
                    } else {
                        ic.hide(WindowInsets.Type.statusBars());
                    }
                    return;
                }
            }
            View decor = w.getDecorView();
            if (decor != null) {
                int flags = decor.getSystemUiVisibility();
                if (visible) {
                    flags &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            Log.e(TAG, "setSystemBarVisible failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 组件槽位 + 自定义清单：统一匹配、隐藏与独立透明度                       */
    /* ------------------------------------------------------------------ */

    private static void applyOverlays(Activity a) {
        if (!isHost(a)) {
            return;
        }
        View decor = a.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) decor;
        int[] size = screenSize(a, decor);

        // 全局叠加透明度只是一个**系数**，乘在每一项自己的透明度上：
        //     生效透明度 = 该项透明度 × 全局 / 100
        // 「整体拉低」与「单项微调」因此可以叠加，语义也和面板文案对得上。
        int global = Math.max(0, Math.min(100, Config.alphaPercent(a)));

        // 计算出本轮「应当被改」的视图集合
        IdentityHashMap<View, int[]> desired = new IdentityHashMap<>();

        for (int i = 0; i < Config.SLOT_KEYS.length; i++) {
            String key = Config.SLOT_KEYS[i];
            boolean hide = Config.overlayHide(a, key);
            int alpha = Config.overlayAlpha(a, key);
            if (!hide && alpha >= 100 && global >= 100) {
                continue;   // 没勾隐藏、自身不透明、全局也没拉低 → 保持宿主原样
            }
            List<View> hits = new ArrayList<>();
            for (String rule : Config.SLOT_RULES[i]) {
                resolveInto(root, rule, size, hits);
            }
            if (hits.isEmpty()) {
                miss("[slot]" + key + " " + join(Config.SLOT_RULES[i]), a);
            } else {
                int eff = alpha * global / 100;
                for (View v : hits) {
                    hit("[slot]" + key, v, a);
                    desired.put(v, new int[]{hide ? 1 : 0, eff});
                }
            }
        }

        for (String[] it : Config.overlayList(a)) {
            boolean hide = "1".equals(it[1]);
            int alpha = 100;
            try {
                alpha = Integer.parseInt(it[2]);
            } catch (Throwable ignored) {
            }
            if (!hide && alpha >= 100 && global >= 100) {
                continue;
            }
            List<View> hits = new ArrayList<>();
            resolveInto(root, it[0], size, hits);
            if (hits.isEmpty()) {
                miss("[list]" + it[0], a);
            } else {
                int eff = alpha * global / 100;
                for (View v : hits) {
                    hit("[list]" + it[0], v, a);
                    desired.put(v, new int[]{hide ? 1 : 0, eff});
                }
            }
        }

        applyDesired(root, desired);
        applyBottomTabs(root, a);
        applyIdleClear(root, a);
        applyNavAutoHide(a);
    }

    /* ------------------------------------------------------------------ */
    /* 底部标签栏：按标签文字单独隐藏 BottomTabBarLayout 的直接子节点          */
    /* ------------------------------------------------------------------ */

    /** 收集底部标签容器。每个 tab 是 BottomTabBarLayout 的直接子节点，
     *  内含 ScaleTextView id=gbx 显示标签文字（首页/剧场/我的…）。 */
    private static List<View> bottomTabItems(ViewGroup root) {
        List<View> out = new ArrayList<>();
        try {
            List<View> bars = collectByClassSuffix(root, "com.dragon.read.widget.BottomTabBarLayout");
            for (View bar : bars) {
                if (!(bar instanceof ViewGroup)) {
                    continue;
                }
                ViewGroup g = (ViewGroup) bar;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && firstText(c, 6) != null) {
                        out.add(c);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 供面板枚举当前底部标签文字（去重、保序）。 */
    public static List<String> bottomTabLabels(Activity a) {
        List<String> out = new ArrayList<>();
        if (!isHost(a)) {
            return out;
        }
        View decor = a.getWindow().getDecorView();
        if (decor instanceof ViewGroup) {
            for (View v : bottomTabItems((ViewGroup) decor)) {
                String t = firstText(v, 6);
                if (t != null && !out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * 按配置隐藏底部标签。这里用 **GONE** 而不是浮层的 INVISIBLE ——
     * 标签栏要的是「腾地方」，剩下的标签自动摊满整行；两种语义相反是刻意的。
     * 可见性归属规则与 applyDesired 相同：只在「我们要藏」与
     * 「恢复我们自己藏过的」两种情况下动手，宿主自己的行为一律不碰。
     */
    private static void applyBottomTabs(ViewGroup root, Activity a) {
        List<View> tabs = bottomTabItems(root);
        if (tabs.isEmpty()) {
            return;
        }
        HashSet<String> hide = new HashSet<>();
        for (String s : Config.hiddenBottomTabs(a).split("\\|")) {
            s = s.trim();
            if (!s.isEmpty()) {
                hide.add(s);
            }
        }
        for (View tab : tabs) {
            String label = firstText(tab, 6);
            boolean want = label != null && hide.contains(label);
            boolean weHid = Boolean.TRUE.equals(tab.getTag(TAG_BTAB_WEHID));
            if (want && !weHid) {
                if (tab.getVisibility() == View.VISIBLE) {
                    // 只藏宿主正常显示的；原值必然是 VISIBLE，恢复时直接置回即可
                    tab.setTag(TAG_BTAB_WEHID, Boolean.TRUE);
                    tab.setVisibility(View.GONE);
                    hit("[btab]" + label, tab, a);
                }
            } else if (!want && weHid) {
                tab.setTag(TAG_BTAB_WEHID, null);
                tab.setVisibility(View.VISIBLE);
            }
        }
    }

    /** 供面板显示导航栏状态（诊断用）。全部基于 insets 实测，不靠推断。 */
    public static String navBarStatus(Activity a) {
        if (a == null || !isHost(a)) {
            return "（未在红果内）";
        }
        try {
            StringBuilder sb = new StringBuilder();
            int h = navBarHeight(a);
            boolean vis = !navBarHiddenNow(a);
            sb.append("导航栏高度 ").append(h).append("px")
                    .append(" · 当前").append(vis ? "可见" : "已隐藏");
            sb.append(" · ").append(Boolean.TRUE.equals(NAV_HIDDEN_MARK.get(a))
                    ? "本模块已接管" : "未接管");
            try {
                int m = android.provider.Settings.Secure.getInt(
                        a.getContentResolver(), "navigation_mode");
                sb.append(" · ").append(m == 2 ? "手势导航" : (m == 0 ? "三键导航" : "mode=" + m));
            } catch (Throwable ignored) {
            }
            if (h <= 0) {
                sb.append("（本机未上报导航栏 insets，暂不可控）");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败：" + t;
        }
    }

    /** 供面板显示：开关是否处于「App 内已生效」状态。 */
    public static String navBarEnableState() {
        if (sForegroundCount <= 0) {
            return "（App 不在前台，导航栏已恢复）";
        }
        return "已生效 · 红果内全程隐藏，退出红果时自动恢复";
    }

    /* ------------------------------------------------------------------ */
    /* 清屏 + 空闲 10 秒：自动收起其余控件，触摸即恢复（仅二级播放页）          */
    /* ------------------------------------------------------------------ */

    /** 仅二级短剧播放页（竖屏）。横屏是另一个 Activity，天然不参与。 */
    private static boolean isPlayPage(Activity a) {
        return a.getClass().getName().endsWith(".shortvideo.impl.ShortSeriesActivity");
    }

    /**
     * 清屏是否开着：参考「清屏会藏、平时常驻」的控件（弹幕入口 / 热评行 / 标题行）。
     * 实测（7.3.9.32）宿主清屏时这些视图**自身的 visibility 仍是 VISIBLE**，
     * 被藏掉的是公共祖先容器 —— 所以必须用 isShown()（沿祖先链）判断，
     * 配合「屏幕内坐标」排除页外预 inflation 实例。
     */
    private static boolean clearScreenOn(ViewGroup root, Activity a, int[] size, StringBuilder dbg) {
        List<View> refs = new ArrayList<>();
        resolveInto(root, "cls:com.dragon.read.component.shortvideo.danmaku.PublishDanmakuEntranceView",
                size, refs);
        resolveInto(root, "cls:com.dragon.read.component.shortvideo.impl.comment.view.InfoPanelHotCommentView",
                size, refs);
        resolveInto(root, "id:g41", size, refs);
        for (View v : refs) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            boolean onScreen = loc[0] > -v.getWidth() && loc[0] < size[0]
                    && loc[1] > -v.getHeight() && loc[1] < size[1];
            if (dbg != null) {
                dbg.append(' ').append(simpleName(v.getClass().getName()))
                        .append("@").append(loc[1])
                        .append(":v").append(v.getVisibility())
                        .append("/s").append(v.isShown() ? 1 : 0)
                        .append("/a").append(String.format(Locale.US, "%.2f", v.getAlpha()))
                        .append(onScreen ? "*" : "");
            }
            if (onScreen && !v.isShown()) {
                return true;
            }
        }
        return false;
    }

    /** 触摸即时恢复（onTouch 在 ACTION_DOWN 时调用，不等周期循环）。 */
    private static void restoreIdleHidden(Activity a) {
        List<View> hid = IDLE_HIDDEN.remove(a);
        if (hid == null || hid.isEmpty()) {
            return;
        }
        int n = 0;
        for (View v : hid) {
            if (Boolean.TRUE.equals(v.getTag(TAG_IDLE_WEHID))) {
                v.setTag(TAG_IDLE_WEHID, null);
                v.setVisibility(View.VISIBLE);
                n++;
            }
        }
        if (n > 0) {
            logFile("idle: touch -> restore " + n);
        }
    }

    /**
     * 周期调用：清屏开着且超过 {@link #IDLE_HIDE_MS} 无触摸 →
     * 把槽位 + 自定义清单命中的、当前可见的实例整层收起（INVISIBLE，保留占位）。
     * 与清屏的宿主行为互不冲突：宿主藏的我们不动，我们藏的靠触摸恢复。
     */
    private static void applyIdleClear(ViewGroup root, Activity a) {
        if (!isPlayPage(a)) {
            return;
        }
        List<View> hid = IDLE_HIDDEN.get(a);
        if (hid != null && !hid.isEmpty()) {
            return;   // 已处于空闲隐藏态，等触摸即时恢复
        }
        int[] size = screenSize(a, root);
        StringBuilder dbg = new StringBuilder();
        boolean cs = clearScreenOn(root, a, size, dbg);
        long bucket = SystemClock.uptimeMillis() / 60000L;
        if (bucket != LAST_IDLE_DBG) {
            LAST_IDLE_DBG = bucket;
            Long t0 = LAST_TOUCH.get(a);
            logFile("idle dbg: cs=" + cs + " sinceTouch="
                    + (t0 == null ? -1 : SystemClock.uptimeMillis() - t0) + dbg);
        }
        if (!cs) {
            return;
        }
        Long t = LAST_TOUCH.get(a);
        if (t == null) {
            LAST_TOUCH.put(a, SystemClock.uptimeMillis());   // 进页即起表
            return;
        }
        if (SystemClock.uptimeMillis() - t < IDLE_HIDE_MS) {
            return;
        }

        List<View> targets = new ArrayList<>();
        for (int i = 0; i < Config.SLOT_KEYS.length; i++) {
            for (String rule : Config.SLOT_RULES[i]) {
                resolveInto(root, rule, size, targets);
            }
        }
        for (String[] it : Config.overlayList(a)) {
            resolveInto(root, it[0], size, targets);
        }
        // 清屏后仍留在屏上的：顶栏（plbar 已含）、选集条（id:hh 已含）之外，还有细进度条
        resolveInto(root, "id:i47", size, targets);
        List<View> toHide = new ArrayList<>();
        for (View v : targets) {
            if (v.getVisibility() != View.VISIBLE || !v.isShown()) {
                continue;   // 宿主已收起 / 页外实例
            }
            if (Boolean.TRUE.equals(v.getTag(TAG_WEHID))
                    || Boolean.TRUE.equals(v.getTag(TAG_IDLE_WEHID))) {
                continue;
            }
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            boolean onScreen = loc[0] > -v.getWidth() && loc[0] < size[0]
                    && loc[1] > -v.getHeight() && loc[1] < size[1];
            if (onScreen) {
                toHide.add(v);
            }
        }
        if (toHide.isEmpty()) {
            return;
        }
        for (View v : toHide) {
            v.setTag(TAG_IDLE_WEHID, Boolean.TRUE);
            v.setVisibility(View.INVISIBLE);
        }
        IDLE_HIDDEN.put(a, toHide);
        logFile("idle: clear-screen " + (IDLE_HIDE_MS / 1000) + "s -> hide " + toHide.size());
    }

    /* ------------------------------------------------------------------ */
    /* 底部导航栏（小白条）：静置自动隐藏、触摸/下滑到底部时唤出              */
    /* ------------------------------------------------------------------ */

    /**
     * 底部导航栏（小白条）自动隐藏 —— 走系统 insets 控制器。
     *
     * <p><b>为什么不能用 setVisibility：</b>实测（小米15 / 红果 7.3.9.32）导航栏是
     * SystemUI 的**独立窗口**（{@code Window{... NavigationBar0} pkg=com.android.systemui}
     * {@code ty=NAVIGATION_BAR}），跨进程，模块拿不到它的 View，改不了。
     * 红果 {@code ScreenUtils.getCurrentNaviBarHeight()} 里那个
     * {@code findViewById(0x1020030)} 找的是**它自己窗口内**的同名占位视图，不是真小白条。
     *
     * <p>因此只能走 {@link WindowInsetsController#hide(int)}。好处是系统原生支持
     * 「隐藏 + 滑动临时唤出」——正是需求要的语义，且用
     * {@code BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE} 时滑出的是临时浮层，不来就永远不来。
     *
     * <p>红果窗口带 {@code FIT_INSETS_CONTROLLED}（实测确认），说明它没有锁死 insets，
     * 我们的调用有生效空间。
     */
    private static WindowInsetsController insetsController(Activity a) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return null;
            }
            Window w = a.getWindow();
            return w != null ? w.getInsetsController() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 隐藏导航栏。 */
    private static void hideNavBar(Activity a) {
        try {
            WindowInsetsController ic = insetsController(a);
            if (ic != null) {
                ic.hide(WindowInsets.Type.navigationBars());
                NAV_HIDDEN_MARK.put(a, Boolean.TRUE);
                // 系统会在 ~50ms 后才更新 insets，延迟回读一次确认，便于排查
                final Activity act = a;
                UI.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!Boolean.TRUE.equals(NAV_HIDDEN_MARK.get(act))) {
                            logFile("nav: hide reverted by host (h=" + navBarHeight(act) + ")");
                        }
                    }
                }, 500L);
                return;
            }
            // Android 11 以下：退回系统 UI flag
            View decor = a.getWindow().getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
                NAV_HIDDEN_MARK.put(a, Boolean.TRUE);
                logFile("nav: hide via legacy flags");
            }
        } catch (Throwable t) {
            logFile("nav hide failed: " + t);
        }
    }

    /**
     * 唤出导航栏（仅用于「开关被关掉」这一场景）。
     *
     * <p>只在「确实是本模块藏的」时才恢复 —— 宿主自己藏的不碰，沿用既有归属哲学。
     * 退出软件的场景不走这里，走 {@link #restoreNavBar}，那是无条件恢复。
     */
    private static void showNavBar(Activity a) {
        try {
            if (!Boolean.TRUE.equals(NAV_HIDDEN_MARK.get(a))) {
                return;   // 不是我们藏的，不去动
            }
            WindowInsetsController ic = insetsController(a);
            if (ic != null) {
                ic.show(WindowInsets.Type.navigationBars());
                NAV_HIDDEN_MARK.remove(a);
                return;
            }
            View decor = a.getWindow().getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                        & ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
                NAV_HIDDEN_MARK.remove(a);
            }
        } catch (Throwable t) {
            logFile("nav show failed: " + t);
        }
    }

    /**
     * 导航栏当前是否处于「隐藏」状态。
     *
     * <p><b>不能只看 {@code isVisible()}。</b>实测（小米15）导航栏被 hide 之后，
     * 系统的 navigationBars insets 会归零，此时 {@code isVisible(navigationBars())}
     * 反而报 true —— 只按它判断会让每轮循环都重复调用 hide（实测日志 1.2 秒刷一次）。
     *
     * <p>判定顺序：先看是不是我们自己藏的（最可靠）；否则用 insets 兜底 ——
     * 系统 insets 高度归零或 isVisible 为 false 都算隐藏，避免重复操作宿主已藏的导航栏。
     */
    private static boolean navBarHiddenNow(Activity a) {
        if (Boolean.TRUE.equals(NAV_HIDDEN_MARK.get(a))) {
            return true;
        }
        try {
            View decor = a.getWindow().getDecorView();
            WindowInsets wi = decor.getRootWindowInsets();
            if (wi == null) {
                return false;
            }
            if (!wi.isVisible(WindowInsets.Type.navigationBars())) {
                return true;
            }
            // insets 归零：系统此刻没有导航栏占位（可能是被 hide 后的状态）
            return wi.getInsets(WindowInsets.Type.navigationBars()).bottom <= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 周期调用：把导航栏钉死在隐藏态。
     *
     * <p><b>需求语义：软件内全程不显示导航栏，只有退出软件才恢复。</b>
     * 因此这里不做任何「触摸唤出 / 静置隐藏」的时间判断 —— 只要开关开着且 App 在前台，
     * 每一轮都确认它是藏着的。
     *
     * <p>为什么必须每轮确认：Android 会在这些时机**强制恢复**导航栏，
     * 单次 {@code hide()} 根本守不住 ——
     * <ul>
     *   <li>下拉通知栏 / 上滑进最近任务 / 手势导航条被系统短暂拉起；</li>
     *   <li>输入法弹出、对话框获得焦点；</li>
     *   <li>Activity 切换时 insets 重新下发。</li>
     * </ul>
     * 周期性重新 hide 是唯一可靠的压制手段。复用既有的 1.2s 重应用循环，
     * 不额外开线程。
     */
    private static void applyNavAutoHide(final Activity a) {
        if (!Config.autoHideNav(a)) {
            // 开关关掉时，把之前藏过的还回去，别留下半截状态
            if (Boolean.TRUE.equals(NAV_HIDDEN_MARK.get(a))) {
                showNavBar(a);
            }
            return;
        }
        // App 不在前台就别压着了 —— 退出软件要让系统导航栏正常显示
        if (sForegroundCount <= 0) {
            if (Boolean.TRUE.equals(NAV_HIDDEN_MARK.get(a))) {
                showNavBar(a);
            }
            return;
        }
        if (!NAV_HIDDEN_MARK.containsKey(a)) {
            // 首次：记一条日志便于排查是否真的生效
            if (navBarHeight(a) > 0) {
                logFile("nav: keep-hidden engaged (h=" + navBarHeight(a) + ")");
            }
        }
        // 无条件重新 hide：即使上一轮已经藏好，系统也可能刚把它恢复了。
        // hide() 是幂等的，重复调用无副作用，代价远低于「漏一轮就露出小白条」。
        hideNavBar(a);
    }

    /**
     * App 进入前台：重置压制状态。
     *
     * <p>从后台回来时系统已把导航栏恢复，靠 {@link #applyNavAutoHide} 的周期压制
     * 重新藏回去；这里只维护计数，不做多余动作。
     */
    public static void onAppForeground(Activity a) {
        if (sForegroundCount++ == 0) {
            logFile("nav: app -> foreground");
        }
    }

    /**
     * App 退到后台：恢复导航栏。
     *
     * <p><b>这是需求「退出软件才显示」的落点。</b>
     * 只有整个 App 真正离开前台（计数器归零）才恢复 —— App 内部切 Activity 时
     * 新旧页面会交错 pause，计数器保证不会误判。
     */
    public static void onAppBackground() {
        sForegroundCount--;
        if (sForegroundCount > 0) {
            return;   // 还有本 App 的页面在前台，属于内部切换
        }
        sForegroundCount = 0;
        // 把所有被我们藏过的页面都还回去
        for (Activity act : new ArrayList<>(NAV_HIDDEN_MARK.keySet())) {
            if (act != null) {
                restoreNavBar(act);
            }
        }
        logFile("nav: app background -> restore nav bar");
    }

    /**
     * 无条件恢复导航栏（退出软件时用）。
     *
     * <p>与 {@link #showNavBar} 的区别：后者只在「确实是本模块藏的」时才恢复，
     * 用于开关关闭；这里是退出场景，无论标记如何都要还回去，避免留下半截状态。
     */
    private static void restoreNavBar(Activity a) {
        try {
            WindowInsetsController ic = insetsController(a);
            if (ic != null) {
                ic.show(WindowInsets.Type.navigationBars());
            } else {
                View decor = a.getWindow().getDecorView();
                if (decor != null) {
                    decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                            & ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
                }
            }
        } catch (Throwable t) {
            logFile("nav restore failed: " + t);
        } finally {
            NAV_HIDDEN_MARK.remove(a);
        }
    }

    /**
     * 导航栏高度（px）。从根窗口 insets 读，跨进程唯一可靠的来源。
     *
     * <p>隐藏后 insets 会归零，所以额外记一份「本页面见过的最大值」作为基线 ——
     * 边缘感应带必须用真实高度，否则隐藏后带子会缩到只剩 24dp。
     */
    private static int navBarHeight(Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            WindowInsets wi = decor.getRootWindowInsets();
            if (wi == null) {
                return 0;
            }
            Insets ins = wi.getInsets(WindowInsets.Type.navigationBars());
            int now = ins != null ? ins.bottom : 0;
            if (now > 0) {
                NAV_BASE_H.put(a, now);
                return now;
            }
            Integer base = NAV_BASE_H.get(a);
            return base == null ? 0 : base;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void applyDesired(View v, IdentityHashMap<View, int[]> desired) {
        int[] d = desired.get(v);
        Object origTag = v.getTag(TAG_ORIG);
        if (d != null) {
            if (!(origTag instanceof float[])) {
                // 第一次改动前记下原始 (visibility, alpha)
                origTag = new float[]{v.getVisibility(), v.getAlpha()};
                v.setTag(TAG_ORIG, origTag);
            }
            float[] orig = (float[]) origTag;
            if (d[0] == 1) {
                v.setVisibility(View.INVISIBLE);
                v.setTag(TAG_WEHID, Boolean.TRUE);
            } else {
                /*
                 * 只调透明度时**不要碰 visibility**。
                 *
                 * 宿主自己也在改同一批视图的可见性 ——「清屏」就是典型：点清屏它把
                 * 浮层藏起来，再点一下恢复。如果我们这里按快照 setVisibility，
                 * 就是在和宿主打架：它藏、我们按旧快照显示回来；它恢复、我们又按
                 * 旧快照按回去 —— 界面永远回不到正确状态，叠字就是这么来的。
                 *
                 * 所以 visibility 只有一种情况归我们管：之前是**我们**隐藏的，
                 * 用户取消了隐藏，这时才恢复原值。
                 */
                if (Boolean.TRUE.equals(v.getTag(TAG_WEHID))) {
                    v.setVisibility((int) orig[0]);
                    v.setTag(TAG_WEHID, Boolean.FALSE);
                }
                v.setAlpha(Math.max(0, Math.min(100, d[1])) / 100f);
            }
        } else if (origTag instanceof float[]) {
            // 不再被管理：只有我们改过的可见性才复原；alpha 总是我们设的，总要复原
            float[] o = (float[]) origTag;
            if (Boolean.TRUE.equals(v.getTag(TAG_WEHID))) {
                v.setVisibility((int) o[0]);
            }
            v.setAlpha(o[1]);
            v.setTag(TAG_ORIG, null);
            v.setTag(TAG_WEHID, null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyDesired(g.getChildAt(i), desired);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 匹配规则引擎                                                         */
    /* ------------------------------------------------------------------ */

    private static int[] screenSize(Activity a, View decor) {
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            w = a.getResources().getDisplayMetrics().widthPixels;
            h = a.getResources().getDisplayMetrics().heightPixels;
        }
        return new int[]{w, h};
    }

    private static String join(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 规则：cls:类名后缀 / pcls:类名后缀的父级 / text:文案 / bar:文案 / id:名字 / @文案 / 裸串
     *
     * 注意：一个规则会命中「所有」符合条件的实例。短剧页会预 inflation 多页
     * （可见页 + 若干净页），只改第一个命中的很可能改到不可见的那一份。
     */
    private static void resolveInto(View root, String rule, int[] size, List<View> out) {
        if (rule == null) {
            return;
        }
        rule = rule.trim();
        if (rule.isEmpty()) {
            return;
        }
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<>();
        if (rule.startsWith("@")) {
            byText(root, rule.substring(1), size, out, seen);
            return;
        }
        if (rule.startsWith("cls:")) {
            String suffix = rule.substring(4).trim();
            if (!safeSuffix(suffix)) {
                logFile("bad cls rule: " + suffix);
                return;
            }
            addAll(collectByClassSuffix(root, suffix), out, seen);
            return;
        }
        if (rule.startsWith("pcls:")) {
            String suffix = rule.substring(5).trim();
            if (!safeSuffix(suffix)) {
                logFile("bad pcls rule: " + suffix);
                return;
            }
            List<View> all = collectByClassSuffix(root, suffix);
            for (View v : all) {
                ViewParent p = v.getParent();
                addOne((p instanceof View) ? (View) p : v, out, seen);
            }
            return;
        }
        if (rule.startsWith("text:")) {
            byText(root, rule.substring(5), size, out, seen);
            return;
        }
        if (rule.startsWith("bar:")) {
            byBar(root, rule.substring(4), size[0], out, seen);
            return;
        }
        if (rule.startsWith("id:")) {
            addAll(collectByIdName(root, rule.substring(3).trim()), out, seen);
            return;
        }
        if (rule.indexOf('.') >= 0) {
            addAll(collectByClassSuffix(root, rule), out, seen);
            return;
        }
        byText(root, rule, size, out, seen);
    }

    private static void addOne(View v, List<View> out, IdentityHashMap<View, Boolean> seen) {
        if (v != null && seen.put(v, Boolean.TRUE) == null) {
            out.add(v);
        }
    }

    /**
     * 类名后缀太短（如 `s`、`e`、`a` 这类混淆名）会误伤一大片，
     * 所以不带包名的后缀至少要有 4 个字符才允许按类名匹配。
     */
    private static boolean safeSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return false;
        }
        return suffix.indexOf('.') >= 0 || suffix.length() >= 4;
    }

    private static void addAll(List<View> src, List<View> out, IdentityHashMap<View, Boolean> seen) {
        for (View v : src) {
            addOne(v, out, seen);
        }
    }

    private static void byText(View root, String keyExpr, int[] size,
                               List<View> out, IdentityHashMap<View, Boolean> seen) {
        if (keyExpr == null) {
            return;
        }
        for (String k : keyExpr.split("\\|")) {
            k = k.trim();
            if (k.isEmpty()) {
                continue;
            }
            List<TextView> tvs = new ArrayList<>();
            collectTextContaining(root, k, tvs);
            for (TextView tv : tvs) {
                addOne(boxOf(tv, size[0]), out, seen);
            }
        }
    }

    /** 从命中的文案向上取「那一行」：只要祖先还在这段文字的高度量级内就继续上溯。 */
    private static View boxOf(View tv, int sw) {
        View best = tv;
        int limit = tv.getHeight() * 4 + dp(tv.getContext(), 24);
        View v = tv;
        for (int i = 0; i < 10; i++) {
            ViewParent p = v.getParent();
            if (!(p instanceof View)) {
                break;
            }
            View pv = (View) p;
            if (pv.getClass().getName().startsWith("com.android.internal.policy")) {
                break;
            }
            if (pv.getHeight() > limit) {
                break;
            }
            if (sw > 0 && pv.getWidth() > sw * 1.02f) {
                break;
            }
            best = pv;
            v = pv;
        }
        return best;
    }

    /** 底部选集条：文案 → 向上找到「可点击且几乎满宽」的那一层。 */
    private static void byBar(View root, String keyExpr, int screenW,
                              List<View> out, IdentityHashMap<View, Boolean> seen) {
        for (String k : keyExpr.split("\\|")) {
            k = k.trim();
            if (k.isEmpty()) {
                continue;
            }
            List<TextView> tvs = new ArrayList<>();
            collectTextContaining(root, k, tvs);
            for (TextView tv : tvs) {
                View v = tv;
                View chosen = null;
                while (v != null) {
                    if (v.isClickable() && screenW > 0 && v.getWidth() >= screenW * 0.9f) {
                        chosen = v;
                        break;
                    }
                    ViewParent p = v.getParent();
                    v = (p instanceof View) ? (View) p : null;
                }
                addOne(chosen != null ? chosen : tv, out, seen);
            }
        }
    }

    private static List<View> collectByIdName(View root, String name) {
        List<View> out = new ArrayList<>();
        collectByIdName(root, name, out);
        return out;
    }

    private static void collectByIdName(View root, String name, List<View> out) {
        try {
            int id = root.getId();
            if (id != View.NO_ID) {
                String rn = root.getResources().getResourceName(id);
                if (rn != null && (rn.endsWith("/" + name) || rn.equals(name))) {
                    out.add(root);
                }
            }
        } catch (Throwable ignored) {
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectByIdName(g.getChildAt(i), name, out);
            }
        }
    }

    private static List<View> collectByClassSuffix(View root, String suffix) {
        List<View> out = new ArrayList<>();
        collectByClassSuffix(root, suffix, out);
        return out;
    }

    private static void collectByClassSuffix(View root, String suffix, List<View> out) {
        if (root == null) {
            return;
        }
        if (root.getClass().getName().endsWith(suffix)) {
            out.add(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectByClassSuffix(g.getChildAt(i), suffix, out);
            }
        }
    }

    private static TextView findTextContaining(View root, String key) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().contains(key)) {
                return (TextView) root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView r = findTextContaining(g.getChildAt(i), key);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void collectTextContaining(View root, String key, List<TextView> out) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().contains(key)) {
                out.add((TextView) root);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTextContaining(g.getChildAt(i), key, out);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 页面组件扫描：给面板列出「可以单独隐藏的块」                            */
    /* ------------------------------------------------------------------ */

    /** 返回若干组 {label, rule}：label 给人看，rule 可直接进清单。 */
    public static List<String[]> scanBlocks(Activity a) {
        List<String[]> out = new ArrayList<>();
        if (!isHost(a)) {
            return out;
        }
        View decor = a.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return out;
        }
        int[] size = screenSize(a, decor);
        List<View> blocks = new ArrayList<>();
        collectBlocks((ViewGroup) decor, size, blocks, 0);

        // 类名出现次数：同名多份时更倾向于用 id / 文案定位
        List<String> classNames = new ArrayList<>();
        for (View v : blocks) {
            classNames.add(v.getClass().getName());
        }
        for (View v : blocks) {
            String rule = ruleOf(a, v, classNames);
            if (rule == null) {
                continue;
            }
            out.add(new String[]{labelOf(a, v, size), rule});
        }
        return out;
    }

    /**
     * 逐层下钻找「块」：
     *   - 整屏容器 / 纯结构性容器（无文案、不可点、无背景、无 id）继续往里钻；
     *   - 对用户有意义的（有文案 / 可点 / 有 id / 有稳定类名）才记一条。
     *     但记录后**继续往里钻**（放宽面积门槛）：右侧互动栏、顶部导航这类
     *     容器自己带包装层（外层 FrameLayout 会借子节点文案过审），如果就此
     *     停止下钻，里面的单个按钮（关注/收藏/评论/点赞/分享、漫剧/真人剧
     *     标签）就永远扫不出来 —— 这正是清单想要的可单独控制粒度。
     */
    private static void collectBlocks(ViewGroup g, int[] size, List<View> out, int depth) {
        collectBlocks(g, size, out, depth, 0.004f);
    }

    /** minArea：记录一个块的最小面积占比。顶层 0.004 挡噪点；
     *  钻进已记录块的内部后放宽到 0.002，单个互动按钮（约 0.009）、
     *  顶部标签（约 0.004）都在 0.002 之上。 */
    private static void collectBlocks(ViewGroup g, int[] size, List<View> out, int depth, float minArea) {
        if (depth > 40 || out.size() > 120) {
            return;
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getWidth() <= 0 || c.getHeight() <= 0 || !c.isShown()) {
                continue;
            }
            if (c.getTag(TAG_ORIG) != null) {
                continue;   // 已被模块控制过，不再作为候选
            }
            String cn = c.getClass().getName();
            if (cn.startsWith("android.view.ViewStub")) {
                continue;
            }
            double area = (double) c.getWidth() * c.getHeight() / ((double) size[0] * size[1]);
            if (area >= 0.85) {
                if (c instanceof ViewGroup) {
                    collectBlocks((ViewGroup) c, size, out, depth + 1, minArea);
                }
                continue;
            }
            String text = firstText(c, 12);
            String idName = idNameOf(c);
            boolean clickable = c.isClickable();
            boolean hasBg = false;
            try {
                hasBg = c.getBackground() != null;
            } catch (Throwable ignored) {
            }
            boolean meaningful = clickable || text != null
                    || (area >= 0.02 && !isFrameworkClass(cn));
            boolean structural = (c instanceof ViewGroup) && !clickable && text == null
                    && idName == null && !hasBg;

            if (structural) {
                collectBlocks((ViewGroup) c, size, out, depth + 1, minArea);
                continue;
            }
            if (area >= minArea && meaningful) {
                out.add(c);
                // 块已记录，但继续下钻（放宽门槛）列出里面的可单独控制项；
                // out.size() 上限在上面兜底，防止复杂页面把清单撑爆。
                if (c instanceof ViewGroup) {
                    collectBlocks((ViewGroup) c, size, out, depth + 1, 0.002f);
                }
                continue;
            }
            if (c instanceof ViewGroup) {
                collectBlocks((ViewGroup) c, size, out, depth + 1, minArea);
            }
        }
    }

    private static boolean isFrameworkClass(String cn) {
        return cn.startsWith("android.") || cn.startsWith("androidx.")
                || cn.startsWith("com.facebook.") || cn.startsWith("com.airbnb.");
    }

    private static String idNameOf(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) {
                return null;
            }
            String rn = v.getResources().getResourceName(id);
            if (rn == null) {
                return null;
            }
            int slash = rn.indexOf('/');
            return slash >= 0 ? rn.substring(slash + 1) : rn;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String labelOf(Activity a, View v, int[] size) {
        StringBuilder sb = new StringBuilder(displayClass(v.getClass().getName()));
        String idName = idNameOf(v);
        if (idName != null) {
            sb.append(" #").append(idName);
        }
        String txt = firstText(v, 10);
        if (txt != null && !txt.isEmpty()) {
            sb.append(" · ").append(txt);
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int cy = loc[1] + v.getHeight() / 2;
        String band = cy < size[1] / 3 ? "上" : (cy < size[1] * 2 / 3 ? "中" : "下");
        sb.append(" · ").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" · ").append(band);
        return sb.toString();
    }

    /**
     * 生成一条可复现的规则。优先用有辨识度的类名，其次资源 id，再其次文案；
     * 都拿不到可靠标识（例如纯混淆的 1~2 字类名且无 id）就不列出来，
     * 免得生成 `cls:s` 这种会误伤一大片的规则。
     */
    private static String ruleOf(Activity a, View v, List<String> classNames) {
        String cn = v.getClass().getName();
        String simple = simpleName(cn);
        int same = 0;
        for (String x : classNames) {
            if (x.equals(cn)) {
                same++;
            }
        }
        boolean framework = isFrameworkClass(cn);
        boolean obfuscated = simple.length() <= 2 || cn.indexOf('.') < 0;
        if (!framework && !obfuscated && same <= 1) {
            return "cls:" + cn;
        }
        String idName = idNameOf(v);
        if (idName != null && !idName.isEmpty()) {
            return "id:" + idName;
        }
        String txt = firstText(v, 12);
        if (txt != null && !txt.isEmpty()) {
            return "@" + txt;
        }
        if (!framework && !obfuscated) {
            return "cls:" + cn;
        }
        return null;
    }

    private static String firstText(View v, int max) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                String s = t.toString().trim();
                if (!s.isEmpty()) {
                    return s.length() > max ? s.substring(0, max) : s;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String s = firstText(g.getChildAt(i), max);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String simpleName(String full) {
        int dot = full.lastIndexOf('.');
        return dot >= 0 ? full.substring(dot + 1) : full;
    }

    /**
     * 标签用的类名。普通类用简名即可；混淆短名（simple ≤ 2 字符，如
     * ssbiz.collect.ui.b / impl.rightview.b）取最后 3 段，
     * 让 Names 能靠包名里的 collect / like / rightview.b 认出是哪个按钮。
     */
    private static String displayClass(String cn) {
        String s = simpleName(cn);
        if (s.length() > 2 || cn.indexOf('.') < 0) {
            return s;
        }
        String[] seg = cn.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, seg.length - 3); i < seg.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(seg[i]);
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 长按入口 + 双击打开评论区                                            */
    /* ------------------------------------------------------------------ */

    public static boolean onTouch(Activity a, MotionEvent ev) {
        if (!isHost(a)) {
            return false;
        }
        // 清屏空闲检测：任何按下/移动都刷新计时，按下时立刻恢复空闲隐藏的控件
        int idleAct = ev.getActionMasked();
        if (idleAct == MotionEvent.ACTION_DOWN || idleAct == MotionEvent.ACTION_MOVE) {
            LAST_TOUCH.put(a, SystemClock.uptimeMillis());
            restoreIdleHidden(a);
        }
        if (Boolean.TRUE.equals(CONSUME.get(a))) {
            if (Boolean.TRUE.equals(SWALLOW.get(a))) {
                // 这是我们主动吞掉的那次点击：仍要参与连击判定
                countTaps(a, ev);
            }
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                CONSUME.put(a, Boolean.FALSE);
                SWALLOW.put(a, Boolean.FALSE);
            }
            return true;
        }
        if (!Config.entryEnabled(a)) {
            return false;
        }
        countTaps(a, ev);
        detector(a).onTouchEvent(ev);
        return Boolean.TRUE.equals(CONSUME.get(a));
    }

    private static boolean isLandscape(Activity a) {
        try {
            return a.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 是否需要吞掉触发前的点击：只吞 **横屏** —— 横屏的连击是三击，
     * 前两击放行会触发宿主的「双击暂停」，所以整段吞掉。
     *
     * 竖屏两个页面都**不吞**（7.3.9.32 实测）：
     *   首页：吞第二次点击会让宿主把第一击当「单击确认」→ 直接跳转播放页，评论区就没了；
     *         双击点赞由 onDoubleTap hook（fullscreen.i$d + social.ui.t）直接屏蔽。
     *   二级页：单击视频 = 暂停/恢复切换。吞第二次会让 tap1 的「暂停」留在屏幕上
     *         （打开评论区但视频停住）；放行 tap2 则 tap1 暂停 → tap2 恢复，净效果=播放中。
     */
    private static boolean needSwallow(Activity a) {
        return isLandscape(a);
    }

    /**
     * 连击计数：竖屏双击、横屏三击触发「打开评论区」。
     * 触发前的那几次点击会把整个手势吞掉，宿主收不到完整的连击，
     * 它的「双击点赞」「双击暂停」就都不会发生。
     */
    private static void countTaps(Activity a, MotionEvent ev) {
        if (!Config.doubleTapComment(a)) {
            return;
        }
        Tap t = TAPS.get(a);
        if (t == null) {
            t = new Tap();
            TAPS.put(a, t);
        }
        int action = ev.getActionMasked();
        boolean land = isLandscape(a);
        long gapLimit = land ? 520L : 360L;
        int need = land ? 3 : 2;

        if (action == MotionEvent.ACTION_DOWN) {
            long now = ev.getEventTime();
            if (Config.doubleTapSwallow(a) && needSwallow(a)
                    && t.count >= 1 && now - t.lastUp <= gapLimit) {
                t.swallow = true;
                CONSUME.put(a, Boolean.TRUE);
                SWALLOW.put(a, Boolean.TRUE);
                logFile("swallow 2nd tap on " + a.getClass().getSimpleName());
            } else {
                t.swallow = false;
            }
            t.downX = ev.getX();
            t.downY = ev.getY();
            t.downTime = ev.getDownTime();
            return;
        }
        if (action != MotionEvent.ACTION_UP) {
            if (action == MotionEvent.ACTION_CANCEL) {
                t.count = 0;
                t.swallow = false;
            }
            return;
        }
        boolean swallowed = t.swallow;
        t.swallow = false;
        // 只认「轻点」：按住时间短、位移小
        long duration = ev.getEventTime() - t.downTime;
        float dx = Math.abs(ev.getX() - t.downX);
        float dy = Math.abs(ev.getY() - t.downY);
        if (duration > 260L || dx > 40f || dy > 40f) {
            t.count = 0;
            return;
        }

        long now = ev.getEventTime();
        if (t.count > 0 && now - t.lastUp <= gapLimit) {
            t.count++;
        } else {
            t.count = 1;
        }
        t.lastUp = now;
        if (t.count >= need) {
            t.count = 0;
            logFile("tap x" + need + " landscape=" + land + " swallowed=" + swallowed
                    + " act=" + a.getClass().getSimpleName());
            openComment(a);
            if (a.getClass().getName().endsWith("MainFragmentActivity")) {
                // 首页信息流：tap1 已被宿主当「单击暂停」，补一击恢复播放
                //（tap2 被宿主的双击检测吸收，不会自己恢复）
                final float ix = ev.getX();
                final float iy = ev.getY();
                UI.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        injectTap(a, ix, iy);
                    }
                }, 400L);
            }
        }
    }

    /** 往宿主注入一次轻点（DOWN+UP），用于恢复被 tap1 暂停的信息流视频。 */
    private static void injectTap(Activity a, float x, float y) {
        try {
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
            a.dispatchTouchEvent(down);
            a.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            logFile("feed: resume tap injected");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 入口手势：长按底部导航栏的「首页」那一格。
     * 落在该格内才唤出面板，其它位置的长按完全交还宿主。
     */
    private static GestureDetector detector(final Activity a) {
        GestureDetector gd = DETECTORS.get(a);
        if (gd != null) {
            return gd;
        }
        gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (!isHomeTab(a, e.getRawX(), e.getRawY())
                        && !isTopCenter(a, e.getRawX(), e.getRawY())) {
                    return;
                }
                CONSUME.put(a, Boolean.TRUE);
                showPanel(a);
            }
        });
        DETECTORS.put(a, gd);
        return gd;
    }

    /**
     * 顶部中央区域（左右各留 30%，高度 8%）长按也能唤出面板。
     * 二级界面没有底部 tab 栏，只能靠这个入口。
     */
    private static boolean isTopCenter(Activity a, float x, float y) {
        try {
            View decor = a.getWindow().getDecorView();
            int w = decor.getWidth();
            int h = decor.getHeight();
            if (w <= 0 || h <= 0) {
                w = a.getResources().getDisplayMetrics().widthPixels;
                h = a.getResources().getDisplayMetrics().heightPixels;
            }
            return x >= w * 0.30f && x <= w * 0.70f && y >= 0 && y <= h * 0.08f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 判断坐标是否落在底部 tab 栏「首页」那一格内。 */
    private static boolean isHomeTab(Activity a, float x, float y) {
        try {
            View bar = a.getWindow().getDecorView();
            if (!(bar instanceof ViewGroup)) {
                return false;
            }
            View item = findHomeTabItem((ViewGroup) bar);
            if (item == null || item.getWidth() <= 0) {
                return false;
            }
            int[] loc = new int[2];
            item.getLocationOnScreen(loc);
            // 手指有接触面积，纵向给一点容差
            float pad = item.getHeight() * 0.35f;
            return x >= loc[0] - pad && x <= loc[0] + item.getWidth() + pad
                    && y >= loc[1] - pad && y <= loc[1] + item.getHeight() + pad;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在视图树里找到底部 tab 栏（BottomTabBarLayout）并返回它的第一个格子。 */
    private static View findHomeTabItem(ViewGroup root) {
        ViewGroup barLayout = null;
        ViewGroup frame = null;
        List<ViewGroup> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty() && barLayout == null) {
            ViewGroup g = queue.remove(0);
            String cn = g.getClass().getName();
            if (cn.endsWith("BottomTabBarLayout")) {
                barLayout = g;
                break;
            }
            if (cn.endsWith("BottomTabFrameLayout")) {
                frame = g;
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof ViewGroup) {
                    queue.add((ViewGroup) c);
                }
            }
        }
        ViewGroup target = barLayout != null ? barLayout : frame;
        if (target == null || target.getChildCount() == 0) {
            return null;
        }
        View first = target.getChildAt(0);
        return first != null && first.getWidth() > 0 ? first : null;
    }

    private static void showPanel(final Activity a) {
        if (SettingsPanel.isShowing()) {
            return;
        }
        try {
            SettingsPanel.show(a);
        } catch (Throwable t) {
            Log.e(TAG, "show panel failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 双击打开评论区                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 评论区入口的候选类名后缀。
     * 首页与播放页的右侧互动栏都是同一个 ShortSeriesRightView，
     * 其中「评论」这一格是混淆类 impl.rightview.b（它的可点击子节点才是真正响应点击的）。
     */
    private static final String[] COMMENT_CLASSES = {
            "impl.rightview.b",
            "comment.view.CommentEntranceView",
            "impl.comment.view.CommentEntranceView",
            // 横屏全屏底部动作行里的「评论」格（同排是 点赞 DiggLayout / 收藏 CollectLayout /
            // 弹幕 / 倍速 1.0x / 清晰度 720P / 选集）。竖屏没有这一行，
            // 所以放在最后，不会改变竖屏既有行为。
            "toplayer.CommentLayout",
            "CommentLayout",
    };

    public static void openComment(Activity a) {
        if (!isHost(a)) {
            return;
        }
        try {
            View decor = a.getWindow().getDecorView();
            if (decor == null) {
                return;
            }
            boolean land = isLandscape(a);
            View clickable = null;

            // 1) 有 contentDescription 就直接用（最稳）
            View target = findByDescription(decor, "评论");
            if (target != null) {
                clickable = nearestClickable(target);
            }
            // 2) 按类名找评论区入口那一格
            if (clickable == null) {
                clickable = findCommentByClass(a, decor, false);
            }
            // 2b) 横屏控制栏常常整层隐藏，放宽可见性再找一次
            if (clickable == null && land) {
                clickable = findCommentByClass(a, decor, true);
            }
            // 3) 兜底：右侧那一列可点击控件的第 2 个（收藏 / 评论 / 点赞 / 分享）
            //
            //    这条兜底只在**竖屏**成立。横屏是全屏铺开的横向布局，右侧那一列
            //    并不存在，按屏幕坐标筛出来的全是选集/音量/倍速/清晰度这类无关控件 ——
            //    实测会点到 720P 那一格，点开的是分辨率面板。
            //    尤其注意：这类控件**自身** vis=VISIBLE，只是祖先层被隐藏，
            //    所以任何「按可见性筛」的宽泛兜底在横屏都不可信。横屏宁可不点。
            if (clickable == null && !land) {
                clickable = guessRightColumn(decor, 1);
            }
            if (clickable == null) {
                logFile("comment target not found landscape=" + land);
                // 落一份当时的视图树：入口规则靠这个补，别靠猜。
                // 同一页面只落一次，免得反复重写同一个文件。
                if (MISS_DUMPED.add(a.getClass().getName() + (land ? "#L" : "#P"))) {
                    logTree(a);
                }
                return;
            }
            clickable.performClick();
            logFile("comment clicked -> " + clickable.getClass().getName());
        } catch (Throwable t) {
            logFile("openComment failed: " + t);
        }
    }

    /** 视图是否真的在当前屏幕内（预 inflation 的页外实例会被这个条件排除）。 */
    private static boolean onScreen(View v, Activity a) {
        if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int h = a.getResources().getDisplayMetrics().heightPixels;
        return loc[1] + v.getHeight() > 0 && loc[1] < h;
    }

    /**
     * 按类名找「评论」那一格。
     *
     * @param allowHidden 放宽可见性：只要该视图自身是 VISIBLE 且尺寸正常就算命中，
     *                    不管祖先是不是被隐藏。横屏控制栏会整层隐藏 —— 快速连击时
     *                    宿主的 onSingleTapConfirmed 根本不会触发，控制栏就不会显示，
     *                    但这类视图的 {@code performClick()} 依然有效（它不检查可见性）。
     */
    private static View findCommentByClass(Activity a, View decor, boolean allowHidden) {
        for (String suffix : COMMENT_CLASSES) {
            for (View v : collectByClassSuffix(decor, suffix)) {
                if (allowHidden) {
                    if (v.getVisibility() != View.VISIBLE
                            || v.getWidth() <= 0 || v.getHeight() <= 0) {
                        continue;
                    }
                } else if (!onScreen(v, a)) {
                    continue;
                }
                View c = firstClickable(v);
                if (c != null) {
                    return c;
                }
            }
        }
        return null;
    }

    /** 自己可点击就返回自己，否则返回子树里第一个可点击的子节点。 */
    private static View firstClickable(View v) {
        if (v.isClickable()) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getWidth() > 0 && c.getHeight() > 0) {
                    View r = firstClickable(c);
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        return null;
    }

    private static View nearestClickable(View v) {
        View cur = v;
        while (cur != null && !cur.isClickable()) {
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return cur != null ? cur : v;
    }

    private static View findByDescription(View root, String key) {
        CharSequence cd = root.getContentDescription();
        if (cd != null && cd.toString().contains(key)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByDescription(g.getChildAt(i), key);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 兜底：取屏幕右侧那一列可点击控件里的第 index 个（0=收藏 1=评论 2=点赞 3=分享）。 */
    private static View guessRightColumn(View root, int index) {
        final List<View> cands = new ArrayList<>();
        collectRightClickable(root, cands);
        Collections.sort(cands, new Comparator<View>() {
            @Override
            public int compare(View o1, View o2) {
                int[] a1 = new int[2];
                int[] a2 = new int[2];
                o1.getLocationOnScreen(a1);
                o2.getLocationOnScreen(a2);
                return Integer.compare(a1[1], a2[1]);
            }
        });
        if (cands.size() > index) {
            return cands.get(index);
        }
        return null;
    }

    private static void collectRightClickable(View root, List<View> out) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) root;
        int w = g.getWidth();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getWidth() <= 0) {
                continue;
            }
            // 左右位置要用屏幕坐标：嵌套子视图的 getLeft 是相对父级的
            int[] loc = new int[2];
            c.getLocationOnScreen(loc);
            int sw = root.getResources().getDisplayMetrics().widthPixels;
            boolean rightSide = loc[0] > sw * 0.70 && c.getWidth() < sw * 0.35;
            if (c.isClickable() && rightSide && containsTextView(c)) {
                out.add(c);
            }
            collectRightClickable(c, out);
        }
    }

    private static boolean containsTextView(View v) {
        if (v instanceof TextView) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsTextView(g.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* 调试：把视图树写进文件（真机 logcat 被屏蔽，所以同时落盘）              */
    /* ------------------------------------------------------------------ */

    public static void logTree(Activity a) {
        View decor = a.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        appendTree(decor, 0, sb);
        for (String line : sb.toString().split("\n")) {
            Log.i(TAG, "TREE " + line);
        }
        try {
            File dir = a.getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, "rgtools_tree.txt");
                FileOutputStream fos = new FileOutputStream(f, false);
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.close();
                logFile("tree written: " + f.getAbsolutePath() + " views="
                        + sb.toString().split("\n").length);
            }
        } catch (Throwable t) {
            Log.e(TAG, "write tree failed", t);
        }
    }

    private static void appendTree(View v, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getName());
        try {
            sb.append(" [").append(v.getLeft()).append(',').append(v.getTop())
                    .append(' ').append(v.getWidth()).append('x').append(v.getHeight()).append(']');
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            sb.append(" @(").append(loc[0]).append(',').append(loc[1]).append(')');
        } catch (Throwable ignored) {
        }
        if (!v.isShown()) {
            sb.append(" !shown");
        }
        if (v.getVisibility() != View.VISIBLE) {
            sb.append(" vis=").append(v.getVisibility());
        }
        if (v.getAlpha() < 1f) {
            sb.append(" alpha=").append(v.getAlpha());
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                sb.append(" text=").append(t);
            }
        }
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String rn = v.getResources().getResourceName(id);
                if (rn != null) {
                    sb.append(" id=").append(rn);
                }
            }
        } catch (Throwable ignored) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) {
            sb.append(" cd=").append(cd);
        }
        if (v.isClickable()) {
            sb.append(" clickable");
        }
        sb.append('\n');
        if (depth < 30 && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                appendTree(g.getChildAt(i), depth + 1, sb);
            }
        }
    }
}
