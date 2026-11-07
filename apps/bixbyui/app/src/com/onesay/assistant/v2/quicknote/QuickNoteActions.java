package com.onesay.assistant.v2.quicknote;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * [quicknote] R10 推荐动作（纯 Java 规划；执行在 {@link QuickNoteActionRunner}，全部走系统界面由用户确认，不静默执行）。
 * 结果卡最多 {@link #MAX_CARD} 个（D5），详情页列全部可用动作。拨号不放进来（要打电话照旧走红卡）。
 */
public final class QuickNoteActions {

    public static final int MAX_CARD = 3;

    public static final String CALENDAR = "calendar", ALARM = "alarm", NAV = "nav", CONTACT = "contact", OPEN_APP = "open_app",
            COPY = "copy", UNDO = "undo", LINK = "link",
            /** ★ R11 邮件：一点即设本机提醒（S3 待办 + 到点灵动岛提醒；S3 关着 → 系统闹钟）。arg = 提醒时刻|事件时刻。 */
            REMIND = "remind",
            /** ★ R11 邮件：没有时间的待办 → 一点加进待办。 */
            TODO = "todo";

    public static final class Action {
        public final String type, label, arg;

        public Action(String type, String label, String arg) {
            this.type = type;
            this.label = label;
            this.arg = arg == null ? "" : arg;
        }

        @Override public String toString() {
            return type + ":" + label;
        }
    }

    private QuickNoteActions() {}

    /** 全部可用动作（详情页），按推荐顺序。 */
    public static List<Action> all(QuickNoteEntry e, TimeZone tz) {
        return all(e, tz, System.currentTimeMillis());
    }

    public static List<Action> all(QuickNoteEntry e, TimeZone tz, long now) {
        List<Action> out = new ArrayList<Action>();
        if (e == null) return out;
        SimpleDateFormat f = new SimpleDateFormat("M/d HH:mm", Locale.CHINA);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);
        SimpleDateFormat md = new SimpleDateFormat("M/d", Locale.CHINA);
        f.setTimeZone(tz);
        hm.setTimeZone(tz);
        md.setTimeZone(tz);
        if ("邮件".equals(e.category)) {
            // ★ R11 邮件：有将来的时间 → 「提醒 …」（本机）+「加日程 …」（系统日历）；只有待办没时间 → 「加待办」
            MailDigest.Kind k = mailKind(e);
            boolean allDay = e.tags.contains("全天");
            if (e.eventAt > now && k != MailDigest.Kind.NONE) {
                long r = MailDigest.remindAt(k, e.eventAt, allDay, now, tz);
                if (r > 0) out.add(new Action(REMIND, "提醒 " + f.format(new Date(r)), r + "|" + e.eventAt));
                out.add(new Action(CALENDAR, "加日程 " + (allDay ? md.format(new Date(e.eventAt)) : f.format(new Date(e.eventAt))),
                        String.valueOf(e.eventAt)));
            } else if (k != MailDigest.Kind.NONE) {
                out.add(new Action(TODO, "加待办", ""));
            }
        }
        if (e.hasCode()) out.add(new Action(COPY, "复制" + (e.codeKind.length() > 0 ? e.codeKind : "码"), e.codeValue));
        boolean sale = e.tags.contains("开售");
        if (e.eventAt > 0 && !"邮件".equals(e.category)) {
            if (sale) out.add(new Action(ALARM, "抢票闹钟 " + f.format(new Date(e.eventAt - 60000L)), String.valueOf(e.eventAt - 60000L)));
            out.add(new Action(CALENDAR, "加日程 " + f.format(new Date(e.eventAt)), String.valueOf(e.eventAt)));
            if (!sale && "随口记".equals(e.category)) out.add(new Action(ALARM, "设闹钟 " + hm.format(new Date(e.eventAt)), String.valueOf(e.eventAt)));
        }
        QuickNoteEntry.Ent addr = e.firstEnt("ADDRESS");
        if (addr != null) out.add(new Action(NAV, "导航 " + clip(addr.value, 8), addr.value));
        QuickNoteEntry.Ent ph = e.firstEnt("PHONE");
        if (ph != null) {
            QuickNoteEntry.Ent nm = e.firstEnt("NAME");
            out.add(new Action(CONTACT, "存联系人", (nm != null ? nm.value : "") + "|" + ph.value));
        }
        QuickNoteEntry.Ent url = e.firstEnt("URL");
        if (url != null) out.add(new Action(LINK, "打开链接", url.value));
        if (e.appPkg != null && e.appPkg.length() > 0 && !e.appPkg.equals(com.onesay.assistant.v2.BuildVariant.APPLICATION_ID)) {
            out.add(new Action(OPEN_APP, "打开" + (e.appLabel != null && e.appLabel.length() > 0 ? clip(e.appLabel, 6) : "来源"), e.appPkg));
        }
        if (!e.hasCode()) {
            QuickNoteEntry.Ent trk = e.firstEnt("TRACKING");
            if (trk != null) out.add(new Action(COPY, "复制单号", trk.value));
            else if (ph != null) out.add(new Action(COPY, "复制号码", ph.value));
            else out.add(new Action(COPY, "复制", e.summary));
        }
        return out;
    }

    static MailDigest.Kind mailKind(QuickNoteEntry e) {
        if (e.tags.contains("会议")) return MailDigest.Kind.MEETING;
        if (e.tags.contains("截止")) return MailDigest.Kind.DEADLINE;
        if (e.tags.contains("待办")) return MailDigest.Kind.TODO;
        return MailDigest.Kind.NONE;
    }

    /** 结果卡：最多 2 个推荐 + 「撤销」。 */
    public static List<Action> card(QuickNoteEntry e, TimeZone tz) {
        return card(e, tz, System.currentTimeMillis());
    }

    public static List<Action> card(QuickNoteEntry e, TimeZone tz, long now) {
        List<Action> all = all(e, tz, now);
        List<Action> out = new ArrayList<Action>();
        for (Action a : all) {
            if (out.size() >= MAX_CARD - 1) break;
            out.add(a);
        }
        out.add(new Action(UNDO, "撤销", String.valueOf(e == null ? 0 : e.id)));
        return out;
    }

    public static String[] labels(List<Action> l) {
        String[] r = new String[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i).label;
        return r;
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
