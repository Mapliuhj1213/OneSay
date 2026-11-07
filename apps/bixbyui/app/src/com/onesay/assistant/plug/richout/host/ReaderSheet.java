/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。应用内阅读器：面板上方的底部弹层，阅读模式 / 原网页两档。
 */
package com.onesay.assistant.plug.richout.host;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onesay.assistant.plug.richout.core.LinkPolicy;
import com.onesay.assistant.plug.richout.core.ReaderText;
import com.onesay.assistant.v2.errand.web.PageScript;
import com.onesay.assistant.v2.errand.web.PageSnapshot;

/**
 * 应用内阅读器（{@link OverlaySheet}，高 88%，叠在面板上方；关掉回到回答）。
 *
 * <p><b>阅读模式</b>（默认）：{@link ReaderFetch} 直接取页（无 Cookie、不跑脚本）→ {@link ReaderText} 抽正文；
 * 不够再 Tavily extract；还不够就后台加载原网页，页面加载完用 {@link PageScript#EXTRACT}（evaluateJavascript，
 * 不是 JS 接口）读 {@link PageSnapshot} 的正文。三档都没有 → 自动切到原网页。
 *
 * <p><b>原网页</b>：WebView，JS 开（真实网页离不开）、禁 file / content 访问、禁混合内容、Safe Browsing 开（API 26+）、
 * 不注入任何 JS 接口（并移除老 WebView 自带桥）、禁弹窗 / 多窗口 / 定位 / 摄像头麦克风 / 下载；
 * 页内每次跳转都过 {@link LinkPolicy}：http(s) 照常、第三方深链弹确认、intent:// / file:// / javascript: 等拦下；
 * SSL 错误一律取消。不接受第三方 Cookie。
 *
 * <p>Cookie：Android WebView 的 Cookie 库按进程共享，阅读器与跑腿浏览器同进程，所以<b>原网页这一档与跑腿浏览器共用第一方 Cookie</b>
 * （在跑腿里登录过的网站，这里也是登录状态）；阅读模式的直接取页走 HttpURLConnection，不带任何 Cookie。见 RICH-OUTPUT.md 待拍板。
 */
final class ReaderSheet extends OverlaySheet {

    private static final String TAG = "RichOut";
    static final float HEIGHT = 0.88f;
    static final long SNAPSHOT_DELAY_MS = 1500L;

    private final LinkPolicy.Decision mLink;
    private final RichRenderer.Style mSt = new RichRenderer.Style();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private String mUrl;
    private TextView mTitle;
    private TextView mTabReader;
    private TextView mTabWeb;
    private FrameLayout mStage;
    private ScrollView mReaderScroll;
    private LinearLayout mReaderBox;
    private TextView mReaderStatus;
    private ProgressBar mProgress;
    private WebView mWeb;
    private boolean mWebMode;
    private boolean mReaderDone;
    private boolean mWantSnapshot;
    private volatile boolean mClosed;

    private ReaderSheet(Context ctx, LinkPolicy.Decision d) {
        super(ctx);
        mLink = d;
        mUrl = d.url;
    }

    static void show(Context ctx, LinkPolicy.Decision d) {
        new ReaderSheet(ctx, d).show();
    }

    @Override protected float heightFraction() {
        return HEIGHT;
    }

