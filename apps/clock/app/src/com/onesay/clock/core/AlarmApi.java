package com.onesay.clock.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * 对外闹钟接口（给同一证书签名的 OneSay 助手用）的纯 Java 部分：动作常量、参数校验、对闹钟表的增删改、结果 JSON。
 *
 * <h3>通道</h3>
 * Android 层是 {@code com.onesay.clock.api.AlarmApiReceiver}：exported，但受 signature 级权限
 * {@link #PERMISSION} 保护（系统只授予与 OneSay Clock 同一证书签名的 App；与 Plan 的 AccessPolicy 同一思路，
 * 不内置任何证书指纹）。调用方用<b>有序广播</b>发 {@code setPackage("com.onesay.clock")} 的 Intent，
 * 接收器把结果写回 resultCode / resultData。
 *
 * <h3>动作与参数（Intent extras）</h3>
 * <pre>
 *   ALARM_SET    hour(0-23) minute(0-59) [days 位掩码 bit0=周日…bit6=周六，0=只响一次] [label ≤ 30 字]
 *   ALARM_TOGGLE id on(boolean)
 *   ALARM_CANCEL id
 *   ALARM_QUERY  （无参数）
 * </pre>
 *
 * <h3>结果</h3>
 * resultCode = {@link #RC_OK} / {@link #RC_BAD_ARGS} / {@link #RC_NOT_FOUND} / {@link #RC_UNKNOWN}；
 * resultData = 当前闹钟表（成功和失败都给，方便调用方如实回话）：
 * <pre>{"ts":毫秒,"count":N,"lastId":本次操作的 id,"alarms":[{"id":1,"h":7,"m":0,"d":0,"on":true,"r":"smart","next":毫秒,"label":""}]}</pre>
 * 调试 / 截图用的闹钟（id ≥ {@link AlarmMath#DEBUG_ID_MIN}）不出现在结果里，也不能经接口改动。
 */
public final class AlarmApi {
    private AlarmApi() { }

    /** signature 级权限（Clock 与 OneSay 助手两边都声明，避免安装先后导致不授予）。 */
    public static final String PERMISSION = "com.onesay.clock.permission.ALARM_CONTROL";

    public static final String A_SET = "com.onesay.clock.action.ALARM_SET";
    public static final String A_TOGGLE = "com.onesay.clock.action.ALARM_TOGGLE";
    public static final String A_CANCEL = "com.onesay.clock.action.ALARM_CANCEL";
    public static final String A_QUERY = "com.onesay.clock.action.ALARM_QUERY";

    public static final String X_HOUR = "hour", X_MINUTE = "minute", X_DAYS = "days", X_ID = "id", X_ON = "on", X_LABEL = "label";

    public static final int RC_OK = 1, RC_BAD_ARGS = 2, RC_NOT_FOUND = 3, RC_UNKNOWN = 4;

    static final int MAX_LABEL = 30;

    /** 一次调用的结果：新的闹钟表（未变时就是原表）、是否改动、本次操作的 id。 */
    public static final class Result {
        public final int code;
        public final List<Alarm> alarms;
        public final boolean changed;
        public final int lastId;

        Result(int code, List<Alarm> alarms, boolean changed, int lastId) {
            this.code = code; this.alarms = alarms; this.changed = changed; this.lastId = lastId;
        }
    }

    /** 是否本接口认识的动作。 */
    public static boolean known(String action) {
        return A_SET.equals(action) || A_TOGGLE.equals(action) || A_CANCEL.equals(action) || A_QUERY.equals(action);
    }

    /**
     * 执行一次调用（纯函数：不改入参 list 里的对象，返回新表）。
     * hour / minute 用 -1 表示「没给」。
     */
    public static Result apply(List<Alarm> in, String action, int id, int hour, int minute, int days, boolean on, String label) {
        List<Alarm> as = copy(in);
        if (A_QUERY.equals(action)) return new Result(RC_OK, as, false, -1);
        if (A_SET.equals(action)) {
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return new Result(RC_BAD_ARGS, as, false, -1);
            days &= 0x7F;
            String lb = cleanLabel(label);
            for (Alarm a : as) {
                if (AlarmMath.isDebugId(a.id)) continue;
                if (a.hour == hour && a.minute == minute && a.days == days) {   // 同一时刻同一重复：打开它，不重复建
                    a.on = true;
                    if (lb.length() > 0) a.label = lb;
                    return new Result(RC_OK, as, true, a.id);
                }
            }
            Alarm a = new Alarm();
            a.id = nextId(as);
            if (a.id <= 0) return new Result(RC_BAD_ARGS, as, false, -1);   // 普通 id 用完（< 900 个）
            a.hour = hour; a.minute = minute; a.days = days; a.on = true; a.label = lb;
            as.add(a);
            return new Result(RC_OK, as, true, a.id);
        }
        if (A_TOGGLE.equals(action) || A_CANCEL.equals(action)) {
            if (id <= 0 || AlarmMath.isDebugId(id)) return new Result(RC_BAD_ARGS, as, false, -1);
            for (int k = 0; k < as.size(); k++) {
                Alarm a = as.get(k);
                if (a.id != id) continue;
                if (A_CANCEL.equals(action)) { as.remove(k); return new Result(RC_OK, as, true, id); }
                boolean ch = a.on != on;
                a.on = on;
                return new Result(RC_OK, as, ch, id);
            }
            return new Result(RC_NOT_FOUND, as, false, id);
        }
        return new Result(RC_UNKNOWN, as, false, -1);
    }

    /** 新闹钟 id：普通闹钟里最大 id + 1（不进调试段 ≥ 900）；用完返回 -1。 */
    static int nextId(List<Alarm> as) {
        int max = 0;
        for (Alarm a : as) if (!AlarmMath.isDebugId(a.id)) max = Math.max(max, a.id);
        int n = max + 1;
        return AlarmMath.isDebugId(n) ? -1 : n;
    }

    /** 标签：去控制 / 双向覆盖字符，截断 30 字。 */
    static String cleanLabel(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_LABEL; i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')) continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    /** 结果 JSON（OneSay 闹钟卡片直接用这个格式渲染）。 */
    public static String dump(List<Alarm> as, int lastId, long now, TimeZone tz) {
        try {
            JSONArray arr = new JSONArray();
            for (Alarm a : as) {
                if (AlarmMath.isDebugId(a.id)) continue;
                arr.put(new JSONObject().put("id", a.id).put("h", a.hour).put("m", a.minute).put("d", a.days & 0x7F)
                        .put("on", a.on).put("r", a.brief ? "smart" : "ring").put("next", AlarmMath.next(a, now, tz))
                        .put("label", a.label == null ? "" : a.label));
            }
            return new JSONObject().put("ts", now).put("count", arr.length()).put("lastId", lastId).put("alarms", arr).toString();
        } catch (Exception e) {
            return "{\"ts\":" + now + ",\"count\":0,\"lastId\":-1,\"alarms\":[]}";
        }
    }

    static List<Alarm> copy(List<Alarm> in) {
        List<Alarm> out = new ArrayList<Alarm>();
        if (in == null) return out;
        for (Alarm a : in) {
            if (a == null) continue;
            try { out.add(Alarm.fromJson(a.toJson())); } catch (Exception ignored) { }
        }
        return out;
    }
}
