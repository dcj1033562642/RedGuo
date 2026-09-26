package com.wodi.redguotools;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 播放器微调：默认最高画质 + 自定义倍速（1.0~4.0 无级）。
 *
 * 两个挂载点（7.3.9.32 实测，均为诊断日志确认过的真实调用路径）：
 *   1. 倍速  com.ss.ttm.player.PlaybackParams.setSpeed(F)
 *      弹层选倍速、起播时宿主都通过它把速度写进播放器（1.0 在起播时写）。
 *      启用自定义倍速时替换参数 —— 弹层按钮本身有限位，这里没有。
 *   2. 画质  com.ss.ttvideoengine.TTVideoEngineImpl.configResolution(Resolution)
 *      起播时宿主以所选画质（如 540p）调用；替换为 Resolution.ExtremelyHigh。
 *      引擎对不可用档位自带回退。
 * 设置改动对「下一个起播的视频」生效（短剧一集很短，自动连播即见效）。
 */
final class PlayerTweaks {

    private static volatile boolean sInit;
    private static volatile Object sMaxRes;   // Resolution.ExtremelyHigh（兜底 SuperHigh/High）
    private static volatile String sStatus;   // 安装结果（Activity 出现后延迟落盘）

    /** 供 onActivityCreated 延迟落盘：hook 安装在 Activity 出现前，logFile 当时不可用。 */
    static String status() {
        return sStatus;
    }

    static void hook(XposedModule mod, ClassLoader cl) throws Throwable {
        if (sInit) {
            return;
        }
        try {
            Class<?> resCls = Class.forName("com.ss.ttvideoengine.Resolution", false, cl);
            sMaxRes = staticRes(resCls, "ExtremelyHigh");
            if (sMaxRes == null) {
                sMaxRes = staticRes(resCls, "SuperHigh");
            }
            if (sMaxRes == null) {
                sMaxRes = staticRes(resCls, "High");
            }

            // 1) 倍速：PlaybackParams.setSpeed(F)
            Class<?> pp = Class.forName("com.ss.ttm.player.PlaybackParams", false, cl);
            Method setSpeed = pp.getDeclaredMethod("setSpeed", float.class);
            setSpeed.setAccessible(true);
            mod.hook(setSpeed).setId("rgPlayerSpeed")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (speedOn()) {
                            float v = speedValue();
                            float orig = (Float) chain.getArg(0);
                            if (Math.abs(orig - v) > 0.001f) {
                                UiController.logFile("player: speed " + orig + " -> " + v);
                                return chain.proceed(new Object[]{v});
                            }
                        }
                        return chain.proceed();
                    });

            // 2) 画质：TTVideoEngineImpl.configResolution(Resolution)
            Class<?> impl = Class.forName("com.ss.ttvideoengine.TTVideoEngineImpl", false, cl);
            Method cfg = impl.getDeclaredMethod("configResolution", resCls);
            cfg.setAccessible(true);
            mod.hook(cfg).setId("rgPlayerRes")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (qualityOn() && sMaxRes != null && chain.getArg(0) != sMaxRes) {
                            Object orig = chain.getArg(0);
                            UiController.logFile("player: resolution " + orig + " -> " + sMaxRes);
                            return chain.proceed(new Object[]{sMaxRes});
                        }
                        return chain.proceed();
                    });

            sInit = true;
            sStatus = "ok maxRes=" + sMaxRes;
        } catch (Throwable t) {
            sStatus = "FAILED: " + t;
            throw t;
        }
    }

    /* ---------------- 配置读取（hook 回调里没有 Activity，用 appCtx） ---------------- */

    private static boolean qualityOn() {
        Context c = UiController.appCtx();
        return c == null || Config.maxQuality(c);   // 配置未就绪时按默认（开）
    }

    private static boolean speedOn() {
        Context c = UiController.appCtx();
        return c != null && Config.speedCustomOn(c);
    }

    private static float speedValue() {
        Context c = UiController.appCtx();
        return (c == null ? 20 : Config.speedCustom(c)) / 10f;
    }

    /** 读引擎 Resolution 的静态常量（不一定是 public，用 declared + accessible）。 */
    private static Object staticRes(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
