package com.onesay.assistant.v2.errand;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

/**
 * ★ lane C3：跑腿开关（prefs {@code bixby_errand}，kill-switch {@code errand.off}）。
 * <ul>
 *   <li>{@link #MAIN}：总开关，默认开（用户 10-22 要求优先做浏览器跑腿）；</li>
 *   <li>{@link #WEBVIEW}：允许升级到内置 WebView，默认开；kill {@code errand_webview.off}；</li>
 *   <li>{@link #SHOP}：购物（加购物车 / 进结算页），默认开（用户 10-22 23:52 确认「购物停在付款前」）；kill {@code errand_shop.off}；</li>
 *   <li>{@link #BROWSER}：最后一档用默认浏览器打开链接交给用户，默认关；kill {@code errand_browser.off}。</li>
 * </ul>
 */
public final class ErrandSettings {

    private ErrandSettings() {}

    public static final String PREF = "bixby_errand";
    public static final String KILL = "errand.off";

    public static final FeatureSwitch MAIN = new FeatureSwitch(PREF, "enabled", true, KILL);
    public static final FeatureSwitch WEBVIEW = new FeatureSwitch(PREF, "webview", true, KILL, "errand_webview.off");
    public static final FeatureSwitch SHOP = new FeatureSwitch(PREF, "shop", true, KILL, "errand_shop.off");
    public static final FeatureSwitch BROWSER = new FeatureSwitch(PREF, "browser", false, KILL, "errand_browser.off");

    public static boolean enabled(Context ctx) {
        return MAIN.on(ctx);
    }

    public static String dump(Context ctx) {
        return "enabled=" + MAIN.on(ctx) + " webview=" + WEBVIEW.on(ctx) + " shop=" + SHOP.on(ctx) + " browser=" + BROWSER.on(ctx);
    }
}
