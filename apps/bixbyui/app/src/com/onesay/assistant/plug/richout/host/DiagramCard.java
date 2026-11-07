/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。回答里的一张图（svg / mermaid / chart）。
 */
package com.onesay.assistant.plug.richout.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.assistant.plug.richout.core.DiagramHtml;
import com.onesay.assistant.plug.richout.core.RichOutPlugs;

/**
 * 图卡片：标题行（类型 · 全屏 · 保存）+ 沙箱 WebView。渲染完成按内容高度定高（最多 {@link RichOutPlugs#DIAGRAM_MAX_HEIGHT_DP}，
 * 点卡片 / 「全屏」看全图、双指缩放）；渲染失败或 8 秒没回音 → 换成源码卡片（降级，不留空白）。
 * 卡片离开窗口时销毁 WebView。
 */
final class DiagramCard {

    private static final String TAG = "RichOut";
    static final long TIMEOUT_MS = 8000L;

    private final Context mCtx;
    private final String mKind;
    private final String mSrc;
    private final RichRenderer.Style mSt;
    private final LinearLayout mCard;
    private final FrameLayout mBody;
    private final TextView mStatus;
    private WebView mWeb;
    private boolean mDone;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    DiagramCard(Context ctx, String kind, String src, RichRenderer.Style st) {
        mCtx = ctx;
        mKind = kind;
        mSrc = src;
        mSt = st;
        mCard = new LinearLayout(ctx);
        mCard.setOrientation(LinearLayout.VERTICAL);
        mCard.setBackground(RichRenderer.rounded(st.codeBg, RichRenderer.dp(ctx, 12)));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(RichRenderer.dp(ctx, 12), RichRenderer.dp(ctx, 6), RichRenderer.dp(ctx, 4), RichRenderer.dp(ctx, 2));
        TextView label = new TextView(ctx);
        label.setText(RichRenderer.kindLabel(kind));
        label.setTextColor(st.t3);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView full = RichRenderer.chip(ctx, "全屏", st);
        TextView save = RichRenderer.chip(ctx, "保存", st);
        head.addView(full);
        head.addView(save);
        mCard.addView(head);

        mBody = new FrameLayout(ctx);
        mStatus = new TextView(ctx);
        mStatus.setText("画图中…");
        mStatus.setTextColor(st.t3);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mStatus.setGravity(Gravity.CENTER);
        mBody.addView(mStatus, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, RichRenderer.dp(ctx, 120)));
        mCard.addView(mBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View.OnClickListener openFull = new View.OnClickListener() {
            @Override public void onClick(View v) {
                DiagramViewer.show(mCtx, mKind, mSrc);
            }
        };
        full.setOnClickListener(openFull);
        mStatus.setOnClickListener(openFull);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mWeb != null && mDone) RichImageSaver.saveView(mCtx, mWeb, mSt.dark);
                else DiagramViewer.show(mCtx, mKind, mSrc);
            }
        });
        mCard.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                start();
            }

            @Override public void onViewDetachedFromWindow(View v) {
                mMain.removeCallbacksAndMessages(null);
                SandboxWeb.destroy(mWeb);
                mWeb = null;
                mDone = false;
            }
        });
    }

    View view() {
        return mCard;
    }

    private void start() {
        if (mWeb != null) return;
        final DiagramHtml.Page page = DiagramHtml.forKind(mKind, mSrc, mSt.dark, false);
        if (!page.ok()) {
            fallback(page.error);
            return;
        }
        mWeb = SandboxWeb.create(mCtx, page.js, false, new SandboxWeb.Listener() {
            @Override public void onRendered(int cssHeight) {
                onOk(cssHeight);
            }

            @Override public void onFailed(String why) {
                fallback("图渲染失败：" + why);
            }
        });
        // 点卡片 = 全屏（WebView 自己吃掉触摸，单击在这里转）
        mWeb.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0;

            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    x0 = e.getX();
                    y0 = e.getY();
                } else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                        && Math.abs(e.getX() - x0) < 12 && Math.abs(e.getY() - y0) < 12) {
                    DiagramViewer.show(mCtx, mKind, mSrc);
                }
                return true;
            }
        });
        mBody.addView(mWeb, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, RichRenderer.dp(mCtx, 120)));
        SandboxWeb.load(mWeb, page.html);
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (!mDone) fallback("图渲染超时");
            }
        }, TIMEOUT_MS);
    }

    private void onOk(int cssHeight) {
        if (mWeb == null) return;
        mDone = true;
        mStatus.setVisibility(View.GONE);
        int max = RichRenderer.dp(mCtx, RichOutPlugs.DIAGRAM_MAX_HEIGHT_DP);
        int h = cssHeight > 0 ? RichRenderer.dp(mCtx, cssHeight + 4) : (int) (mWeb.getContentHeight() * mCtx.getResources().getDisplayMetrics().density) + RichRenderer.dp(mCtx, 4);
        if (h <= RichRenderer.dp(mCtx, 20)) h = RichRenderer.dp(mCtx, 200);
        ViewGroup.LayoutParams lp = mWeb.getLayoutParams();
        lp.height = Math.min(max, h);
        mWeb.setLayoutParams(lp);
        Log.i(TAG, "[RICHOUT] 图已画好 " + mKind + " css 高 " + cssHeight);
    }

    private void fallback(String why) {
        if (mDone && mWeb != null) return;
        mDone = false;
        mMain.removeCallbacksAndMessages(null);
        Log.w(TAG, "[RICHOUT] 图降级为源码（" + mKind + "）：" + why);
        SandboxWeb.destroy(mWeb);
        mWeb = null;
        mBody.removeAllViews();
        TextView note = new TextView(mCtx);
        note.setText(why + "，下面是源码");
        note.setTextColor(mSt.t3);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(RichRenderer.dp(mCtx, 12), RichRenderer.dp(mCtx, 4), RichRenderer.dp(mCtx, 12), RichRenderer.dp(mCtx, 4));
        mBody.addView(note, RichRenderer.matchWrap());
        View code = RichRenderer.codeCard(mCtx, "svg".equals(mKind) ? "svg" : "chart".equals(mKind) ? "json" : mKind, mSrc, mSt);
        FrameLayout.LayoutParams lp = RichRenderer.matchWrap();
        lp.topMargin = RichRenderer.dp(mCtx, 24);
        mBody.addView(code, lp);
    }
}
