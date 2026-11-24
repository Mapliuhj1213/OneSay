package com.onesay.assistant.v2.fastscreen;

import java.lang.reflect.Method;

/**
 * ★ fix/inject-a14：读屏助手注入的纯逻辑（不碰 android 运行时，JVM 可测）。
 * <ul>
 *   <li>取注入入口：先 {@code android.hardware.input.InputManagerGlobal.getInstance()}（安卓 14 起 InputManager 不再有 getInstance），
 *       取不到再退 {@code android.hardware.input.InputManager.getInstance()}（安卓 8 旧路径）。</li>
 *   <li>手势兜底：按下注入失败（含取不到入口）才改走 {@code input} 命令；按下已成功只补抬起，绝不走命令，免得点两下。</li>
 *   <li>截图：安卓 12（API 31）起旧的 SurfaceControl.screenshot(int,int) 没了，改走 {@code screencap -p}。</li>
 * </ul>
 */
final class InjectPlan {

    private InjectPlan() {}

    static final String GLOBAL = "android.hardware.input.InputManagerGlobal";
    static final String LEGACY = "android.hardware.input.InputManager";
    static final String INPUT_BIN = "/system/bin/input";

    /** 找类的口子：真机走 Class.forName，测试塞假类。 */
    interface Finder {
        Class<?> find(String name) throws ClassNotFoundException;
    }

    static final Finder SYSTEM = new Finder() {
        @Override
        public Class<?> find(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }
    };

    /** 注入入口：实例 + injectInputEvent(InputEvent,int) 方法；{@link #from} 记下用的是哪个类。 */
    static final class Injector {
        final Object target;
        final Method method;
        final String from;

        Injector(Object t, Method m, String f) {
            target = t;
            method = m;
            from = f;
        }
    }

    /** 依次试 Global → 旧 InputManager；都不行返回 null（调用方改走命令）。 */
    static Injector resolve(Finder f, Class<?> eventType) {
        String[] order = {GLOBAL, LEGACY};
        for (int k = 0; k < order.length; k++) {
            try {
                Class<?> c = f.find(order[k]);
                Object inst = c.getMethod("getInstance").invoke(null);
                if (inst == null) continue;
                Method m = c.getMethod("injectInputEvent", eventType, int.class);
                return new Injector(inst, m, order[k]);
            } catch (Throwable ignore) {
                // 下一个
            }
        }
        return null;
    }

    /** 一次手势的几个动作；down/up 抛异常按失败算。 */
    interface Gesture {
        boolean down() throws Exception;

        /** 按住 / 滑动中间的 MOVE；返回是否全成功。 */
        boolean moves() throws Exception;

        boolean up() throws Exception;

        /** 兜底：执行 input 命令，返回是否成功。 */
        boolean shell() throws Exception;
    }

    static final String VIA_INJECT = "inject";
    static final String VIA_SHELL = "shell";

    /**
     * 跑一次手势：按下成功 → 走完 MOVE 补 UP（UP 失败再补一次 UP，仍不走命令）；按下失败 → 只走命令。
     * 返回 VIA_INJECT / VIA_SHELL；失败返回 null。
     */
    static String run(Gesture g) {
        boolean d;
        try {
            d = g.down();
        } catch (Throwable t) {
            d = false;
        }
        if (!d) {
            try {
                return g.shell() ? VIA_SHELL : null;
            } catch (Throwable t) {
                return null;
            }
        }
        boolean ok = true;
        try {
            ok = g.moves();
        } catch (Throwable t) {
            ok = false;
        }
        boolean u;
        try {
            u = g.up();
        } catch (Throwable t) {
            u = false;
        }
        if (!u) {
            try {
                u = g.up();
            } catch (Throwable t) {
                u = false;
            }
        }
        return ok && u ? VIA_INJECT : null;
    }

    static String[] tapArgv(int x, int y, int holdMs) {
        if (holdMs > 60) return swipeArgv(x, y, x, y, holdMs);   // 长按：原地 swipe
        return new String[] {INPUT_BIN, "tap", String.valueOf(x), String.valueOf(y)};
    }

    static String[] swipeArgv(int x1, int y1, int x2, int y2, int ms) {
        return new String[] {INPUT_BIN, "swipe", String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2),
                String.valueOf(Math.max(1, ms))};
    }

    static String[] keyArgv(int code) {
        return new String[] {INPUT_BIN, "keyevent", String.valueOf(code)};
    }

    /** 安卓 12 起截图走 screencap。 */
    static boolean useScreencap(int sdk) {
        return sdk >= 31;
    }

    static String[] screencapArgv(String path) {
        return new String[] {"/system/bin/screencap", "-p", path};
    }
}
