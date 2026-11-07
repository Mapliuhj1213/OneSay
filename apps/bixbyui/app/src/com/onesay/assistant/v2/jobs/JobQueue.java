package com.onesay.assistant.v2.jobs;

import java.util.List;

/**
 * 任务队列（lane-a 第 4 项）：排队、插队、岛上显示进度。和定时任务<b>共用同一个状态机</b>（{@link JobMachine}）和同一张 run 表；
 * 队列里的 Run 来源键是 {@code q:}，一次只跑一个（{@link #next}），等确认的那条也占着执行位（不越过它去跑后面的）。纯 Java，JVM 可测。
 *
 * <p>队列项是用户当下亲口交代的事（「排队：…」「插队：…」），不是无人值守的定时任务：执行时走当次对话同样的 ExecutionGate
 * （红卡照样在岛上要确认），不走权限卡预授权（{@link JobsEngine#ADHOC}）。
 */
public final class JobQueue {

    public static final String OWNER_PREFIX = "q:";
    /** 队列最多排多少个（含正在跑的）。 */
    public static final int MAX = 10;

    private final JobsEngine mEngine;

    public JobQueue(JobsEngine engine) {
        mEngine = engine;
    }

    static boolean mine(JobStore.Run r) {
        return r.owner.startsWith(OWNER_PREFIX);
    }

    /** 排队中的（待执行 / 待重试）按执行顺序。 */
    public List<JobStore.Run> waiting() {
        List<JobStore.Run> l = JobStore.Util.inState(mEngine.store().runs(), JobMachine.State.PENDING, JobMachine.State.RETRY_WAIT);
        for (int i = l.size() - 1; i >= 0; i--) if (!mine(l.get(i))) l.remove(i);
        return l;
    }

    /** 正在跑 / 等确认的那一条（没有返回 null）。 */
    public JobStore.Run active() {
        for (JobStore.Run r : JobStore.Util.inState(mEngine.store().runs(), JobMachine.State.RUNNING, JobMachine.State.WAITING_CONFIRM)) {
            if (mine(r)) return r;
        }
        return null;
    }

    /** 排队；满了返回 null。 */
    public JobStore.Run enqueue(String command, long now) {
        if (command == null || command.trim().length() == 0) return null;
        int n = waiting().size() + (active() == null ? 0 : 1);
        if (n >= MAX) return null;
        JobStore.Run r = new JobStore.Run();
        String c = command.trim();
        r.owner = OWNER_PREFIX + (c.length() > 24 ? c.substring(0, 24) : c);
        r.title = com.onesay.assistant.v2.shortcut.ShortcutModel.displayTitle(c);   // 「执行快捷指令#id（名字）」→「快捷指令「名字」」
        r.command = c;
        r.createdAt = now;
        r.updatedAt = now;
        r.maxAttempts = 1;   // 用户当面交代的事：失败就告诉他，不悄悄重试
        mEngine.store().addRun(r);
        return r;
    }

    /** 插队：排到所有等待项前面（不打断正在跑的）。 */
    public JobStore.Run jump(long runId, long now) {
        JobStore.Run r = mEngine.store().run(runId);
        if (r == null || !mine(r) || r.state != JobMachine.State.PENDING) return null;
        int max = 0;
        for (JobStore.Run w : waiting()) max = Math.max(max, w.priority);
        r.priority = max + 1;
        r.updatedAt = now;
        mEngine.store().updateRun(r);
        return r;
    }

    /** 下一条该跑的：有正在跑 / 等确认的就不出（一次一个）；否则排第一的待执行。 */
    public JobStore.Run next() {
        if (active() != null) return null;
        for (JobStore.Run r : waiting()) if (r.state == JobMachine.State.PENDING) return r;
        return null;
    }

    /** 第几位（1 起；正在跑 = 0；不在队列 = -1）。 */
    public int position(long runId) {
        JobStore.Run a = active();
        if (a != null && a.id == runId) return 0;
        List<JobStore.Run> w = waiting();
        for (int i = 0; i < w.size(); i++) if (w.get(i).id == runId) return i + 1;
        return -1;
    }

