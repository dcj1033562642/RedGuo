package com.wodi.redguotools;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块自身的入口：仿卡片式主页（状态 / 设备与构建 / 功能分类 / 技巧 / 关于与免责声明）。
 * 界面全部代码构建，不依赖任何资源文件。
 */
public class MainActivity extends Activity {

    private static final int C_BG = 0xFFEAF2FB;        // 页面浅蓝底
    private static final int C_CARD = 0xFFFFFFFF;      // 卡片白
    private static final int C_STATUS_BG = 0xFFB8D8F1; // 状态卡蓝
    private static final int C_STATUS_ICON = 0xFF2E6DA4;
    private static final int C_TITLE = 0xFF17324F;     // 深蓝标题
    private static final int C_SECTION = 0xFF5B87B5;   // 分区标题
    private static final int C_TEXT = 0xFF333333;
    private static final int C_SUB = 0xFF8A8A8A;
    private static final int C_ACCENT = 0xFF3A7ABF;
    private static final int C_NAV_BG = 0xFFF4F8FC;

    private static final String AUTHOR = "酷安@残話";
    private static final String GITHUB = "github.com/staciepomar-spec/RedGuo";

    private LinearLayout mNav;
    private TextView[] mTabs;
    private View[] mPages;
    private int mTabActive = 0xFF3A7ABF;
    private int mTabIdle = 0xFF9AAABB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        setContentView(root);

