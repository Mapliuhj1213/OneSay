package com.onesay.assistant.v2.summary;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

/**
 * [summary] #18 长内容摘要的<b>全部挂钩入口</b>（写法同 ImageSaveHooks / DiagHooks：既有文件里只有几行 {@code // [summary]} 调用指向这里）。
 *
 * <table>
 *   <tr><td>SU-H1</td><td>L0RuleRouter.route 开头（存图之后）</td><td>{@link #l0}：「总结一下 / 这篇讲了什么 / 太长不看」→ {@code screen.summary}
 *       （不注册进 CapabilityRegistry：AgentLoop 看不到、BuiltinCapabilities 快照不变）</td></tr>
 *   <tr><td>SU-H2</td><td>UnifiedRouter.cascade ⓪c3：存图之后、S2 之前</td><td>{@link #route}：直接交付，不查不写 L1 / ASK 缓存、不登记待验证</td></tr>
 *   <tr><td>SU-H3</td><td>UnifiedRouter.shouldUseAgentLoop / triage</td><td>{@link #claims} 永不进 AgentLoop；{@link #enabled} 关闭时分诊不判 5s-screen-summary</td></tr>
 *   <tr><td>SU-H4</td><td>FloatingSheetService.onSubmitCommand</td><td>{@link #bypassScreenRef}：「总结这页」里的「这页」不是识屏指代</td></tr>
 *   <tr><td>SU-H5</td><td>FloatingSheetService.dispatch</td><td>{@link #onDispatch}：{@code screen.summary} → {@link SummaryController#start}</td></tr>
 *   <tr><td>SU-H6</td><td>FloatingSheetService.onDestroy</td><td>{@link #onServiceDestroy}</td></tr>
 * </table>
 */
public final class SummaryHooks {

    private static final String TAG = "Summary";
    public static final String SIGN = "screen.summary";
    public static final String CHIP_COPY = "复制摘要";
    public static final String CHIP_NOTE = "记下来";

    private SummaryHooks() {}

    private static SummaryController sCtl;

    public static boolean enabled(Context ctx) {
        return SummarySettings.enabled(ctx);
    }

    /** 开关开且句子是「总结这页」指令。 */
    public static boolean claims(Context ctx, String query) {
        try {
            return query != null && SummaryIntent.isTrigger(query) && SummarySettings.enabled(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** [SU-H1] L0 规则（任意线程）。null = 不接管。 */
    public static Invocation l0(Context ctx, String query) {
        try {
            if (query == null || !SummaryIntent.isTrigger(query) || !SummarySettings.enabled(ctx)) return null;
            JSONObject a = new JSONObject();
            a.put("query", query.trim());
            return new Invocation(SIGN, a, 1.0, Invocation.RouteSource.L0_RULE, query);
        } catch (Throwable t) {
            return null;
        }
    }

    /** [SU-H2] 路由级联（后台线程）。null = 不接管。 */
    public static Invocation route(Context ctx, String query) {
        Invocation inv = l0(ctx, query);
        if (inv != null) Log.i(TAG, "[SUMMARY] L0 规则命中 → screen.summary");
        return inv;
    }

    /** [SU-H4] 主线程。 */
    public static boolean bypassScreenRef(Context ctx, String command) {
        return claims(ctx, command);
    }

    /** [SU-H5] 主线程。 */
    public static void onDispatch(FloatingSheetService svc, Invocation inv, String command) {
        try {
            if (!SummarySettings.enabled(svc)) {
                Log.i(TAG, "[SUMMARY] 开关已关，忽略 screen.summary");
                return;
            }
            controller(svc).start(command);
        } catch (Throwable t) {
            Log.w(TAG, "[SUMMARY] 启动异常: " + t.getMessage());
        }
    }

    /** [SU-H6] */
    public static void onServiceDestroy(FloatingSheetService svc) {
        SummaryController c = sCtl;
        sCtl = null;
        if (c != null) {
            try {
                c.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static synchronized SummaryController controller(FloatingSheetService svc) {
        if (sCtl == null) sCtl = new SummaryController(svc);
        return sCtl;
    }

    // ------------------------------------------------------------------ 调试入口（SummaryTestDriver 用）

    public static String debugStatus(Context ctx) {
        SummaryController c = sCtl;
        return SummarySettings.dump(ctx) + " busy=" + (c != null && c.busy()) + " last=" + (c != null ? c.mLastResult : "");
    }

    /** 调试：等同在面板里说了这句话（只走摘要流程）。主线程。 */
    public static boolean debugRun(FloatingSheetService svc, String text) {
        if (svc == null) return false;
        controller(svc).start(text == null ? "总结一下" : text);
        return true;
    }

    /** 调试：点最近一次摘要的芯片（复制摘要 / 记下来）。主线程。 */
    public static String debugChip(FloatingSheetService svc, String label) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        SummaryController c = sCtl;
        if (c == null || c.last() == null) return "no-summary";
        c.onChip(label, c.last());
        return "clicked " + label;
    }
}
