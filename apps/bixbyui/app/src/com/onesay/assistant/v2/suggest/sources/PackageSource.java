package com.onesay.assistant.v2.suggest.sources;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SignalSource;
import com.onesay.assistant.v2.suggest.ForegroundProbe;

/** ★ feat/suggest 场景 8 信号源：动态注册 ACTION_PACKAGE_ADDED（Android 8 起 manifest 收不到这类隐式广播），带 EXTRA_REPLACING。 */
public final class PackageSource implements SignalSource {
    static final String TAG = "Suggest";
    private final Context mCtx;
    private final Handler mH;
    private volatile Sink mSink;

    public PackageSource(Context ctx, Handler h) {
        mCtx = ctx.getApplicationContext();
        mH = h;
    }

    @Override public String type() {
        return Signal.PKG_ADDED;
    }

    private final BroadcastReceiver mRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null || i.getData() == null) return;
            String pkg = i.getData().getSchemeSpecificPart();
            boolean replacing = i.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            boolean launchable;
            try {
                launchable = mCtx.getPackageManager().getLaunchIntentForPackage(pkg) != null;
            } catch (Throwable t) {
                launchable = false;
            }
            Sink s = mSink;
            if (s == null) return;
            s.emit(Signal.of(Signal.PKG_ADDED, SystemClock.elapsedRealtime(), System.currentTimeMillis(), "pkg", pkg,
                    "label", ForegroundProbe.label(mCtx, pkg), "launchable", launchable ? "1" : "0", "replacing", replacing ? "1" : "0"));
        }
    };

    @Override public void attach(Sink sink) {
        mSink = sink;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            f.addDataScheme("package");
            mCtx.registerReceiver(mRx, f, null, mH);
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 安装广播注册失败: " + t.getClass().getSimpleName());
        }
    }

    @Override public void detach() {
        mSink = null;
        try {
            mCtx.unregisterReceiver(mRx);
        } catch (Throwable ignored) {
        }
    }
}
