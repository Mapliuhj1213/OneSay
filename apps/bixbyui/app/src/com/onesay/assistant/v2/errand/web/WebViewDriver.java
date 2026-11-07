package com.onesay.assistant.v2.errand.web;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ★ lane C3-2：无头 WebView 页面驱动（{@link BrowserAgent.Driver} 的 Android 实现）。
 *
 * <ul>
 *   <li>WebView 在主线程创建 / 操作；本类方法在后台线程调用，内部用 latch 等主线程结果；</li>
 *   <li>不挂窗口：手动 measure / layout 到 {@link WebViewSetup#VIEW_W}×{@link WebViewSetup#VIEW_H}（桌面视口）；</li>
 *   <li>只加载 http(s)：任何拉起 App 的 scheme 都被拦下（不做第三方 App 自动化）；JS 弹窗一律自动取消；</li>
 *   <li>主文档 HTTP ≥ 400 / 网络错误 / 证书错误 → {@link #blockedReason()}，升级链据此退到三星浏览器。</li>
 * </ul>
 */
public final class WebViewDriver implements BrowserAgent.Driver {

    private static final String TAG = "Errand/WebView";
    private static final long EVAL_TIMEOUT_MS = 8000L;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Context mCtx;
    private volatile WebView mWeb;
    private volatile boolean mLoading;
    private volatile int mProgress;
    private volatile String mBlocked;
    private volatile CountDownLatch mLoadLatch;
    private volatile int mBlockedSchemes;

    public WebViewDriver(Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    /** 主线程建 WebView（阻塞等待）。 */
    private boolean ensure() {
        if (mWeb != null) return true;
        final CountDownLatch l = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                try {
                    WebView w = new WebView(mCtx);
                    WebViewSetup.apply(w, true);
                    w.setWebViewClient(new Client());
                    w.setWebChromeClient(new Chrome());
                    int ws = View.MeasureSpec.makeMeasureSpec(WebViewSetup.VIEW_W, View.MeasureSpec.EXACTLY);
                    int hs = View.MeasureSpec.makeMeasureSpec(WebViewSetup.VIEW_H, View.MeasureSpec.EXACTLY);
                    w.measure(ws, hs);
                    w.layout(0, 0, WebViewSetup.VIEW_W, WebViewSetup.VIEW_H);
                    w.onResume();
                    w.resumeTimers();
                    mWeb = w;
                } catch (Throwable t) {
                    Log.w(TAG, "创建 WebView 失败: " + t);
                }
                l.countDown();
            }
        });
        await(l, 5000);
        return mWeb != null;
    }

    @Override
    public PageSnapshot load(final String url) {
        if (!WebViewSetup.isWebUrl(url) || !ensure()) return null;
        mBlocked = null;
        mLoading = true;
        mProgress = 0;
        mLoadLatch = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                WebView w = mWeb;
                if (w != null) w.loadUrl(url);
            }
        });
        Log.i(TAG, "load " + url);
        await(mLoadLatch, BrowsePolicy.LOAD_TIMEOUT_MS);
        if (mBlocked != null && mProgress < 100) return null;
        return snapshot();
    }

    @Override
    public PageSnapshot snapshot() {
        if (!ensure()) return null;
        waitIdle(8000);
        SystemClock.sleep(BrowsePolicy.SETTLE_MS);
        String raw = eval(PageScript.EXTRACT);
        PageSnapshot p = PageSnapshot.parse(raw);
        Log.i(TAG, "snapshot " + p);
        return p;
    }

    @Override
    public BrowserAgent.Result act(AgentAction a) {
        String js;
        switch (a.type) {
            case CLICK: js = PageScript.click(a.id); break;
            case TYPE: js = PageScript.type(a.id, a.text, a.submit); break;
            case SCROLL: js = PageScript.scroll(a.dir); break;
            case NEXT_PAGE: js = PageScript.NEXT_PAGE; break;
            default: return new BrowserAgent.Result(false, null, "不支持的动作");
        }
        mBlocked = null;
        String raw = eval(js);
        SystemClock.sleep(350);   // 给导航一个开始的机会（onPageStarted 置 loading）
        return parseResult(raw);
    }

    /** 解析 {ok, value, err}（纯函数，JVM 可测）。 */
    public static BrowserAgent.Result parseResult(String raw) {
        try {
            String s = raw == null ? "" : raw.trim();
            if (s.startsWith("\"")) s = new JSONArray("[" + s + "]").getString(0);
            JSONObject o = new JSONObject(s);
            return new BrowserAgent.Result(o.optBoolean("ok"), o.has("value") ? o.optString("value") : null, o.optString("err", ""));
        } catch (Throwable t) {
            return new BrowserAgent.Result(false, null, "脚本没有返回");
        }
    }

    @Override
    public boolean back() {
        final boolean[] ok = {false};
        final CountDownLatch l = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                WebView w = mWeb;
                if (w != null && w.canGoBack()) {
                    mLoading = true;
                    w.goBack();
                    ok[0] = true;
                }
                l.countDown();
            }
        });
        await(l, 3000);
        return ok[0];
    }

    @Override
    public String blockedReason() {
        return mBlocked;
    }

    /** 当前网址（交给接手页 / 三星浏览器）。 */
    public String currentUrl() {
        final String[] u = {null};
        final CountDownLatch l = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                WebView w = mWeb;
                u[0] = w != null ? w.getUrl() : null;
                l.countDown();
            }
        });
        await(l, 2000);
        return u[0];
    }

    public void destroy() {
        mMain.post(new Runnable() {
            @Override public void run() {
                WebView w = mWeb;
                mWeb = null;
                if (w != null) {
                    try {
                        w.stopLoading();
                        w.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    CookieManager.getInstance().flush();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public int blockedSchemeCount() {
        return mBlockedSchemes;
    }

    // ------------------------------------------------------------------ 内部

    private void waitIdle(long maxMs) {
        long t0 = SystemClock.elapsedRealtime();
        while (mLoading && SystemClock.elapsedRealtime() - t0 < maxMs) SystemClock.sleep(150);
    }

    private String eval(final String js) {
        if (mWeb == null) return null;
        final String[] out = {null};
        final CountDownLatch l = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                WebView w = mWeb;
                if (w == null) {
                    l.countDown();
                    return;
                }
                try {
                    w.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            out[0] = v;
                            l.countDown();
                        }
                    });
                } catch (Throwable t) {
                    l.countDown();
                }
            }
        });
        await(l, EVAL_TIMEOUT_MS);
        return out[0];
    }

    private static void await(CountDownLatch l, long ms) {
        try {
            l.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return blockScheme(req.getUrl() != null ? req.getUrl().toString() : null);
        }

        @SuppressWarnings("deprecation")
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return blockScheme(url);
        }

        private boolean blockScheme(String url) {
            if (WebViewSetup.isWebUrl(url)) return false;
            mBlockedSchemes++;
            Log.i(TAG, "拦下拉起 App 的跳转（不做 App 自动化）: " + (url == null ? "" : url.replaceAll("[?#].*$", "")));
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mLoading = true;
        }

        @Override public void onPageFinished(WebView view, String url) {
            mLoading = false;
            CountDownLatch l = mLoadLatch;
            if (l != null) l.countDown();
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req != null && req.isForMainFrame()) {
                mBlocked = "网络错误：" + (err != null ? err.getDescription() : "");
                mLoading = false;
                CountDownLatch l = mLoadLatch;
                if (l != null) l.countDown();
            }
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
            if (req != null && req.isForMainFrame() && resp != null && resp.getStatusCode() >= 400) {
                mBlocked = "站点返回 HTTP " + resp.getStatusCode();
            }
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();   // 证书有问题一律不继续
            mBlocked = "证书错误";
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int p) {
            mProgress = p;
            if (p >= 100) mLoading = false;
        }

        @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.cancel();
            return true;
        }

        @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            result.cancel();
            return true;
        }

        @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            result.cancel();
            return true;
        }
    }
}
