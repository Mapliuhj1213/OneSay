package com.onesay.assistant.v2.companion;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.router.Invocation;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * [companion] 宿主挂钩。挂在已有的计划插件槽位上（kernel/plugins/PlannerPlugin：TAKEOVER 285 / CLAIMS 285，S3 300 之前），
 * 不新增内核插件，内核插件表不变：
 * <table>
 *   <tr><td>{@link #route}</td><td>READY 时经连接执行删 / 改待办等，本地回话（写法同 PlannerHooks.route 的取件码）</td></tr>
 *   <tr><td>{@link #claims}</td><td>伴侣在场的待办 / 日程 / 闹钟句永不进 AgentLoop（UnifiedRouter.shouldUseAgentLoop 最前面也直调）</td></tr>
 *   <tr><td>{@link #agentPrecheck}</td><td>AgentLoop 工具过闸：伴侣在场时不替这些意图探测第三方 App / root 读 shell</td></tr>
 *   <tr><td>{@link #briefStateViaLink}</td><td>简报来源对账：经时钟连接取 state.json（不要 root）</td></tr>
 *   <tr><td>{@link #assistantStatus}</td><td>计划 / 时钟反查助手状态（CompanionLinkReceiver）</td></tr>
 * </table>
 */
public final class CompanionHooks {
    private CompanionHooks() {}

    static final String TAG = CompanionLink.TAG;

    /** 真机环境。live = true 时允许触发 hello（后台线程）。 */
    static CompanionRouter.Env env(final Context ctx, final boolean live) {
        final CompanionLink l = CompanionLink.get(ctx);
        return new CompanionRouter.Env() {
            @Override public CompanionHandshake.State plan() {
                return live ? l.state(CompanionLink.Peer.PLAN) : l.cachedState(CompanionLink.Peer.PLAN);
            }

            @Override public CompanionHandshake.State clock() {
                return live ? l.state(CompanionLink.Peer.CLOCK) : l.cachedState(CompanionLink.Peer.CLOCK);
            }

            @Override public boolean s3Todo() {
                try {
                    return com.onesay.assistant.v2.s3.S3Settings.todoEnabled(ctx);
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override public CompanionRouter.PlanPort planPort() {
                return new CompanionClient(ctx);
            }
        };
    }

    /** 后台线程。null = 不接管（CLAIM 也返回 null：交给后面的规则层，但 claims 会挡住 AgentLoop）。 */
    public static Invocation route(Context ctx, String query) {
        try {
            if (ctx == null || query == null) return null;
            CompanionRouter.Outcome o = CompanionRouter.handle(query, env(ctx, true));
            if (o.action == CompanionRouter.Action.NONE) return null;
            Log.i(TAG, "[COMPANION] 路由 " + o);
            if (o.action != CompanionRouter.Action.REPLY || o.reply == null) return null;
            JSONObject a = new JSONObject();
            a.put("text", o.reply);
            a.put("query", query.trim());
            a.put("planner", true);
            a.put("companion", o.intent.kind.name());
            return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
        } catch (Throwable t) {
            Log.w(TAG, "[COMPANION] route 异常，交回原流程: " + t.getMessage());
            return null;
        }
    }

    /** 后台线程。 */
    public static boolean claims(Context ctx, String query) {
        try {
            if (ctx == null || query == null) return false;
            CompanionRouter.Outcome o = CompanionRouter.classify(query, env(ctx, true));
            if (o.blocksAgentLoop()) Log.i(TAG, "[COMPANION] 伴侣在场，不进 AgentLoop: " + o.intent);
            return o.blocksAgentLoop();
        } catch (Throwable t) {
            return false;
        }
    }

    /** AgentLoop 工具过闸前（FeatureKernel.agentPrecheck → PlannerPlugin）。 */
    public static String agentPrecheck(Context ctx, String name, JSONObject args) {
        try {
            if (ctx == null || name == null) return null;
            StringBuilder sb = new StringBuilder();
            String pkg = null;
            if (args != null) {
                Iterator<String> it = args.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = args.opt(k);
                    if (v instanceof String) {
                        sb.append(v).append(' ');
                        if ("package".equals(k) || "pkg".equals(k)) pkg = (String) v;
                    }
                }
            }
            String obs = CompanionRouter.agentPrecheck(name, sb.toString(), pkg, env(ctx, false));
            if (obs != null) Log.w(TAG, "[COMPANION] 拦下 AgentLoop 工具 " + name + "（伴侣在场，不探测第三方 App）");
            return obs;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 简报来源对账（不要 root）：时钟连接 READY 时经 getBriefStatus 取 state.json，拼成与 BriefState.VERIFY_CMD 相同的输出
     * （第一行时钟 uid，其余是 state.json），交给原来的 BriefState.verifySource 判。连接不通返回 null（调用方再试 root）。
     */
    public static String briefStateViaLink(Context ctx) {
        try {
            CompanionLink l = CompanionLink.get(ctx);
            if (!l.state(CompanionLink.Peer.CLOCK).ready()) return null;
            String json = new CompanionClient(ctx).briefStatus();
            if (json == null) return null;
            int uid = l.uid(CompanionLink.Peer.CLOCK);
            Log.i(TAG, "[COMPANION] 简报状态经时钟连接取到（" + json.length() + " 字）");
            return uid + "\n" + json;
        } catch (Throwable t) {
            return null;
        }
    }

    /** getAssistantStatus 的结果 JSON（不含任何密钥 / 个人数据）。 */
    public static String assistantStatus(Context ctx) {
        try {
            CompanionLink l = CompanionLink.get(ctx);
            JSONObject o = new JSONObject();
            o.put(CompanionContract.K_IFACE, CompanionContract.IFACE);
            o.put(CompanionContract.K_VERSION, CompanionContract.VERSION);
            o.put("pkg", ctx.getPackageName());
            try {
                o.put(CompanionContract.K_APP_VERSION, ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName);
            } catch (Throwable ignored) {
            }
            o.put("plan", l.cachedState(CompanionLink.Peer.PLAN).name());
            o.put("clock", l.cachedState(CompanionLink.Peer.CLOCK).name());
            o.put("enabled", CompanionLink.SWITCH.on(ctx));
            o.put("ts", System.currentTimeMillis());
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static String debugStatus(Context ctx) {
        return "伴侣连接：" + (CompanionLink.SWITCH.on(ctx) ? "开" : "关") + " · " + CompanionLink.get(ctx).status();
    }
}
