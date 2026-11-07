/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。全屏看图（双指缩放）+ 保存为 PNG。
 */
package com.onesay.assistant.plug.richout.host;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.assistant.plug.richout.core.DiagramHtml;

final class DiagramViewer extends OverlaySheet {

    private final String mKind;
    private final String mSrc;
    private WebView mWeb;
    private boolean mReady;
    private final RichRenderer.Style mSt = new RichRenderer.Style();

    private DiagramViewer(Context ctx, String kind, String src) {
        super(ctx);
        mKind = kind;
        mSrc = src;
    }

    static void show(Context ctx, String kind, String src) {
        new DiagramViewer(ctx, kind, src).show();
    }

    @Override protected float heightFraction() {
        return 1f;
    }

    @Override protected View content(FrameLayout root) {
        LinearLayout col = new LinearLayout(mCtx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xF2111114);
        LinearLayout bar = new LinearLayout(mCtx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = RichRenderer.dp(mCtx, 12);
        bar.setPadding(p, RichRenderer.dp(mCtx, 28), p / 2, p / 2);
        TextView title = new TextView(mCtx);
        title.setText(RichRenderer.kindLabel(mKind) + " · 双指缩放");
        title.setTextColor(mSt.t2);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = RichRenderer.chip(mCtx, "保存为图片", mSt);
        TextView close = RichRenderer.chip(mCtx, "关闭", mSt);
        bar.addView(save);
        bar.addView(close);
        col.addView(bar);

        final DiagramHtml.Page page = DiagramHtml.forKind(mKind, mSrc, true, true);
        if (!page.ok()) {
            TextView err = new TextView(mCtx);
            err.setText(page.error);
            err.setTextColor(mSt.t3);
            err.setPadding(p, p, p, p);
            col.addView(err);
        } else {
            mWeb = SandboxWeb.create(mCtx, page.js, true, new SandboxWeb.Listener() {
                @Override public void onRendered(int cssHeight) {
                    mReady = true;
                }

                @Override public void onFailed(String why) {
                    mReady = false;
                }
            });
            col.addView(mWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            SandboxWeb.load(mWeb, page.html);
        }
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mWeb != null && mReady) RichImageSaver.saveView(mCtx, mWeb, true);
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                close();
            }
        });
        return col;
    }

    @Override protected void onClosed() {
        SandboxWeb.destroy(mWeb);
        mWeb = null;
    }
}
