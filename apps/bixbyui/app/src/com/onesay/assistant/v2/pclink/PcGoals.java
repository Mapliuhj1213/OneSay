package com.onesay.assistant.v2.pclink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * [rel-1123-b] 电脑交给手机的目标（operate）做完以后，把结果回传电脑。纯 Java（Java 7），JVM 可测。
 *
 * <p>面板那边（{@code FloatingSheetService} / {@code FloatingSheetView}）在这几处报一声：
 * <ul>
 *   <li>{@link #submitted}：面板真的开始处理一句话（和电脑交来的目标一样 = 认领这一轮；之后又来别的话 = 被打断）；</li>
 *   <li>{@link #agentStarted} / {@link #agentEnded}：这一轮走了复合任务循环，循环结束就是结果（做完 / 没做完 / 停了）；</li>
 *   <li>{@link #shown}：简单指令的结果 / 回答上屏了（等 {@link #SETTLE_MS} 没有新动静就算最终结果）；</li>
 *   <li>{@link #waiting}：手机上弹了确认卡，等人点的时候不收尾；</li>
 *   <li>{@link #stopped}：在手机上说了「取消 / 停止」。</li>
 * </ul>
 * 结果经常连通道发一帧签名的 {@code goal_result}（{@link PcRx#goalFrame}）。常连不在就先留着（最多 {@link #OUTBOX_MAX} 条、
 * {@link #OUTBOX_MS} 内），常连连上时补发。只传手机自己说出来的最终回答文字和几行步骤，<b>不传屏幕内容</b>。
 */
public final class PcGoals {

    private PcGoals() {}

    /** 简单指令结果上屏后多久没有新动静就收尾。 */
    public static long SETTLE_MS = 2500;
    /** 手机上最多做多久（再久就按「做太久」回传）。 */
    public static long TOTAL_MS = 10 * 60_000L;
    /** 交过去以后多久面板还没接这句话，就按「面板没起来」回传。 */
    public static long BIND_MS = 30_000L;
    /** 简单指令认领以后这么久屏上一句结果都没有（比如直接跳去了别的 App）：按「没留下结果」回传，不硬说做完了。 */
    public static long QUIET_MS = 60_000L;
    public static final int OUTBOX_MAX = 5;
    public static final long OUTBOX_MS = 24 * 3600_000L;
    public static final int TEXT_MAX = 1500, STEPS_MAX = 8;

    public static final String RUNNING = "running", DONE = "done", FAILED = "failed", DENIED = "denied", STOPPED = "stopped",
            TIMEOUT = "timeout", INTERRUPTED = "interrupted", UNKNOWN = "unknown";

    /** 一次电脑交来的目标。 */
    public static final class Goal {
        public final String id, goal, pc;
        public final long at;
        volatile String state = RUNNING, text = "";
        final List<String> steps = new ArrayList<String>();
        boolean bound, agent, waitingUser;
        int seq = Integer.MIN_VALUE;
        long boundAt;
        String cand;
        String candState;
        long settleAt = Long.MAX_VALUE;
        long endAt;

        Goal(String id, String goal, String pc, long at) {
            this.id = id;
            this.goal = goal;
            this.pc = pc;
            this.at = at;
        }

        public synchronized String state() { return state; }

        public synchronized String text() { return text; }

        public synchronized List<String> steps() { return new ArrayList<String>(steps); }

        public synchronized boolean done() { return !RUNNING.equals(state); }
    }

    /** 结果发给电脑；发出去了返回 true（没有常连 = false，留着补发）。 */
    public interface Sink {
        boolean send(Goal g);
    }

    private static Goal active, last;
    private static final List<Goal> outbox = new ArrayList<Goal>();
    static volatile Sink sink;
    private static Thread ticker;
    /** 测试换成假时钟（null = 系统时钟；那时自己起一条线程每 300 毫秒看一次）。 */
    static volatile Clock clock;

    interface Clock {
        long now();
    }

    static long now() {
        Clock c = clock;
        return c != null ? c.now() : System.currentTimeMillis();
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    /** 电脑交来一个目标（PcRxAndroid.operate 里，交给面板之前）。上一个还在做的按「被打断」收尾。返回给电脑的 goal_id。 */
    public static String begin(String pc, String goal) {
        Goal g;
        Goal old;
        synchronized (PcGoals.class) {
            old = active;
            if (old != null) end(old, INTERRUPTED, "电脑又交来一个新目标，前一个没做完就停了");
            g = new Goal(newId(), goal == null ? "" : goal.trim(), pc == null ? "" : pc, now());
            active = g;
        }
        if (old != null) deliver(old);
        startTicker();
        return g.id;
    }

    /** 面板开始处理一句话（主线程，取消 / 插话 / 回答追问这些早返回之后）。seq = 这一轮的请求号。 */
    public static void submitted(String command, int seq) {
        Goal fin = null;
        synchronized (PcGoals.class) {
            Goal g = active;
            if (g == null) return;
            String c = command == null ? "" : command.trim();
            if (!g.bound) {
                if (c.equals(g.goal)) {
                    g.bound = true;
                    g.seq = seq;
                    g.boundAt = now();
                }
                return;
            }
            if (seq != g.seq) {
                end(g, INTERRUPTED, g.cand != null && g.cand.length() > 0 ? g.cand + "（之后手机上又说了别的，这个目标没继续）" : "手机上又说了别的，这个目标被打断了");
                fin = g;
            }
        }
        if (fin != null) deliver(fin);
    }

    /** 这一轮走了复合任务循环（seq 对得上才算）。 */
    public static synchronized void agentStarted(int seq) {
        Goal g = active;
        if (g != null && g.bound && g.seq == seq) g.agent = true;
    }

    /** 复合任务循环结束（主线程）：finished = 模型说做完了；cancelled = 面板收起 / 说了停止；answer = 最终回答；why = 没做完的原因。 */
    public static void agentEnded(int seq, boolean finished, boolean cancelled, String answer, String why, List<String> steps) {
        Goal fin = null;
        synchronized (PcGoals.class) {
            Goal g = active;
            if (g == null || !g.bound || g.seq != seq) return;
            if (steps != null) for (String s : steps) addStep(g, s);
            if (cancelled) end(g, STOPPED, nz(answer).length() > 0 ? answer : "在手机上停掉了");
            else if (finished) end(g, DONE, nz(answer).length() > 0 ? answer : "做完了");
            else end(g, FAILED, nz(answer).length() > 0 ? answer + (nz(why).length() > 0 ? "（" + why + "）" : "") : nz(why).length() > 0 ? why : "没做完");
            fin = g;
        }
        deliver(fin);
    }

    /** 结果 / 回答上屏（简单指令）。ok = 成功；text = 屏上那句话。复合任务在跑时不算（等循环结束）。 */
    public static synchronized void shown(boolean ok, String text) {
        shownState(ok ? DONE : FAILED, text);
    }

    /** 同上，指定状态（拒绝 = {@link #DENIED}）。 */
    public static synchronized void shownState(String state, String text) {
        Goal g = active;
        if (g == null || !g.bound || g.agent) return;
        String t = nz(text).trim();
        if (t.length() == 0) return;
        g.cand = t;
        g.candState = state;
        addStep(g, t);
        g.waitingUser = false; // 结果都上屏了，确认卡肯定已经点完
        g.settleAt = now() + SETTLE_MS;
    }

    /** 手机上弹了 / 收起了确认卡：等人点的时候不收尾。 */
    public static synchronized void waiting(boolean on) {
        Goal g = active;
        if (g == null || !g.bound) return;
        g.waitingUser = on;
        if (on) g.settleAt = Long.MAX_VALUE;
        else if (g.cand != null) g.settleAt = now() + SETTLE_MS;
    }

    /** 在手机上说了「取消 / 停止」（复合任务的取消由 {@link #agentEnded} 报）。 */
    public static void stopped() {
        Goal fin = null;
        synchronized (PcGoals.class) {
            Goal g = active;
            if (g == null || !g.bound || g.agent) return;
            end(g, STOPPED, "在手机上停掉了");
            fin = g;
        }
        deliver(fin);
    }

    /** 看一次要不要收尾（ticker 线程 / 测试调用）。 */
    public static void tick() {
        Goal fin = null;
        synchronized (PcGoals.class) {
            Goal g = active;
            if (g == null) return;
            long t = now();
            if (!g.bound && t - g.at >= BIND_MS) {
                end(g, FAILED, "手机上的 OneSay 没接到这个目标（面板没起来），没做");
            } else if (t - g.at >= TOTAL_MS) {
                end(g, TIMEOUT, g.cand != null ? g.cand + "（手机上做了 " + (TOTAL_MS / 60_000) + " 分钟还没完，先停在这里）" : "手机上做了 " + (TOTAL_MS / 60_000) + " 分钟还没做完");
            } else if (g.bound && !g.agent && !g.waitingUser && g.cand != null && t >= g.settleAt) {
                end(g, g.candState == null ? DONE : g.candState, g.cand);
            } else if (g.bound && !g.agent && !g.waitingUser && g.cand == null && t - g.boundAt >= QUIET_MS) {
                end(g, UNKNOWN, "手机处理了这句话，但屏幕上没有留下文字结果；做成没有要看手机屏幕");
            } else {
                return;
            }
            fin = g;
        }
        deliver(fin);
    }

    /** 常连连上了：补发没发出去的。 */
    public static void flush() {
        List<Goal> l;
        synchronized (PcGoals.class) {
            long t = now();
            for (int i = outbox.size() - 1; i >= 0; i--) if (t - outbox.get(i).endAt > OUTBOX_MS) outbox.remove(i);
            l = new ArrayList<Goal>(outbox);
        }
        Sink s = sink;
        if (s == null) return;
        for (Goal g : l) {
            boolean ok;
            try {
                ok = s.send(g);
            } catch (Throwable e) {
                ok = false;
            }
            if (!ok) return;
            synchronized (PcGoals.class) {
                outbox.remove(g);
            }
        }
    }

    /** 给 operate_status：最近一次的状态（没有返回 null）。 */
    public static synchronized Map<String, Object> status() {
        Goal g = active != null ? active : last;
        if (g == null) return null;
        Map<String, Object> d = new TreeMap<String, Object>();
        d.put("goal_id", g.id);
        d.put("goal", g.goal);
        d.put("state", g.state());
        d.put("text", g.text());
        d.put("minutes_ago", Long.valueOf(Math.max(0, (now() - g.at) / 60000)));
        return d;
    }

    public static synchronized int pending() {
        return outbox.size();
    }

    /** 测试用：清空。 */
    static synchronized void reset() {
        active = null;
        last = null;
        outbox.clear();
        sink = null;
    }

    static synchronized Goal active() {
        return active;
    }

    // ------------------------------------------------------------------ 内部

    private static void end(Goal g, String state, String text) {
        synchronized (g) {
            g.state = state;
            g.text = clip(nz(text).trim(), TEXT_MAX);
            g.endAt = now();
        }
        if (active == g) active = null;
        last = g;
    }

    private static void addStep(Goal g, String s) {
        String t = clip(nz(s).replace('\n', ' ').trim(), 120);
        if (t.length() == 0) return;
        synchronized (g) {
            if (g.steps.size() > 0 && g.steps.get(g.steps.size() - 1).equals(t)) return;
            g.steps.add(t);
            while (g.steps.size() > STEPS_MAX) g.steps.remove(0);
        }
    }

    private static void deliver(Goal g) {
        if (g == null) return;
        Sink s = sink;
        boolean ok = false;
        if (s != null) {
            try {
                ok = s.send(g);
            } catch (Throwable e) {
                ok = false;
            }
        }
        if (!ok) {
            synchronized (PcGoals.class) {
                outbox.add(g);
                while (outbox.size() > OUTBOX_MAX) outbox.remove(0);
            }
        }
    }

    private static synchronized void startTicker() {
        if (clock != null) return; // 测试：手动 tick
        if (ticker != null && ticker.isAlive()) return;
        ticker = com.onesay.assistant.v2.util.Threads.start("pclink-goal", new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        return;
                    }
                    tick();
                    synchronized (PcGoals.class) {
                        if (active == null) {
                            ticker = null;
                            return;
                        }
                    }
                }
            }
        }); // 统一命名线程；没有进行中的目标时自己退出（上面 active == null）
    }

    static String newId() {
        byte[] b = new byte[9];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder("g");
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return s.toString();
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 按字符截（不截半个汉字 / 半个代理对）。 */
    static String clip(String s, int n) {
        if (s.length() <= n) return s;
        int end = n;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }
}
