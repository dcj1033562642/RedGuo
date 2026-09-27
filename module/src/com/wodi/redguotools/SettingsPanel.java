package com.wodi.redguotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 长按唤出的模块设置面板。纯代码构建 UI，不依赖任何资源与第三方库。
 *
 * <p>视觉全部走 {@link Theme}：浅色底 + 圆角描边卡片 + 按功能分组。
 * 功能逻辑与旧版完全一致，仅外观与信息分组改变。
 */
public final class SettingsPanel {

    private static final String TAG = RGModule.TAG;
    private static android.app.Dialog sDialog;

    private SettingsPanel() {
    }

    public static boolean isShowing() {
        return sDialog != null && sDialog.isShowing();
    }

    public static void show(final Activity a) {
        if (isShowing()) {
            return;
        }
        final Theme.Palette p = Theme.of(a);

        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Theme.sheet(a, p));
        int pad = Theme.dp(a, 14);
        root.setPadding(pad, pad, pad, pad);

        TextView title = Theme.title(a, p, "ReadGuo");
        root.addView(title);

        TextView hint = Theme.hint(a, p, "长按底部「首页」或顶部正中唤出 · 改动即时生效");
        hint.setPadding(0, Theme.dp(a, 4), 0, Theme.dp(a, 2));
        root.addView(hint);

        /* ---------- 卡片：状态栏 ---------- */
        LinearLayout cardSb = addCard(a, p, root, "sb", "状态栏", false);

