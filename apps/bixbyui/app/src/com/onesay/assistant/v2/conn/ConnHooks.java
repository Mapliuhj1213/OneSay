package com.onesay.assistant.v2.conn;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.conn.mcp.McpConnector;
import com.onesay.assistant.v2.conn.mcp.McpServerConfig;
import com.onesay.assistant.v2.executor.ExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * [conn] 连接器的 Android 装配层：进程内单例注册表（Tavily 适配 + 各 MCP 服务器 + Google），
 * ConnectorPlugin 的挂钩都委托到这里。所有入口在开关关闭或异常时返回「不干预」。
 */
public final class ConnHooks {

    private static final String TAG = "Conn";

    private ConnHooks() {}

    private static volatile ConnectorRegistry sReg;
    private static volatile SecretStore sSecrets;
    private static final ConnHttp HTTP = new UrlConnHttp();

    public static ConnHttp http() { return HTTP; }

    /** 额外的执行后端目标（邮件草稿发送等），由各模块在类加载时登记。 */
    private static final java.util.List<ConnBackend.Target> TARGETS = new java.util.concurrent.CopyOnWriteArrayList<ConnBackend.Target>();

    public static void addBackendTarget(ConnBackend.Target t) {
        if (t != null && !TARGETS.contains(t)) TARGETS.add(t);
    }

    public static ConnBackend.Target[] backendTargets() {
        return TARGETS.toArray(new ConnBackend.Target[0]);
    }

    /** 控制台附加行（Google 登录等），由各模块登记。 */
    public interface ConsoleRows {
        void add(com.onesay.assistant.v2.kernel.ConsoleHost host, android.widget.LinearLayout root);
    }

    private static final java.util.List<ConsoleRows> CONSOLE = new java.util.concurrent.CopyOnWriteArrayList<ConsoleRows>();

    public static void addConsoleRows(ConsoleRows r) {
        if (r != null && !CONSOLE.contains(r)) CONSOLE.add(r);
    }

    public static void addConsoleRows(com.onesay.assistant.v2.kernel.ConsoleHost host, android.widget.LinearLayout root) {
        for (ConsoleRows r : CONSOLE) {
            try {
                r.add(host, root);
            } catch (Throwable t) {
                Log.w(TAG, "[CONN] 控制台行异常: " + Redact.t(t));
            }
        }
    }

    public static SecretStore secrets(Context ctx) {
        SecretStore s = sSecrets;
        if (s == null) {
            synchronized (ConnHooks.class) {
                if (sSecrets == null) sSecrets = new PrefsSecretStore(ctx);
                s = sSecrets;
            }
        }
        return s;
    }

    /** 已装配过的注册表（没有返回 null，不触发装配）。 */
    public static ConnectorRegistry registryOrNull() {
        return sReg;
    }

    public static ConnectorRegistry registry(Context ctx) {
        ConnectorRegistry r = sReg;
        if (r != null) return r;
        synchronized (ConnHooks.class) {
            if (sReg == null) sReg = build(ctx.getApplicationContext());
            return sReg;
        }
    }

    /** 配置变了（加 / 删 MCP 服务器、登录 / 登出 Google）后重建。 */
    public static void reload(Context ctx) {
        synchronized (ConnHooks.class) {
            sReg = build(ctx.getApplicationContext());
        }
    }

    private static ConnectorRegistry build(final Context app) {
        ConnectorRegistry reg = new ConnectorRegistry();
        SecretStore sec = secrets(app);
        com.onesay.assistant.v2.conn.google.GoogleHooks.api(app).reach(REACH);
        try {
            reg.register(new TavilyConnector(new com.onesay.assistant.v2.search.TavilySearchProvider(new com.onesay.assistant.v2.search.SearchConfig(app))));
        } catch (Throwable t) {
            Log.w(TAG, "[CONN] Tavily 适配装配失败: " + Redact.t(t));
        }
        for (McpServerConfig c : ConnSettings.mcpServers(app)) {
            McpConnector m = new McpConnector(c, sec, HTTP, versionName(app), new McpConnector.CacheSink() {
                @Override public void save(String id, String json) {
                    ConnSettings.saveCache(app, id, json);
                }
            });
            m.loadCache(ConnSettings.cache(app, c.id), ConnSettings.cacheAt(app, c.id));
            reg.register(m);
        }
        // 内置：邮件（Gmail + IMAP）、Google 日历 / Tasks
        try {
            com.onesay.assistant.v2.conn.mail.MailHooks.install(app, reg, sec, HTTP, netPolicy(app));
        } catch (Throwable t) {
            Log.w(TAG, "[CONN] 邮件连接器装配失败: " + Redact.t(t));
        }
        try {
            reg.register(new com.onesay.assistant.v2.conn.google.GoogleConnector(com.onesay.assistant.v2.conn.google.GoogleHooks.api(app),
                    java.util.TimeZone.getDefault(), null, new com.onesay.assistant.v2.conn.google.AndroidLocalCalendar(app)));
        } catch (Throwable t) {
            Log.w(TAG, "[CONN] Google 日历 / Tasks 装配失败: " + Redact.t(t));
        }
        Log.i(TAG, "[CONN] 注册表就绪：" + reg.all().size() + " 个连接器，交给模型的工具 " + reg.agentTools().size() + " 个");
        return reg;
    }

