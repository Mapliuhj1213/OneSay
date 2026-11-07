package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.conn.ConnBackend;
import com.onesay.assistant.v2.conn.ConnHooks;
import com.onesay.assistant.v2.conn.ConnSettings;
import com.onesay.assistant.v2.conn.Connector;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;

import org.json.JSONObject;

/**
 * [conn] 连接器（2026-10-22 B 线）：MCP 客户端（Streamable HTTP / SSE）、Tavily 适配、Google（日历 / Gmail / Tasks）。
 *
 * <ul>
 *   <li>AGENT_TOOLS：白名单放行的连接器工具交给 AgentLoop（最多 24 个；只读缓存，不触网）；</li>
 *   <li>AGENT_EXECUTE：只执行 READ 工具；有副作用的返回 null → 宿主带确认令牌走 UnifiedExecutor → BACKENDS 的 {@link ConnBackend}；</li>
 *   <li>开关 bixby_conn.enabled（默认开，没配置就什么都不做）/ kill-switch conn.off；debug 入口 --es conn …。</li>
 * </ul>
 */
public final class ConnectorPlugin extends FeaturePlugin {

    public static final String ID = "conn";

    @Override public String id() { return ID; }
    @Override public String title() { return "连接器（MCP / Google）"; }
    @Override public String logTag() { return "[CONN]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return ConnSettings.SWITCH.prefs; }
    @Override public String[] killFiles() { return ConnSettings.SWITCH.killFiles; }
    @Override public boolean defaultOn() { return ConnSettings.SWITCH.def; }
    @Override public boolean enabled(Context ctx) { return ConnSettings.SWITCH.on(ctx); }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case AGENT_TOOLS: return 500;      // 识屏 / 记忆之后
            case AGENT_DESCRIBE: return 500;
            case AGENT_EXECUTE: return 500;
            case AGENT_PRECHECK: return 500;   // 邮件发送：补内容指纹 / 确认卡预览
            case DESCRIBE: return 500;
            case BACKENDS: return 500;         // S2 / S3 / 诊断之后
            case CONSOLE: return 700;
            default: return 1000;
        }
    }

    @Override public Capability describe(String sign) {
        // 2026-10-23 A+B 合并：conn.mail.send_draft 不经面板路由执行（唯一发送路径 = A 确认卡 → LaneBMailDraftAdapter）
        if (com.onesay.assistant.v2.conn.mail.MailSendGuard.SIGN.equals(sign)) return null;
        return ConnHooks.describe(null, sign);
    }

    @Override public void appendAgentTools(Context ctx, org.json.JSONArray tools, String query) {
        ConnHooks.appendTools(ctx, tools, query);
    }

    @Override public Capability describeForAgent(Context ctx, String name) { return ConnHooks.describe(ctx, name); }

    @Override public String agentPrecheck(Context ctx, String name, JSONObject args) {
        return ConnHooks.agentPrecheck(ctx, name, args);
    }

    @Override public ExecutionResult executeForAgent(Context ctx, Capability cap, JSONObject args) {
        return ConnHooks.executeRead(ctx, cap, args);
    }

    @Override public com.onesay.assistant.v2.executor.backend.ExecutorBackend executorBackend() {
        return new ConnBackend(new ConnBackend.Provider() {
            @Override public com.onesay.assistant.v2.conn.ConnectorRegistry registry() { return ConnHooks.registryOrNull(); }
            @Override public ConnBackend.Target[] targets() { return ConnHooks.backendTargets(); }
        });
    }

    @Override public String consoleCard() { return "功能与权限：连接器（各连接器状态 / 刷新工具）"; }
    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(final ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (!ConsoleHost.PAGE_FEATURES.equals(page)) return;
        final android.app.Activity act = host.activity();
        final boolean killed = ConnSettings.SWITCH.killed(act);
        ConsoleHost.Row main = host.switchRow(root, "连接器（MCP / Google）",
                killed ? ConsoleHost.KILLED : "把远程工具接给大模型；有副作用的一律先弹确认卡",
                ConnSettings.prefs(act).getBoolean(ConnSettings.SWITCH.key, ConnSettings.SWITCH.def),
                new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        ConnSettings.SWITCH.set(act, on);
                    }
                });
        if (killed) {
            main.setEnabled(false);
            return;
        }
        for (Connector c : ConnHooks.registry(act).all()) {
            host.valueRow(root, c.title(), c.status(), c.isReady() ? "就绪" : "未配置", c.isReady() ? ConsoleHost.TONE_OK : ConsoleHost.TONE_MUTED);
        }
        com.onesay.assistant.v2.conn.google.GoogleHooks.addConsoleRows(host, root);
        ConnHooks.addConsoleRows(host, root);
        host.actionRow(root, "刷新远程工具清单", "向各 MCP 服务器重新拉 tools/list（需联网）", "刷新", new Runnable() {
            @Override public void run() {
                ConnHooks.refreshAllAsync(act, new Runnable() {
                    @Override public void run() {
                        host.ui(new Runnable() {
                            @Override public void run() {
                                host.toast("已刷新：" + ConnHooks.registry(act).agentTools().size() + " 个工具可用");
                            }
                        });
                    }
                });
            }
        });
    }

    @Override public String debugStatus(Context ctx) { return ConnHooks.status(ctx); }
    @Override public String debugExtra() { return ID; }
}
