package com.onesay.plan.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 伴侣连接（companion link）契约 · 计划 App 侧副本。与 OneSay 助手 v2/companion/CompanionContract、时钟 core/LinkContract 逐项一致
 * （助手 CompanionContractTest 读三边源码比对）。设计：docs/COMPANION_LINK.md（仓库根）。
 *
 * <pre>
 * 通道       已有数据 provider 的 call()（authority / 权限见 {@link Contract}；门卫 AccessPolicy 不变）
 * 握手       link.hello → iface / version / min_client / caps / app_version / descriptor（OACP 0.3 形状的能力清单）
 * 函数       createTask listTasks updateTask completeTask deleteTask createEvent listEvents
 * 结果       Bundle: code（1 成功 / 2 参数不对 / 3 找不到 / 4 不认识）、ok、count、id、data（JSON）、error
 * 反查助手   有序广播 com.onesay.link.action.QUERY（setPackage = 清单 meta-data com.onesay.link.ASSISTANT，
 *            构建时由占位符 ${assistantAppId} 填写），method = link.hello / getAssistantStatus
 * </pre>
 * 纯 Java（org.json 在 JVM 测试里用 json.jar）。
 */
public final class LinkContract {
    private LinkContract() {}

    public static final String IFACE = "onesay.companion";
    public static final int VERSION = 1;
    public static final int MIN_PEER_VERSION = 1;

    public static final String ASSISTANT_PKG = "com.onesay.assistant";
    public static final String DEBUG_SUFFIX = ".debug";
    public static final String META_ASSISTANT = "com.onesay.link.ASSISTANT";
    public static final String ACTION_QUERY = "com.onesay.link.action.QUERY";

    public static String assistantControlPermission(String assistantPkg) {
        return (assistantPkg == null || assistantPkg.length() == 0 ? ASSISTANT_PKG : assistantPkg) + ".permission.CONTROL";
    }

    public static final String M_HELLO = "link.hello";
    public static final String F_CREATE_TASK = "createTask", F_LIST_TASKS = "listTasks", F_UPDATE_TASK = "updateTask",
            F_COMPLETE_TASK = "completeTask", F_DELETE_TASK = "deleteTask", F_CREATE_EVENT = "createEvent", F_LIST_EVENTS = "listEvents";
    public static final String F_GET_ASSISTANT_STATUS = "getAssistantStatus";
    public static final String[] PLAN_CAPS = {F_CREATE_TASK, F_LIST_TASKS, F_UPDATE_TASK, F_COMPLETE_TASK, F_DELETE_TASK, F_CREATE_EVENT, F_LIST_EVENTS};

    public static final String K_IFACE = "iface", K_VERSION = "version", K_MIN_CLIENT = "min_client", K_CAPS = "caps",
            K_APP_VERSION = "app_version", K_DESCRIPTOR = "descriptor", K_CLIENT_VERSION = "client_version", K_CLIENT_PKG = "client_pkg";
    public static final String K_OK = "ok", K_CODE = "code", K_DATA = "data", K_ERROR = "error", K_METHOD = "method";
    public static final String X_ID = "id", X_TITLE = "title", X_DUE_AT = "due_at", X_DONE = "done", X_START_AT = "start_at",
            X_END_AT = "end_at", X_LOCATION = "location", X_FROM = "from", X_TO = "to", X_INCLUDE_DONE = "include_done";
    public static final int RC_OK = 1, RC_BAD_ARGS = 2, RC_NOT_FOUND = 3, RC_UNKNOWN = 4;

    public static boolean isLinkMethod(String m) {
        if (M_HELLO.equals(m)) return true;
        for (String c : PLAN_CAPS) if (c.equals(m)) return true;
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

    /** 助手版本够不够（hello 时对端报 client_version；0 = 没报，按 1 算）。 */
    public static boolean clientOk(int clientVersion) {
        return (clientVersion <= 0 ? 1 : clientVersion) >= MIN_PEER_VERSION;
    }

    /** hello 的键值（Android 层放进 Bundle）。 */
    public static Map<String, Object> hello(String appVersion, String authority) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(K_IFACE, IFACE);
        m.put(K_VERSION, VERSION);
        m.put(K_MIN_CLIENT, MIN_PEER_VERSION);
        m.put(K_CAPS, joinCaps(PLAN_CAPS));
        m.put(K_APP_VERSION, appVersion == null ? "" : appVersion);
        m.put(K_DESCRIPTOR, descriptor(authority).toString());
        return m;
    }

    /** OACP 0.3 形状的能力清单（invoke.android.type = x-provider-call 是我们的扩展）。 */
    public static JSONObject descriptor(String authority) {
        try {
            JSONArray caps = new JSONArray()
                    .put(cap(F_CREATE_TASK, "新建一条待办", authority, "title:string:req", "due_at:integer"))
                    .put(cap(F_LIST_TASKS, "列出待办（默认只列未完成）", authority, "include_done:boolean"))
                    .put(cap(F_UPDATE_TASK, "修改一条待办的内容", authority, "id:integer:req", "title:string:req"))
                    .put(cap(F_COMPLETE_TASK, "勾掉 / 取消勾掉一条待办", authority, "id:integer:req", "done:boolean"))
                    .put(cap(F_DELETE_TASK, "删除一条待办", authority, "id:integer:req"))
                    .put(cap(F_CREATE_EVENT, "新建日程", authority, "title:string:req", "start_at:integer:req", "end_at:integer", "location:string"))
                    .put(cap(F_LIST_EVENTS, "列出时间段内的日程", authority, "from:integer", "to:integer"));
            return new JSONObject().put("oacpVersion", "0.3").put("appId", "com.onesay.plan").put("displayName", "OneSay Plan").put("capabilities", caps)
                    .put("x-onesayLink", new JSONObject().put(K_IFACE, IFACE).put(K_VERSION, VERSION).put(K_MIN_CLIENT, MIN_PEER_VERSION));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject cap(String id, String desc, String authority, String... params) throws Exception {
        JSONArray ps = new JSONArray();
        for (String p : params) {
            String[] s = p.split(":");
            ps.put(new JSONObject().put("name", s[0]).put("type", s.length > 1 ? s[1] : "string").put("required", s.length > 2 && "req".equals(s[2])));
        }
        return new JSONObject().put("id", id).put("description", desc).put("parameters", ps).put("completionMode", "sync_result")
                .put("invoke", new JSONObject().put("android", new JSONObject().put("type", "x-provider-call").put("authority", authority).put("method", id)));
    }
}
