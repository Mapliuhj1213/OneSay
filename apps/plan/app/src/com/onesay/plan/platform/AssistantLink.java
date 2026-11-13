package com.onesay.plan.platform;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.onesay.plan.core.LinkContract;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 伴侣连接 · 反查助手：有序广播 {@link LinkContract#ACTION_QUERY} 发给配对的助手（包名来自清单 meta-data，构建时由占位符
 * ${assistantAppId} 填写；没有 meta-data 时按 release / debug 两个候选里「装了且同证书」的那个）。
 * 助手接收器受它自己的 signature 级 CONTROL 权限保护，本 App 清单里 uses-permission 同样由占位符生成，所以 debug / release 永远对得上。
 * 只读；不要在主线程调用 {@link #query}。
 */
public final class AssistantLink {
    private AssistantLink() {}

    static final String TAG = "BixbyPlan";
    static final long TIMEOUT_MS = 3000L;
    private static Handler sHandler;

    private static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("assistant-link");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

    /** 配对的助手包名（装了且与本 App 同证书）；没有返回 null。 */
    public static String assistantPkg(Context c) {
        PackageManager pm = c.getPackageManager();
        String meta = null;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) meta = ai.metaData.getString(LinkContract.META_ASSISTANT);
        } catch (Throwable ignored) {
        }
        String[] cands = meta != null && meta.length() > 0 && !meta.contains("$")
                ? new String[]{meta}
                : new String[]{LinkContract.ASSISTANT_PKG, LinkContract.ASSISTANT_PKG + LinkContract.DEBUG_SUFFIX};
        for (String p : cands) {
            try {
                pm.getPackageInfo(p, 0);
                if (pm.checkSignatures(c.getPackageName(), p) == PackageManager.SIGNATURE_MATCH) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** method = link.hello / getAssistantStatus；返回助手的 JSON，失败 null。 */
    public static String query(Context c, String method) {
        final String pkg = assistantPkg(c);
        if (pkg == null) return null;
        Intent i = new Intent(LinkContract.ACTION_QUERY).setPackage(pkg).putExtra(LinkContract.K_METHOD, method)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] code = {0};
        final String[] data = {null};
        try {
            c.sendOrderedBroadcast(i, null, new BroadcastReceiver() {
                @Override public void onReceive(Context cc, Intent in) {
                    code[0] = getResultCode();
                    data[0] = getResultData();
                    latch.countDown();
                }
            }, handler(), 0, null, null);
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "[LINK] 反查助手失败: " + t.getClass().getSimpleName());
            return null;
        }
        Log.i(TAG, "[LINK] 反查助手 " + pkg + " " + method + " rc=" + code[0]);
        return code[0] == LinkContract.RC_OK ? data[0] : null;
    }
}