    /** 邮件 / 日历选路用的网络策略（第 4 项：Google 可达性）。 */
    static com.onesay.assistant.v2.conn.mail.MailConnector.NetPolicy netPolicy(Context app) {
        return reach();
    }

    private static final GoogleReach REACH = new GoogleReach(null);

    /** 进程内唯一的 Google 可达性状态（GoogleApi、邮件选路、日历退路共用）。 */
    public static GoogleReach reach() {
        return REACH;
    }

    /** AGENT_PRECHECK：邮件发送过闸前补内容指纹 / 预览。 */
    public static String agentPrecheck(Context ctx, String name, JSONObject args) {
        if (name == null || !name.startsWith(ConnTool.PREFIX)) return null;
        if (ctx != null && !ConnSettings.SWITCH.on(ctx)) return null;
        // 2026-10-23 A+B 合并：发邮件只走 A 线确认卡（LaneBMailDraftAdapter）。回调中枢（priority 400）在前面已把发送类工具改成
        // 起草 + 确认卡；它被关掉时这里兜底拦下，AgentLoop 无论如何到不了 send_draft 的执行。
        if (com.onesay.assistant.v2.conn.mail.MailSendGuard.SIGN.equals(name)) {
            return "{\"ok\":false,\"error\":\"发邮件只能由用户在邮件确认卡上点「发送」，这里不能直接发送。不要重试。\"}";
        }
        return com.onesay.assistant.v2.conn.mail.MailHooks.precheck(name, args);
    }

    static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    // ------------------------------------------------------------------ 挂钩

    public static void appendTools(Context ctx, JSONArray tools, String query) {
        try {
            if (ctx == null || !ConnSettings.SWITCH.on(ctx)) return;
            int n = registry(ctx).appendTools(tools);
            if (n > 0) Log.i(TAG, "[CONN] 追加连接器工具 " + n + " 个");
        } catch (Throwable t) {
            Log.w(TAG, "[CONN] 追加工具异常: " + Redact.t(t));
        }
    }

    public static Capability describe(Context ctx, String sign) {
        if (sign == null || !sign.startsWith(ConnTool.PREFIX)) return null;
        if (ctx != null && !ConnSettings.SWITCH.on(ctx)) return null;
        ConnectorRegistry r = ctx != null ? registry(ctx) : sReg;
        return r == null ? null : r.describe(sign);
    }

    public static ExecutionResult executeRead(Context ctx, Capability cap, JSONObject args) {
        if (cap == null || cap.sign == null || !cap.sign.startsWith(ConnTool.PREFIX)) return null;
        if (ctx == null || !ConnSettings.SWITCH.on(ctx)) return null;
        ExecutionResult r = registry(ctx).executeRead(cap, args);
        if (r != null) Log.i(TAG, "[CONN] 只读工具 " + cap.sign + " ok=" + r.ok + " code=" + r.code);
        return r;
    }

    public static String status(Context ctx) {
        if (!ConnSettings.SWITCH.on(ctx)) return "enabled=false";
        return "enabled=true " + REACH.status() + " | " + registry(ctx).status();
    }

    /** 后台刷新所有 MCP 服务器的工具清单。 */
    public static void refreshAllAsync(final Context ctx, final Runnable done) {
        com.onesay.assistant.v2.util.Threads.start("conn-refresh", new Runnable() {
            @Override public void run() {
                for (Connector c : registry(ctx).all()) {
                    ConnResult r = c.refreshTools();
                    Log.i(TAG, "[CONN] 刷新 " + c.id() + " → " + r);
                }
                if (done != null) done.run();
            }
        });
    }
}
