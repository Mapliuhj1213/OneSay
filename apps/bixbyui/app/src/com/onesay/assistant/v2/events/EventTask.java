package com.onesay.assistant.v2.events;

import com.onesay.assistant.v2.jobs.JobStore;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 事件任务的定义（lane-a 第 7 项 P1 事件触发）。纯 Java，JVM 可测；存 jobs.db 的 event_task 表（{@link SqliteEventStore}）。
 *
 * <ul>
 *   <li>触发源 {@link Source}：充电（插上 / 拔掉）、Wi‑Fi（连上 / 断开某个 SSID）、蓝牙（某设备连上 / 断开）、通知（白名单 App + 白名单关键词）；</li>
 *   <li>防护：{@link #debounceMs} 防抖、{@link #windowStart}/{@link #windowEnd} 时段守卫、{@link #dailyCap} 每天上限、
 *       连续失败 {@link #maxFailures} 次自动暂停（{@link #paused}）；</li>
 *   <li>授权：任务按 {@link #asJob()} 描述成一个 {@link JobStore.Job}，与定时任务共用 jobs.db 的授权表（键 {@code ev:<id>}）、
 *       {@link com.onesay.assistant.v2.jobs.TaskGrant} 指纹和权限卡。<b>规格只含用户配置的条件和指令，不含任何事件内容</b>：
 *       通知原文再怎么写也改不了授权清单（扩不了权限）。</li>
 * </ul>
 */
public final class EventTask {

    public enum Source {
        CHARGING("充电"), WIFI("Wi‑Fi"), BLUETOOTH("蓝牙"), NOTIFICATION("通知");

        public final String label;

        Source(String label) {
            this.label = label;
        }

        public static Source parse(String s) {
            if (s == null) return null;
            try {
                return Source.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** 防抖默认 10 分钟（同一任务两次触发的最小间隔）。 */
    public static final long DEFAULT_DEBOUNCE_MS = 10L * 60L * 1000L;
    public static final int DEFAULT_DAILY_CAP = 5;
    public static final int DEFAULT_MAX_FAILURES = 3;
    /** 白名单上限（App / 关键词各自）。 */
    public static final int MAX_LIST = 10;

    public long id;
    public String title = "";
    public Source source = Source.CHARGING;
    /** true = 连上 / 插上 / 收到通知；false = 断开 / 拔掉（通知只有 true）。 */
    public boolean onConnect = true;
    /** Wi‑Fi：SSID（精确匹配，空 = 任意）；蓝牙：设备名或 MAC（空 = 任意）；充电 / 通知不用。 */
    public String target = "";
    /** 通知：白名单 App 包名（空 = 不处理任何通知）。 */
    public List<String> apps = new ArrayList<String>();
    /** 通知：白名单关键词（标题或正文含其一才触发；空 = 不触发）。 */
    public List<String> keywords = new ArrayList<String>();
    /** 触发后要做的事（AGENT 指令，用户写的）。 */
    public String command = "";
    public long debounceMs = DEFAULT_DEBOUNCE_MS;
    /** 时段守卫：一天中的分钟数，-1 = 全天。start &gt; end 表示跨午夜。 */
    public int windowStart = -1, windowEnd = -1;
    public int dailyCap = DEFAULT_DAILY_CAP;
    public int maxFailures = DEFAULT_MAX_FAILURES;
    public boolean enabled = true;

    // ---- 运行态（不参与规格 / 授权指纹）
    public boolean paused;
    public String pausedReason = "";
    public int failures;
    public long lastFireAt;
    public String dayKey = "";
    public int firesToday;
    public long createdAt;

    public String grantKey() {
        return "ev:" + id;
    }

    /** 模板 id 前缀：{@code ev.<源>#<条件摘要>}（{@link com.onesay.assistant.v2.jobs.PermissionAnalyzer#forTemplate} 按源给出读状态 / 读通知权限）。 */
    public static final String TPL_PREFIX = "ev.";

    /** 条件规格（参与授权指纹：改了触发源 / 条件 / 白名单 / 守卫 → 重新授权）。 */
    public String conditionSpec() {
        StringBuilder sb = new StringBuilder();
        sb.append("src=").append(source.name()).append("|on=").append(onConnect).append("|target=").append(target.trim())
                .append("|apps=");
        for (String a : apps) sb.append(a.trim()).append(',');
        sb.append("|kw=");
        for (String k : keywords) sb.append(k.trim()).append(',');
        sb.append("|db=").append(debounceMs).append("|win=").append(windowStart).append('-').append(windowEnd)
                .append("|cap=").append(dailyCap).append("|fail=").append(maxFailures);
        return sb.toString();
    }

    /** 授权用的任务描述：templateId = ev.&lt;源&gt;#&lt;条件摘要&gt;，command = 用户指令（只有这两样参与静态分析 / 指纹）。 */
    public JobStore.Job asJob() {
        JobStore.Job j = new JobStore.Job();
        j.id = id;
        j.title = title.length() > 0 ? title : describeTrigger();
        j.templateId = TPL_PREFIX + source.name() + "#" + sha16(conditionSpec());
        j.command = command;
        j.speak = false;
        j.enabled = enabled;
        return j;
    }

    /** 给人看的触发条件。 */
    public String describeTrigger() {
        switch (source) {
            case CHARGING: return onConnect ? "插上充电时" : "拔掉充电时";
            case WIFI: return (onConnect ? "连上 " : "断开 ") + (target.length() > 0 ? "Wi‑Fi「" + target + "」" : "任意 Wi‑Fi") + "时";
            case BLUETOOTH: return (onConnect ? "连上 " : "断开 ") + (target.length() > 0 ? "蓝牙「" + target + "」" : "任意蓝牙设备") + "时";
            default: {
                StringBuilder sb = new StringBuilder("收到 ");
                sb.append(apps.isEmpty() ? "（未设白名单 App）" : join(apps)).append(" 含「").append(keywords.isEmpty() ? "（未设关键词）" : join(keywords))
                        .append("」的通知时");
                return sb.toString();
            }
        }
    }

    public EventTask copy() {
        try {
            EventTask t = fromJson(toJson());
            t.id = id;
            return t;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 序列化

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("source", source.name());
            o.put("on", onConnect);
            o.put("target", target);
            o.put("apps", new org.json.JSONArray(apps));
            o.put("keywords", new org.json.JSONArray(keywords));
            o.put("command", command);
            o.put("debounceMs", debounceMs);
            o.put("windowStart", windowStart);
            o.put("windowEnd", windowEnd);
            o.put("dailyCap", dailyCap);
            o.put("maxFailures", maxFailures);
            o.put("enabled", enabled);
            o.put("paused", paused);
            o.put("pausedReason", pausedReason);
            o.put("failures", failures);
            o.put("lastFireAt", lastFireAt);
            o.put("dayKey", dayKey);
            o.put("firesToday", firesToday);
            o.put("createdAt", createdAt);
            return o.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static EventTask fromJson(String json) throws org.json.JSONException {
        JSONObject o = new JSONObject(json);
        EventTask t = new EventTask();
        t.title = o.optString("title", "");
        Source s = Source.parse(o.optString("source", ""));
        t.source = s == null ? Source.CHARGING : s;
        t.onConnect = o.optBoolean("on", true);
        t.target = o.optString("target", "");
        t.apps = list(o.optJSONArray("apps"));
        t.keywords = list(o.optJSONArray("keywords"));
        t.command = o.optString("command", "");
        t.debounceMs = o.optLong("debounceMs", DEFAULT_DEBOUNCE_MS);
        t.windowStart = o.optInt("windowStart", -1);
        t.windowEnd = o.optInt("windowEnd", -1);
        t.dailyCap = o.optInt("dailyCap", DEFAULT_DAILY_CAP);
        t.maxFailures = o.optInt("maxFailures", DEFAULT_MAX_FAILURES);
        t.enabled = o.optBoolean("enabled", true);
        t.paused = o.optBoolean("paused", false);
        t.pausedReason = o.optString("pausedReason", "");
        t.failures = o.optInt("failures", 0);
        t.lastFireAt = o.optLong("lastFireAt", 0L);
        t.dayKey = o.optString("dayKey", "");
        t.firesToday = o.optInt("firesToday", 0);
        t.createdAt = o.optLong("createdAt", 0L);
        return t;
    }

    private static List<String> list(org.json.JSONArray a) {
        List<String> l = new ArrayList<String>();
        if (a == null) return l;
        for (int i = 0; i < a.length() && l.size() < MAX_LIST; i++) {
            String s = a.optString(i, "").trim();
            if (s.length() > 0) l.add(s);
        }
        return l;
    }

    /** 逗号 / 顿号 / 空白分隔的输入 → 列表（去空、去重、最多 {@link #MAX_LIST} 项）。 */
    public static List<String> splitList(String s) {
        List<String> l = new ArrayList<String>();
        if (s == null) return l;
        for (String x : s.split("[,，、;；\\s]+")) {
            String v = x.trim();
            if (v.length() > 0 && !l.contains(v) && l.size() < MAX_LIST) l.add(v);
        }
        return l;
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : "、").append(s);
        return sb.toString();
    }

    static String sha16(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 8; i++) h.append(String.format("%02x", d[i] & 0xff));
            return h.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    @Override public String toString() {
        return "ev#" + id + "[" + source + (paused ? " 暂停" : "") + (enabled ? "" : " 关") + "]";
    }
}
