package com.onesay.desktop;

import java.util.Locale;

/**
 * 电脑上「手机请求，需要你确认」那张确认框的时间 / 输入规则，抽成纯逻辑（时间都由调用方传进来，测试用假时钟）。
 * {@link PcGuard.Confirms} 每条待确认请求带一个；界面（index.html）照同样的数字画按钮，最终以这里为准。
 *
 * <ul>
 *   <li><b>防误点</b>：确认框在界面上真正显示出来（界面报 seen）之后 {@link #ARM_MS} 毫秒内「允许」不算数；
 *       窗口失焦后再拿回焦点，重新等 {@link #ARM_MS}（点一下把窗口切到前台的那一下不会顺手批准）。窗口没焦点时「允许」也不算数。</li>
 *   <li><b>默认拒绝</b>：超时、确认框显示后窗口拿过焦点又失焦 {@link #BLUR_DENY_MS} 毫秒没人点、界面 {@link #UI_GONE_MS} 毫秒没动静（窗口关了 /
 *       卡死）、窗口关闭 / Esc（界面直接报拒绝或 closed）——都算拒绝。</li>
 *   <li><b>只认本机点击</b>：「允许」只接受电脑本机的鼠标 / 键盘点击（{@link Input#MOUSE} / {@link Input#KEYBOARD}）；语音、远程消息、来源不明的一律
 *       不算批准（{@link Answer#NOT_LOCAL}，原因码 {@link LinkPolicy#R_VOICE_APPROVAL_BLOCKED}），请求照旧等本机点击。「拒绝」从哪来都收（拒绝总是安全的）。</li>
 * </ul>
 *
 * <p>思路来源：Haris（MIT License，Copyright (c) 2026 TENMATECH Digital Solutions L.L.C.，
 * https://github.com/TENMATECH-DIGITAL-SOLUTIONS/haris）的审批卡 ——「按钮 0.7 秒后才可点、Esc 与超时即拒、不能用说话批准」。
 * 只借鉴了做法，代码是 OneSay 自己写的（见 NOTICE）。
 */
public final class ConfirmGate {

    /** 「允许」解锁前的等待（毫秒）。 */
    public static final long ARM_MS = 700;
    /** 确认框显示后窗口拿过焦点、又失焦这么久没人点 = 拒绝。 */
    public static final long BLUR_DENY_MS = 10_000;
    /** 确认框显示后界面这么久没报到（界面每 2.5 秒刷新一次）= 窗口关了 / 卡了 = 拒绝。 */
    public static final long UI_GONE_MS = 8_000;

    /** 一次回答从哪来。 */
    public enum Input {
        MOUSE, KEYBOARD, VOICE, REMOTE, UNKNOWN;

        /** 界面传来的 input 字段：只认 "mouse" / "keyboard"，其余（含没传、语音、远程）都不是本机点击。 */
        public static Input of(Object o) {
            String s = o == null ? "" : String.valueOf(o).trim().toLowerCase(Locale.ROOT);
            switch (s) {
                case "mouse": return MOUSE;
                case "keyboard": return KEYBOARD;
                case "voice": return VOICE;
                case "remote": return REMOTE;
                default: return UNKNOWN;
            }
        }

        public boolean local() { return this == MOUSE || this == KEYBOARD; }
    }

    /** 一次回答的结果：只有 ACCEPTED 会真的结束这条请求。 */
    public enum Answer {
        ACCEPTED,
        /** 界面还没报告确认框显示出来。 */
        NOT_SHOWN,
        /** 还在防误点的等待里，或窗口没焦点。 */
        TOO_EARLY,
        /** 不是本机鼠标 / 键盘（语音、远程、来源不明）：不算批准。 */
        NOT_LOCAL,
        /** 请求已经结束（超时 / 拒绝 / 已回答 / 编号不对）。 */
        GONE
    }

    /** 请求自己结束的原因（{@link #expired}）。 */
    public static final String END_TIMEOUT = "timeout", END_BLUR = "blur", END_UI_GONE = "ui_gone", END_CLOSED = "closed";

    private final long deadline;
    private long seenAt = -1, armAt = Long.MAX_VALUE, blurSince = -1, lastUi = -1;
    private boolean focused, focusedSinceSeen;
    private String ended;

