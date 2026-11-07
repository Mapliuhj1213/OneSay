/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。回答里的链接被点了怎么办。
 */
package com.onesay.assistant.plug.richout.host;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;

import com.onesay.assistant.plug.richout.core.LinkPolicy;
import com.onesay.assistant.plug.richout.core.RichOutPlugs;

/**
 * 点击链接（{@link LinkPolicy} 裁决）：
 * <ul>
 *   <li>http(s) → 应用内阅读器（面板上方的底部弹层，{@link ReaderSheet}）；</li>
 *   <li>第三方 App 深链 / mailto / tel… → 弹确认（显示 scheme 和完整地址），点「打开」才交给系统：重建干净的 VIEW + BROWSABLE
 *       Intent（不带 component / selector / extras），解析到本 App 自己的组件时拒绝；</li>
 *   <li>其它 → 拦下并提示；</li>
 *   <li>插件 richout.reader 关着 → 不跳转，复制链接。</li>
 * </ul>
 */
public final class LinkRouter {

    private LinkRouter() {}

    private static final String TAG = "RichOut";

    public static void open(Context ctx, String url) {
        LinkPolicy.Decision d = LinkPolicy.decide(url);
        Log.i(TAG, "[RICHOUT] 点链接 → " + d);
        if (d.action == LinkPolicy.Action.BLOCK) {
            RichImageSaver.toast(ctx.getApplicationContext(), "已拦截不安全的链接");
            return;
        }
        if (!RichOutHost.readerOn()) {
            RichRenderer.copyText(ctx, d.url, "已复制链接");
            return;
        }
        if (d.action == LinkPolicy.Action.READER) {
            ReaderSheet.show(ctx, d);
            return;
        }
        confirmExternal(ctx, d.url, d.reason);
    }

    /** 离开本 App（深链 / 系统浏览器）前的确认弹窗（悬浮窗类型，压在面板上面）。 */
    static void confirmExternal(final Context ctx, final String url, String reason) {
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            String shown = url.length() > 200 ? url.substring(0, 200) + "…" : url;
            AlertDialog dlg = new AlertDialog.Builder(new ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                    .setTitle("要离开 OneSay 吗？")
                    .setMessage((reason != null ? reason + "：" : "") + "\n" + shown)
                    .setNegativeButton("取消", null)
                    .setNeutralButton("复制链接", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            RichRenderer.copyText(app, url, "已复制链接");
                        }
                    })
                    .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            launchView(app, url, false);
                        }
                    })
                    .create();
            if (dlg.getWindow() != null) {
                dlg.getWindow().setType(Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE);
            }
            RichOutHost.overlayOpened();
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface dialog) {
                    RichOutHost.overlayClosed();
                }
            });
            dlg.show();
        } catch (Throwable t) {
            Log.w(TAG, "[RICHOUT] 确认框打不开: " + t.getMessage());
            RichOutHost.overlayClosed();
            RichRenderer.copyText(app, url, "打不开确认框，已复制链接");
        }
    }

    /** 交给系统：干净的 VIEW Intent；http(s) 时 browserOnly = 只交给浏览器类应用。 */
    static boolean launchView(Context ctx, String url, boolean browserOnly) {
        LinkPolicy.Decision d = LinkPolicy.decide(url);
        if (d.action == LinkPolicy.Action.BLOCK) return false;
        if (browserOnly && d.action != LinkPolicy.Action.READER) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(d.url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setComponent(null);
            i.setSelector(null);
            ComponentName target = i.resolveActivity(ctx.getPackageManager());
            if (target == null) {
                RichImageSaver.toast(ctx, "没有能打开它的应用");
                return false;
            }
            if (ctx.getPackageName().equals(target.getPackageName())) {
                Log.w(TAG, "[RICHOUT] 链接解析到本 App 自己的组件，拒绝");
                return false;
            }
            stepAsideForExternal();
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[RICHOUT] 打不开: " + t.getMessage());
            RichImageSaver.toast(ctx, "打不开这个链接");
            return false;
        }
    }

    /** 要去别的 App 了：面板（悬浮窗）会盖住它，先收起面板。 */
    static void stepAsideForExternal() {
        try {
            com.onesay.assistant.v2.ui.FloatingSheetService s = com.onesay.assistant.v2.ui.FloatingSheetService.getInstance();
            if (s != null) s.s1HideSheetNow();
        } catch (Throwable ignored) {
        }
    }
}
