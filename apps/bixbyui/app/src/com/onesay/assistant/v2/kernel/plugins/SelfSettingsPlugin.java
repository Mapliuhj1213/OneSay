package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.selfset.SelfSettingsHooks;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

/**
 * [settings-diag-1124] 「自身设置」：在聊天里问 OneSay 自己的设置为什么用不了、开着没有，让它直接开 / 关（敏感项先确认，系统权限只给跳转）。
 * 委托 {@link SelfSettingsHooks}。常开、不进插件列表（它就是设置本身的入口）；能力另外注册在能力表里（SelfSettingsCaps）。
 *
 * <p>TAKEOVER 优先级 250：落在「状态插件」档（RouteArbiter.CONTENT_TIER 290 之前），排在零 LLM 快路径前面 ——
 * 否则「把快捷开关打开」会被快路径当成打开某个 App。只认清单里的项 + 明确的说法，认不出就不接。
 */
public final class SelfSettingsPlugin extends FeaturePlugin {

    @Override public String id() { return "selfset"; }
    @Override public String title() { return "自身设置"; }
    @Override public String logTag() { return "[SELFSET]"; }
    @Override public String prefsName() { return "bixby_selfset"; }
    @Override public String[] killFiles() { return new String[]{KILL}; }
    @Override public String acceptanceModule() { return null; }   // 真机验收走 TEST-settings-1124.md 的手测步骤
    @Override public boolean consoleV2() { return true; }         // 没有自己的设置页（它就是设置本身的入口）
    @Override public boolean kernelGated() { return false; }
    /** 常开；只有关停文件 selfset.off 能停（停了 = 聊天里不再接 OneSay 设置类的话，能力表里的工具照样在）。 */
    @Override public boolean enabled(Context ctx) { return ctx == null || !com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL); }

    public static final String KILL = "selfset.off";

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 250;
            case CLAIMS: return 250;
            case BYPASS_SCREENREF: return 250;
            case DISPATCH: return 250;
            case DESCRIBE: return 250;
            case REPLY_SHOWN: return 250;
            case EXECUTED: return 250;
            case BACKENDS: return 290;          // 在 Shell 后端之前（executor=selfset）
            default: return 1000;
        }
    }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return SelfSettingsHooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-SELFSET] 自身设置接管: "; }
    @Override public boolean claims(Context ctx, String query) { return SelfSettingsHooks.claims(ctx, query); }
    @Override public boolean bypassScreenRef(Context ctx, String command) { return SelfSettingsHooks.claims(ctx, command); }
    @Override public boolean dispatch(FloatingSheetService svc, Invocation inv, String command) { return SelfSettingsHooks.dispatch(svc, inv, command); }
    @Override public Capability describe(String sign) { return SelfSettingsHooks.describe(sign); }
    @Override public void onReplyShown(FloatingSheetService svc, Invocation inv) { SelfSettingsHooks.onReplyShown(svc, inv); }

    @Override public void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        SelfSettingsHooks.onExecuted(svc, cap, args, r);
    }

    @Override public void appendAgentTools(Context ctx, org.json.JSONArray tools, String query) { SelfSettingsHooks.appendTools(ctx, tools, query); }
    @Override public Capability describeForAgent(Context ctx, String name) { return SelfSettingsHooks.describeForAgent(ctx, name); }
    @Override public String agentPrecheck(Context ctx, String name, JSONObject args) { return SelfSettingsHooks.agentPrecheck(ctx, name, args); }

    @Override public com.onesay.assistant.v2.executor.backend.ExecutorBackend executorBackend() {
        return new com.onesay.assistant.v2.executor.backend.SelfSettingsBackend();
    }

    @Override public String consoleCard() { return null; }
    @Override public String debugStatus(Context ctx) { return "selfset items=" + com.onesay.assistant.v2.selfset.SelfCatalog.all().size(); }
}
