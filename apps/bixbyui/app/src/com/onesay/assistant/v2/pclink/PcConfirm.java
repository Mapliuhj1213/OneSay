package com.onesay.assistant.v2.pclink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [approve-not-block] 手机屏幕上的确认卡（纯 Java，JVM 可测；Android 界面是 {@code PcConfirmActivity}，只负责画和把触摸交进来）。两种卡：
 * <ul>
 *   <li><b>本机卡</b>（{@link #KIND_LOCAL}）：电脑发来的指令在手机上要你确认（打开 / 卸载支付类与系统 App、带账号或疑似仿冒的网址），
 *       {@link #ask} 阻塞等结果；</li>
 *   <li><b>电脑卡</b>（{@link #KIND_PC}）：电脑上那张「手机请求，需要你确认」也发到了手机，手机上点了由 {@link Reply} 回给电脑（签名的 confirm_answer）。</li>
 * </ul>
 * 规则和电脑端 ConfirmGate 同一套数：
 * <ul>
 *   <li>防误点：卡真正显示出来（界面报 {@link #shown}）后 {@link LinkPolicy#CONFIRM_ARM_MS} 内「允许」不算；窗口失焦再回来重新等；没焦点时不算。</li>
 *   <li>默认拒绝：{@link LinkPolicy#CONFIRM_TIMEOUT_MS}（60 秒）没人点、返回键 / 关掉卡片、出错 = 拒绝。</li>
 *   <li>只认真人触摸：「允许」只收 {@link Input#TOUCH}（手机界面上手指按下再抬起，没被遮挡、不是无障碍代点、不在机器人刚注入手势的那一会儿）；
 *       语音、无障碍点击、机器人（{@link #noteRobotInput}）、远程、来源不明的一律不算（请求照旧等）。「拒绝」从哪来都收。</li>
 *   <li>「以后这类不用问」：只有卡上给了这个勾选（动作类型可记住）且是真人允许时才算数；三条硬规则根本不会弹卡，记不了。</li>
 * </ul>
 */
public final class PcConfirm {

    private PcConfirm() {}

    public static final int ALLOW = 1, DENY = 0, TIMEOUT = -1, ALWAYS = 2, REMEMBER = 3;
    public static final String KIND_LOCAL = "local", KIND_PC = "pc";
    /** 机器人（OneSay 自己的手机操作）刚注入过手势：这么久之内的触摸不算真人。 */
    public static final long ROBOT_WINDOW_MS = 1500;

    public enum Input {
        /** 手机界面上真人手指的按下 + 抬起。 */
        TOUCH,
        /** 无障碍服务代点（performAction / ACTION_CLICK，或带无障碍标记的注入事件）。 */
        ACCESSIBILITY,
        /** 被别的窗口遮住时的触摸（可能是诱导点击）。 */
        OBSCURED,
        /** OneSay 自己的机器人刚注入过手势。 */
        ROBOT,
        VOICE, REMOTE, UNKNOWN
    }

    public enum Answer { ACCEPTED, NOT_SHOWN, TOO_EARLY, NOT_HUMAN, GONE }

    /** 电脑卡点完之后怎么回电脑（PcRx 接上：签名的 confirm_answer 走常连通道）。 */
    public interface Reply {
        void send(Card c, boolean allow, boolean always, boolean remember, long shownMs);
    }

    /** 有新卡 / 卡结束了（Android 侧：拉起确认页、发通知、收起页面）。 */
    public interface Ui {
        void open(Card c);

        void close(Card c);
    }

    /** 一张卡。 */
    public static final class Card {
        public final int id;
        public final String kind, title, detail, rememberKind, rememberZh;
        public final boolean always;
        public final long deadline;
        /** 电脑卡：电脑那边的编号和一次性 token（原样带回）。 */
        public final long pcId;
        public final String pcToken, pcFrom;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger res = new AtomicInteger(Integer.MIN_VALUE);
        Reply reply;
        private long shownAt = -1, armAt = Long.MAX_VALUE;
        private boolean focused;
        private boolean ended;

        Card(int id, String kind, String title, String detail, String rememberKind, boolean always, long deadline, long pcId, String pcToken, String pcFrom) {
            this.id = id;
            this.kind = kind;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            this.rememberKind = rememberKind != null && LinkPolicy.knownKind(rememberKind) ? rememberKind : "";
            this.rememberZh = this.rememberKind.length() == 0 ? "" : LinkPolicy.kindZh(this.rememberKind);
            this.always = always;
            this.deadline = deadline;
            this.pcId = pcId;
            this.pcToken = pcToken == null ? "" : pcToken;
            this.pcFrom = pcFrom == null ? "" : pcFrom;
        }

        public synchronized boolean shown() { return shownAt >= 0; }

        public synchronized boolean armed(long now) { return !ended && shownAt >= 0 && focused && now >= armAt && now < deadline; }

        public synchronized long armIn(long now) { return shownAt < 0 ? LinkPolicy.CONFIRM_ARM_MS : Math.max(0, armAt - now); }

        public synchronized long shownFor(long now) { return shownAt < 0 ? 0 : now - shownAt; }

        public synchronized boolean ended() { return ended; }

        synchronized void end() { ended = true; }
    }

    private static final Map<Integer, Card> CARDS = new ConcurrentHashMap<Integer, Card>();
    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static volatile long robotAt = -1;
    /** 时钟（测试可换）。 */
    public static volatile Clock clock = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };
    public static volatile Ui ui = null;

    public interface Clock {
        long now();
    }

    static long now() { return clock.now(); }

    // ================================================================== 机器人

    /** OneSay 自己的手机操作每次注入手势 / 按键前调用：这之后 {@link #ROBOT_WINDOW_MS} 内卡上的触摸不算真人。 */
    public static void noteRobotInput() {
        robotAt = now();
    }

    static boolean robotRecently(long t) {
        long r = robotAt;
        return r >= 0 && t - r >= -ROBOT_WINDOW_MS && t - r <= ROBOT_WINDOW_MS;
    }

    // ================================================================== 开卡

    /** 本机卡：阻塞等你点（最多 timeoutMs，默认 60 秒）。没有界面（JVM / 没接上）时直接按没人确认。 */
    public static int ask(String title, String detail, String rememberKind, long timeoutMs) {
        Ui u = ui;
        if (u == null) return TIMEOUT;
        Card c = new Card(SEQ.getAndIncrement(), KIND_LOCAL, title, detail, rememberKind, false, now() + Math.max(0, timeoutMs), 0, "", "");
        CARDS.put(c.id, c);
        try {
            try {
                u.open(c);
            } catch (Throwable t) {
                c.res.compareAndSet(Integer.MIN_VALUE, DENY); // 界面打不开 = 拒绝
            }
            while (c.res.get() == Integer.MIN_VALUE) {
                long left = c.deadline - now();
                if (left <= 0) break;
                if (c.latch.await(Math.min(left, 200), TimeUnit.MILLISECONDS)) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            c.res.compareAndSet(Integer.MIN_VALUE, DENY);
        } finally {
            c.res.compareAndSet(Integer.MIN_VALUE, TIMEOUT);
            finish(c);
        }
        return c.res.get();
    }

    /** 电脑卡：显示，不阻塞；点了之后经 reply 回电脑。返回卡（没有界面返回 null）。 */
    public static Card offer(String from, long pcId, String token, String detail, String rememberKind, boolean always, long timeoutMs, Reply reply) {
        Ui u = ui;
        if (u == null || reply == null || token == null || token.length() < 16) return null;
        for (Card old : CARDS.values()) if (KIND_PC.equals(old.kind) && old.pcId == pcId && old.pcFrom.equals(from)) return old; // 重复发来的同一张
        long t = Math.max(0, Math.min(timeoutMs, LinkPolicy.CONFIRM_TIMEOUT_MS));
        Card c = new Card(SEQ.getAndIncrement(), KIND_PC, "电脑上的请求，需要你确认", detail, rememberKind, always, now() + t, pcId, token, from);
        c.reply = reply;
        CARDS.put(c.id, c);
        try {
            u.open(c);
        } catch (Throwable e) {
            finish(c);
            return null;
        }
        return c;
    }

    /** 电脑说这条确认已经结束（别处点了 / 超时）：收卡。 */
    public static void closePc(String from, long pcId) {
        for (Card c : CARDS.values()) if (KIND_PC.equals(c.kind) && c.pcId == pcId && c.pcFrom.equals(from)) finish(c);
    }

    static void finish(Card c) {
        c.end();
        CARDS.remove(c.id);
        c.latch.countDown();
        Ui u = ui;
        if (u != null) try { u.close(c); } catch (Throwable ignored) { }
    }

    // ================================================================== 界面报上来的事

    public static Card card(int id) { return CARDS.get(id); }

    public static List<Card> pending() {
        List<Card> out = new ArrayList<Card>(CARDS.values());
        return out;
    }

    public static boolean anyPending() { return !CARDS.isEmpty(); }

    /** 卡画出来了（focused = 窗口当时有焦点）。只记第一次。 */
    public static boolean shown(int id, boolean focused) {
        Card c = CARDS.get(id);
        if (c == null) return false;
        synchronized (c) {
            long t = now();
            if (c.shownAt < 0) {
                c.shownAt = t;
                c.armAt = t + LinkPolicy.CONFIRM_ARM_MS;
            } else if (focused && !c.focused) {
                c.armAt = t + LinkPolicy.CONFIRM_ARM_MS;
            }
            c.focused = focused;
        }
        return true;
    }

    /** 窗口焦点变了：拿回焦点要重新等 0.7 秒。 */
    public static void focus(int id, boolean focused) {
        Card c = CARDS.get(id);
        if (c == null) return;
        synchronized (c) {
            if (focused && !c.focused && c.shownAt >= 0) c.armAt = now() + LinkPolicy.CONFIRM_ARM_MS;
            c.focused = focused;
        }
    }

    /** 返回键 / 卡片被关掉 / 页面销毁：按拒绝结束。 */
    public static void dismissed(int id) {
        answer(id, false, false, false, Input.UNKNOWN);
    }

    /**
     * 回答一张卡。拒绝从哪来都收；允许要真人触摸（{@link Input#TOUCH}、不在机器人注入的窗口里）+ 已显示 + 已解锁。
     * 没收的回答不改变卡：它照旧等真人点击（等不到就拒）。
     */
    public static Answer answer(int id, boolean allow, boolean always, boolean remember, Input in) {
        Card c = CARDS.get(id);
        if (c == null) return Answer.GONE;
        long t = now();
        if (c.ended() || t >= c.deadline) return Answer.GONE;
        if (allow) {
            if (in != Input.TOUCH || robotRecently(t)) return Answer.NOT_HUMAN;
            if (!c.shown()) return Answer.NOT_SHOWN;
            if (!c.armed(t)) return Answer.TOO_EARLY;
        }
        int v = !allow ? DENY : always && c.always ? ALWAYS : remember && c.rememberKind.length() > 0 ? REMEMBER : ALLOW;
        if (!c.res.compareAndSet(Integer.MIN_VALUE, v)) return Answer.GONE;
        long shownMs = c.shownFor(t);
        if (KIND_PC.equals(c.kind) && c.reply != null) {
            try {
                c.reply.send(c, allow, v == ALWAYS, v == REMEMBER, shownMs);
            } catch (Throwable ignored) {
                // 回不了电脑：电脑那边等不到就按拒绝
            }
        }
        finish(c);
        return Answer.ACCEPTED;
    }

    /** 到时间的卡按没人确认结束（Android 侧每秒看一次）。返回结束了几张。 */
    public static int expire() {
        int n = 0;
        long t = now();
        for (Card c : CARDS.values()) {
            if (t >= c.deadline) {
                c.res.compareAndSet(Integer.MIN_VALUE, TIMEOUT);
                finish(c);
                n++;
            }
        }
        return n;
    }

    /** 测试用：清空。 */
    static void reset() {
        for (Card c : new ArrayList<Card>(CARDS.values())) finish(c);
        robotAt = -1;
    }

    // ================================================================== 说话不算批准

    private static final String[] SPOKEN = {
            "允许", "允许一次", "允许执行", "同意", "批准", "确认", "确定", "确认执行", "确认允许", "可以", "行", "好", "好的", "是", "是的", "对",
            "执行", "继续", "通过", "放行", "以后直接执行", "直接执行", "以后这类不用问", "点允许", "点同意", "帮我点允许", "帮我允许", "帮我批准",
            "allow", "yes", "ok", "okay", "approve", "confirm", "sure", "go", "goahead", "doit"
    };

    /** 有卡在等时，一句话是不是在「口头批准」（和电脑端同一套）：是的话不执行，告诉你要在屏幕上点。 */
    public static boolean spokenApproval(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’…~～]+", "")
                .replaceAll("^(那就|那|就|嗯|呃|啊|请|给我|帮忙)+", "").replaceAll("(吧|啊|呀|了|吗|嘛|哦|噢|呗)+$", "");
        if (t.length() == 0 || t.length() > 10) return false;
        for (String s : SPOKEN) if (t.equals(s)) return true;
        return false;
    }
}
