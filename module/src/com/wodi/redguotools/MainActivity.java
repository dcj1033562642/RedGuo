package com.wodi.redguotools;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 模块自身的入口。红果内的设置面板靠「长按」唤出，
 * 这个界面给用户一份完整的使用说明、隐藏技巧与免责声明。
 */
public class MainActivity extends Activity {

    private static final int C_TEXT = 0xFF333333;
    private static final int C_SUB = 0xFF666666;
    private static final int C_TITLE = 0xFFFF6B35;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF7F5F2);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, dp(40));
        scroll.addView(root);
        setContentView(scroll);

        /* 标题 */
        TextView title = new TextView(this);
        title.setText("ReadGuo");
        title.setTextSize(26);
        title.setTextColor(C_TITLE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("红果免费短剧 · 界面工具模块\n仅供学习交流使用");
        subtitle.setTextSize(13);
        subtitle.setTextColor(C_SUB);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        /* 各章节 */
        section(root, "使用方式",
                "1. 在 LSPosed 中启用本模块，作用域勾选「红果免费短剧」\n"
                        + "2. 强制停止红果后重新打开\n"
                        + "3. 唤出设置面板：在底部导航栏「长按 首页」"
                        + "（可在面板「面板唤出」卡里改为长按屏幕顶部中央）\n"
                        + "4. 面板里所有改动即时生效，无需重启红果");

        section(root, "功能一览",
                "· 状态栏：隐藏 / 纯黑 / 纯白 / 跟随背景，点状态栏可临时显示数字时间\n"
                        + "· 叠加组件透明度：全局系数 × 各项独立透明度，双层叠加\n"
                        + "· 组件显隐：11 个常用组件（顶栏 / 右侧互动 / 底部条 / 标题 / 热评…）逐项开关\n"
                        + "· 自定义隐藏清单：「扫描当前页面」自动列出可隐藏组件，也可手动加规则\n"
                        + "· 底部标签栏：按文字勾选隐藏（如 商城 / 赚钱 / 我的）\n"
                        + "· 播放画质：默认使用最高可用画质\n"
                        + "· 自定义倍速：1.0 ~ 4.0 倍无级调节\n"
                        + "· 双击屏幕直接打开评论区（横屏三击）\n"
                        + "· 清屏省心：清屏状态下 10 秒不碰屏幕，自动收起其余控件只留画面");

        section(root, "不容易发现的点",
                "1. 「扫描当前页面」要先停在有目标组件的页面再扫——它会把当前页能隐藏的东西"
                        + "列成清单，带「@文案」标注方便辨认，混淆类名只显示末段。\n"
                        + "2. 所有隐藏都是清单制：不勾就默认显示，模块绝不动你没勾的东西。\n"
                        + "3. 全局透明度和单项透明度是「乘法」：生效值 = 该项 × 全局。"
                        + "只想微调某一项时，把全局调回 100。\n"
                        + "4. 隐藏浮层用「隐形」保留占位，界面不会塌；底部标签栏是唯一例外——"
                        + "它要「腾地方」，隐藏后剩余标签自动摊满整行，这是刻意的。\n"
                        + "5. 清屏的正确入口：长按视频 → 播放设置 → 清屏播放。"
                        + "（选集条右侧那个方块图标不是清屏，别点错。）\n"
                        + "6. 清屏状态下 10 秒不碰屏幕，顶栏 / 进度条 / 选集条会自动收起，"
                        + "屏幕上只剩视频本身；手指一碰立即恢复。\n"
                        + "7. 倍速和画质改动对「下一个起播的视频」生效——短剧自动连播，"
                        + "切一集或重进页面就能看到。关闭倍速开关后跟随宿主设置（可用 0.75x 等档位）。\n"
                        + "8. 最高画质替换的是引擎档位，弹层的清晰度行会如实显示 1080；"
                        + "片源没有高画质时引擎自动回退可用档，不会黑屏。\n"
                        + "9. 双击怕误触点赞？开「双击时吞掉第二次点击」。\n"
                        + "10. 自检与排错：/sdcard/Android/data/com.phoenix.read/files/ 下的"
                        + " rgtools_log.txt（功能命中记录）与 rgtools_tree.txt（进页面 1.8 秒后的视图树）。");

        section(root, "免责声明",
                "· 本模块仅供学习交流与个人技术研究使用，请勿用于任何商业或非法用途。\n\n"
                        + "· 本模块不修改、不破解、不绕过任何付费、会员或版权保护机制；"
                        + "所有功能仅调整本机的界面显示与个人偏好设置。\n\n"
                        + "· 请在遵守法律法规及平台用户协议的前提下使用；请支持正版，"
                        + "相关内容、商标与软件版权归原作者及平台所有。\n\n"
                        + "· 本模块按「现状」提供，不附带任何明示或默示的担保；"
                        + "因下载、安装或使用本模块而产生的一切后果，由使用者自行承担。\n\n"
                        + "· 若本模块侵犯了相关方的合法权益，请与我们联系，将第一时间删除。");

        Log.i(RGModule.TAG, "MainActivity created");
    }

    /* ---------------- 章节：圆角卡片 + 标题 + 正文 ---------------- */

    private void section(LinearLayout root, String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextSize(16);
        head.setTextColor(C_TITLE);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(head);

        TextView text = new TextView(this);
        text.setText(body);
        text.setTextSize(14);
        text.setTextColor(C_TEXT);
        text.setLineSpacing(dp(3), 1f);
        text.setPadding(0, dp(10), 0, 0);
        card.addView(text);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        root.addView(card, lp);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
