/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。图卡片用的沙箱 WebView。
 */
package com.onesay.assistant.plug.richout.host;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.onesay.assistant.plug.richout.core.DiagramHtml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 沙箱 WebView：
 * <ul>
 *   <li>页面用 {@code loadDataWithBaseURL("https://richout.invalid/", …)} 加载；所有子请求在 shouldInterceptRequest 里拦下：
 *       只有两个库地址映射到 assets，其余一律 403 空响应 —— 页面拿不到任何网络、文件、content:// 资源；</li>
 *   <li>不 addJavascriptInterface（并移除老版本 WebView 自带的 searchBoxJavaBridge_ / accessibility 桥）；</li>
 *   <li>禁 file / content 访问、禁 DOM 存储 / 数据库 / 定位 / 表单保存；禁弹窗、禁多窗口；任何跳转一律拦；</li>
 *   <li>svg 页面不开 JS；mermaid / chart 开 JS（页面 CSP 只放行内联脚本和那两个库）；</li>
 *   <li>结果回报靠 document.title（{@code RO:ok:…} / {@code RO:err:…}），不经过 JS 接口。</li>
 * </ul>
 */
public final class SandboxWeb {

    private SandboxWeb() {}

    private static final String TAG = "RichOut";

    public interface Listener {
        /** 渲染完成：内容高度（CSS 像素）；svg 页面（无 JS）在 onPageFinished 时以 -1 回报。 */
        void onRendered(int cssHeight);

        void onFailed(String why);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static WebView create(Context ctx, boolean js, boolean zoom, final Listener l) {
        final WebView w = new WebView(ctx);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(js);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setDomStorageEnabled(false);
        s.setDatabaseEnabled(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(zoom);
        s.setBuiltInZoomControls(zoom);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(zoom);
        s.setLoadWithOverviewMode(zoom);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                s.setSafeBrowsingEnabled(false);   // 沙箱页面不联网，没必要查（也省一次 GMS 调用）
            } catch (Throwable ignored) {
            }
        }
        try {
            w.removeJavascriptInterface("searchBoxJavaBridge_");
            w.removeJavascriptInterface("accessibility");
            w.removeJavascriptInterface("accessibilityTraversal");
        } catch (Throwable ignored) {
        }
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);
        } catch (Throwable ignored) {
        }
        w.setBackgroundColor(Color.TRANSPARENT);
        w.setVerticalScrollBarEnabled(zoom);
        w.setHorizontalScrollBarEnabled(zoom);
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return true;   // 沙箱里不许跳转
            }

            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return intercept(view.getContext(), req.getUrl() == null ? null : req.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return intercept(view.getContext(), url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!view.getSettings().getJavaScriptEnabled() && l != null) l.onRendered(-1);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) {
                if (l == null || title == null) return;
                if (title.startsWith(DiagramHtml.TITLE_OK)) l.onRendered(DiagramHtml.parseOkHeight(title));
                else if (title.startsWith(DiagramHtml.TITLE_ERR)) l.onFailed(title.substring(DiagramHtml.TITLE_ERR.length()));
            }

            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override public boolean onJsPrompt(WebView view, String url, String message, String def, JsPromptResult result) {
                result.cancel();
                return true;
            }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });
        return w;
    }

    public static void load(WebView w, String html) {
        w.loadDataWithBaseURL(DiagramHtml.BASE, html, "text/html", "utf-8", null);
    }

    /** 两个库映射到 assets；其余（含 favicon）一律 403。主文档（loadDataWithBaseURL）不经过这里。 */
    static WebResourceResponse intercept(Context ctx, String url) {
        String asset = DiagramHtml.assetFor(url);
        if (asset != null) {
            try {
                InputStream in = ctx.getAssets().open(asset);
                Map<String, String> h = new HashMap<String, String>();
                h.put("Cache-Control", "no-store");
                return new WebResourceResponse("application/javascript", "utf-8", 200, "OK", h, in);
            } catch (Throwable t) {
                Log.w(TAG, "[RICHOUT] 库读取失败 " + asset + ": " + t.getMessage());
            }
        }
        if (url != null && !url.startsWith("data:")) Log.i(TAG, "[RICHOUT] 沙箱拦下请求 " + clip(url));
        return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", new HashMap<String, String>(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String clip(String u) {
        return u.length() > 80 ? u.substring(0, 80) + "…" : u;
    }

    public static void destroy(WebView w) {
        if (w == null) return;
        try {
            w.stopLoading();
            if (w.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) w.getParent()).removeView(w);
            w.destroy();
        } catch (Throwable ignored) {
        }
    }
}
