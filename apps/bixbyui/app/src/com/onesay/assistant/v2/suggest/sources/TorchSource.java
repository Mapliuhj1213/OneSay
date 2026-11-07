package com.onesay.assistant.v2.suggest.sources;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SignalSource;
import com.onesay.assistant.plug.suggest.core.scenes.TorchScene;
import com.onesay.assistant.v2.suggest.ForegroundProbe;

/**
 * ★ feat/suggest 场景 7 信号源：CameraManager.registerTorchCallback（API 23+）。手电筒开着时：满 30 s（{@link TorchScene#ON_MIN_MS}）
 * 发第一次 tick，之后每 2 分钟一次（息屏 / 锁屏时场景自己不弹，计时照走）；亮屏发 screen_on、解锁发 user_present（动态注册）；
 * 关掉 → 停 tick 并发 off + withdraw=torch（引擎撤掉岛上的手电筒卡、取消推迟中的候选）。
 */
public final class TorchSource implements SignalSource {
    static final String TAG = "Suggest";
    static final long TICK_MS = 2L * 60000L;
    private final Context mCtx;
    private final Handler mH;
    private volatile Sink mSink;
    private CameraManager mCm;
    private volatile boolean mOn;
    private volatile long mOnSince;
    private volatile String mCamId;

    public TorchSource(Context ctx, Handler h) {
        mCtx = ctx.getApplicationContext();
        mH = h;
    }

    @Override public String type() {
        return Signal.TORCH;
    }

    private final CameraManager.TorchCallback mCb = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (enabled == mOn) return;
            mOn = enabled;
            mCamId = cameraId;
            mH.removeCallbacks(mTick);
            if (enabled) {
                mOnSince = SystemClock.elapsedRealtime();
                mH.postDelayed(mTick, TorchScene.ON_MIN_MS + 200L);
            } else {
                emit(TorchScene.EV_OFF);
            }
        }
    };

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (!mOn) return;
            emit(TorchScene.EV_TICK);
            mH.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver mScreen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!mOn) return;
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) emit(TorchScene.EV_SCREEN_ON);
            else if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) emit(TorchScene.EV_USER_PRESENT);
        }
    };

    private void emit(String ev) {
        Sink s = mSink;
        if (s == null) return;
        String[] fg = TorchScene.EV_OFF.equals(ev) ? null : ForegroundProbe.now(mCtx, false);
        s.emit(Signal.of(Signal.TORCH, SystemClock.elapsedRealtime(), System.currentTimeMillis(), "on", mOn ? "1" : "0",
                "on_since", String.valueOf(mOnSince), "event", ev, "fg", fg == null ? null : fg[0], "camera_id", mCamId,
                Signal.K_WITHDRAW, TorchScene.EV_OFF.equals(ev) ? TorchScene.ID : null));
    }

    @Override public void attach(Sink sink) {
        mSink = sink;
        try {
            mCm = (CameraManager) mCtx.getSystemService(Context.CAMERA_SERVICE);
            if (mCm != null) mCm.registerTorchCallback(mCb, mH);
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            mCtx.registerReceiver(mScreen, f, null, mH);
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 手电筒源注册失败: " + t.getClass().getSimpleName());
        }
    }

    @Override public void detach() {
        mSink = null;
        mH.removeCallbacks(mTick);
        try {
            if (mCm != null) mCm.unregisterTorchCallback(mCb);
        } catch (Throwable ignored) {
        }
        try {
            mCtx.unregisterReceiver(mScreen);
        } catch (Throwable ignored) {
        }
    }
}
