package com.onesay.assistant.v2.companion;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * [companion] 能力清单（descriptor）：形状按 OACP 0.3 的 oacp.json（oacpVersion / appId / displayName / capabilities[id, description,
 * parameters, completionMode, invoke.android]），函数名用 AppFunctions 风格。我们的调用方式不是 OACP 已有的 activity / broadcast，
 * 而是 signature 权限保护的 provider call，记为 invoke.android.type = "x-provider-call"（扩展字段，见 docs/COMPANION_LINK.md）。
 * 以后要接 OACP / AppFunctions，只需把同一份清单经 OACP 的发现 provider 暴露、或把函数名映射到 AppFunctions。纯 Java。
 */
public final class CompanionDescriptor {
    private CompanionDescriptor() {}

    public static final String OACP_VERSION = "0.3";

    /** 一个函数：id、说明、参数（name:type[:required]）。 */
    static JSONObject cap(String id, String desc, String authority, String invokeType, String... params) throws Exception {
        JSONArray ps = new JSONArray();
        for (String p : params) {
            String[] s = p.split(":");
            ps.put(new JSONObject().put("name", s[0]).put("type", s.length > 1 ? s[1] : "string").put("required", s.length > 2 && "req".equals(s[2])));
        }
        JSONObject android = new JSONObject().put("type", invokeType);
        if ("x-provider-call".equals(invokeType)) android.put("authority", authority).put("method", id);
        else android.put("action", authority).put("extraMethod", id);
        return new JSONObject().put("id", id).put("description", desc).put("parameters", ps).put("completionMode", "sync_result")
                .put("invoke", new JSONObject().put("android", android));
    }

    /** 助手自己的清单（对端反查用）。 */
    public static JSONObject assistant(String appId) {
        try {
            JSONArray caps = new JSONArray().put(cap(CompanionContract.F_GET_ASSISTANT_STATUS, "助手状态：版本、伴侣连接状态", CompanionContract.ACTION_QUERY, "x-ordered-broadcast"));
            return wrap(appId, "OneSay", caps);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject wrap(String appId, String name, JSONArray caps) throws Exception {
        return new JSONObject().put("oacpVersion", OACP_VERSION).put("appId", appId).put("displayName", name).put("capabilities", caps)
                .put("x-onesayLink", new JSONObject().put(CompanionContract.K_IFACE, CompanionContract.IFACE).put(CompanionContract.K_VERSION, CompanionContract.VERSION)
                        .put(CompanionContract.K_MIN_CLIENT, CompanionContract.MIN_PEER_VERSION));
    }

    /** 助手的 hello：状态 JSON + iface / version / caps / descriptor。 */
    public static String helloJson(String appId, String statusJson) {
        try {
            JSONObject o = new JSONObject(statusJson == null ? "{}" : statusJson);
            o.put(CompanionContract.K_IFACE, CompanionContract.IFACE).put(CompanionContract.K_VERSION, CompanionContract.VERSION)
                    .put(CompanionContract.K_MIN_CLIENT, CompanionContract.MIN_PEER_VERSION)
                    .put(CompanionContract.K_CAPS, CompanionContract.joinCaps(CompanionContract.ASSISTANT_CAPS))
                    .put(CompanionContract.K_DESCRIPTOR, assistant(appId));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
