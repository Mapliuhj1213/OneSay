package com.onesay.assistant.v2.errand;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.errand.web.WebViewSetup;

/**
 * ★ lane C3-2：跑腿「请你接手」页 —— 与代理<b>同一套 Cookie / 同样的电脑版 UA</b>。
 *
 * <ul>
 *   <li>{@link #MODE_TAKEOVER}：登录 / 验证码。用户自己处理完点「继续跑腿」→ 代理在后台接着跑；点「结束」/ 返回 = 放弃。</li>
 *   <li>{@link #MODE_HANDOFF}：购物到了结算页（C3-3）/ 跑腿结果页。<b>付款由用户自己点</b>；这里没有任何自动操作。</li>
 * </ul>
 * 这一页是用户本人在操作：页面里要拉起支付宝 / 淘宝等 App 时按用户的点击放行（代理那边一律拦下）。
 */
public final class ErrandBrowserActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TASK = "task";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_REASON = "reason";
    public static final String MODE_TAKEOVER = "takeover";
    public static final String MODE_HANDOFF = "handoff";

    private WebView mWeb;
    private String mTask;
    private boolean mTakeover;
    private boolean mAnswered;

    public static Intent intent(android.content.Context ctx, String url, String task, String mode, String reason) {
        Intent i = new Intent(ctx, ErrandBrowserActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_TASK, task);
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_REASON, reason);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // ★ r2/input：网页里的登录 / 验证码输入框弹键盘时，窗口压缩到键盘上方（WebView 变矮、页面自己滚到焦点处），底部按钮条也在键盘上方。
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Intent in = getIntent();
        String url = in.getStringExtra(EXTRA_URL);
        mTask = in.getStringExtra(EXTRA_TASK);
        mTakeover = MODE_TAKEOVER.equals(in.getStringExtra(EXTRA_MODE));
        String reason = in.getStringExtra(EXTRA_REASON);
        if (url == null || !WebViewSetup.isWebUrl(url)) {
            finish();
            return;
        }

        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1C1C1E"));

        TextView banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(14);
        banner.setPadding(pad, pad, pad, pad);
        banner.setText(mTakeover
                ? (reason != null ? reason : "需要你接手") + "：请在下面的页面里自己完成，然后点「继续跑腿」。"
                : (reason != null ? reason : "请你自己核对") + "。付款只能由你自己完成，跑腿不会替你点付款。");
        root.addView(banner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mWeb = new WebView(this);
        WebViewSetup.apply(mWeb, false);
        mWeb.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl() != null ? req.getUrl().toString() : null;
                if (WebViewSetup.isWebUrl(u)) return false;
                // 用户本人点的（例如结算页「打开支付宝」）：交给系统。
                // 只放行已知支付 / 登录 scheme；intent: 不再 parseUri（拒 component / selector，
                //   丢 extras），重建干净的 VIEW Intent；放行时提示将离开本 App。
                com.onesay.assistant.v2.errand.web.ExternalSchemePolicy.Decision d = com.onesay.assistant.v2.errand.web.ExternalSchemePolicy.decide(u);
                if (!d.allowed) {
                    Log.w("Errand", "接手页：拦截外部跳转（" + d.reason + "）");
                    try { Toast.makeText(ErrandBrowserActivity.this, "已拦截网页跳转到外部应用", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
                    return true;
                }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(d.viewUri));
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    if (d.pkg != null) i.setPackage(d.pkg);
                    Log.i("Errand", "接手页：离开本 App 打开外部应用 scheme=" + Uri.parse(d.viewUri).getScheme());
                    try { Toast.makeText(ErrandBrowserActivity.this, "正在离开 OneSay，打开外部应用", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
                    startActivity(i);
                } catch (Throwable t) {
                    Log.i("Errand", "接手页：无法打开 " + t.getMessage());
                }
                return true;
            }
        });
        root.addView(mWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END);
        bar.setPadding(pad, pad / 2, pad, pad / 2);
        if (mTakeover) {
            bar.addView(button("结束", new Runnable() {
                @Override public void run() {
                    answer(false);
                }
            }));
            bar.addView(button("继续跑腿", new Runnable() {
                @Override public void run() {
                    answer(true);
                }
            }));
        } else {
            bar.addView(button("关闭", new Runnable() {
                @Override public void run() {
                    finish();
                }
            }));
        }
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        // ★ integrate：沉浸式全屏下 adjustResize 不生效，按可见区补底部内边距，按钮条留在键盘上方
        com.onesay.assistant.v2.ui.ImeInsetPadder.attach(this, root);
        mWeb.loadUrl(url);
    }

    private Button button(String text, final Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                r.run();
            }
        });
        return b;
    }

    private void answer(boolean resume) {
        if (mAnswered) return;
        mAnswered = true;
        try {
            android.webkit.CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        if (mTakeover) ErrandHooks.takeoverDone(mTask, resume, mWeb != null ? mWeb.getUrl() : null);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (mWeb != null && mWeb.canGoBack()) {
            mWeb.goBack();
            return;
        }
        if (mTakeover) answer(false);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (mTakeover && !mAnswered) {
            mAnswered = true;
            ErrandHooks.takeoverDone(mTask, false, null);
        }
        if (mWeb != null) {
            try {
                mWeb.destroy();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
