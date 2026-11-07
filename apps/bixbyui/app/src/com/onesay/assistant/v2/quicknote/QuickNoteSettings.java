package com.onesay.assistant.v2.quicknote;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * [quicknote] R10 记一记的子开关（与总开关同一个 prefs：bixby_quicknote；总闸 quicknote.off 仍优先）。
 * <ul>
 *   <li>{@code thumbs} 截图缩略图（D7，默认<b>关</b>；只存私有目录）；</li>
 *   <li>{@code vl} 文字太少时用已有识屏视觉模型兜底（D8，默认开；会把截图发给 qwen3-vl-plus）；</li>
 *   <li>{@code ledger} 订单 / 账单类自动记一笔到 S2 账本（D9，默认开）；</li>
 *   <li>{@code hold} 长按后继续按住 ≥ 2 s = 随口记（D6，默认开）。</li>
 * </ul>
 */
public final class QuickNoteSettings {

    public static final String PREF = "bixby_quicknote";
    public static final String KEY_THUMBS = "thumbs", KEY_VL = "vl", KEY_LEDGER = "ledger", KEY_HOLD = "hold";

    private QuickNoteSettings() {}

    public static boolean defaultOf(String key) {
        return !KEY_THUMBS.equals(key);
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean get(Context ctx, String key) {
        if (ctx == null) return false;
        try {
            return sp(ctx).getBoolean(key, defaultOf(key));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void set(Context ctx, String key, boolean on) {
        sp(ctx).edit().putBoolean(key, on).apply();
    }

    public static boolean migrated(Context ctx) {
        return sp(ctx).getBoolean("migrated_v1", false);
    }

    public static void setMigrated(Context ctx) {
        sp(ctx).edit().putBoolean("migrated_v1", true).apply();
    }

    public static String dump(Context ctx) {
        return "thumbs=" + get(ctx, KEY_THUMBS) + " vl=" + get(ctx, KEY_VL) + " ledger=" + get(ctx, KEY_LEDGER) + " hold=" + get(ctx, KEY_HOLD);
    }

    /** 码胶囊兜底时长（D4）：取餐码 3 h、券码 24 h、取件码 / 其他 12 h。 */
    public static long codeTtlMs(String kind) {
        long H = 3600L * 1000L;
        if (kind == null) return 12 * H;
        if (kind.startsWith("取餐")) return 3 * H;
        if (kind.contains("券") || kind.contains("核销") || kind.contains("兑换")) return 24 * H;
        return 12 * H;
    }

    /** 敏感 App 黑名单：银行 / 支付 / 密码管理器 —— 不截缩略图、不走视觉兜底（root 截图会绕过 FLAG_SECURE）。 */
    public static boolean sensitivePkg(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("bank") || p.contains("alipay") || p.contains("wallet") || p.contains("pay") || p.contains("unionpay")
                || p.contains("icbc") || p.contains("cmb") || p.contains("ccb") || p.contains("abc.mobile") || p.contains("boc")
                || p.contains("password") || p.contains("keepass") || p.contains("bitwarden") || p.contains("1password")
                || p.contains("lastpass") || p.contains("authenticator") || p.contains("securities") || p.contains("stock")
                || p.contains("keyguard") || "com.android.systemui".equals(p);
    }
}