    public boolean cancel(long runId, long now) {
        JobStore.Run r = mEngine.store().run(runId);
        if (r == null || !mine(r)) return false;
        return mEngine.apply(r, JobMachine.Event.CANCEL, now);
    }

    /** 岛上的进度文字；队列空返回 null。 */
    public String progressText() {
        JobStore.Run a = active();
        List<JobStore.Run> w = waiting();
        if (a == null && w.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        if (a != null) {
            if (com.onesay.assistant.v2.shortcut.ShortcutModel.isRunCommand(a.command) && a.state != JobMachine.State.WAITING_CONFIRM) {
                sb.append(com.onesay.assistant.v2.shortcut.ShortcutModel.RUNNING_TITLE);   // 内部指令「执行快捷指令#id（…）」不上屏
            } else {
                sb.append(a.state == JobMachine.State.WAITING_CONFIRM ? "等你确认：" : "正在：").append(shortly(a.title));
            }
            if (a.progress >= 0) sb.append(" ").append(a.progress).append("%");
            else if (a.step.length() > 0) sb.append(" · ").append(shortly(a.step));
        }
        if (!w.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("还有 ").append(w.size()).append(" 个排队");
        }
        return sb.toString();
    }

    /** 「队列」口头汇报。 */
    public String statusReply() {
        JobStore.Run a = active();
        List<JobStore.Run> w = waiting();
        if (a == null && w.isEmpty()) return "队列是空的。";
        StringBuilder sb = new StringBuilder();
        if (a != null) sb.append(a.state == JobMachine.State.WAITING_CONFIRM ? "在等你确认：" : "正在做：").append(shortly(a.title)).append("。");
        for (int i = 0; i < w.size() && i < 5; i++) {
            sb.append("第 ").append(i + 1).append(" 个：").append(shortly(w.get(i).title)).append("。");
        }
        if (w.size() > 5) sb.append("还有 ").append(w.size() - 5).append(" 个。");
        return sb.toString();
    }

    static String shortly(String s) {
        if (s == null) return "";
        return s.length() <= 16 ? s : s.substring(0, 15) + "…";
    }

    // ------------------------------------------------------------------ 口令解析

    public enum Verb { ENQUEUE, JUMP, STATUS, CLEAR }

    public static final class Parsed {
        public final Verb verb;
        public final String command;

        Parsed(Verb verb, String command) {
            this.verb = verb;
            this.command = command;
        }
    }

    /** 「排队 / 先排上 / 等会儿帮我 …」「插队 …」「队列 / 排队情况」「清空队列」。 */
    public static Parsed parse(String raw) {
        if (raw == null) return null;
        String q = raw.trim().replaceAll("[。！!？?]+$", "");
        if (q.equals("队列") || q.equals("排队情况") || q.equals("队列情况") || q.equals("还有几个任务") || q.equals("任务队列")) {
            return new Parsed(Verb.STATUS, null);
        }
        if (q.equals("清空队列") || q.equals("取消排队")) return new Parsed(Verb.CLEAR, null);
        String[] jump = {"插队：", "插队:", "插队，", "插个队", "插队"};
        for (String p : jump) if (q.startsWith(p) && q.length() > p.length() + 1) return new Parsed(Verb.JUMP, strip(q.substring(p.length())));
        String[] enq = {"排队：", "排队:", "排队，", "排个队", "排队", "先排上", "加到队列", "放进队列"};
        for (String p : enq) if (q.startsWith(p) && q.length() > p.length() + 1) return new Parsed(Verb.ENQUEUE, strip(q.substring(p.length())));
        return null;
    }

    static String strip(String s) {
        String t = s.trim();
        while (t.length() > 0 && "：:，, ".indexOf(t.charAt(0)) >= 0) t = t.substring(1);
        return t.trim();
    }
}
