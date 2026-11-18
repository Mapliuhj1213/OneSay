package com.onesay.assistant.v2.a11y;

/**
 * ★ plan-1118（真机 Meizu 21 Pro）：无障碍服务被解绑 / 重新绑定的约 1.1 s 间隙里，读屏 / 动作不直接判失败，
 * 等新实例连上（最多 {@link #MAX_WAIT_MS}）再做；做到一半旧实例没了（读树 / 动作返回失败、实例已换）→ 在新实例上重做一次。
 * <p>
 * 实测的解绑 / 重绑全部对得上测试脚本的 {@code uiautomator dump}（UiAutomation 默认连接会压下所有无障碍服务，断开后系统重新绑定）；
 * 读屏助手 HelperMain 连 UiAutomation 时带 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，不会触发。别的系统组件
 * （或 ROM 自己）做同样的事时也走这里。纯 Java，JVM 可测。
 */
public final class RebindWait {
    private RebindWait() {}

    /** 重绑间隙最多等多久（实测间隙约 1.1 s）。 */
    public static final long MAX_WAIT_MS = 2000;
    public static final long STEP_MS = 50;

    public interface Source<T> {
        /** 当前实例；没连上返回 null。 */
        T current();
    }

    public interface Clock {
        long now();

        void sleep(long ms);
    }

    public interface Op<T, R> {
        /** 在实例 t 上做一次；失败返回 null。 */
        R run(T t);
    }

    /** 等到有实例（最多 maxMs）；一直没有返回 null。 */
    public static <T> T await(Source<T> s, long maxMs, Clock c) {
        T t = s.current();
        if (t != null || maxMs <= 0) return t;
        long end = c.now() + maxMs;
        while (c.now() < end) {
            c.sleep(STEP_MS);
            t = s.current();
            if (t != null) return t;
        }
        return s.current();
    }

    /** 等到实例换成不是 old 的新实例（最多 maxMs）；超时返回当前实例（可能仍是 old，也可能 null）。 */
    public static <T> T awaitOther(Source<T> s, T old, long maxMs, Clock c) {
        long end = c.now() + Math.max(0, maxMs);
        T t = s.current();
        while ((t == null || t == old) && c.now() < end) {
            c.sleep(STEP_MS);
            t = s.current();
        }
        return t;
    }

    /**
     * 在当前实例上做 op；没有实例先等。op 失败且做的时候实例已经掉线 / 换了 → 等新实例、在新实例上重做一次。
     * 实例一直连着、op 自己失败 → 不重做（那是真失败）。整体最多等 maxMs。
     */
    public static <T, R> R call(Source<T> s, Op<T, R> op, long maxMs, Clock c) {
        long t0 = c.now();
        T a = await(s, maxMs, c);
        if (a == null) return null;
        R r = op.run(a);
        if (r != null) return r;
        T now = s.current();
        if (now == a) return null;   // 实例没变：真失败
        long left = maxMs - (c.now() - t0);
        T b = awaitOther(s, a, Math.max(left, STEP_MS), c);
        if (b == null || b == a) return null;
        return op.run(b);
    }
}
