package com.onesay.assistant.v2.notice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日程提醒的纯逻辑：解析 Google Calendar events.list 结果、找会议链接、按提前量生成 {@link IslandNotice}。
 *
 * <ul>
 *   <li>默认开始前 {@link #DEFAULT_LEAD_MIN} 分钟提醒（控制台可改 0–120）；全天日程不提醒；已开始超过 5 分钟的不提醒。</li>
 *   <li>有会议链接（hangoutLink / conferenceData 视频入口 / 地点或备注里的 Meet、Zoom、Teams、腾讯会议、飞书、Webex 链接）→ 动作「加入」。</li>
 *   <li>优先级：有会议链接、有其他参会人、或标题像赶点的事（航班 / 高铁 / 面试 / 考试…）→ HIGH（免打扰时段也提醒，开关可关）；其余 NORMAL。</li>
 *   <li>去重键 {@code cal:<事件 id>:<开始时刻>}：日程改了时间就是一条新提醒，旧的由 {@link NoticeCenter#sync} 撤掉。</li>
 * </ul>
 */
public final class CalendarNotices {

    private CalendarNotices() {}

    public static final int DEFAULT_LEAD_MIN = 10;
    public static final String TAG = "calendar";
    public static final long STARTED_GRACE_MS = 5L * 60000L;

    public static final class Event {
        public final String id, title, location, link;
        public final long start, end;
        public final boolean allDay;
        public final int attendees;

        public Event(String id, String title, long start, long end, boolean allDay, String location, String link, int attendees) {
            this.id = id == null ? "" : id;
            this.title = title == null || title.trim().length() == 0 ? "（无标题日程）" : title.trim();
            this.start = start;
            this.end = end;
            this.allDay = allDay;
            this.location = location == null ? "" : location.trim();
            this.link = link;
            this.attendees = attendees;
        }
    }

    static final Pattern MEETING_URL = Pattern.compile(
            "https?://[A-Za-z0-9.-]*(meet\\.google\\.com|zoom\\.us|zoom\\.com\\.cn|teams\\.microsoft\\.com|teams\\.live\\.com|meeting\\.tencent\\.com"
                    + "|voovmeeting\\.com|webex\\.com|feishu\\.cn|larksuite\\.com|dingtalk\\.com)[^\\s\"'<>）)]*",
            Pattern.CASE_INSENSITIVE);

    static final Pattern URGENT_TITLE = Pattern.compile("航班|飞机|登机|高铁|火车|动车|面试|考试|开考|接机|接人|出发|医院|挂号|手术");

    /** 从文字里找第一个会议链接（只认 http(s) 且域名在白名单里）。 */
    public static String findMeetingLink(String... texts) {
        for (String t : texts) {
            if (t == null) continue;
            Matcher m = MEETING_URL.matcher(t);
            if (m.find()) {
                String u = m.group();
                if (NoticeAction.safeUrl(u)) return u;
            }
        }
        return null;
    }

    /** Google events.list 的 JSON → 事件列表（读不懂的条目跳过）。 */
    public static List<Event> parseGoogle(String json, TimeZone tz) {
        List<Event> out = new ArrayList<Event>();
        if (json == null) return out;
        try {
            JSONArray items = new JSONObject(json).optJSONArray("items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject e = items.optJSONObject(i);
                if (e == null || "cancelled".equals(e.optString("status"))) continue;
                JSONObject st = e.optJSONObject("start"), en = e.optJSONObject("end");
                if (st == null) continue;
                boolean allDay = st.has("date") && !st.has("dateTime");
                long s = allDay ? parseDate(st.optString("date"), tz) : parseRfc3339(st.optString("dateTime"));
                long f = en == null ? s : (en.has("dateTime") ? parseRfc3339(en.optString("dateTime")) : parseDate(en.optString("date"), tz));
                if (s <= 0) continue;
                String link = null;
                String hl = e.optString("hangoutLink", "");
                if (NoticeAction.safeUrl(hl)) link = hl;
                JSONObject cd = e.optJSONObject("conferenceData");
                JSONArray eps = cd == null ? null : cd.optJSONArray("entryPoints");
                if (link == null && eps != null) {
                    for (int k = 0; k < eps.length(); k++) {
                        JSONObject ep = eps.optJSONObject(k);
                        if (ep != null && "video".equals(ep.optString("entryPointType")) && NoticeAction.safeUrl(ep.optString("uri"))) {
                            link = ep.optString("uri");
                            break;
                        }
                    }
                }
                if (link == null) link = findMeetingLink(e.optString("location", ""), e.optString("description", ""));
                int att = 0;
                JSONArray at = e.optJSONArray("attendees");
                if (at != null) for (int k = 0; k < at.length(); k++) {
                    JSONObject a = at.optJSONObject(k);
                    if (a != null && !a.optBoolean("self", false) && !a.optBoolean("resource", false)) att++;
                }
                out.add(new Event(e.optString("id"), e.optString("summary", ""), s, f, allDay, e.optString("location", ""), link, att));
            }
        } catch (Exception ignored) {
            // 返回已解析的部分
        }
        return out;
    }

    public static IslandNotice.Priority priorityOf(Event e) {
        if (e.link != null || e.attendees > 0 || URGENT_TITLE.matcher(e.title).find()) return IslandNotice.Priority.HIGH;
        return IslandNotice.Priority.NORMAL;
    }

    /** 事件 → 提醒（不需要提醒的返回 null）。 */
    public static IslandNotice toNotice(Event e, int leadMin, long now, TimeZone tz) {
        if (e == null || e.allDay || e.start <= 0) return null;
        if (e.start < now - STARTED_GRACE_MS) return null;
        int lead = Math.max(0, Math.min(120, leadMin));
        long showAt = e.start - lead * 60000L;
        long end = e.end > e.start ? e.end : e.start + 30L * 60000L;
        long expire = Math.min(end, e.start + 15L * 60000L);
        if (expire <= now) return null;
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        hm.setTimeZone(tz == null ? TimeZone.getDefault() : tz);
        String when = hm.format(new Date(e.start)) + (e.end > e.start ? "–" + hm.format(new Date(e.end)) : "");
        String summary = when + (e.location.length() > 0 && !e.location.startsWith("http") ? " · " + e.location : "");
        IslandNotice n = new IslandNotice(IslandNotice.Source.CALENDAR, "cal:" + e.id + ":" + e.start, priorityOf(e), e.title, summary);
        n.sourceTag = TAG;
        n.showAt = showAt;
        n.expireAt = expire;
        if (e.link != null) n.action(new NoticeAction("加入", NoticeAction.Kind.OPEN_URL, e.link));
        n.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
        return n;
    }

    public static List<IslandNotice> plan(List<Event> events, int leadMin, long now, TimeZone tz) {
        List<IslandNotice> l = new ArrayList<IslandNotice>();
        if (events == null) return l;
        for (Event e : events) {
            IslandNotice n = toNotice(e, leadMin, now, tz);
            if (n != null) l.add(n);
        }
        return l;
    }

    static long parseRfc3339(String s) {
        if (s == null || s.length() < 19) return 0L;
        try {
            String t = s.trim();
            String base = t.substring(0, 19);
            String rest = t.substring(19);
            int ms = 0;
            if (rest.startsWith(".")) {
                int j = 1;
                while (j < rest.length() && Character.isDigit(rest.charAt(j))) j++;
                String frac = (rest.substring(1, j) + "000").substring(0, 3);
                ms = Integer.parseInt(frac);
                rest = rest.substring(j);
            }
            TimeZone z;
            if (rest.length() == 0 || rest.equalsIgnoreCase("Z")) z = TimeZone.getTimeZone("UTC");
            else z = TimeZone.getTimeZone("GMT" + rest);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(z);
            return f.parse(base).getTime() + ms;
        } catch (Exception e) {
            return 0L;
        }
    }

    static long parseDate(String s, TimeZone tz) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            f.setTimeZone(tz == null ? TimeZone.getDefault() : tz);
            return f.parse(s).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }
}
