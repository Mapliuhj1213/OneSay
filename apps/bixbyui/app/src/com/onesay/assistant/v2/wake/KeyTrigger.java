package com.onesay.assistant.v2.wake;

/**
 * 解析 bixby.trigger 的内容（纯 Java，JVM 全测）。
 *
 * <ul>
 *   <li>旧守护 v5/v6：写 {@code <epoch 秒>} → {@link Kind#PRESS}；</li>
 *   <li>守护 v7：按下写 {@code press <seq> <t_ms>} → PRESS；按住满 800 ms 写 {@code long <seq> <dur_ms>} → {@link Kind#LONG}
 *       （只在 App 建了 {@code bixby.gesture_v2} 标记时才写，见 tools/kdaemon/hijack_daemon.v7.sh）；</li>
 *   <li>★ R10 守护 v8（App 建了 {@code bixby.gesture_v3} 标记时）：按下写 {@code down <seq> <t_ms>} → {@link Kind#DOWN}（只做静默预热，
 *       不出声、不弹面板）；松手且不到 800 ms 写 {@code press <seq> <dur_ms>} → PRESS（此时才唤醒）；按住满 800 ms 写 {@code long}；
 *       长按后松手写 {@code up <seq> <dur_ms>} → {@link Kind#UP}（目前只记日志，留给随口记）；</li>
 *   <li>读不到 / 空 / 认不出 → PRESS（与旧行为一致：任何 CLOSE_WRITE 都算一次按键）。</li>
 * </ul>
 */
public final class KeyTrigger {

    public enum Kind { PRESS, LONG, DOWN, UP }

    public static final String GESTURE_FLAG = "bixby.gesture_v2";
    /** ★ R10：守护 v8「按下只预热、松手才算短按」的能力标记（与 v2 标记一起建；v7 守护不认它，照旧）。 */
    public static final String GESTURE_FLAG_V3 = "bixby.gesture_v3";

    public final Kind kind;
    public final int seq;
    public final long ms;

    private KeyTrigger(Kind kind, int seq, long ms) {
        this.kind = kind; this.seq = seq; this.ms = ms;
    }

    public static KeyTrigger parse(String content) {
        if (content == null) return new KeyTrigger(Kind.PRESS, 0, 0);
        String[] p = content.trim().split("\\s+");
        if (p.length >= 1 && "long".equals(p[0])) {
            return new KeyTrigger(Kind.LONG, p.length > 1 ? toInt(p[1]) : 0, p.length > 2 ? toLong(p[2]) : 0);
        }
        if (p.length >= 1 && "down".equals(p[0])) {
            return new KeyTrigger(Kind.DOWN, p.length > 1 ? toInt(p[1]) : 0, p.length > 2 ? toLong(p[2]) : 0);
        }
        if (p.length >= 1 && "up".equals(p[0])) {
            return new KeyTrigger(Kind.UP, p.length > 1 ? toInt(p[1]) : 0, p.length > 2 ? toLong(p[2]) : 0);
        }
        if (p.length >= 1 && "press".equals(p[0])) {
            return new KeyTrigger(Kind.PRESS, p.length > 1 ? toInt(p[1]) : 0, p.length > 2 ? toLong(p[2]) : 0);
        }
        return new KeyTrigger(Kind.PRESS, 0, 0);
    }

    /** 长按不受 800 ms 按键去抖约束（它本来就在按下后约 800 ms 到达）；短按照旧去抖。 */
    public static boolean debounced(Kind kind, long nowMs, long lastPressMs) {
        return kind == Kind.PRESS && nowMs - lastPressMs < 800;
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static long toLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    @Override public String toString() {
        return kind + (seq > 0 ? " seq=" + seq : "") + (ms > 0 ? " ms=" + ms : "");
    }
}
