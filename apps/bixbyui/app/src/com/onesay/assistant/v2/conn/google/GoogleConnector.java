package com.onesay.assistant.v2.conn.google;

import com.onesay.assistant.v2.conn.Codec;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.ConnTool;
import com.onesay.assistant.v2.conn.Connector;
import com.onesay.assistant.v2.conn.ToolEffect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * [conn/google] Google 日历 + Tasks 连接器（id = google）。
 *
 * <table>
 *   <tr><th>工具</th><th>等级</th><th>接口</th></tr>
 *   <tr><td>conn.google.calendar_list</td><td>READ</td><td>events.list（primary，singleEvents，按开始时间）</td></tr>
 *   <tr><td>conn.google.calendar_create</td><td>WRITE（黄卡）</td><td>events.insert</td></tr>
 *   <tr><td>conn.google.calendar_update</td><td>WRITE（黄卡）</td><td>events.patch（只改给了的字段）</td></tr>
 *   <tr><td>conn.google.tasks_list</td><td>READ</td><td>tasks.list（@default，默认不含已完成）</td></tr>
 *   <tr><td>conn.google.tasks_create</td><td>WRITE（黄卡）</td><td>tasks.insert</td></tr>
 *   <tr><td>conn.google.tasks_update</td><td>WRITE（黄卡）</td><td>tasks.patch（改标题 / 备注 / 截止 / 完成）</td></tr>
 * </table>
 * 没有删除工具（删除日程 / 待办不在本轮范围）。时间按手机时区理解（{@link GTime}）。
 */
public final class GoogleConnector implements Connector {

    public static final String ID = "google";
    static final String CAL = "https://www.googleapis.com/calendar/v3/calendars/primary/events";
    static final String TASKS = "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks";

    public interface Clock {
        long now();
    }

    private final GoogleApi mApi;
    private final TimeZone mTz;
    private final Clock mClock;
    private final LocalCalendar mLocal;

    public GoogleConnector(GoogleApi api, TimeZone tz, Clock clock) {
        this(api, tz, clock, null);
    }

