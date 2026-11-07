package com.onesay.assistant.v2.errand.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
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
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * ★ fix/shop-research（用户 11-03 14:37 / 14:38）：应用内<b>离屏</b> WebView 只读取价器 —— 比价 / 调研「走浏览器」拿渲染后的真实价格。
 *
 * <ul>
 *   <li><b>不显示、不弹界面</b>：WebView 不挂任何窗口，手动 measure / layout 成桌面视口（默认 {@link ReaderPolicy#DESKTOP_W}×{@link ReaderPolicy#DESKTOP_H}，
 *       桌面 Chrome（Windows）UA、宽视口 + overview；可选移动 UA）。不点击、不输入、不加购、不下单；</li>
 *   <li><b>主线程安全</b>：WebView 只在主线程创建 / 操作；{@link #read} 只许后台线程调用（主线程调用直接回 ERROR，绝不阻塞主线程）；</li>
 *   <li><b>单实例、串行</b>：进程内一个读取器，{@link Semaphore}(1) 串行；单页 {@link ReaderPolicy#PAGE_TIMEOUT_MS}（12 s）超时；</li>
 *   <li><b>安全</b>：不挂任何 JS 接口（并移除系统遗留的 searchBoxJavaBridge_ / accessibility*）、禁文件 / content 访问、禁混合内容、
 *       只放行 https 导航（intent:// / openapp.jdmobile:// / taobao:// 等唤起 App 的 scheme 一律拦截不跳转，子资源里的这类请求回空）、
 *       证书错误一律取消、不保存密码 / 表单、JS 弹窗自动取消、不开新窗口、不下载；</li>
 *   <li><b>Cookie</b>：Android 8（API 26）上 WebView 的 Cookie 库是进程级单例（{@code setDataDirectorySuffix} 要 API 28 且只能用于独立进程），
 *       做不到真正独立的存储；本类记下本次读取碰过的站点，{@link #clear} 时只清这些站点<b>本次新产生</b>的 Cookie 与 Web Storage
 *       （读取前这些站点已有 Cookie 的——比如用户在接手页登录过——不动，日志注明），并清缓存。比价一轮结束即调用。</li>
 *   <li>要登录 / 要验证码 → {@link ReaderResult.Status#LOGIN} / {@link ReaderResult.Status#CAPTCHA}，调用方跳过并注明，<b>不尝试登录</b>。</li>
 * </ul>
 */
public final class BrowserReader {

    private static final String TAG = "Errand/Reader";

    private static volatile BrowserReader sInst;

    public static BrowserReader get(Context ctx) {
        BrowserReader r = sInst;
        if (r == null) {
            synchronized (BrowserReader.class) {
                r = sInst;
                if (r == null) sInst = r = new BrowserReader(ctx.getApplicationContext());
            }
        }
        return r;
    }

    private final Context mCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Semaphore mLock = new Semaphore(1, true);
    private volatile WebView mWeb;
    private volatile boolean mMobile;
    private volatile boolean mLoading;
    private volatile int mProgress;
    private volatile String mBlocked;
    private volatile CountDownLatch mLoadLatch;
    private volatile int mBlockedSchemes;
    /** 本轮碰过的站点（https://host）。 */
    private final Set<String> mTouched = new LinkedHashSet<String>();
    /** 读取前就已经有 Cookie 的站点（不清）。 */
    private final Set<String> mPreexisting = new LinkedHashSet<String>();
    /** 本轮已经检查过「原有 Cookie」的注册域。 */
    private final Set<String> mSeenReg = new LinkedHashSet<String>();

    private BrowserReader(Context ctx) {
        mCtx = ctx;
    }

    /** 能用吗（有 WebView 实现）。 */
    public boolean available() {
        try {
            return Build.VERSION.SDK_INT >= 21 && mCtx.getPackageManager().hasSystemFeature("android.software.webview");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读一页（电脑模式）。 */
    public ReaderResult read(String url) {
        return read(url, false);
    }

    /**
     * 读一页：加载 → 等渲染（价格节点出现且稳定，或到点）→ 抽正文与价格。只许后台线程调用。
     *
     * @param mobile true = 移动 UA + 窄视口（默认 false：电脑模式）
     */
    public ReaderResult read(String url, boolean mobile) {
        return read(url, mobile, ReaderPolicy.PAGE_TIMEOUT_MS);
    }

    /** ★ 11-04 午：AgentLoop 调研读页用更短的上限（{@link ReaderPolicy#AGENT_PAGE_TIMEOUT_MS}），比价跑腿仍 12 s。 */
    public ReaderResult read(String url, boolean mobile, long timeoutMs) {
        final long t0 = SystemClock.elapsedRealtime();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "[READER] 不能在主线程调用");
            return ReaderResult.fail(ReaderResult.Status.ERROR, url, 0, "不能在主线程调用");
        }
        final String u = ReaderPolicy.prepare(url, mobile);
        if (u == null) return ReaderResult.fail(ReaderResult.Status.BLOCKED, url, 0, "不是 http(s) 网页");
        boolean got = false;
        try {
            got = mLock.tryAcquire(ReaderPolicy.LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (!got) return ReaderResult.fail(ReaderResult.Status.BUSY, url, SystemClock.elapsedRealtime() - t0, "另一个读取正在进行");
        try {
            return readLocked(u, mobile, t0, timeoutMs);
        } catch (Throwable t) {
            Log.w(TAG, "[READER] 异常 " + t);
            return ReaderResult.fail(ReaderResult.Status.ERROR, u, SystemClock.elapsedRealtime() - t0, t.getClass().getSimpleName());
        } finally {
            mLock.release();
        }
    }

    private ReaderResult readLocked(final String u, boolean mobile, long t0, long timeoutMs) {
        final long deadline = t0 + Math.max(3000L, Math.min(timeoutMs, ReaderPolicy.PAGE_TIMEOUT_MS));
        if (!ensure(mobile)) return ReaderResult.fail(ReaderResult.Status.ERROR, u, SystemClock.elapsedRealtime() - t0, "WebView 建不起来");
        noteHost(u, true);
        mBlocked = null;
        mLoading = true;
        mProgress = 0;
        mBlockedSchemes = 0;
        final CountDownLatch load = new CountDownLatch(1);
        mLoadLatch = load;
        mMain.post(new Runnable() {
            @Override public void run() {
                WebView w = mWeb;
                if (w != null) w.loadUrl(u);
                else load.countDown();
            }
        });
        Log.i(TAG, "[READER] load " + strip(u) + (mobile ? " (移动)" : " (电脑)"));
        await(load, Math.max(0, deadline - SystemClock.elapsedRealtime() - 1500));
        if (mBlocked != null && mProgress < 100) {
            stopLoading();
            return ReaderResult.fail(ReaderResult.Status.BLOCKED, u, SystemClock.elapsedRealtime() - t0, mBlocked);
        }
        // 等渲染：至少 MIN_SETTLE；之后每 PROBE_EVERY 探一次，价格节点数 > 0 且连续两次不变就抽；到点也抽（留 1.2 s 给抽取）
        SystemClock.sleep(Math.min(ReaderPolicy.MIN_SETTLE_MS, Math.max(0, deadline - SystemClock.elapsedRealtime() - 1200)));
        int last = -1, stable = 0, polls = 0, lastLen = -1;
        while (SystemClock.elapsedRealtime() < deadline - 1200) {
            int[] p = ReaderParser.probe(eval(ReaderScript.PROBE, 1500));
            if (p != null) {
                if (p[2] == 1 && p[0] == 0) break;                         // 可见密码框且没价格：登录墙，直接抽取判定
                if (polls++ < 16) Log.i(TAG, "[READER] probe n=" + p[0] + " len=" + p[1] + " pw=" + p[2] + " complete=" + p[3] + " loading=" + mLoading);
                if (p[0] > 0 && p[0] == last && p[1] >= 200 && !mLoading) { if (++stable >= 1) break; } else stable = 0;
                // ★ 11-04 午（摩托车句一页等了 11.2 s）：没有价格节点的文章页，加载完、正文长度连续两次不变 → 就是读完了，不再等到点
                if (ReaderPolicy.settledWithoutPrice(p[0], p[1], lastLen, p[3] == 1, mLoading)) break;
                last = p[0];
                lastLen = p[1];
            }
            SystemClock.sleep(ReaderPolicy.PROBE_EVERY_MS);
        }
        long left = deadline - SystemClock.elapsedRealtime();
        String raw = eval(ReaderScript.EXTRACT, Math.max(800, left));
        ReaderResult r = raw == null ? null : ReaderParser.parse(raw, u, SystemClock.elapsedRealtime() - t0, System.currentTimeMillis(), mBlockedSchemes);
        // 抽到空页（还在跳转 / 校验页）且还有时间 → 等一下再抽一次
        if ((r == null || (r.status == ReaderResult.Status.BLOCKED && r.text.length() < ReaderParser.EMPTY_TEXT))
                && deadline - SystemClock.elapsedRealtime() > 1500) {
            SystemClock.sleep(Math.min(1500, deadline - SystemClock.elapsedRealtime() - 1200));
            String again = eval(ReaderScript.EXTRACT, Math.max(800, deadline - SystemClock.elapsedRealtime()));
            if (again != null) r = ReaderParser.parse(again, u, SystemClock.elapsedRealtime() - t0, System.currentTimeMillis(), mBlockedSchemes);
        }
        String fin = currentUrl();
        if (fin != null) noteHost(fin, false);
        long ms = SystemClock.elapsedRealtime() - t0;
        stopLoading();
        if (r == null) return ReaderResult.fail(ReaderResult.Status.TIMEOUT, u, ms, "12 s 内没读完");
        Log.i(TAG, "[READER] " + r + " final=" + strip(r.finalUrl));
        return r;
    }

    /** 本轮结束：清本轮新碰过的站点的 Cookie / Web Storage、清缓存、销毁 WebView。可在任何线程调用（不阻塞主线程）。 */
    public void clear() {
        final Set<String> hosts;
        final Set<String> keep;
        synchronized (mTouched) {
            hosts = new LinkedHashSet<String>(mTouched);
            keep = new LinkedHashSet<String>(mPreexisting);
            mTouched.clear();
            mPreexisting.clear();
            mSeenReg.clear();
        }
        Runnable job = new Runnable() {
            @Override public void run() {
                int n = 0;
                try {
                    CookieManager cm = CookieManager.getInstance();
                    for (String h : hosts) {
                        if (keep.contains(regDomain(h))) continue;
                        n += expireCookies(cm, h);
                        try {
                            WebStorage.getInstance().deleteOrigin(h);
                        } catch (Throwable ignored) {
                        }
                    }
                    cm.flush();
                } catch (Throwable t) {
                    Log.w(TAG, "[READER] 清 Cookie 异常 " + t);
                }
                WebView w = mWeb;
                mWeb = null;
                if (w != null) {
                    try {
                        w.stopLoading();
                        w.clearCache(true);
                        w.clearHistory();
                        w.clearFormData();
                        w.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                Log.i(TAG, "[READER] 已清理：站点 " + hosts.size() + " 个、Cookie " + n + " 条、缓存已清"
                        + (keep.isEmpty() ? "" : "；读取前已有 Cookie 的 " + keep.size() + " 个站点未动"));
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) job.run();
        else mMain.post(job);
    }

    /** 把某站点能看到的 Cookie 逐个设为过期（host 与上级域都试一遍）。 */
    private static int expireCookies(CookieManager cm, String origin) {
        String c = cm.getCookie(origin);
        if (c == null || c.length() == 0) return 0;
        String host = origin.replaceFirst("^https?://", "");
        String[] parts = host.split("\\.");
        String reg = parts.length >= 2 ? parts[parts.length - 2] + "." + parts[parts.length - 1] : host;
        int n = 0;
        for (String kv : c.split(";")) {
            int eq = kv.indexOf('=');
            String name = (eq > 0 ? kv.substring(0, eq) : kv).trim();
            if (name.length() == 0) continue;
            String exp = name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";
            cm.setCookie(origin, exp);
            cm.setCookie(origin, exp + "; Domain=" + host);
            cm.setCookie(origin, exp + "; Domain=." + reg);
            n++;
        }
        return n;
    }

    /** 记下碰过的站点；checkPre = 读取开始前（这时该站点的注册域已有 Cookie = 用户原有的，不清）。 */
    private void noteHost(String url, boolean checkPre) {
        String o = origin(url);
        if (o == null) return;
        synchronized (mTouched) {
            mTouched.add(o);
            String reg = regDomain(o);
            if (checkPre && !mSeenReg.contains(reg)) {
                mSeenReg.add(reg);
                try {
                    String c = CookieManager.getInstance().getCookie(o);
                    if (c != null && c.length() > 0) mPreexisting.add(reg);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** search.jd.com → jd.com（只看最后两段；够用于京东 / 淘宝 / 天猫 / 值得买）。 */
    static String regDomain(String origin) {
        String host = origin == null ? "" : origin.replaceFirst("^https?://", "");
        String[] parts = host.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] + "." + parts[parts.length - 1] : host;
    }

    static String origin(String url) {
        if (url == null || !url.startsWith("https://")) return null;
        String s = url.substring(8);
        int k = s.indexOf('/');
        int q = s.indexOf('?');
        if (k < 0 || (q >= 0 && q < k)) k = q;
        return "https://" + (k > 0 ? s.substring(0, k) : s);
    }

    // ------------------------------------------------------------------ WebView（主线程）

    private boolean ensure(final boolean mobile) {
        if (mWeb != null && mMobile == mobile) return true;
        final CountDownLatch l = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                try {
                    WebView old = mWeb;
                    if (old != null) {
                        mWeb = null;
                        old.destroy();
                    }
                    WebView w = new WebView(mCtx);
                    setup(w, mobile);
                    int ww = mobile ? ReaderPolicy.MOBILE_W : ReaderPolicy.DESKTOP_W;
                    int hh = mobile ? ReaderPolicy.MOBILE_H : ReaderPolicy.DESKTOP_H;
                    w.measure(View.MeasureSpec.makeMeasureSpec(ww, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY));
                    w.layout(0, 0, ww, hh);
                    w.onResume();
                    w.resumeTimers();
                    mWeb = w;
                    mMobile = mobile;
                } catch (Throwable t) {
                    Log.w(TAG, "[READER] 创建 WebView 失败: " + t);
                }
                l.countDown();
            }
        });
        await(l, 5000);
        return mWeb != null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void setup(WebView w, boolean mobile) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);          // 京东 / 淘宝 / 值得买的列表是脚本渲染的；不挂任何 JS 接口
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false);
        s.setUserAgentString(mobile ? ReaderPolicy.MOBILE_UA : ReaderPolicy.DESKTOP_UA);
        s.setUseWideViewPort(!mobile);
        s.setLoadWithOverviewMode(!mobile);
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setSavePassword(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        try {
            w.removeJavascriptInterface("searchBoxJavaBridge_");
            w.removeJavascriptInterface("accessibility");
            w.removeJavascriptInterface("accessibilityTraversal");
        } catch (Throwable ignored) {
        }
        w.setDownloadListener(null);
        w.setWebViewClient(new Client());
        w.setWebChromeClient(new Chrome());
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(w, false);
    }

    private void stopLoading() {
        mMain.post(new Runnable() {
            @Override public void run() {
                WebView w = mWeb;
                if (w != null) {
                    try {
                        w.stopLoading();
                        w.loadUrl("about:blank");
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private String currentUrl() {
        final String[] u = {null};
        final CountDownLatch l = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                WebView w = mWeb;
                u[0] = w != null ? w.getUrl() : null;
                l.countDown();
            }
        });
        await(l, 1500);
        return u[0];
    }

    private String eval(final String js, long timeoutMs) {
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
        await(l, timeoutMs);
        return out[0];
    }

    private static void await(CountDownLatch l, long ms) {
        try {
            l.await(Math.max(1, ms), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static String strip(String url) {
        return url == null ? "" : url.replaceAll("#.*$", "");
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return block(req.getUrl() != null ? req.getUrl().toString() : null);
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return block(url);
        }

        private boolean block(String url) {
            if (ReaderPolicy.allowNavigation(url)) {
                noteHost(url, false);
                return false;
            }
            mBlockedSchemes++;
            Log.i(TAG, "[READER] 拦下跳转（" + (ReaderPolicy.isAppScheme(url) ? "唤起 App" : "非 https") + "）: "
                    + (url == null ? "" : url.replaceAll("[?#].*$", "")));
            return true;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            String u = req.getUrl() != null ? req.getUrl().toString() : "";
            if (ReaderPolicy.isAppScheme(u)) {
                mBlockedSchemes++;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
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
            handler.cancel();
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
