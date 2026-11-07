package com.onesay.assistant.v2.jobs;

import java.util.List;

/**
 * 内置模板「每日播报」：今日待办、未读重要邮件标题、天气（lane-a 第 3 项）。纯 Java 的拼稿部分，JVM 可测。
 * 取数在 {@link JobsHooks}（每一项取数都先过 {@link PermissionGuard}：没授权的那项不取，稿子里说「没授权」）。
 *
 * <p>邮件标题、天气资料都是外部数据：只拼进朗读稿，不交给大模型当指令，不触发任何动作。
 */
public final class DailyDigest {

    private DailyDigest() {}

    public static final String TEMPLATE_ID = "daily_digest";
    public static final String TITLE = "每日播报";
    static final int MAX_TODO = 5;
    static final int MAX_MAIL = 3;
    static final int MAX_ITEM = 30;

    /** 一项取数的结果：ok + 内容；没授权 / 没接入 / 失败用 note 说明。 */
    public static final class Part {
        public final List<String> items;
        public final String note;

        public Part(List<String> items, String note) {
            this.items = items;
            this.note = note;
        }

        public static Part of(List<String> items) {
            return new Part(items, null);
        }

        public static Part note(String why) {
            return new Part(null, why);
        }
    }

    /** 新建这个模板任务（默认每天 7:30，提前 5 分钟生成）。 */
    public static JobStore.Job newJob(int hour, int minute) {
        JobStore.Job j = new JobStore.Job();
        j.title = TITLE;
        j.templateId = TEMPLATE_ID;
        j.repeat = com.onesay.assistant.v2.s3.S3Schedule.DAILY;
        j.hour = hour;
        j.minute = minute;
        j.leadMs = JobsEngine.DEFAULT_LEAD_MS;
        j.speak = true;
        return j;
    }

    public static String compose(Part todos, Part mails, String weather) {
        return compose(todos, mails, weather, null);
    }

    static final int MAX_EVENT = 5;

    /**
     * [sched] 2026-10-24：带今日本地日程（放在最前面：「今天有 2 个日程：上午9点 晨会；下午3点 开会。」）。
     * events = null → 不提日程（本地日程关着，和旧稿逐字一致）；空列表 → 「今天没有日程。」。
     */
    public static String compose(Part todos, Part mails, String weather, Part events) {
        StringBuilder sb = new StringBuilder("早上好。");
        if (events != null) {
            if (events.items == null) {
                sb.append("日程").append(events.note == null ? "暂时读不到" : events.note).append("。");
            } else if (events.items.isEmpty()) {
                sb.append("今天没有日程。");
            } else {
                sb.append("今天有 ").append(events.items.size()).append(" 个日程：");
                join(sb, events.items, MAX_EVENT, "；");
                if (events.items.size() > MAX_EVENT) sb.append("等");
                sb.append("。");
            }
        }
        if (todos == null || todos.items == null) {
            sb.append("待办").append(todos == null || todos.note == null ? "暂时读不到" : todos.note).append("。");
        } else if (todos.items.isEmpty()) {
            sb.append("今天没有待办。");
        } else {
            sb.append("今天有 ").append(todos.items.size()).append(" 件待办：");
            join(sb, todos.items, MAX_TODO, "、");
            if (todos.items.size() > MAX_TODO) sb.append("等");
            sb.append("。");
        }
        if (mails == null || mails.items == null) {
            sb.append("邮件").append(mails == null || mails.note == null ? "暂时读不到" : mails.note).append("。");
        } else if (mails.items.isEmpty()) {
            sb.append("没有未读的重要邮件。");
        } else {
            sb.append("未读重要邮件 ").append(mails.items.size()).append(" 封：");
            join(sb, mails.items, MAX_MAIL, "；");
            if (mails.items.size() > MAX_MAIL) sb.append("等");
            sb.append("。");
        }
        String w = clean(weather, 60);
        sb.append(w.length() > 0 ? "天气：" + w + (w.endsWith("。") ? "" : "。") : "天气暂时没查到。");
        return sb.toString();
    }

    private static void join(StringBuilder sb, List<String> items, int max, String sep) {
        int n = 0;
        for (String s : items) {
            if (n >= max) break;
            String c = clean(s, MAX_ITEM);
            if (c.length() == 0) continue;
            if (n > 0) sb.append(sep);
            sb.append(c);
            n++;
        }
    }

    /** 外部文本只当展示：去控制字符 / 双向覆盖字符，截断。 */
    public static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x2028 && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