        /* ---------- 顶部大标题 ---------- */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(24), dp(32), dp(24), dp(18));
        root.addView(head);
        TextView title = new TextView(this);
        title.setText("红果工具");
        title.setTextSize(30);
        title.setTextColor(C_TITLE);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title);
        TextView sub = new TextView(this);
        sub.setText("红果免费短剧 · 界面工具模块 · 仅供学习交流");
        sub.setTextSize(13);
        sub.setTextColor(C_SECTION);
        sub.setPadding(0, dp(4), 0, 0);
        head.addView(sub);

        /* ---------- 内容区：四个分类页 ---------- */
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mPages = new View[]{homePage(), featurePage(), tipsPage(), aboutPage()};
        for (int i = 0; i < mPages.length; i++) {
            content.addView(mPages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mPages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        }

        /* ---------- 底部导航 ---------- */
        mNav = new LinearLayout(this);
        mNav.setOrientation(LinearLayout.HORIZONTAL);
        mNav.setBackgroundColor(C_NAV_BG);
        mNav.setPadding(dp(8), dp(10), dp(8), dp(14));
        String[] names = {"主页", "功能", "技巧", "关于"};
        mTabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView tab = new TextView(this);
            tab.setText(names[i]);
            tab.setTextSize(14);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(6), 0, dp(6), 0);
            tab.setBackground(rounded(12, 0xFFFFFFFF));
            mNav.addView(tab, lp);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(idx);
                }
            });
            mTabs[i] = tab;
        }
        root.addView(mNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        select(0);

        Log.i(RGModule.TAG, "MainActivity created");
    }

    private void select(int idx) {
        for (int i = 0; i < mPages.length; i++) {
            mPages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < mTabs.length; i++) {
            mTabs[i].setTextColor(i == idx ? mTabActive : mTabIdle);
            mTabs[i].setTypeface(null, i == idx ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /* ================= 主页：状态 + 设备与构建 ================= */

    private View homePage() {
        ScrollView sv = scroll();
        LinearLayout col = column(sv);

        /* 状态卡 */
        LinearLayout status = card(col);
        status.setBackgroundColor(C_STATUS_BG);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        status.addView(row);
        TextView icon = new TextView(this);
        icon.setText("✓");
        icon.setTextSize(22);
        icon.setTextColor(0xFFFFFFFF);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(20, C_STATUS_ICON));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout vcol = new LinearLayout(this);
        vcol.setOrientation(LinearLayout.VERTICAL);
        vcol.setPadding(dp(16), 0, 0, 0);
        row.addView(vcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView st = new TextView(this);
        st.setText("模块已安装");
        st.setTextSize(19);
        st.setTextColor(C_TITLE);
        st.setTypeface(null, Typeface.BOLD);
        vcol.addView(st);
        TextView ver = new TextView(this);
        ver.setText(moduleVersion());
        ver.setTextSize(13);
        ver.setTextColor(0xFF41638A);
        vcol.addView(ver);
        TextView api = new TextView(this);
        api.setText("API 102");
        api.setTextSize(13);
        api.setTextColor(C_TITLE);
        api.setTypeface(null, Typeface.BOLD);
        row.addView(api);

        colTitle(col, "设备与构建");
        LinearLayout dev = card(col);
        String honguo;
        try {
            PackageInfo p = getPackageManager().getPackageInfo("com.phoenix.read", 0);
            honguo = p.versionName;
        } catch (Throwable t) {
            honguo = "未安装";
        }
        row(dev, "红果版本", honguo);
        row(dev, "模块版本", moduleVersion());
        row(dev, "作者", AUTHOR);
        row(dev, "构建时间", buildTime());
        row(dev, "手机型号", Build.MANUFACTURER + " " + Build.MODEL);
        row(dev, "安卓版本", Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）");

        colTitle(col, "使用方式");
        LinearLayout use = card(col);
        text(use, "1. 在 LSPosed 中启用本模块，作用域勾选「红果免费短剧」\n"
                + "2. 强制停止红果后重新打开\n"
                + "3. 长按底部「首页」标签唤出设置面板（可在面板里改为长按屏幕顶部中央）\n"
                + "4. 面板里所有改动即时生效，无需重启红果");
        return sv;
    }

    /* ================= 功能页：分类展示 ================= */

    private View featurePage() {
        ScrollView sv = scroll();
        LinearLayout col = column(sv);

        colTitle(col, "界面显示");
        LinearLayout c1 = card(col);
        text(c1, "· 状态栏：隐藏 / 纯黑 / 纯白 / 跟随背景，点击可临时显示数字时间\n"
                + "· 组件显隐：11 个常用组件（顶栏 / 右侧互动 / 底部条 / 标题 / 热评…）逐项开关\n"
                + "· 自定义隐藏清单：「扫描当前页面」自动列出，或手动加规则"
                + "（cls: / @文案 / text: / id:）\n"
                + "· 底部标签栏：按标签文字勾选隐藏，剩余标签自动摊满整行\n"
                + "· 叠加透明度：全局系数 × 各项独立，双层叠加");

        colTitle(col, "播放器");
        LinearLayout c2 = card(col);
        text(c2, "· 默认最高画质：起播自动选最高可用档，弹层清晰度会如实显示 1080\n"
                + "· 自定义倍速：1.0 ~ 4.0 倍无级调节（0.1 步进），不依赖宿主档位\n"
                + "· 改动对下一个起播的视频生效，连播 / 重进页面即可看到");

        colTitle(col, "交互与自动化");
        LinearLayout c3 = card(col);
        text(c3, "· 双击屏幕直接打开评论区（横屏三击），可吞掉第二次点击避免误触点赞\n"
                + "· 清屏省心：清屏状态下 10 秒不碰屏幕，自动收起其余控件只留视频，触摸立即恢复");

        colTitle(col, "小提示");
        LinearLayout c4 = card(col);
        text(c4, "· 清屏的正确入口：长按视频 → 播放设置 → 清屏播放\n"
                + "· 所有隐藏都是清单制：不勾就默认显示\n"
                + "· 「扫描当前页面」要先停在有目标组件的页面再扫");
        return sv;
    }

    /* ================= 技巧页 ================= */

    private View tipsPage() {
        ScrollView sv = scroll();
        LinearLayout col = column(sv);
        colTitle(col, "不容易发现的点");
        LinearLayout c = card(col);
        text(c, "1. 全局透明度和单项透明度是「乘法」：生效值 = 该项 × 全局；"
                + "只想微调某一项时把全局调回 100。\n\n"
                + "2. 隐藏浮层用「隐形」保留占位，界面不会塌；底部标签栏是唯一例外——"
                + "它要「腾地方」，隐藏后剩余标签自动摊满，这是刻意的。\n\n"
                + "3. 清屏的正确入口：长按视频 → 播放设置 → 清屏播放。"
                + "（选集条右侧那个方块图标不是清屏。）\n\n"
                + "4. 扫描清单里混淆类名只显示末段（如 collect.ui.b），配合 @文案 辨认。\n\n"
                + "5. 双击怕误触点赞？开「双击时吞掉第二次点击」。\n\n"
                + "6. 自检与排错：/sdcard/Android/data/com.phoenix.read/files/ 下的\n"
                + "    rgtools_log.txt（功能命中记录）\n"
                + "    rgtools_tree.txt（进页面 1.8 秒后的视图树快照）");
        return sv;
    }

    /* ================= 关于页 ================= */

    private View aboutPage() {
        ScrollView sv = scroll();
        LinearLayout col = column(sv);

        colTitle(col, "关于");
        LinearLayout a = card(col);
        row(a, "模块名称", "ReadGuo（红果工具）");
        row(a, "作者", AUTHOR);
        row(a, "开源地址", GITHUB);
        row(a, "作用域", "红果免费短剧（com.phoenix.read）");
        row(a, "运行框架", "LSPosed / libxposed API 102");

        colTitle(col, "免责声明");
        LinearLayout d = card(col);
        text(d, "· 本模块仅供学习交流与个人技术研究使用，请勿用于任何商业或非法用途。\n\n"
                + "· 本模块不修改、不破解、不绕过任何付费、会员或版权保护机制；"
                + "所有功能仅调整本机的界面显示与个人偏好设置。\n\n"
                + "· 请在遵守法律法规及平台用户协议的前提下使用；请支持正版，"
                + "相关内容、商标与软件版权归原作者及平台所有。\n\n"
                + "· 本模块按「现状」提供，不附带任何明示或默示的担保；"
                + "因下载、安装或使用本模块而产生的一切后果，由使用者自行承担。\n\n"
                + "· 若本模块侵犯了相关方的合法权益，请联系作者删除。");
        return sv;
    }

    /* ================= 视图工具 ================= */

    private ScrollView scroll() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, dp(24));
        return sv;
    }

    private LinearLayout column(ScrollView sv) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(8), dp(20), 0);
        sv.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    /** 白色圆角卡片。 */
    private LinearLayout card(LinearLayout col) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        card.setBackground(rounded(16, C_CARD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        col.addView(card, lp);
        return card;
    }

    private void colTitle(LinearLayout col, String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setTextColor(C_SECTION);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(4), dp(4), 0, dp(8));
        col.addView(tv);
    }

    /** 卡片内「标签 + 值」两行。 */
    private void row(LinearLayout card, String label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(C_TITLE);
        l.setTypeface(null, Typeface.BOLD);
        card.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(C_SUB);
        v.setPadding(0, dp(1), 0, dp(10));
        card.addView(v);
    }

    private void text(LinearLayout card, String body) {
        TextView v = new TextView(this);
        v.setText(body);
        v.setTextSize(14);
        v.setTextColor(C_TEXT);
        v.setLineSpacing(dp(2), 1f);
        card.addView(v);
    }

    private GradientDrawable rounded(int radiusDp, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        return d;
    }

    private String moduleVersion() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return p.versionName + " (" + p.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private String buildTime() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return new SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
                    .format(new Date(p.lastUpdateTime));
        } catch (Throwable t) {
            return "?";
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