    /** @param local 连不上 Google 时的本机日历退路（可为 null） */
    public GoogleConnector(GoogleApi api, TimeZone tz, Clock clock, LocalCalendar local) {
        mLocal = local;
        mApi = api;
        mTz = tz != null ? tz : TimeZone.getDefault();
        mClock = clock != null ? clock : new Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        };
    }

    @Override public String id() { return ID; }
    @Override public String title() { return "Google 日历 / Tasks"; }
    @Override public boolean exposeToAgent() { return true; }

    boolean calendarOk() {
        return mApi != null && mApi.auth().signedIn() && mApi.auth().hasScope(GoogleScopes.CALENDAR_EVENTS);
    }

    boolean tasksOk() {
        return mApi != null && mApi.auth().signedIn() && mApi.auth().hasScope(GoogleScopes.TASKS);
    }

    @Override
    public boolean isReady() {
        return mApi != null && mApi.auth().configured() && (calendarOk() || tasksOk());
    }

    @Override public ConnResult refreshTools() { return ConnResult.ok("本地定义工具"); }

    @Override
    public List<ConnTool> tools() {
        List<ConnTool> l = new ArrayList<ConnTool>();
        if (!isReady()) return l;
        String timeHint = "时间格式 yyyy-MM-dd HH:mm（按手机时区）或带时区的 RFC3339；全天写 yyyy-MM-dd";
        if (calendarOk()) {
            l.add(tool("calendar_list", "查 Google 日历上的日程。", ToolEffect.READ,
                    props("from", "string", "从哪天开始（yyyy-MM-dd），默认今天", "days", "integer", "查几天，默认 1，上限 14",
                            "query", "string", "关键词过滤（可选）"), null));
            l.add(tool("calendar_create", "在 Google 日历上新建日程（执行前弹确认卡）。" + timeHint, ToolEffect.WRITE,
                    props("summary", "string", "标题", "start", "string", "开始时间", "end", "string", "结束时间（不填 = 开始后 1 小时 / 全天当天）",
                            "location", "string", "地点（可选）", "description", "string", "备注（可选）"), new String[]{"summary", "start"}));
            l.add(tool("calendar_update", "修改一个已有日程（event_id 来自 calendar_list；只改给了的字段；执行前弹确认卡）。" + timeHint, ToolEffect.WRITE,
                    props("event_id", "string", "日程 id", "summary", "string", "新标题", "start", "string", "新开始时间", "end", "string", "新结束时间",
                            "location", "string", "新地点", "description", "string", "新备注"), new String[]{"event_id"}));
        }
        if (tasksOk()) {
            l.add(tool("tasks_list", "查 Google Tasks 待办（默认只列未完成）。", ToolEffect.READ,
                    props("include_completed", "boolean", "是否包含已完成", "max", "integer", "最多几条，默认 20"), null));
            l.add(tool("tasks_create", "新建一条 Google Tasks 待办（执行前弹确认卡）。", ToolEffect.WRITE,
                    props("title", "string", "待办内容", "notes", "string", "备注（可选）", "due", "string", "截止日期 yyyy-MM-dd（可选）"),
                    new String[]{"title"}));
            l.add(tool("tasks_update", "修改 / 完成一条待办（task_id 来自 tasks_list；执行前弹确认卡）。", ToolEffect.WRITE,
                    props("task_id", "string", "待办 id", "title", "string", "新内容", "notes", "string", "新备注", "due", "string", "新截止日期",
                            "completed", "boolean", "true = 标记完成，false = 重新打开"), new String[]{"task_id"}));
        }
        return l;
    }

    private static ConnTool tool(String name, String desc, ToolEffect e, JSONObject props, String[] required) {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object").put("properties", props);
            if (required != null) {
                JSONArray r = new JSONArray();
                for (String s : required) r.put(s);
                schema.put("required", r);
            }
        } catch (Exception ignored) {
        }
        return new ConnTool(ID, name, desc, schema, e);
    }

    private static JSONObject props(String... t) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 2 < t.length; i += 3) o.put(t[i], new JSONObject().put("type", t[i + 1]).put("description", t[i + 2]));
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public ConnResult call(String toolName, JSONObject a) {
        if (a == null) a = new JSONObject();
        try {
            if ("calendar_list".equals(toolName)) return calendarList(a);
            if ("calendar_create".equals(toolName)) return calendarCreate(a);
            if ("calendar_update".equals(toolName)) return calendarUpdate(a);
            if ("tasks_list".equals(toolName)) return tasksList(a);
            if ("tasks_create".equals(toolName)) return tasksCreate(a);
            if ("tasks_update".equals(toolName)) return tasksUpdate(a);
        } catch (Exception e) {
            return ConnResult.fail(ConnResult.Code.BAD_ARGS, "参数处理失败：" + e.getClass().getSimpleName());
        }
        return ConnResult.fail(ConnResult.Code.DENIED, "未知工具 " + toolName);
    }

    // ------------------------------------------------------------------ 日历

    public ConnResult calendarList(JSONObject a) throws Exception {
        long from;
        String f = a.optString("from", "").trim();
        if (f.length() > 0) {
            GTime.When w = GTime.parse(f, mTz);
            if (w == null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "from 看不懂：" + f);
            from = GTime.startOfDay(w.millis, mTz);
        } else {
            from = GTime.startOfDay(mClock.now(), mTz);
        }
        int days = Math.max(1, Math.min(14, a.optInt("days", 1)));
        long to = from + days * 24L * 3600 * 1000;
        ConnResult g = calendarOk() ? googleList(a, from, to, days)
                : ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "没有 Google 日历权限，需要登录 / 重新授权");
        if (g.ok() || !g.shouldFallback() || mLocal == null) return g;
        return localList(from, to, days, "（Google 日历暂时用不了：" + fallbackReason(g) + "，以下是手机本机日历）\n");
    }

    String fallbackReason(ConnResult g) {
        com.onesay.assistant.v2.conn.GoogleReach r = mApi == null ? null : mApi.reach();
        String n = r == null ? "" : r.notice();
        if (n.length() > 0) return n;
        switch (g.code) {
            case UNREACHABLE: return "连不上 Google";
            case TIMEOUT: return "Google 响应超时";
            case TLS_ERROR: return "Google 证书校验失败";
            case CANCELLED: return "请求已取消";
            case AUTH_REVOKED: return "Google 授权失效";
            default: return "未登录";
        }
    }

    ConnResult localList(long from, long to, int days, String note) {
        List<LocalCalendar.Event> l;
        try {
            l = mLocal.events(from, to);
        } catch (SecurityException e) {
            return ConnResult.fail(ConnResult.Code.DENIED, note + "读本机日历需要「日历」权限：请在系统设置 › 应用 › OneSay › 权限里打开");
        } catch (Exception e) {
            return ConnResult.fail(ConnResult.Code.REMOTE_ERROR, note + "读本机日历失败：" + e.getClass().getSimpleName());
        }
        StringBuilder sb = new StringBuilder(note);
        sb.append("本机日历 ").append(l.size()).append(" 个日程");
        java.text.SimpleDateFormat f = GTime.fmt("MM-dd HH:mm", mTz);
        for (int i = 0; i < l.size(); i++) {
            LocalCalendar.Event e = l.get(i);
            sb.append('\n').append(i + 1).append(". ").append(e.allDay ? GTime.fmt("MM-dd", mTz).format(new java.util.Date(e.begin)) + "（全天）"
                    : f.format(new java.util.Date(e.begin))).append(' ').append(e.title);
            if (e.location.length() > 0) sb.append(" @").append(e.location);
        }
        if (l.isEmpty()) sb.append("（空）");
        return ConnResult.ok(sb.toString(), "[]");
    }

    ConnResult googleList(JSONObject a, long from, long to, int days) throws Exception {
        String url = CAL + "?singleEvents=true&orderBy=startTime&maxResults=30&timeMin=" + Codec.urlEncode(GTime.rfc3339(from, mTz))
                + "&timeMax=" + Codec.urlEncode(GTime.rfc3339(to, mTz))
                + (a.optString("query", "").trim().length() > 0 ? "&q=" + Codec.urlEncode(a.optString("query").trim()) : "");
        ConnResult r = mApi.get(url);
        if (!r.ok()) return r;
        JSONArray items = new JSONObject(r.data).optJSONArray("items");
        JSONArray out = new JSONArray();
        StringBuilder sb = new StringBuilder();
        int n = items == null ? 0 : items.length();
        sb.append(GTime.human(GTime.rfc3339(from, mTz), null, mTz).substring(0, 5)).append(days > 1 ? " 起 " + days + " 天" : "").append(" 日程 ").append(n).append(" 个");
        for (int i = 0; i < n; i++) {
            JSONObject e = items.getJSONObject(i);
            JSONObject st = e.optJSONObject("start"), en = e.optJSONObject("end");
            String when = GTime.human(st != null ? st.optString("dateTime", null) : null, st != null ? st.optString("date", null) : null, mTz);
            String endH = en == null ? "" : GTime.human(en.optString("dateTime", null), null, mTz);
            sb.append('\n').append(i + 1).append(". ").append(when).append(endH.length() > 6 ? "–" + endH.substring(6) : "")
                    .append(" ").append(e.optString("summary", "(无标题)"));
            if (e.optString("location", "").length() > 0) sb.append(" @").append(e.optString("location"));
            sb.append("  [event_id=").append(e.optString("id")).append(']');
            out.put(new JSONObject().put("id", e.optString("id")).put("summary", e.optString("summary")).put("when", when)
                    .put("location", e.optString("location", "")));
        }
        if (n == 0) sb.append("（空）");
        return ConnResult.ok(sb.toString(), out.toString());
    }

    /** 把 start / end 参数写成 Google 的 {dateTime} 或 {date}。 */
    JSONObject timeObj(GTime.When w) throws Exception {
        return w.allDay() ? new JSONObject().put("date", w.date) : new JSONObject().put("dateTime", w.dateTime).put("timeZone", mTz.getID());
    }

    public ConnResult calendarCreate(JSONObject a) throws Exception {
        if (!calendarOk()) return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "没有 Google 日历权限");
        String summary = a.optString("summary", "").trim();
        if (summary.length() == 0) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "缺标题 summary");
        GTime.When st = GTime.parse(a.optString("start", ""), mTz);
        if (st == null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "开始时间看不懂：" + a.optString("start"));
        GTime.When en = a.optString("end", "").trim().length() > 0 ? GTime.parse(a.optString("end"), mTz) : null;
        if (a.optString("end", "").trim().length() > 0 && en == null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "结束时间看不懂");
        if (en == null) {
            if (st.allDay()) {
                String next = GTime.fmt("yyyy-MM-dd", mTz).format(new java.util.Date(st.millis + 24L * 3600 * 1000));
                en = GTime.parse(next, mTz);
            } else {
                en = GTime.parse(GTime.rfc3339(st.millis + 3600 * 1000L, mTz), mTz);
            }
        }
        if (en.millis <= st.millis) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "结束时间要晚于开始时间");
        JSONObject body = new JSONObject().put("summary", summary).put("start", timeObj(st)).put("end", timeObj(en));
        if (a.optString("location", "").length() > 0) body.put("location", a.optString("location"));
        if (a.optString("description", "").length() > 0) body.put("description", a.optString("description"));
        ConnResult r = mApi.post(CAL, body);
        if (r.code == ConnResult.Code.TIMEOUT) {
            // 只有读阶段超时才到这里（连接阶段超时已归 UNREACHABLE，见 ConnErrors）：
            // 请求可能已经到了 Google，不自动转本机，免得建重
            return ConnResult.fail(ConnResult.Code.TIMEOUT, "Google 没有及时响应，日程可能已经建好也可能没有；请稍后用 calendar_list 确认，别重复新建");
        }
        if (r.code == ConnResult.Code.UNREACHABLE && mLocal != null) {
            boolean opened = mLocal.openInsert(summary, st.millis, en.millis, st.allDay(), a.optString("location", ""), a.optString("description", ""));
            if (opened) {
                return ConnResult.ok("（" + fallbackReason(r) + "）已改为打开手机日历的新建页面，并填好「" + summary + "」"
                        + GTime.human(st.dateTime, st.date, mTz) + "，请在日历里点「保存」。这条日程不会同步到 Google 日历。");
            }
        }
        if (!r.ok()) return r;
        JSONObject e = new JSONObject(r.data);
        return ConnResult.ok("已在 Google 日历新建「" + summary + "」：" + GTime.human(st.dateTime, st.date, mTz)
                + "（event_id=" + e.optString("id") + "）", new JSONObject().put("id", e.optString("id")).toString());
    }

    public ConnResult calendarUpdate(JSONObject a) throws Exception {
        if (!calendarOk()) return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "没有 Google 日历权限");
        String id = a.optString("event_id", "").trim();
        if (!id.matches("[A-Za-z0-9_\\-]{1,1024}")) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "event_id 不合法");
        JSONObject body = new JSONObject();
        for (String k : new String[]{"summary", "location", "description"}) {
            if (a.has(k)) body.put(k, a.optString(k));
        }
        for (String k : new String[]{"start", "end"}) {
            if (a.optString(k, "").trim().length() == 0) continue;
            GTime.When w = GTime.parse(a.optString(k), mTz);
            if (w == null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, k + " 时间看不懂");
            body.put(k, timeObj(w));
        }
        if (body.length() == 0) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "没有要改的字段");
        ConnResult r = mApi.patch(CAL + "/" + Codec.urlEncode(id), body);
        if (r.code == ConnResult.Code.UNREACHABLE || r.code == ConnResult.Code.TIMEOUT || r.code == ConnResult.Code.TLS_ERROR) {
            return ConnResult.fail(r.code, fallbackReason(r) + "：Google 日历上的日程现在改不了，等能连上 Google 再试");
        }
        if (!r.ok()) return r.code == ConnResult.Code.BAD_ARGS ? ConnResult.fail(ConnResult.Code.BAD_ARGS, "找不到这个日程或参数不对：" + r.text) : r;
        JSONObject e = new JSONObject(r.data);
        return ConnResult.ok("日程已更新：「" + e.optString("summary") + "」");
    }

    // ------------------------------------------------------------------ Tasks

    public ConnResult tasksList(JSONObject a) throws Exception {
        if (!tasksOk()) return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "没有 Google Tasks 权限，需要登录 / 重新授权");
        int max = Math.max(1, Math.min(100, a.optInt("max", 20)));
        boolean all = a.optBoolean("include_completed", false);
        ConnResult r = mApi.get(TASKS + "?maxResults=" + max + "&showCompleted=" + all + "&showHidden=" + all);
        if (!r.ok()) return tasksOffline(r);
        JSONArray items = new JSONObject(r.data).optJSONArray("items");
        JSONArray out = new JSONArray();
        StringBuilder sb = new StringBuilder();
        int n = items == null ? 0 : items.length();
        sb.append("Google Tasks 待办 ").append(n).append(" 条");
        for (int i = 0; i < n; i++) {
            JSONObject t = items.getJSONObject(i);
            boolean done = "completed".equals(t.optString("status"));
            String due = t.optString("due", "");
            sb.append('\n').append(i + 1).append(". ").append(done ? "[已完成] " : "").append(t.optString("title", "(无标题)"))
                    .append(due.length() >= 10 ? "（截止 " + due.substring(5, 10) + "）" : "").append("  [task_id=").append(t.optString("id")).append(']');
            out.put(new JSONObject().put("id", t.optString("id")).put("title", t.optString("title")).put("done", done).put("due", due));
        }
        if (n == 0) sb.append("（空）");
        return ConnResult.ok(sb.toString(), out.toString());
    }

    public ConnResult tasksCreate(JSONObject a) throws Exception {
        if (!tasksOk()) return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "没有 Google Tasks 权限");
        String title = a.optString("title", "").trim();
        if (title.length() == 0) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "缺待办内容 title");
        JSONObject body = new JSONObject().put("title", title);
        if (a.optString("notes", "").length() > 0) body.put("notes", a.optString("notes"));
        if (a.optString("due", "").trim().length() > 0) {
            GTime.When w = GTime.parse(a.optString("due"), mTz);
            if (w == null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "截止日期看不懂");
            body.put("due", GTime.taskDue(w, mTz));
        }
        ConnResult r = mApi.post(TASKS, body);
        if (!r.ok()) return tasksOffline(r);
        return ConnResult.ok("已新建待办「" + title + "」（task_id=" + new JSONObject(r.data).optString("id") + "）");
    }

    public ConnResult tasksUpdate(JSONObject a) throws Exception {
        if (!tasksOk()) return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "没有 Google Tasks 权限");
        String id = a.optString("task_id", "").trim();
        if (!id.matches("[A-Za-z0-9_\\-]{1,1024}")) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "task_id 不合法");
        JSONObject body = new JSONObject();
        if (a.has("title")) body.put("title", a.optString("title"));
        if (a.has("notes")) body.put("notes", a.optString("notes"));
        if (a.optString("due", "").trim().length() > 0) {
            GTime.When w = GTime.parse(a.optString("due"), mTz);
            if (w == null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "截止日期看不懂");
            body.put("due", GTime.taskDue(w, mTz));
        }
        if (a.has("completed")) {
            boolean done = a.optBoolean("completed");
            body.put("status", done ? "completed" : "needsAction");
            if (!done) body.put("completed", JSONObject.NULL);
        }
        if (body.length() == 0) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "没有要改的字段");
        ConnResult r = mApi.patch(TASKS + "/" + Codec.urlEncode(id), body);
        if (!r.ok()) return tasksOffline(r);
        JSONObject t = new JSONObject(r.data);
        return ConnResult.ok("待办已更新：「" + t.optString("title") + "」" + ("completed".equals(t.optString("status")) ? "（已完成）" : ""));
    }

    /** Tasks 没有本机对应物：连不上时说清楚，并提示可改用本机待办（S3「记个待办」）。 */
    ConnResult tasksOffline(ConnResult r) {
        if (r.code != ConnResult.Code.UNREACHABLE && r.code != ConnResult.Code.TIMEOUT && r.code != ConnResult.Code.TLS_ERROR) return r;
        return ConnResult.fail(r.code, fallbackReason(r) + "：Google Tasks 暂时用不了。可以先记到本机待办（对我说「记个待办…」），等能连上再同步");
    }

    @Override
    public String status() {
        if (mApi == null) return "google -";
        return mApi.auth().status() + " calendar=" + calendarOk() + " tasks=" + tasksOk();
    }
}