    public ConfirmGate(long now, long timeoutMs) {
        this.deadline = now + Math.max(0, timeoutMs);
    }

    /** 界面把确认框画出来了；focused = 当时窗口有没有焦点。只记第一次。 */
    public synchronized void seen(long now, boolean focused) {
        if (seenAt < 0) {
            seenAt = now;
            this.focused = focused;
            focusedSinceSeen = focused;
            armAt = now + ARM_MS;
        } else {
            window(now, focused);
        }
        lastUi = now;
    }

    /** 窗口焦点变化（界面报 focus / blur，或 visibilitychange）。拿回焦点要重新等 {@link #ARM_MS}。 */
    public synchronized void window(long now, boolean f) {
        if (seenAt >= 0) lastUi = now;
        if (f) {
            if (!focused && seenAt >= 0) armAt = now + ARM_MS;
            focused = true;
            blurSince = -1;
            if (seenAt >= 0) focusedSinceSeen = true;
        } else {
            if (focused && seenAt >= 0 && focusedSinceSeen) blurSince = now;
            focused = false;
        }
    }

    /** 界面还活着（每次刷新状态都报）。 */
    public synchronized void uiAlive(long now) {
        if (seenAt >= 0) lastUi = now;
    }

    /** 窗口关了 / 界面主动报拒绝之外的结束：之后任何回答都不算。 */
    public synchronized void close() {
        if (ended == null) ended = END_CLOSED;
    }

    public synchronized boolean seen() { return seenAt >= 0; }

    /** 「允许」现在能不能点：已显示、窗口有焦点、过了防误点等待、还没结束。 */
    public synchronized boolean armed(long now) {
        return ended == null && seenAt >= 0 && focused && now >= armAt && now < deadline;
    }

    /** 还要等多少毫秒才解锁（界面画倒计时用；已解锁 / 没显示返回 0 或 ARM_MS）。 */
    public synchronized long armIn(long now) {
        if (seenAt < 0) return ARM_MS;
        return Math.max(0, armAt - now);
    }

    /** 请求是不是该按拒绝结束了；null = 继续等。 */
    public synchronized String expired(long now) {
        if (ended != null) return ended;
        if (now >= deadline) return END_TIMEOUT;
        if (blurSince >= 0 && now - blurSince >= BLUR_DENY_MS) return END_BLUR;
        if (seenAt >= 0 && lastUi >= 0 && now - lastUi >= UI_GONE_MS) return END_UI_GONE;
        return null;
    }

    /** 判一次回答能不能收：拒绝总收；允许要本机点击 + 已显示 + 已解锁。 */
    public synchronized Answer check(long now, boolean allow, Input in) {
        if (ended != null || now >= deadline) return Answer.GONE;
        if (!allow) return Answer.ACCEPTED;
        if (in == null || !in.local()) return Answer.NOT_LOCAL;
        if (seenAt < 0) return Answer.NOT_SHOWN;
        if (!armed(now)) return Answer.TOO_EARLY;
        return Answer.ACCEPTED;
    }

    // ------------------------------------------------------------------ 说话不算批准

    private static final String[] SPOKEN = {
            "允许", "允许一次", "允许执行", "同意", "批准", "确认", "确定", "确认执行", "确认允许", "可以", "行", "好", "好的", "是", "是的", "对",
            "执行", "继续", "通过", "放行", "以后直接执行", "直接执行", "点允许", "点同意", "帮我点允许", "帮我允许", "帮我批准",
            "allow", "yes", "ok", "okay", "approve", "confirm", "sure", "go", "goahead", "doit"
    };

    /**
     * 有确认框在等时，一句话是不是在「口头批准」（整句只有批准类的词，去掉标点 / 空格 / 语气词后比较）。
     * 是的话不执行、不转给对话，告诉你得在电脑上点。「确认关机」这类带了具体动作的本机语音指令不算。
     */
    public static boolean spokenApproval(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’…~～]+", "")
                .replaceAll("^(那就|那|就|嗯|呃|啊|请|给我|帮忙)+", "").replaceAll("(吧|啊|呀|了|吗|嘛|哦|噢|呗)+$", "");
        if (t.isEmpty() || t.length() > 10) return false;
        for (String s : SPOKEN) if (t.equals(s)) return true;
        return false;
    }
}
