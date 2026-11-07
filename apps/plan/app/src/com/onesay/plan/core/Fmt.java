package com.onesay.plan.core;

import com.onesay.plan.core.recur.CnTime;
import com.onesay.plan.core.recur.S3Schedule;
import com.onesay.plan.core.recur.SchedEvent;
import com.onesay.plan.core.recur.SchedRecur;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** 给人看的文案（纯 Java，大白话）。 */
public final class Fmt {
    private Fmt() {}

    static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    /** 「HH:mm」。 */
    public static String hm(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        return String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
    }

    /** 「今天」「明天」「后天」「昨天」，否则「11月8日 周六」（跨年带年份）。 */
    public static String day(long t, long now, TimeZone tz) {
        long d = CnTime.dayStart(t, tz), n = CnTime.dayStart(now, tz);
        int diff = (int) Math.round((d - n) / 86400000.0);
        if (diff == 0) return "今天";
        if (diff == 1) return "明天";
        if (diff == 2) return "后天";
        if (diff == -1) return "昨天";
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        Calendar kn = Calendar.getInstance(tz);
        kn.setTimeInMillis(now);
        String y = k.get(Calendar.YEAR) != kn.get(Calendar.YEAR) ? k.get(Calendar.YEAR) + "年" : "";
        return y + (k.get(Calendar.MONTH) + 1) + "月" + k.get(Calendar.DAY_OF_MONTH) + "日 " + WEEK[k.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** 相对 now 的人读时间（今天 / 明天 / 几月几日 + 钟点）。 */
    public static String when(long t, long now, TimeZone tz) {
        return day(t, now, tz) + " " + hm(t, tz);
    }

    /** 相对时长：「还有 2 小时 10 分」「已过 5 分钟」。 */
    public static String left(long t, long now) {
        long d = t - now;
        boolean past = d < 0;
        d = Math.abs(d) / 60000L;
        String s;
        if (d < 1) s = "不到 1 分钟";
        else if (d < 60) s = d + " 分钟";
        else if (d < 24 * 60) s = (d / 60) + " 小时" + (d % 60 != 0 ? " " + (d % 60) + " 分" : "");
        else s = (d / 1440) + " 天";
        return past ? "已过 " + s : "还有 " + s;
    }

    /** 日程一次发生的副标题行。 */
    public static String eventLine(SchedEvent e, long start, TimeZone tz) {
        String t = e.allDay ? "全天" : hm(start, tz) + (e.endAt > e.startAt ? "–" + hm(start + e.duration(), tz) : "");
        return t + (e.location != null && e.location.length() > 0 ? " · " + e.location : "");
    }

    /** 重复规则的人读描述（不重复为空串）。 */
    public static String repeatLabel(SchedEvent e) {
        return e.repeating() ? SchedRecur.describe(e) : "";
    }

    /** 票种类代码（train/flight/…）→ 中文。 */
    public static String ticketKind(String k) {
        if ("train".equals(k)) return "火车票";
        if ("flight".equals(k)) return "机票";
        if ("bus".equals(k)) return "汽车票";
        if ("show".equals(k)) return "演出票";
        if ("movie".equals(k)) return "电影票";
        return "票";
    }

    /** 票卡片标题。 */
    public static String ticketTitle(Row r) {
        String num = r.s("number");
        String route = r.s("from_place").length() > 0 && r.s("to_place").length() > 0 ? r.s("from_place") + " → " + r.s("to_place") : "";
        if (route.length() > 0) return (num.length() > 0 ? num + " " : "") + route;
        return r.s("title");
    }

    /** 票卡片副标题（时间、地点、座位等）。 */
    public static String ticketLine(Row r, TimeZone tz) {
        StringBuilder sb = new StringBuilder(hm(r.l("depart_at"), tz)).append(" 出发");
        if (r.s("gate").length() > 0) sb.append(" · ").append("flight".equals(r.s("kind")) ? "登机口 " : "检票口 ").append(r.s("gate"));
        if (r.s("seat").length() > 0) sb.append(" · ").append(r.s("seat"));
        if (r.s("venue").length() > 0) sb.append(" · ").append(r.s("venue"));
        return sb.toString();
    }

    /** 票临期提醒正文：「还有 30 分钟开场 · 19:30 · 3 号厅 7 排 8 座 · 取票码 ABC123」。 */
    public static String ticketRemind(Row r, int leadMin, TimeZone tz) {
        String k = r.s("kind");
        boolean show = "movie".equals(k) || "show".equals(k) || "concert".equals(k) || "event".equals(k);
        StringBuilder sb = new StringBuilder(Reminders.leadText(leadMin)).append(show ? "开场" : "出发").append(" · ").append(hm(r.l("depart_at"), tz));
        if (r.s("gate").length() > 0) sb.append(" · ").append("flight".equals(k) ? "登机口 " : "检票口 ").append(r.s("gate"));
        if (r.s("venue").length() > 0) sb.append(" · ").append(r.s("venue"));
        if (r.s("seat").length() > 0) sb.append(" · ").append(r.s("seat"));
        if (r.s("order_no").length() > 0) sb.append(" · ").append(show ? "取票码 " : "订单 ").append(r.s("order_no"));
        return sb.toString();
    }

    /** 触发器代码 → 中文。 */
    public static String triggerLabel(String t) {
        if ("time".equals(t)) return "到点";
        if ("charging".equals(t)) return "充电";
        if ("wifi".equals(t)) return "Wi‑Fi";
        if ("bluetooth".equals(t)) return "蓝牙";
        if ("notification".equals(t)) return "收到通知";
        if ("sms".equals(t)) return "收到短信";
        if ("clip".equals(t)) return "复制内容";
        if ("screenshot".equals(t)) return "截图";
        if ("app_open".equals(t)) return "打开 App";
        if ("screen_on".equals(t)) return "亮屏";
        if ("location".equals(t)) return "到达地点";
        if ("headset".equals(t)) return "耳机";
        if ("battery_low".equals(t)) return "电量低";
        if ("missed_call".equals(t)) return "未接来电";
        if ("web_watch".equals(t)) return "网页有更新";
        return t;
    }

    /** 自动任务的「什么时候」：定时 → 「每天 08:00」；触发 → 「连上 Wi‑Fi「家」时」。 */
    public static String autoWhen(Row r, long now, TimeZone tz) {
        if (Contract.MODE_TIME.equals(r.s("mode"))) {
            return S3Schedule.describe((int) r.l("repeat"), (int) r.l("hour"), (int) r.l("minute"), (int) r.l("week_mask"), r.l("once_at"), now, tz);
        }
        String tt = r.s("trigger_type");
        JSONObject j;
        try {
            j = new JSONObject(r.s("trigger_json").length() == 0 ? "{}" : r.s("trigger_json"));
        } catch (Exception e) {
            j = new JSONObject();
        }
        if (j.optString("describe", "").length() > 0) return j.optString("describe");
        boolean on = j.optBoolean("on", true);
        String target = j.optString("target", "");
        if ("charging".equals(tt)) return on ? "插上充电时" : "拔掉充电时";
        if ("wifi".equals(tt)) return (on ? "连上 " : "断开 ") + (target.length() > 0 ? "Wi‑Fi「" + target + "」" : "任意 Wi‑Fi") + "时";
        if ("bluetooth".equals(tt)) return (on ? "连上 " : "断开 ") + (target.length() > 0 ? "蓝牙「" + target + "」" : "任意蓝牙") + "时";
        if ("notification".equals(tt) || "sms".equals(tt)) {
            JSONArray kw = j.optJSONArray("keywords");
            String k = kw == null || kw.length() == 0 ? "" : "含「" + join(kw) + "」的";
            return "收到" + k + ("sms".equals(tt) ? "短信" : "通知") + "时";
        }
        if ("web_watch".equals(tt)) return "「" + j.optString("query", target) + "」有新结果时";
        return triggerLabel(tt) + (target.length() > 0 ? "「" + target + "」" : "") + "时";
    }

    /** 自动任务动作的人读描述。 */
    public static String autoAction(Row r) {
        String a = r.s("action");
        if (Contract.ACT_REMIND.equals(r.s("action_type"))) return "提醒：" + (a.length() > 0 ? a : r.s("title"));
        if (Contract.ACT_BUILTIN.equals(r.s("action_type"))) return "内置：" + a;
        return "让助手：" + a;
    }

    static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) sb.append("、");
            sb.append(a.optString(i));
        }
        return sb.toString();
    }
}
