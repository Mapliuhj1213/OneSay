package com.onesay.assistant.v2.notice;

import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.jobs.PermissionAnalyzer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 定时邮件发送前提醒（纯逻辑）。
 *
 * <p>现有架构里「定时邮件」= 一条 S3 定时 AGENT 任务，指令里要发邮件（{@link PermissionAnalyzer} 分析出 {@link Perm#MAIL_SEND}）。
 * 到点时 A 线只<b>起草 + 存草稿 + 等确认卡</b>，发送只能在确认卡上点「发送」。
 * 所以这里提醒的是「这条定时任务快到点了」：默认提前 {@link #DEFAULT_LEAD_MIN} 分钟，动作：
 * <ul>
 *   <li>立即查看 → 打开定时任务列表（{@link NoticeAction.Kind#VIEW_TASK}），不提前执行、不起草、不发送；</li>
 *   <li>推迟 → 这一次往后挪 {@link #POSTPONE_MIN} 分钟（只改这次的 nextAt，不改任务规格，所以不用重新授权）；</li>
 *   <li>取消发送 → 跳过这一次（重复任务排到下一次；一次性任务停用）。</li>
 * </ul>
 * 「草稿待确认」（定时任务到点后已存好草稿）另有 {@link #draftPending}：立即查看 = 打开确认卡。
 */
public final class ScheduledMailNotices {

    private ScheduledMailNotices() {}

    public static final int DEFAULT_LEAD_MIN = 5;
    public static final int POSTPONE_MIN = 10;
    public static final String TAG = "mail";

    /** ★ integrate 上机：定时邮件扫描的兜底间隔。任务库改动会经 NoticeHooks.onTasksChanged 立刻重扫，提醒的 showAt 进 nextWakeAt，这里只是兜底。 */
    public static final long POLL_MS = 15L * 60000L;

    /**
     * ★ integrate 上机（2026-10-24）：以前恒返回 now —— 共用闹钟据此每 ~1 s 叫醒一次（Note8 上 17 分钟 844 次 RTC_WAKEUP）。
     * 现在：没扫过 → now；扫过 → 上次 + {@link #POLL_MS}。关着 → -1。
     */
    public static long nextPollAt(boolean on, long lastPoll, long now) {
        if (!on) return -1L;
        return lastPoll <= 0 ? now : lastPoll + POLL_MS;
    }
    /** 扫描多远之内的定时任务（提前生成 PENDING 提醒，闹钟按 showAt 叫醒）。 */
    public static final long HORIZON_MS = 36L * 3600000L;

    /** 一条候选定时任务。 */
    public static final class Candidate {
        public final long taskId;
        public final String command;
        public final long plannedAt;
        public final boolean enabled;

        public Candidate(long taskId, String command, long plannedAt, boolean enabled) {
            this.taskId = taskId;
            this.command = command == null ? "" : command;
            this.plannedAt = plannedAt;
            this.enabled = enabled;
        }
    }

    public static boolean sendsMail(String command) {
        try {
            return PermissionAnalyzer.analyze(command, "", null).contains(Perm.MAIL_SEND);
        } catch (Throwable t) {
            return false;
        }
    }

    public static String occurrence(long taskId, long plannedAt) {
        return "s3:" + taskId + "@" + plannedAt;
    }

    /** 「s3:12@1761200000000」→ {12, 1761200000000}；读不懂返回 null。 */
    public static long[] parseOccurrence(String arg) {
        if (arg == null || !arg.startsWith("s3:")) return null;
        int at = arg.indexOf('@');
        if (at < 0) return null;
        try {
            return new long[]{Long.parseLong(arg.substring(3, at)), Long.parseLong(arg.substring(at + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static IslandNotice toNotice(Candidate c, int leadMin, long now, TimeZone tz) {
        if (c == null || !c.enabled || c.plannedAt <= now || c.plannedAt - now > HORIZON_MS || !sendsMail(c.command)) return null;
        int lead = Math.max(1, Math.min(60, leadMin));
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        hm.setTimeZone(tz == null ? TimeZone.getDefault() : tz);
        String occ = occurrence(c.taskId, c.plannedAt);
        IslandNotice n = new IslandNotice(IslandNotice.Source.MAIL, "mail:" + occ, IslandNotice.Priority.NORMAL,
                hm.format(new Date(c.plannedAt)) + " 定时邮件", c.command + "（到点先存草稿，确认卡上点「发送」才会发）");
        n.sourceTag = TAG;
        n.showAt = c.plannedAt - lead * 60000L;
        n.expireAt = c.plannedAt;
        n.action(new NoticeAction("立即查看", NoticeAction.Kind.VIEW_TASK, "s3:" + c.taskId));
        n.action(new NoticeAction("推迟", NoticeAction.Kind.POSTPONE_MAIL, occ + "|" + POSTPONE_MIN));
        n.action(new NoticeAction("取消发送", NoticeAction.Kind.CANCEL_MAIL, occ));
        return n;
    }

    public static List<IslandNotice> plan(List<Candidate> cands, int leadMin, long now, TimeZone tz) {
        List<IslandNotice> l = new ArrayList<IslandNotice>();
        if (cands == null) return l;
        for (Candidate c : cands) {
            IslandNotice n = toNotice(c, leadMin, now, tz);
            if (n != null) l.add(n);
        }
        return l;
    }

    /**
     * 定时任务到点、草稿已存好、等确认：岛上一条提醒。「立即查看」= 打开确认卡（发送仍只在卡上）；「取消发送」= 删草稿；「稍后」= 10 分钟后再提醒。
     * 会话只在内存里（OutboundMail.SESSIONS），所以不落库（persist = false）。
     */
    public static IslandNotice draftPending(String sessionId, String recipient, String subject, long now) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.MAIL, "mail:draft:" + sessionId, IslandNotice.Priority.NORMAL,
                "邮件草稿待确认", "给" + (recipient == null ? "" : recipient) + "：" + (subject == null ? "" : subject) + "（不确认不会发）");
        n.sourceTag = TAG;
        n.persist = false;
        n.createdAt = now;
        n.expireAt = now + 2L * 3600000L;
        n.action(new NoticeAction("立即查看", NoticeAction.Kind.OPEN_MAIL_CARD, sessionId));
        n.action(new NoticeAction("稍后", NoticeAction.Kind.SNOOZE, "10"));
        n.action(new NoticeAction("取消发送", NoticeAction.Kind.CANCEL_MAIL, "draft:" + sessionId));
        return n;
    }

    /** 推迟动作参数「occ|分钟」→ 分钟。 */
    public static int postponeMinutes(String arg) {
        int bar = arg == null ? -1 : arg.lastIndexOf('|');
        if (bar < 0) return POSTPONE_MIN;
        try {
            int m = Integer.parseInt(arg.substring(bar + 1));
            return m > 0 && m <= 240 ? m : POSTPONE_MIN;
        } catch (NumberFormatException e) {
            return POSTPONE_MIN;
        }
    }

    public static String occurrenceOf(String arg) {
        int bar = arg == null ? -1 : arg.lastIndexOf('|');
        return bar < 0 ? arg : arg.substring(0, bar);
    }
}
