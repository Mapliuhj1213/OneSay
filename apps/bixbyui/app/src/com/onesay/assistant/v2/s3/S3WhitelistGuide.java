package com.onesay.assistant.v2.s3;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * [s3] 第一次建定时任务时的「电池优化白名单」引导（用户 2026-10-09 14:25 拍板 D-1c = B）。
 *
 * <ul>
 *   <li>只出现<b>一次</b>：第一次成功新建定时任务 / 提醒（s3.task.add / s3.task.add_confirm）时，如果 App 还不在白名单，
 *       就在回执后面附一段引导，告诉用户去控制台「功能与权限 › 电池优化白名单」点「加入」（R12 新路径）；</li>
 *   <li>已经在白名单 → 不打扰，直接记为已引导；</li>
 *   <li><b>绝不静默执行 root</b>：这里只给文字，加白名单仍然只在用户点控制台按钮时执行（{@link S3Hooks#whitelistByRoot}）。</li>
 * </ul>
 * 记录在 {@code bixby_s3} 的 {@code wl_guide_shown}；调试可用 {@code --es s3 set --es kv wlguide=0} 重置。
 */
public final class S3WhitelistGuide {

    private S3WhitelistGuide() {}

    static final String TAG = "S3";
    public static final String KEY_SHOWN = "wl_guide_shown";

    public static final String TEXT = "\n\n第一次建定时任务的小提示：熄屏久了系统会推迟后台闹钟，定时任务可能晚到。"
            + "想准点，打开 OneSay 控制台 →「功能与权限」→「电池优化白名单」点「加入」（用 root，只在你点的时候执行）。这条提示只出现一次。";

    /** 纯函数：这次要不要附引导。 */
    public static boolean shouldShow(String sign, boolean ok, boolean alreadyShown, boolean whitelisted) {
        if (!ok || alreadyShown || whitelisted) return false;
        return S3Capabilities.SIGN_TASK_ADD.equals(sign) || S3Capabilities.SIGN_TASK_ADD_CONFIRM.equals(sign);
    }

    /** 纯函数：这次之后要不要记「已引导」（第一次成功建任务就记，不管有没有显示）。 */
    public static boolean shouldMark(String sign, boolean ok, boolean alreadyShown) {
        return ok && !alreadyShown
                && (S3Capabilities.SIGN_TASK_ADD.equals(sign) || S3Capabilities.SIGN_TASK_ADD_CONFIRM.equals(sign));
    }

    /** 在新建任务的回执后面按需附引导（失败不影响回执）。 */
    public static String apply(Context ctx, String sign, boolean ok, String message) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(S3Settings.PREF_S3, Context.MODE_PRIVATE);
            boolean shown = sp.getBoolean(KEY_SHOWN, false);
            if (!shouldMark(sign, ok, shown)) return message;
            boolean wl = S3Hooks.batteryWhitelisted(ctx);
            boolean show = shouldShow(sign, ok, shown, wl);
            sp.edit().putBoolean(KEY_SHOWN, true).apply();
            Log.i(TAG, "[S3] 首次建定时任务：白名单引导 " + (show ? "已显示" : "跳过（已在白名单）"));
            return show ? (message == null ? "" : message) + TEXT : message;
        } catch (Throwable t) {
            Log.w(TAG, "[S3] 白名单引导异常（不影响回执）: " + t.getMessage());
            return message;
        }
    }

    public static void reset(Context ctx, boolean shown) {
        ctx.getSharedPreferences(S3Settings.PREF_S3, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOWN, shown).apply();
    }
}
