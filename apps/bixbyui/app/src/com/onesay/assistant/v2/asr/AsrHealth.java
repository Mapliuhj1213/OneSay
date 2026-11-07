package com.onesay.assistant.v2.asr;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * r2/p0 P0-2（M27-1）：语音识别 Key「失效」状态的落地记录，供控制台首页提醒条 / 状态大卡读取。
 *
 * <p>WakeService 云端识别鉴权失败时 {@link #markAuthFailed}，成功时 {@link #markOk}。只存当前凭据的哈希指纹前 12 位
 * （{@link AsrFailure#fingerprint}），不存 Key；换了 Key 指纹对不上，控制台自然不再报「失效」，不用在保存 Key 的地方再清。
 */
public final class AsrHealth {

    private AsrHealth() {}

    static final String PREFS = "bixby_asr_health";
    static final String K_AUTH_FAIL_FP = "auth_fail_fp";

    /** 当前选用通道的凭据串（百炼优先，与 AsrRouter.pickCloud 一致）。 */
    static String credential(AliyunAsrConfig c) {
        if (c == null) return "";
        if (c.hasDashScopeKey()) return c.getDashScopeKey();
        return c.getAppKey() + "|" + c.getAccessKeyId() + "|" + c.getManualToken() + "|" + c.getRemoteTokenUrl();
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void markAuthFailed(Context ctx, AliyunAsrConfig c) {
        try {
            sp(ctx).edit().putString(K_AUTH_FAIL_FP, AsrFailure.fingerprint(credential(c))).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void markOk(Context ctx) {
        try {
            SharedPreferences s = sp(ctx);
            if (s.contains(K_AUTH_FAIL_FP)) s.edit().remove(K_AUTH_FAIL_FP).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 当前 Key 是否就是上次被判鉴权失败的那一个。读不到返回 null（控制台显示未知，不出提醒条）。 */
    public static Boolean isAuthFailed(Context ctx, AliyunAsrConfig c) {
        try {
            String fp = sp(ctx).getString(K_AUTH_FAIL_FP, "");
            return AsrFailure.keyStillInvalid(fp, AsrFailure.fingerprint(credential(c)));
        } catch (Throwable t) {
            return null;
        }
    }
}
