package com.wodi.redguotools;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「ReadGuo 设置」入口塞进宿主的播放设置弹层（清晰度 / 不感兴趣 /
 * 清屏播放 / 弹幕设置…那一整块）。
 *
 * <p>这个弹层在一级（feed）与二级（播放页）界面都会出现，且实现方式
 * 不明朗 —— 可能是 Dialog / PopupWindow，也可能直接挂在 Activity 的
 * DecorView 上。所以这里不猜类名，改成<b>按特征行文案识别</b>：
 * 弹层里稳定出现「不感兴趣 / 清屏播放 / 弹幕设置 / 下载到本地 / 字体大小 /
 * 定时关闭 / 识图找同款」等整行文案，命中足够多条即认定是它，然后把
 * 一行入口追加到承载这些行的容器末尾。
 *
 * <p>两条硬约束：
 * <ul>
 *   <li>只在竖屏做 —— 横屏用户明确不需要；</li>
 *   <li>绝不往 RecyclerView / ListView 里 addView（子视图由适配器管理，
 *       乱加会崩），所以注入容器只挑「竖排 LinearLayout」。</li>
 * </ul>
 */
final class SheetEntry {

    /** 识别弹层用的特征行文案（与整行文字相等才算）。 */
    private static final String[] MARKERS = {
            "不感兴趣", "清屏播放", "弹幕设置", "下载到本地",
            "字体大小", "投屏", "定时关闭", "识图找同款",
    };

    /** 命中多少条不同的特征文案才认定是设置弹层。 */
    private static final int MIN_MARKERS = 3;

    /** 注入行的标记：同一个窗口里只放一行。 */
    private static final int TAG_INJECTED = 0x5A5A0003;

    private SheetEntry() {
    }

    /** 弹层刚 show 时往往还没布局完，隔一会儿多试几次。 */
    static void tryInjectLater(final Activity a, final View root) {
        if (a == null || root == null) {
            return;
        }
        for (int i = 1; i <= 3; i++) {
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        tryInject(a, root);
                    } catch (Throwable ignored) {
                    }
                }
            }, 150L * i);
        }
    }

    /** 立即尝试注入。可重复调用：去重、方向、开关检查都在最前面。 */
    static void tryInject(Activity a, View root) {
        if (a == null || root == null || a.isFinishing()) {
            return;
        }
        if (!Config.sheetEntry(a)) {
            return;
        }
        try {
            if (a.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE) {
                return;   // 横屏不做
            }
        } catch (Throwable ignored) {
        }
        if (hasInjected(root)) {
            return;
        }

        // 1) 收集特征行：文案所在 TextView → 所在那一行（最近的可点击祖先）
        List<View> rows = new ArrayList<>();
        int distinct = 0;
        for (String m : MARKERS) {
            List<TextView> found = new ArrayList<>();
            collectTextEquals(root, m, found);
            if (found.isEmpty()) {
                continue;
            }
            distinct++;
            for (TextView tv : found) {
                View row = nearestClickable(tv);
                if (row != null && !rows.contains(row)) {
                    rows.add(row);
                }
            }
        }
        if (distinct < MIN_MARKERS || rows.size() < MIN_MARKERS) {
            return;
        }

        // 2) 行的公共祖先 → 向上找一个安全的竖排 LinearLayout
        ViewGroup parent = injectionParent(commonAncestor(rows));
        if (parent == null) {
            UiController.logFile("sheet entry: container not found");
            return;
        }

        // 3) 注入
        parent.addView(buildRow(a), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        UiController.logFile("sheet entry injected into " + parent.getClass().getName());
    }

    /* ------------------------------------------------------------------ */

    private static void collectTextEquals(View v, String exact, List<TextView> out) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && exact.contentEquals(t.toString().trim())) {
                out.add((TextView) v);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTextEquals(g.getChildAt(i), exact, out);
            }
        }
    }

    /** 文案向上取「那一行」：优先可点击且有尺寸的祖先，爬不到就用文案自己。 */
    private static View nearestClickable(View v) {
        View cur = v;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable() && cur.getWidth() > 0) {
                return cur;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return v;
    }

    /** 所有视图共同的（最深的）祖先。 */
    private static View commonAncestor(List<View> views) {
        View cur = views.get(0);
        while (cur != null) {
            boolean all = true;
            for (int i = 1; i < views.size(); i++) {
                if (!isSelfOrDescendant(views.get(i), cur)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return cur;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    private static boolean isSelfOrDescendant(View v, View ancestor) {
        View cur = v;
        while (cur != null) {
            if (cur == ancestor) {
                return true;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /**
     * 从公共祖先向上找注入容器：跳过列表/滚动容器（AdapterView 与
     * isScrollContainer 的容器），只要一个竖排 LinearLayout。
     */
    private static ViewGroup injectionParent(View start) {
        View cur = start;
        while (cur != null) {
            if (cur instanceof LinearLayout && !(cur instanceof AdapterView)
                    && !cur.isScrollContainer()) {
                LinearLayout ll = (LinearLayout) cur;
                if (ll.getOrientation() == LinearLayout.VERTICAL) {
                    return ll;
                }
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    /** 入口行：⚙ ReadGuo 设置 ›，配色沿用模块调色板（白底/深底自适应）。 */
    private static View buildRow(final Activity a) {
        final Theme.Palette p = Theme.of(a);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Theme.dp(a, 54));
        int padX = Theme.dp(a, 16);
        row.setPadding(padX, Theme.dp(a, 10), padX, Theme.dp(a, 10));
        row.setBackground(Theme.pressable(a, p));

        TextView icon = new TextView(a);
        icon.setText("⚙");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        icon.setTextColor(p.body);
        row.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(a);
        title.setText("ReadGuo 设置");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(p.title);
        title.setPadding(Theme.dp(a, 12), 0, 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(a);
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        chevron.setTextColor(p.sub);
        row.addView(chevron, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setTag(TAG_INJECTED, Boolean.TRUE);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsPanel.show(a);
            }
        });
        return row;
    }

    private static boolean hasInjected(View v) {
        if (v.getTag(TAG_INJECTED) != null) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasInjected(g.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }
}
