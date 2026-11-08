package com.onesay.clock.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 伴侣连接（companion link）契约 · 时钟侧副本。与 OneSay 助手 v2/companion/CompanionContract、计划 core/LinkContract 逐项一致
 * （助手 CompanionContractTest 读三边源码比对）。设计：docs/COMPANION_LINK.md（仓库根）。
 *
 * <pre>
 * 通道       ContentProvider {@link #AUTHORITY} 的 call()：exported，受 signature 级 {@link AlarmApi#PERMISSION} 保护，代码里再验同证书
 * 握手       link.hello → iface / version / min_client / caps / app_version / descriptor（OACP 0.3 形状）
 * 函数       createAlarm listAlarms deleteAlarm toggleAlarm（= 旧 ALARM_SET / QUERY / CANCEL / TOGGLE，参数与结果完全相同）
 *            getBriefStatus（files/state.json 原文：state / detail / at …）
 * 反查助手   有序广播 com.onesay.link.action.QUERY 发给清单 meta-data com.onesay.link.ASSISTANT（占位符 ${assistantAppId}）
 * </pre>
 * 纯 Java（JVM 测试 LinkTest）。
 */
public final class LinkContract {
    private LinkContract() { }

    public static final String IFACE = "onesay.companion";
    public static final int VERSION = 1;
    public static final int MIN_PEER_VERSION = 1;

    public static final String AUTHORITY = "com.onesay.clock.link";
    public static final String ASSISTANT_PKG = "com.onesay.assistant";
    public static final String DEBUG_SUFFIX = ".debug";
    public static final String META_ASSISTANT = "com.onesay.link.ASSISTANT";
    public static final String ACTION_QUERY = "com.onesay.link.action.QUERY";

    public static String assistantControlPermission(String assistantPkg) {
        return (assistantPkg == null || assistantPkg.length() == 0 ? ASSISTANT_PKG : assistantPkg) + ".permission.CONTROL";
    }

    public static final String M_HELLO = "link.hello";
    public static final String F_CREATE_ALARM = "createAlarm", F_LIST_ALARMS = "listAlarms", F_DELETE_ALARM = "deleteAlarm",
            F_TOGGLE_ALARM = "toggleAlarm", F_GET_BRIEF_STATUS = "getBriefStatus";
    public static final String F_GET_ASSISTANT_STATUS = "getAssistantStatus";
    public static final String[] CLOCK_CAPS = {F_CREATE_ALARM, F_LIST_ALARMS, F_DELETE_ALARM, F_TOGGLE_ALARM, F_GET_BRIEF_STATUS};

    public static final String K_IFACE = "iface", K_VERSION = "version", K_MIN_CLIENT = "min_client", K_CAPS = "caps",
            K_APP_VERSION = "app_version", K_DESCRIPTOR = "descriptor", K_CLIENT_VERSION = "client_version", K_CLIENT_PKG = "client_pkg";
    public static final String K_OK = "ok", K_CODE = "code", K_DATA = "data", K_ERROR = "error", K_METHOD = "method";
    public static final String X_ID = "id", X_HOUR = "hour", X_MINUTE = "minute", X_DAYS = "days", X_ON = "on", X_LABEL = "label";
    public static final int RC_OK = 1, RC_BAD_ARGS = 2, RC_NOT_FOUND = 3, RC_UNKNOWN = 4;

    static final String[][] ALARM_MAP = {
            {AlarmApi.A_SET, F_CREATE_ALARM},
            {AlarmApi.A_QUERY, F_LIST_ALARMS},
            {AlarmApi.A_CANCEL, F_DELETE_ALARM},
            {AlarmApi.A_TOGGLE, F_TOGGLE_ALARM},
    };

    /** 函数名 → 旧广播动作（闹钟四个）；不是闹钟函数返回 null。 */
    public static String alarmAction(String fn) {
        for (String[] m : ALARM_MAP) if (m[1].equals(fn)) return m[0];
        return null;
    }

    public static boolean isLinkMethod(String m) {
        if (M_HELLO.equals(m)) return true;
        for (String c : CLOCK_CAPS) if (c.equals(m)) return true;
        return false;
    }

    public static String joinCaps(String[] caps) {
        StringBuilder sb = new StringBuilder();
        for (String c : caps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        return sb.toString();
    }

    public static boolean clientOk(int clientVersion) {
        return (clientVersion <= 0 ? 1 : clientVersion) >= MIN_PEER_VERSION;
    }

    public static Map<String, Object> hello(String appVersion) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(K_IFACE, IFACE);
        m.put(K_VERSION, VERSION);
        m.put(K_MIN_CLIENT, MIN_PEER_VERSION);
        m.put(K_CAPS, joinCaps(CLOCK_CAPS));
        m.put(K_APP_VERSION, appVersion == null ? "" : appVersion);
        m.put(K_DESCRIPTOR, descriptor().toString());
        return m;
    }

    public static JSONObject descriptor() {
        try {
            JSONArray caps = new JSONArray()
                    .put(cap(F_CREATE_ALARM, "设一个闹钟（同一时刻同一重复只打开不重复建）", "hour:integer:req", "minute:integer:req", "days:integer", "label:string"))
                    .put(cap(F_LIST_ALARMS, "列出闹钟（调试闹钟 id≥900 不列）"))
                    .put(cap(F_DELETE_ALARM, "删除一个闹钟", "id:integer:req"))
                    .put(cap(F_TOGGLE_ALARM, "打开 / 关闭一个闹钟", "id:integer:req", "on:boolean:req"))
                    .put(cap(F_GET_BRIEF_STATUS, "晨间播报当前状态（IDLE / GENERATING / READY / RINGING / PLAYING / DONE / ERROR）"));
            return new JSONObject().put("oacpVersion", "0.3").put("appId", "com.onesay.clock").put("displayName", "OneSay Clock").put("capabilities", caps)
                    .put("x-onesayLink", new JSONObject().put(K_IFACE, IFACE).put(K_VERSION, VERSION).put(K_MIN_CLIENT, MIN_PEER_VERSION));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject cap(String id, String desc, String... params) throws Exception {
        JSONArray ps = new JSONArray();
        for (String p : params) {
            String[] s = p.split(":");
            ps.put(new JSONObject().put("name", s[0]).put("type", s.length > 1 ? s[1] : "string").put("required", s.length > 2 && "req".equals(s[2])));
        }
        return new JSONObject().put("id", id).put("description", desc).put("parameters", ps).put("completionMode", "sync_result")
                .put("invoke", new JSONObject().put("android", new JSONObject().put("type", "x-provider-call").put("authority", AUTHORITY).put("method", id)));
    }

    /** state.json 原文是否像一份状态（getBriefStatus 返回前的自检）。 */
    public static boolean looksLikeState(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String s = o.optString("state", "");
            for (String k : StateMsg.STATES) if (k.equals(s)) return o.has("at");
        } catch (Exception ignored) { }
        return false;
    }
}
