package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.ScreenRefHooks;
import com.onesay.assistant.v2.vision.screenref.ScreenRefSettings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * [kernel] 识屏指代（「这个 / 这张 / 那条」→ 屏幕实体）。
 *
 * <p>第 3 阶段登记进内核（身份 / 开关 / 验收 / 状态）；<b>第 4 阶段（D-3a，用户拍板「插件现在就上」）挂钩整体进内核</b>：
 * 宿主不再直调 {@code ScreenRefHooks}，而是调内核挂钩，由本插件转给 ScreenRefHooks（实现一行没改）。
 * <ul>
 *   <li>唤醒采集：WAKE（优先级最小 → 在其它插件的 WAKE 之前、所有 WAKE_EARLY 之后，与迁移前「WAKE_EARLY → 识屏 → WAKE」一致）；</li>
 *   <li>AgentLoop 注入：AGENT_TOOLS / AGENT_PROMPT / AGENT_DESCRIBE / AGENT_PRECHECK / AGENT_EXECUTE（都排第一，与迁移前「识屏在前、内核在后」一致）；</li>
 *   <li>面板：SCREEN_REFERENCE（INTERCEPT 之后、且没有插件声明 bypassScreenRef 时）、TEXT_MODE（还焦点）、REGION_CHIPS（圈选动作芯片）。</li>
 * </ul>
 * 唯一差别：ScreenRefHooks 原来就自己 try/catch，再包一层内核隔离，只影响本来就会抛异常的路径。
 */
public final class ScreenRefPlugin extends FeaturePlugin {

    /** 所有识屏挂钩排在最前（迁移前宿主里识屏调用都写在内核调用之前）。 */
    static final int FIRST = -100;

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case WAKE:
            case AGENT_TOOLS:
            case AGENT_PROMPT:
            case AGENT_DESCRIBE:
            case AGENT_PRECHECK:
            case AGENT_EXECUTE:
            case SCREEN_REFERENCE:
            case TEXT_MODE:
            case REGION_CHIPS:
                return FIRST;
            case CONSOLE:
                return 700;
            default:
                return super.priority(hook);
        }
    }

    // ---- 唤醒采集 ----
    @Override public void onWake(FloatingSheetService svc) {
        ScreenRefHooks.onWake(svc, svc.getSheetView(), svc.pluginWindowManager(), svc.pluginSheetParams());
    }

    // ---- AgentLoop 注入 ----
    @Override public void appendAgentTools(Context ctx, JSONArray tools, String query) {
        ScreenRefHooks.appendTools(ctx, tools, query);
    }

    @Override public String agentPrompt(Context ctx, String query, String content) {
        return ScreenRefHooks.withScreenHint(ctx, query, content);
    }

    @Override public boolean agentPromptTainted(String before, String after) {
        return ScreenRefHooks.isScreenInjected(before, after);
    }

    @Override public Capability describeForAgent(Context ctx, String name) {
        return ScreenRefHooks.describe(ctx, name);
    }

    @Override public String agentPrecheck(Context ctx, String name, JSONObject args) {
        return ScreenRefHooks.handleLocalTool(ctx, name, args);
    }

    @Override public ExecutionResult executeForAgent(Context ctx, Capability cap, JSONObject args) {
        return ScreenRefHooks.executeForAgent(ctx, cap, args);
    }

    // ---- 面板 / 动作芯片 ----
    @Override public boolean screenReference(FloatingSheetService svc, String command) {
        return ScreenRefHooks.interceptCommand(svc, command);
    }

    @Override public void onTextMode(FloatingSheetService svc) {
        ScreenRefHooks.restoreFocus();
    }

    @Override public int regionChips(FloatingSheetService svc, Object region) {
        return region instanceof ScreenContext ? ScreenRefHooks.showChipsForContext(svc, (ScreenContext) region) : 0;
    }

    @Override public String id() { return "screenref"; }
    @Override public String title() { return "识屏指代"; }
    @Override public String logTag() { return "[SCREENREF]"; }
    @Override public String prefsName() { return ScreenRefSettings.PREF; }
    @Override public String[] killFiles() { return new String[]{ScreenRefSettings.KILL_FILE_NAME}; }
    @Override public boolean kernelGated() { return false; }   // 没有经内核分发的挂钩；开关由 ScreenRefHooks 现查
    @Override public boolean enabled(Context ctx) { return ScreenRefSettings.isEnabled(ctx); }

    @Override public String debugStatus(Context ctx) {
        return "enabled=" + enabled(ctx) + " chips=" + ScreenRefSettings.chipsEnabled(ctx)
                + " vl=" + ScreenRefSettings.vlFallbackEnabled(ctx) + " hooks=内核";
    }

    // ------------------------------------------------------------------ [R12] 控制台 v2（D6：第一次给识屏加 UI，默认值不变）

    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        final android.app.Activity act = host.activity();
        boolean killed = ScreenRefSettings.killSwitchOn(act);
        if (com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_SCREEN.equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleRows.featureRow(host, root, "识屏", "「这个 / 上面那个」读当前屏幕", killed,
                    pref(act, ScreenRefSettings.KEY_ENABLED), "screenref", "识屏");
        } else if ("screenref".equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleRows.detailPage(host, root, "使用识屏", killed, pref(act, ScreenRefSettings.KEY_ENABLED),
                    new String[]{"屏幕上的动作芯片", "看不懂时用视觉模型"},
                    new String[]{"唤醒时按屏幕内容给几个可点的建议", "会上传截图；密码 / 验证码页不传"},
                    new com.onesay.assistant.v2.kernel.ConsoleRows.Pref[]{pref(act, ScreenRefSettings.KEY_CHIPS), pref(act, ScreenRefSettings.KEY_VL)},
                    null, "唤醒时读一下当前屏幕（只读），让「打开这个」「把上面那个地址发给我」这类说法能指到屏幕上的东西。");
        }
    }

    private static com.onesay.assistant.v2.kernel.ConsoleRows.Pref pref(final Context ctx, final String key) {
        return new com.onesay.assistant.v2.kernel.ConsoleRows.Pref() {
            @Override public boolean get() { return ScreenRefSettings.pref(ctx, key); }
            @Override public void set(boolean on) {
                ctx.getApplicationContext().getSharedPreferences(ScreenRefSettings.PREF, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply();
            }
        };
    }
}
