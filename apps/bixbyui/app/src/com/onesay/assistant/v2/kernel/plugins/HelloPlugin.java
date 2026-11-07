package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;

import org.json.JSONObject;

/**
 * [kernel] 最小示例插件（<b>默认关</b>）：说「插件自检」→ 本地回答当前装了哪些插件、各自状态。
 *
 * <p>演示新插件需要写的全部东西：id / 开关（{@link FeatureSwitch}：prefs bixby_hello.enabled，默认 false，kill-switch hello.off）/
 * 路由接管 + 不进 AgentLoop / 日志 tag / 验收模块名。由内核按开关门控（{@link #kernelGated()} = true，基类默认），
 * 关闭时内核根本不调它的挂钩。打开：
 * <pre>su -c "am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver --es kernel set --es kv 'hello=1'"</pre>
 */
public final class HelloPlugin extends FeaturePlugin {

    public static final String TRIGGER = "插件自检";

    public static final FeatureSwitch SWITCH = new FeatureSwitch("bixby_hello", "enabled", false, "hello.off");

    @Override public String id() { return "hello"; }
    @Override public String title() { return "示例插件：插件自检"; }
    @Override public String logTag() { return "[HELLO]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return SWITCH.prefs; }
    @Override public String[] killFiles() { return SWITCH.killFiles; }
    @Override public boolean defaultOn() { return SWITCH.def; }
    @Override public boolean enabled(Context ctx) { return SWITCH.on(ctx); }

    /** 排在所有迁移插件之后：永远不会抢走它们的句子。 */
    @Override public int priority(FeatureHook hook) { return 9000; }

    /** 纯函数：这句话是不是触发词（JVM 单测入口）。 */
    public static boolean matches(String query) {
        if (query == null) return false;
        String q = query.trim().replace("。", "").replace("！", "").replace("?", "").replace("？", "");
        return q.equals(TRIGGER) || q.equals("内核自检");
    }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) {
        if (!matches(query)) return null;
        StringBuilder sb = new StringBuilder("插件内核在线：");
        boolean first = true;
        for (FeaturePlugin p : FeatureKernel.get().plugins()) {
            if (!first) sb.append("；");
            first = false;
            String st;
            try {
                st = p.enabled(ctx) ? "（开）" : "（关）";
            } catch (Throwable t) {
                st = "（状态未知）";
            }
            sb.append(p.title()).append(st);
        }
        JSONObject args = new JSONObject();
        try {
            args.put("text", sb.toString());
            args.put("query", query.trim());
        } catch (Exception ignored) {
        }
        return new Invocation("ask.reply", args, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    @Override public String routeLog() { return "[ROUTE-HELLO] 示例插件接管: "; }
    @Override public boolean claims(Context ctx, String query) { return matches(query); }
    @Override public String debugStatus(Context ctx) { return "enabled=" + enabled(ctx) + " switch=" + SWITCH; }
}
