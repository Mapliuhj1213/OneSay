package com.onesay.assistant.v2.imgsave;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

/**
 * [imgsave] 语音存图的<b>全部挂钩入口</b>（写法同 S1Hooks / DiagHooks：既有文件里只有几行 {@code // [imgsave]} 调用指向这里）。
 *
 * <table>
 *   <tr><td>I-H1</td><td>L0RuleRouter.route 开头</td><td>{@link #l0}：L0 规则「保存图片…」→ {@code image.save}（不注册进 CapabilityRegistry：AgentLoop 看不到这个工具、BuiltinCapabilities 快照不变）</td></tr>
 *   <tr><td>I-H2</td><td>UnifiedRouter.cascade：补槽之后、S2 之前</td><td>{@link #route}：直接交付 {@code image.save}，不查不写 L1 缓存、不登记待验证（「存一下图片」不能被 S2 的「存一下」前缀当成记一下）</td></tr>
 *   <tr><td>I-H3</td><td>UnifiedRouter.shouldUseAgentLoop / triage</td><td>{@link #claims} 永不进 AgentLoop；{@link #enabled} 开关关闭时分诊不判 6i-image-save</td></tr>
 *   <tr><td>I-H4</td><td>FloatingSheetService.onSubmitCommand：圈选之后、识屏指代之前</td><td>{@link #interceptCommand}：多图待选时的「第N张 / 全部 / 取消」；{@link #bypassScreenRef}：存图句不交给识屏</td></tr>
 *   <tr><td>I-H5</td><td>FloatingSheetService.dispatch</td><td>{@link #onDispatch}：{@code image.save} → {@link ImageSaveController#start}</td></tr>
 *   <tr><td>I-H6</td><td>FloatingSheetService.onDestroy</td><td>{@link #onServiceDestroy}</td></tr>
 * </table>
 */
public final class ImageSaveHooks {

    private static final String TAG = "ImageSave";
    public static final String SIGN = "image.save";

    private ImageSaveHooks() {}

    private static ImageSaveController sCtl;

    public static boolean enabled(Context ctx) {
        return ImageSaveSettings.enabled(ctx);
    }

    /** 开关开且句子是存图指令。 */
    public static boolean claims(Context ctx, String query) {
        try {
            return query != null && ImageSaveIntent.isTrigger(query) && ImageSaveSettings.enabled(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    static Invocation toInvocation(ImageSaveIntent.Hit h, String query) {
        JSONObject a = new JSONObject();
        try {
            a.put("all", h.all);
            a.put("ordinal", h.ordinal);
            a.put("query", query == null ? "" : query.trim());
        } catch (Exception ignored) {
        }
        return new Invocation(SIGN, a, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    /** [I-H1] L0 规则（任意线程）。null = 不接管。 */
    public static Invocation l0(Context ctx, String query) {
        try {
            if (query == null) return null;
            ImageSaveIntent.Hit h = ImageSaveIntent.match(query);   // 先跑便宜的正则（L0 在唤醒热路径上被高频调用），命中再读开关
            if (h == null || !ImageSaveSettings.enabled(ctx)) return null;
            return toInvocation(h, query);
        } catch (Throwable t) {
            return null;
        }
    }

    /** [I-H2] 路由级联（后台线程）。null = 不接管。 */
    public static Invocation route(Context ctx, String query) {
        Invocation inv = l0(ctx, query);
        if (inv != null) Log.i(TAG, "[IMGSAVE] L0 规则命中 → image.save " + com.onesay.assistant.v2.util.LogText.args(inv.args) + " 语句=" + com.onesay.assistant.v2.util.LogText.u(query));
        return inv;
    }

    /** [I-H4] 存图句 / 待选口令不交给识屏指代（「存这张图」里的「这张」不是要复制的文字）。 */
    public static boolean bypassScreenRef(Context ctx, String command) {
        ImageSaveController c = sCtl;
        return claims(ctx, command) || (c != null && c.hasPending());
    }

    /** [I-H4] 主线程。true = 已作为多图待选的回答处理。 */
    public static boolean interceptCommand(FloatingSheetService svc, String command) {
        try {
            ImageSaveController c = sCtl;
            if (c == null || command == null || !c.hasPending()) return false;
            ImageSaveIntent.Selection sel = ImageSaveIntent.parseSelection(command, c.pendingCount());
            if (sel == null) {
                Log.i(TAG, "[IMGSAVE] 待选期间收到别的指令，放弃待选: " + com.onesay.assistant.v2.util.LogText.u(command));
                c.abandonPending();
                return false;
            }
            c.onSelection(sel);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[IMGSAVE] 待选拦截异常，回落原流程: " + t.getMessage());
            return false;
        }
    }

    /** [I-H5] 主线程。 */
    public static void onDispatch(FloatingSheetService svc, Invocation inv, String command) {
        try {
            if (!ImageSaveSettings.enabled(svc)) {
                Log.i(TAG, "[IMGSAVE] 开关已关，忽略 image.save");
                return;
            }
            ImageSaveIntent.Hit h = ImageSaveIntent.match(command);
            if (h == null && inv != null && inv.args != null) {
                h = ImageSaveIntent.match(inv.args.optString("query"));
            }
            if (h == null) h = ImageSaveIntent.match("保存图片");
            controller(svc).start(h, command);
        } catch (Throwable t) {
            Log.w(TAG, "[IMGSAVE] 启动异常: " + t.getMessage());
        }
    }

    /** [I-H6] */
    public static void onServiceDestroy(FloatingSheetService svc) {
        ImageSaveController c = sCtl;
        sCtl = null;
        if (c != null) {
            try {
                c.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static synchronized ImageSaveController controller(FloatingSheetService svc) {
        if (sCtl == null) sCtl = new ImageSaveController(svc);
        return sCtl;
    }

    // ------------------------------------------------------------------ 调试入口（ImageSaveTestDriver 用）

    public static String debugStatus(Context ctx) {
        ImageSaveController c = sCtl;
        return ImageSaveSettings.dump(ctx) + " pending=" + (c != null && c.hasPending() ? c.pendingCount() : 0)
                + " last=" + (c != null ? c.mLastResult : "");
    }

    /** 调试：等同在面板里说了这句话（只走存图流程）。主线程。 */
    public static boolean debugRun(FloatingSheetService svc, String text) {
        ImageSaveIntent.Hit h = ImageSaveIntent.match(text);
        if (h == null || svc == null) return false;
        controller(svc).start(h, text);
        return true;
    }

    /** 调试：多图待选时的回答。主线程。 */
    public static String debugSelect(FloatingSheetService svc, String text) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        ImageSaveController c = sCtl;
        if (c == null || !c.hasPending()) return "no-pending";
        ImageSaveIntent.Selection sel = ImageSaveIntent.parseSelection(text, c.pendingCount());
        c.onSelection(sel);
        return String.valueOf(sel);
    }
}
