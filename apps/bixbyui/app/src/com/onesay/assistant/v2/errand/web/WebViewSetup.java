package com.onesay.assistant.v2.errand.web;

import android.annotation.SuppressLint;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * ★ lane C3-2：代理用的无头 WebView 与用户接手页共用的设置（同一套 Cookie：用户在接手页登录后，代理接着跑就是已登录状态）。
 *
 * <p><b>默认电脑版</b>（用户 10-22 要求「默认用 PC 请求格式」）：桌面 Chrome UA + 宽视口。
 * <p>已知限制（上机确认）：Android 8 的 WebView 会给每个请求加 {@code X-Requested-With: com.onesay.assistant(.debug)} 头，
 * 少数站点据此拒绝 WebView —— 这正是升级链最后一档「三星浏览器」的用武之地。
 */
public final class WebViewSetup {

    private WebViewSetup() {}

    /** 桌面 Chrome（Windows）UA，与 search/SearchHttp.UA 同款。 */
    public static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 无头代理视口（CSS 像素）：常见笔记本宽度。 */
    public static final int VIEW_W = 1280;
    public static final int VIEW_H = 2000;

    @SuppressLint("SetJavaScriptEnabled")
    public static void apply(WebView w, boolean agent) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(PC_UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);   // target=_blank 在本页打开
        if (agent) {
            s.setLoadsImagesAutomatically(false);   // 代理不看图，省流量和时间；验证码图在接手页里照常显示
            s.setBlockNetworkImage(true);
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
    }

    /** 只放行 http(s)。代理里遇到 intent:// / taobao:// / openapp.jdmobile:// / weixin:// / alipays:// 一律拦下（不拉起任何 App）。 */
    public static boolean isWebUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:");
    }
}
