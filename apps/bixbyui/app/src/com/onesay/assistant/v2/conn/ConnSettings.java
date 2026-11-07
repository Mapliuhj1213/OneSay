package com.onesay.assistant.v2.conn;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.conn.mcp.McpServerConfig;
import com.onesay.assistant.v2.kernel.FeatureSwitch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [conn] 连接器的非密钥配置：总开关 bixby_conn.enabled（默认开；没配置任何连接器时什么都不做）/ kill-switch conn.off；
 * MCP 服务器列表（JSON）与各自的工具清单缓存。密钥一律在 {@link PrefsSecretStore}（bixby_conn_secrets，Keystore 加密）。
 */
public final class ConnSettings {

    private ConnSettings() {}

    public static final String PREFS = "bixby_conn";
    public static final FeatureSwitch SWITCH = new FeatureSwitch(PREFS, "enabled", true, "conn.off");

    static final String KEY_MCP = "mcp_servers";
    static final String KEY_CACHE = "mcp_cache_";
    static final String KEY_CACHE_AT = "mcp_cache_at_";
    /** Google OAuth Client ID（桌面应用类型；不是秘密，但也不内置——用户自己在 Cloud 控制台建）。 */
    public static final String KEY_GOOGLE_CLIENT_ID = "google_client_id";
    /** 邮件草稿台账（{@link com.onesay.assistant.v2.conn.mail.DraftLedger}）。 */
    public static final String KEY_MAIL_DRAFTS = "mail_drafts";

    public static String googleClientId(Context ctx) {
        String v = prefs(ctx).getString(KEY_GOOGLE_CLIENT_ID, "");
        return v == null ? "" : v.trim();
    }

    /** 只接受 Google OAuth 客户端 ID 的形状（xxx.apps.googleusercontent.com）；空串 = 清除。@return null = 成功 */
    public static String setGoogleClientId(Context ctx, String id) {
        String v = id == null ? "" : id.trim();
        if (v.length() > 0 && !com.onesay.assistant.v2.conn.google.GoogleAuth.validClientId(v)) return "Client ID 应形如 1234-abc.apps.googleusercontent.com";
        prefs(ctx).edit().putString(KEY_GOOGLE_CLIENT_ID, v).commit();
        return null;
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<McpServerConfig> mcpServers(Context ctx) {
        List<McpServerConfig> out = new ArrayList<McpServerConfig>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_MCP, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                McpServerConfig c = McpServerConfig.fromJson(arr.optJSONObject(i));
                if (c != null && c.validate() == null) out.add(c);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 新增或替换（按 id）。@return null = 成功，否则原因 */
    public static String putMcpServer(Context ctx, McpServerConfig cfg) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        String why = cfg == null ? "空配置" : cfg.validate();
        if (why != null) return why;
        JSONArray out = new JSONArray();
        for (McpServerConfig c : mcpServers(ctx)) if (!c.id.equals(cfg.id)) out.put(c.toJson());
        out.put(cfg.toJson());
        prefs(ctx).edit().putString(KEY_MCP, out.toString()).commit();
        return null;
    }

    public static boolean removeMcpServer(Context ctx, String id) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return false;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        JSONArray out = new JSONArray();
        boolean hit = false;
        for (McpServerConfig c : mcpServers(ctx)) {
            if (c.id.equals(id)) hit = true;
            else out.put(c.toJson());
        }
        prefs(ctx).edit().putString(KEY_MCP, out.toString()).remove(KEY_CACHE + id).remove(KEY_CACHE_AT + id).commit();
        return hit;
    }

    public static String cache(Context ctx, String id) {
        return prefs(ctx).getString(KEY_CACHE + id, "");
    }

    public static long cacheAt(Context ctx, String id) {
        return prefs(ctx).getLong(KEY_CACHE_AT + id, 0L);
    }

    public static void saveCache(Context ctx, String id, String json) {
        prefs(ctx).edit().putString(KEY_CACHE + id, json).putLong(KEY_CACHE_AT + id, System.currentTimeMillis()).apply();
    }

    public static JSONObject dump(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", SWITCH.on(ctx)).put("mcp", mcpServers(ctx).size()).put("google_client_id", googleClientId(ctx).length() > 0);
        } catch (Exception ignored) {
        }
        return o;
    }
}