        Switch swHide = Theme.makeSwitch(a, p, "隐藏系统状态栏", Config.hideStatusBar(a));
        swHide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.set(a, "sb_hide", v);
                UiController.refresh(a);
            }
        });
        cardSb.addView(swHide);

        TextView bgLabel = Theme.value(a, p, "状态栏底色");
        cardSb.addView(bgLabel);

        final RadioGroup rg = new RadioGroup(a);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"跟随背景", "纯黑", "纯白"};
        int cur = Config.statusBarBg(a);
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(a);
            rb.setId(1000 + i);
            rb.setText(names[i]);
            rb.setTextColor(p.body);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            rb.setButtonTintList(ColorStateList.valueOf(p.primary));
            rb.setTag(i);
            rg.addView(rb);
            if (i == cur) {
                rb.setChecked(true);
            }
        }
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                View v = g.findViewById(id);
                if (v != null) {
                    Config.setInt(a, "sb_bg", (Integer) v.getTag());
                    UiController.refresh(a);
                }
            }
        });
        cardSb.addView(rg);

        /* 状态栏「点击显示数字」开关已移除 —— 该功能整体下线。 */

        /*
         * 全局叠加透明度：作用于「组件显隐」各项与自定义清单命中的组件，
         * 所以它独立成一张卡，避免让人误以为只管某类组件。
         */
        LinearLayout cardAlpha = addCard(a, p, root, "alpha", "叠加组件透明度", false);
        TextView alphaHint = Theme.hint(a, p, "统一乘在所有叠加组件上，与各项自己的透明度叠加生效");
        alphaHint.setPadding(0, 0, 0, Theme.dp(a, 2));
        cardAlpha.addView(alphaHint);

        final TextView alphaLabel = Theme.value(a, p, "全局透明度：" + Config.alphaPercent(a) + "%");
        cardAlpha.addView(alphaLabel);
        SeekBar sb = new SeekBar(a);
        sb.setMax(100);
        sb.setProgress(Config.alphaPercent(a));
        Theme.styleSeekBar(p, sb);
        sb.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                alphaLabel.setText("全局透明度：" + value + "%");
                if (fromUser) {
                    Config.setInt(a, "alpha", value);
                    UiController.refresh(a);
                }
            }
        });
        cardAlpha.addView(sb);

        /* ---------- 卡片：组件显隐 ---------- */
        LinearLayout cardSlots = addCard(a, p, root, "slots",
                "组件显隐 · " + Config.SLOT_KEYS.length + " 项", true);
        TextView slotsHint = Theme.hint(a, p, "默认全部显示，勾选「隐藏」才生效；透明度可逐项单独调");
        slotsHint.setPadding(0, 0, 0, Theme.dp(a, 2));
        cardSlots.addView(slotsHint);
        for (int i = 0; i < Config.SLOT_KEYS.length; i++) {
            addSlotRow(a, p, cardSlots, Config.SLOT_KEYS[i], Config.SLOT_NAMES[i]);
        }

        /* ---------- 卡片：底部标签栏 ---------- */
        LinearLayout cardBtab = addCard(a, p, root, "btab", "底部标签栏", true);
        TextView btabHint = Theme.hint(a, p, "勾选要隐藏的标签（如 我的、商城、赚钱），剩余标签自动摊满整行");
        btabHint.setPadding(0, 0, 0, Theme.dp(a, 4));
        cardBtab.addView(btabHint);
        String curBtab = Config.hiddenBottomTabs(a);
        TextView btabCur = Theme.hint(a, p,
                curBtab.isEmpty() ? "（当前全部显示）" : "已隐藏：" + curBtab.replace("|", "、"));
        btabCur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btabCur.setPadding(0, 0, 0, Theme.dp(a, 6));
        cardBtab.addView(btabCur);
        cardBtab.addView(Theme.filledButton(a, p, "选择要隐藏的标签", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bottomTabsDialog(a);
            }
        }));

        /* ---------- 卡片：底部导航栏 / 小白条隐藏 ---------- */
        LinearLayout cardNav = addCard(a, p, root, "navbar", "底部导航栏 / 小白条", true);
        TextView navHint = Theme.hint(a, p,
                "开启后，进入红果即隐藏导航栏；软件内全程不显示，"
                        + "只有退出红果时才会恢复");
        navHint.setPadding(0, 0, 0, Theme.dp(a, 4));
        cardNav.addView(navHint);

        Switch swNav = Theme.makeSwitch(a, p, "隐藏底部导航栏（小白条）", Config.autoHideNav(a));
        swNav.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.setAutoHideNav(a, v);
                UiController.refresh(a);
                rebuild(a);
            }
        });
        cardNav.addView(swNav);

        // 状态行：一眼看出开关是否已生效（面板能打开说明 App 在前台，正常应显示「已生效」）
        TextView navState = Theme.value(a, p, UiController.navBarEnableState());
        navState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        navState.setPadding(0, Theme.dp(a, 6), 0, 0);
        cardNav.addView(navState);

        // 诊断行：确认本机到底有没有可隐藏的小白条，避免开了开关却毫无反应时瞎猜
        TextView navStat = Theme.hint(a, p, UiController.navBarStatus(a));
        navStat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        navStat.setPadding(0, Theme.dp(a, 4), 0, 0);
        cardNav.addView(navStat);

        /* ---------- 卡片：播放画质 / 倍速 ---------- */
        LinearLayout cardPlay = addCard(a, p, root, "play", "播放画质 / 倍速", true);
        TextView playHint = Theme.hint(a, p, "改动对下一个起播的视频生效（连播/重进页面即可）");
        playHint.setPadding(0, 0, 0, Theme.dp(a, 4));
        cardPlay.addView(playHint);

        Switch swQ = Theme.makeSwitch(a, p, "默认最高画质", Config.maxQuality(a));
        swQ.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.setMaxQuality(a, v);
            }
        });
        cardPlay.addView(swQ);

        Switch swSp = Theme.makeSwitch(a, p, "自定义倍速（关闭 = 跟随宿主设置）",
                Config.speedCustomOn(a));
        swSp.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.setSpeedCustomOn(a, v);
            }
        });
        cardPlay.addView(swSp);

        final TextView speedLabel = Theme.value(a, p,
                "当前倍速：" + (Config.speedCustom(a) / 10f) + "x");
        cardPlay.addView(speedLabel);
        SeekBar sbSpeed = new SeekBar(a);
        sbSpeed.setMax(30);   // 10~40 => 1.0x~4.0x
        sbSpeed.setProgress(Config.speedCustom(a) - 10);
        Theme.styleSeekBar(p, sbSpeed);
        sbSpeed.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int x10 = value + 10;
                speedLabel.setText("当前倍速：" + (x10 / 10f) + "x");
                Config.setSpeedCustom(a, x10);
            }
        });
        cardPlay.addView(sbSpeed);

        /* ---------- 卡片：自定义隐藏清单 ---------- */
        LinearLayout cardCustom = addCard(a, p, root, "custom", "自定义隐藏清单", true);
        TextView rule = Theme.hint(a, p, Config.resolveHint());
        rule.setPadding(0, 0, 0, Theme.dp(a, 8));
        cardCustom.addView(rule);

        List<String[]> list = Config.overlayList(a);
        if (list.isEmpty()) {
            TextView empty = Theme.hint(a, p, "（清单为空，当前全部显示）");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(0, Theme.dp(a, 2), 0, Theme.dp(a, 6));
            cardCustom.addView(empty);
        }
        for (int i = 0; i < list.size(); i++) {
            addCustomRow(a, p, cardCustom, list, i);
        }

        LinearLayout tools = new LinearLayout(a);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(0, Theme.dp(a, 12), 0, 0);
        tools.addView(Theme.filledButton(a, p, "扫描当前页面", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanDialog(a);
            }
        }), weight(1.4f, 0));
        tools.addView(Theme.outlineButton(a, p, "手动添加", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addDialog(a);
            }
        }), weight(1f, 8));
        tools.addView(Theme.dangerButton(a, p, "清空", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Config.setOverlayList(a, new ArrayList<String[]>());
                UiController.refresh(a);
                rebuild(a);
            }
        }), weight(0.8f, 8));
        cardCustom.addView(tools);

        /* ---------- 卡片：手势 ---------- */
        LinearLayout cardGesture = addCard(a, p, root, "gesture", "手势", true);

        Switch swDt = Theme.makeSwitch(a, p, "双击打开评论区", Config.doubleTapComment(a));
        swDt.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.set(a, "dt_comment", v);
            }
        });
        cardGesture.addView(swDt);

        Switch swSwallow = Theme.makeSwitch(a, p, "双击时吞掉第二次点击（避免误触点赞）",
                Config.doubleTapSwallow(a));
        swSwallow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.set(a, "dt_swallow", v);
            }
        });
        cardGesture.addView(swSwallow);

        /* ---------- 卡片：面板唤出 ---------- */
        LinearLayout cardEntry = addCard(a, p, root, "entry", "面板唤出", true);
        Switch swEntry = Theme.makeSwitch(a, p, "长按底部「首页」唤出本面板", Config.entryEnabled(a));
        swEntry.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.set(a, "entry", v);
            }
        });
        cardEntry.addView(swEntry);

        Switch swSheet = Theme.makeSwitch(a, p, "在播放设置弹层里显示模块入口（竖屏）",
                Config.sheetEntry(a));
        swSheet.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.set(a, "sheet_entry", v);
            }
        });
        cardEntry.addView(swSheet);

        /* ---------- 底部操作 ---------- */
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, Theme.dp(a, 16), 0, 0);
        btns.addView(Theme.ghostButton(a, p, "输出视图树日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiController.logTree(a);
            }
        }), weight(1f, 0));
        btns.addView(Theme.filledButton(a, p, "关闭", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sDialog != null) {
                    sDialog.dismiss();
                }
            }
        }), weight(1f, 8));
        root.addView(btns);

        ScrollView sv = new ScrollView(a);
        sv.addView(root);

        // 上下留出 12dp：面板内容超高时 Dialog 会被撑到满屏，不垫这一层圆角就被裁没了
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(a);
        int vGap = Theme.dp(a, 12);
        wrapper.setPadding(0, vGap, 0, vGap);
        wrapper.addView(sv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(wrapper);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.94),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                sDialog = null;
            }
        });
        sDialog = d;
        try {
            d.show();
        } catch (Throwable t) {
            Log.e(TAG, "dialog show failed", t);
            sDialog = null;
        }
    }

    /* ---------------- 折叠卡片 ---------------- */

    /**
     * 新建一张可折叠的分组卡片并挂到 root，返回卡片的内容容器。
     *
     * <p>标题栏整行可点：折叠后只剩标题，一屏就能扫完所有分类。
     * 折叠状态按卡片 key 持久化 —— 用户折过一次，下次打开还是折的。
     */
    private static LinearLayout addCard(final Activity a, final Theme.Palette p,
                                        LinearLayout root, final String key, String title,
                                        boolean defaultFolded) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.card(a, p));
        int ip = Theme.dp(a, 14);
        card.setPadding(ip, 0, ip, 0);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = Theme.dp(a, 12);
        root.addView(card, cardLp);

        final LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, Theme.dp(a, 12));

        final TextView arrow = new TextView(a);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        arrow.setTextColor(p.sub);
        arrow.setPadding(Theme.dp(a, 10), 0, 0, 0);

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, Theme.dp(a, 12), 0, Theme.dp(a, 10));
        header.setBackground(Theme.pressable(a, p));
        header.addView(Theme.cardTitle(a, p, title),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final boolean[] folded = {Config.folded(a, key, defaultFolded)};
        applyFold(body, arrow, folded[0]);

        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                folded[0] = !folded[0];
                Config.setFolded(a, key, folded[0]);
                applyFold(body, arrow, folded[0]);
            }
        });

        return body;
    }

    private static void applyFold(LinearLayout body, TextView arrow, boolean folded) {
        body.setVisibility(folded ? View.GONE : View.VISIBLE);
        arrow.setText(folded ? "\u25BC" : "\u25B2");
    }

    /** 清单增删后重开面板（结构变了，直接重建比增量刷新简单可靠）。 */
    private static void rebuild(final Activity a) {
        if (sDialog != null) {
            sDialog.dismiss();
        }
        a.getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                show(a);
            }
        });
    }

    /* ---------------- 行构建 ---------------- */

    private static void addSlotRow(final Activity a, final Theme.Palette p, LinearLayout card,
                                   final String key, String name) {
        Switch sw = Theme.makeSwitch(a, p, "隐藏 " + name, Config.overlayHide(a, key));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Config.set(a, key + "_hide", v);
                UiController.refresh(a);
            }
        });
        card.addView(sw);

        // 所属组件已由上面那行开关点明，这里只留数值，避免同一名称重复两遍
        final TextView label = Theme.value(a, p, "透明度：" + Config.overlayAlpha(a, key) + "%");
        card.addView(label);

        SeekBar bar = new SeekBar(a);
        bar.setMax(100);
        bar.setProgress(Config.overlayAlpha(a, key));
        Theme.styleSeekBar(p, bar);
        bar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                label.setText("透明度：" + value + "%");
                if (fromUser) {
                    Config.setInt(a, key + "_alpha", value);
                    UiController.refresh(a);
                }
            }
        });
        card.addView(bar);
    }

    private static void addCustomRow(final Activity a, final Theme.Palette p, LinearLayout card,
                                     final List<String[]> list, final int index) {
        final String[] item = list.get(index);
        String[] n = Names.describe(item[0]);

        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, Theme.dp(a, 8), 0, 0);

        // 图标 + 中文功能名，原始规则作为小字副标题（排查用）
        LinearLayout nameCol = new LinearLayout(a);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        TextView name = Theme.plain(a, p, n[0] + " " + n[1]);
        name.setTextColor(p.title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nameCol.addView(name);
        TextView rule = Theme.hint(a, p, item[0]);
        rule.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        nameCol.addView(rule);
        line.addView(nameCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button del = Theme.dangerButton(a, p, "删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<String[]> now = Config.overlayList(a);
                // 按内容重新定位，避免索引错位
                for (int i = 0; i < now.size(); i++) {
                    if (now.get(i)[0].equals(item[0])) {
                        now.remove(i);
                        break;
                    }
                }
                Config.setOverlayList(a, now);
                UiController.refresh(a);
                rebuild(a);
            }
        });
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        del.setPadding(Theme.dp(a, 12), Theme.dp(a, 4), Theme.dp(a, 12), Theme.dp(a, 4));
        line.addView(del, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(line);

        Switch sw = Theme.makeSwitch(a, p, "隐藏", "1".equals(item[1]));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                patch(a, item[0], "hide", v ? "1" : "0");
            }
        });
        card.addView(sw);

        final TextView label = Theme.value(a, p, "透明度：" + item[2] + "%");
        card.addView(label);

        SeekBar bar = new SeekBar(a);
        bar.setMax(100);
        try {
            bar.setProgress(Integer.parseInt(item[2]));
        } catch (Throwable ignored) {
        }
        Theme.styleSeekBar(p, bar);
        bar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                label.setText("透明度：" + value + "%");
                if (fromUser) {
                    patch(a, item[0], "alpha", String.valueOf(value));
                }
            }
        });
        card.addView(bar);
    }

    private static void patch(Activity a, String match, String field, String value) {
        List<String[]> now = Config.overlayList(a);
        boolean found = false;
        for (String[] it : now) {
            if (it[0].equals(match)) {
                if ("hide".equals(field)) {
                    it[1] = value;
                } else {
                    it[2] = value;
                }
                found = true;
                break;
            }
        }
        if (!found) {
            String[] it = {match, "1", "100"};
            if ("hide".equals(field)) {
                it[1] = value;
            } else {
                it[2] = value;
            }
            now.add(it);
        }
        Config.setOverlayList(a, now);
        UiController.refresh(a);
    }

    /* ---------------- 扫描 / 手动添加 ---------------- */

    /* 注：下面两个弹窗走 AlertDialog.Builder，用的是**宿主主题**，
       模块无法指定其明暗；只有内容视图（EditText）能自带配色。 */

    private static void scanDialog(final Activity a) {
        final List<String[]> blocks = UiController.scanBlocks(a);
        if (blocks.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("扫描当前页面")
                    .setMessage("没有扫描到可单独控制的组件。")
                    .setPositiveButton("好", null).show();
            return;
        }
        final Theme.Palette p = Theme.of(a);
        final boolean[] checked = new boolean[blocks.size()];
        final List<String[]> existing = Config.overlayList(a);

        // 每行：图标 + 中文功能名（原始类名·id·尺寸 作为小字副标题）+ 勾选框
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.dp(a, 8), 0, Theme.dp(a, 8), 0);
        for (int i = 0; i < blocks.size(); i++) {
            final int idx = i;
            String[] b = blocks.get(i);
            // 规则（cls:/id:）与扫描标签（类名 #id · 文案）一起拿去翻译：
            // 规则常是 id:hg6 这种无意义短 id，特征往往在标签的类名/文案里
            String[] n = Names.describe(b[1] + " " + b[0]);
            final boolean on = containsRule(existing, b[1]);
            checked[i] = on;

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Theme.dp(a, 8), 0, Theme.dp(a, 8));
            row.setBackground(Theme.pressable(a, p));

            TextView icon = Theme.plain(a, p, n[0]);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            icon.setGravity(Gravity.CENTER);
            icon.setMinWidth(Theme.dp(a, 30));
            row.addView(icon, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = Theme.plain(a, p, n[1]);
            title.setTextColor(p.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            texts.addView(title);
            TextView sub = Theme.hint(a, p, b[0]);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            texts.addView(sub);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final CheckBox cb = new CheckBox(a);
            cb.setChecked(on);
            cb.setButtonTintList(ColorStateList.valueOf(p.primary));
            cb.setClickable(false);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checked[idx] = !checked[idx];
                    cb.setChecked(checked[idx]);
                }
            });
            row.addView(cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(row);
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(box);

        new AlertDialog.Builder(a)
                .setTitle("扫描到 " + blocks.size() + " 个组件（勾选即加入清单并隐藏）")
                .setView(sv)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        List<String[]> now = Config.overlayList(a);
                        for (int i = 0; i < blocks.size(); i++) {
                            String rule = blocks.get(i)[1];
                            boolean has = false;
                            for (String[] e : now) {
                                if (e[0].equals(rule)) {
                                    has = true;
                                    break;
                                }
                            }
                            if (checked[i] && !has) {
                                now.add(new String[]{rule, "1", "100"});
                            } else if (!checked[i] && has) {
                                for (int k = 0; k < now.size(); k++) {
                                    if (now.get(k)[0].equals(rule)) {
                                        now.remove(k);
                                        break;
                                    }
                                }
                            }
                        }
                        Config.setOverlayList(a, now);
                        UiController.refresh(a);
                        rebuild(a);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static boolean containsRule(List<String[]> list, String rule) {
        for (String[] e : list) {
            if (e[0].equals(rule)) {
                return true;
            }
        }
        return false;
    }

    /** 底部标签栏：枚举当前标签，勾选后按标签文字隐藏。 */
    private static void bottomTabsDialog(final Activity a) {
        final List<String> labels = UiController.bottomTabLabels(a);
        if (labels.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("底部标签栏")
                    .setMessage("没有识别到底部标签。请先回到首页（底部有标签栏的页面）再打开本面板。")
                    .setPositiveButton("好", null).show();
            return;
        }
        final boolean[] checked = new boolean[labels.size()];
        List<String> hidden = new ArrayList<>();
        for (String s : Config.hiddenBottomTabs(a).split("\\|")) {
            s = s.trim();
            if (!s.isEmpty()) {
                hidden.add(s);
            }
        }
        for (int i = 0; i < labels.size(); i++) {
            checked[i] = hidden.contains(labels.get(i));
        }
        new AlertDialog.Builder(a)
                .setTitle("勾选要隐藏的底部标签")
                .setMultiChoiceItems(labels.toArray(new String[0]), checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        List<String> keep = new ArrayList<>();
                        for (int i = 0; i < labels.size(); i++) {
                            if (checked[i]) {
                                keep.add(labels.get(i));
                            }
                        }
                        Config.setHiddenBottomTabs(a, keep);
                        UiController.refresh(a);
                        rebuild(a);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void addDialog(final Activity a) {
        final Theme.Palette p = Theme.of(a);
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("类名 / @文案 / text:文案 / id:名字");
        input.setTextColor(p.title);
        input.setHintTextColor(p.sub);
        input.setBackground(Theme.inputBg(a, p));
        int pad = Theme.dp(a, 14);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(a)
                .setTitle("手动添加（多个用 | 分隔）")
                .setView(input)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String text = input.getText().toString().trim();
                        if (text.isEmpty()) {
                            return;
                        }
                        for (String part : text.split("\\|")) {
                            part = part.trim();
                            if (!part.isEmpty()) {
                                Config.addOverlay(a, part);
                            }
                        }
                        UiController.refresh(a);
                        rebuild(a);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 基础控件 ---------------- */

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }

    private static LinearLayout.LayoutParams weight(float w, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, w);
        lp.leftMargin = leftMarginDp;
        return lp;
    }
}