    @Override protected View content(FrameLayout root) {
        int p = RichRenderer.dp(mCtx, 14);
        LinearLayout col = new LinearLayout(mCtx);
        col.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        float r = RichRenderer.dp(mCtx, 18);
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        col.setBackground(bg);
        col.setClickable(true);

        // 把手
        View grip = new View(mCtx);
        grip.setBackground(RichRenderer.rounded(0x55FFFFFF, RichRenderer.dp(mCtx, 3)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(RichRenderer.dp(mCtx, 36), RichRenderer.dp(mCtx, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = RichRenderer.dp(mCtx, 8);
        col.addView(grip, glp);

        // 标题行
        LinearLayout head = new LinearLayout(mCtx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(p, RichRenderer.dp(mCtx, 6), RichRenderer.dp(mCtx, 6), 0);
        mTitle = new TextView(mCtx);
        mTitle.setText(mLink.host != null && mLink.host.length() > 0 ? mLink.host : mUrl);
        mTitle.setTextColor(mSt.t1);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = RichRenderer.chip(mCtx, "关闭", mSt);
        head.addView(close);
        col.addView(head);

        // 网址（http 明文时提示）
        TextView urlLine = new TextView(mCtx);
        urlLine.setText((mUrl.startsWith("http://") ? "⚠ 非加密连接 · " : "") + clip(mUrl, 90));
        urlLine.setTextColor(mUrl.startsWith("http://") ? 0xFFFFB340 : mSt.t3);
        urlLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        urlLine.setSingleLine(true);
        urlLine.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        urlLine.setPadding(p, 0, p, RichRenderer.dp(mCtx, 4));
        col.addView(urlLine);

        // 两档 + 操作
        LinearLayout bar = new LinearLayout(mCtx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(RichRenderer.dp(mCtx, 8), 0, RichRenderer.dp(mCtx, 6), RichRenderer.dp(mCtx, 4));
        mTabReader = RichRenderer.chip(mCtx, "阅读模式", mSt);
        mTabWeb = RichRenderer.chip(mCtx, "原网页", mSt);
        bar.addView(mTabReader);
        bar.addView(mTabWeb);
        View spacer = new View(mCtx);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView browser = RichRenderer.chip(mCtx, "在浏览器打开", mSt);
        TextView copy = RichRenderer.chip(mCtx, "复制链接", mSt);
        bar.addView(browser);
        bar.addView(copy);
        col.addView(bar);

        mProgress = new ProgressBar(mCtx, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(100);
        mProgress.setVisibility(View.GONE);
        col.addView(mProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, RichRenderer.dp(mCtx, 3)));

        mStage = new FrameLayout(mCtx);
        col.addView(mStage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mReaderScroll = new ScrollView(mCtx);
        mReaderBox = new LinearLayout(mCtx);
        mReaderBox.setOrientation(LinearLayout.VERTICAL);
        mReaderBox.setPadding(RichRenderer.dp(mCtx, 18), RichRenderer.dp(mCtx, 8), RichRenderer.dp(mCtx, 18), RichRenderer.dp(mCtx, 28));
        mReaderStatus = new TextView(mCtx);
        mReaderStatus.setText("正在读取正文…");
        mReaderStatus.setTextColor(mSt.t3);
        mReaderStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mReaderStatus.setPadding(0, RichRenderer.dp(mCtx, 24), 0, 0);
        mReaderStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        mReaderBox.addView(mReaderStatus);
        mReaderScroll.addView(mReaderBox);
        mStage.addView(mReaderScroll, new FrameLayout.LayoutParams(-1, -1));

        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                close();
            }
        });
        mTabReader.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showReader();
            }
        });
        mTabWeb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showWeb();
            }
        });
        browser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String u = currentUrl();
                close();
                LinkRouter.launchView(mCtx, u, true);
            }
        });
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                RichRenderer.copyText(mCtx, currentUrl(), "已复制链接");
            }
        });

        markTabs();
        startReader();
        return col;
    }

    private String currentUrl() {
        if (mWeb != null) {
            String u = mWeb.getUrl();
            if (u != null && LinkPolicy.allowInReader(u)) return u;
        }
        return mUrl;
    }

    private void markTabs() {
        mTabReader.setTextColor(mWebMode ? mSt.t3 : mSt.accent);
        mTabWeb.setTextColor(mWebMode ? mSt.accent : mSt.t3);
        mTabReader.setBackground(RichRenderer.rounded(mWebMode ? 0 : 0x2264D2FF, RichRenderer.dp(mCtx, 12)));
        mTabWeb.setBackground(RichRenderer.rounded(mWebMode ? 0x2264D2FF : 0, RichRenderer.dp(mCtx, 12)));
    }

    // ------------------------------------------------------------------ 阅读模式

    private void startReader() {
        final String url = mUrl;
        new Thread(new Runnable() {
            @Override public void run() {
                final ReaderFetch.Result r = ReaderFetch.fetch(mCtx, url);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (mClosed) return;
                        if (r.doc != null && r.doc.usable()) {
                            fillReader(r.doc, r.via);
                        } else {
                            // 第三档：原网页加载完读 PageSnapshot
                            mReaderStatus.setText("这页需要浏览器渲染，正在加载原网页取正文…");
                            mWantSnapshot = true;
                            ensureWeb();
                        }
                    }
                });
            }
        }, "richout-reader").start();
    }

    private void fillReader(ReaderText.Doc doc, String via) {
        mReaderDone = true;
        mReaderBox.removeAllViews();
        if (doc.title != null && doc.title.length() > 0) {
            TextView t = new TextView(mCtx);
            t.setText(doc.title);
            t.setTextColor(mSt.t1);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setLineSpacing(RichRenderer.dp(mCtx, 2), 1.1f);
            mReaderBox.addView(t);
            mTitle.setText(doc.title);
        }
        TextView src = new TextView(mCtx);
        src.setText("阅读模式 · " + (via == null ? "" : via) + " · 只显示文字，图片和交互请看原网页");
        src.setTextColor(mSt.t3);
        src.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        src.setPadding(0, RichRenderer.dp(mCtx, 6), 0, RichRenderer.dp(mCtx, 10));
        mReaderBox.addView(src);
        for (String para : doc.paragraphs) {
            TextView tv = new TextView(mCtx);
            tv.setText(para);
            tv.setTextColor(0xFFDAD7D2);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            tv.setLineSpacing(RichRenderer.dp(mCtx, 4), 1.15f);
            tv.setTextIsSelectable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = RichRenderer.dp(mCtx, 12);
            mReaderBox.addView(tv, lp);
        }
    }

    private void showReader() {
        if (!mWebMode) return;
        mWebMode = false;
        if (mWeb != null) mWeb.setVisibility(View.GONE);
        mReaderScroll.setVisibility(View.VISIBLE);
        markTabs();
    }

    // ------------------------------------------------------------------ 原网页

    private void showWeb() {
        mWebMode = true;
        ensureWeb();
        mReaderScroll.setVisibility(View.GONE);
        mWeb.setVisibility(View.VISIBLE);
        markTabs();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWeb() {
        if (mWeb != null) return;
        final WebView w = new WebView(mCtx);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(ReaderFetch.UA);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                s.setSafeBrowsingEnabled(true);
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
        w.setBackgroundColor(0xFFFFFFFF);
        w.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                RichImageSaver.toast(mCtx, "阅读器里不下载文件；要下载请「在浏览器打开」");
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return route(req.getUrl() == null ? null : req.getUrl().toString(), req.isForMainFrame());
            }

            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return route(url, true);
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mProgress.setVisibility(View.VISIBLE);
            }

            @Override public void onPageFinished(WebView view, String url) {
                mProgress.setVisibility(View.GONE);
                if (url != null && LinkPolicy.allowInReader(url)) mUrl = url;
                if (mWantSnapshot && !mReaderDone) {
                    mMain.postDelayed(new Runnable() {
                        @Override public void run() {
                            snapshot();
                        }
                    }, SNAPSHOT_DELAY_MS);
                }
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                RichImageSaver.toast(mCtx, "这个网站的证书有问题，已停止加载");
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                mProgress.setProgress(p);
            }

            @Override public void onReceivedTitle(WebView view, String title) {
                if (mWebMode && title != null && title.length() > 0) mTitle.setText(title);
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
        w.setVisibility(mWebMode ? View.VISIBLE : View.GONE);
        mStage.addView(w, new FrameLayout.LayoutParams(-1, -1));
        mWeb = w;
        w.loadUrl(mUrl);
    }

    /** 页内跳转：true = 拦下（自己处理）。 */
    private boolean route(String url, boolean mainFrame) {
        LinkPolicy.Decision d = LinkPolicy.decide(url);
        if (d.action == LinkPolicy.Action.READER) return false;
        if (!mainFrame) return true;
        Log.i(TAG, "[RICHOUT] 阅读器内跳转 → " + d);
        if (d.action == LinkPolicy.Action.CONFIRM) LinkRouter.confirmExternal(mCtx, d.url, d.reason);
        else RichImageSaver.toast(mCtx, "已拦截不安全的跳转");
        return true;
    }

    private void snapshot() {
        if (mWeb == null || mReaderDone || mClosed) return;
        mWeb.evaluateJavascript(PageScript.EXTRACT, new ValueCallback<String>() {
            @Override public void onReceiveValue(String raw) {
                if (mClosed || mReaderDone) return;
                PageSnapshot ps = PageSnapshot.parse(raw);
                ReaderText.Doc doc = ps == null ? null : ReaderText.fromText(ps.title, ps.text);
                if (doc != null && doc.usable()) {
                    fillReader(doc, "原网页提取");
                } else {
                    mReaderStatus.setText("没能提取出正文，已切到原网页。");
                    showWeb();
                }
            }
        });
    }

    @Override protected boolean onBack() {
        if (mWebMode && mWeb != null && mWeb.canGoBack()) {
            mWeb.goBack();
            return true;
        }
        return false;
    }

    @Override protected void onClosed() {
        mClosed = true;
        mMain.removeCallbacksAndMessages(null);
        SandboxWeb.destroy(mWeb);
        mWeb = null;
    }

    private static String clip(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
