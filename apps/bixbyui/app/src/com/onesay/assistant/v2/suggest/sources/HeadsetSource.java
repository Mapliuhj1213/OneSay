package com.onesay.assistant.v2.suggest.sources;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SignalSource;
import com.onesay.assistant.plug.suggest.core.scenes.MusicApps;
import com.onesay.assistant.v2.suggest.ForegroundProbe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★ feat/suggest 场景 6 信号源。双路径：
 * <ol>
 *   <li>AudioManager.registerAudioDeviceCallback（API 23，不要权限，有线 / 蓝牙 A2DP / USB 耳机都报；注册时会把已连着的设备先报一遍 → 注册后 1.5 s 内的忽略）；</li>
 *   <li>ACTION_HEADSET_PLUG 动态注册（有线；忽略初始粘性广播）。</li>
 * </ol>
 * 两路 5 秒内只发一次。候选音乐 App = 已装、有桌面入口的 {@link MusicApps}，按 UsageStats 最近 7 天前台时长排；没开 usage appop 就全是 0（场景不弹）。
 */
public final class HeadsetSource implements SignalSource {
    static final String TAG = "Suggest";
    private final Context mCtx;
    private final Handler mH;
    private volatile Sink mSink;
    private long mAttachedAt, mLastEmit;
    private AudioManager mAm;

    public HeadsetSource(Context ctx, Handler h) {
        mCtx = ctx.getApplicationContext();
        mH = h;
    }

    @Override public String type() {
        return Signal.HEADSET;
    }

    private final AudioDeviceCallback mDev = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            if (SystemClock.elapsedRealtime() - mAttachedAt < 1500L || added == null) return;
            for (AudioDeviceInfo d : added) {
                if (!d.isSink()) continue;
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    connected(t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ? "bt" : "wired", "device_cb");
                    return;
                }
            }
        }
    };

    private final BroadcastReceiver mPlug = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (isInitialStickyBroadcast()) return;
            if (i.getIntExtra("state", 0) == 1) connected("wired", "headset_plug");
        }
    };

    private void connected(String kind, String via) {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastEmit < 5000L) return;
        mLastEmit = now;
        Sink s = mSink;
        if (s == null) return;
        String cands = MusicApps.encode(candidates());
        String[] fg = ForegroundProbe.now(mCtx, false);
        Log.i(TAG, "[SUGGEST] 耳机连上（" + kind + " via " + via + "）候选音乐 App " + (cands.length() == 0 ? 0 : cands.split(";").length) + " 个");
        s.emit(Signal.of(Signal.HEADSET, now, System.currentTimeMillis(), "state", "on", "kind", kind, "candidates", cands,
                "fg", fg == null ? null : fg[0]));
    }

    List<MusicApps.Usage> candidates() {
        List<MusicApps.Usage> out = new ArrayList<MusicApps.Usage>();
        Map<String, UsageStats> agg = null;
        try {
            UsageStatsManager um = (UsageStatsManager) mCtx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            if (um != null) agg = um.queryAndAggregateUsageStats(now - 7L * 24L * 3600000L, now);
        } catch (Throwable ignored) {
        }
        PackageManager pm = mCtx.getPackageManager();
        for (Map.Entry<String, String> e : MusicApps.KNOWN.entrySet()) {
            try {
                if (pm.getLaunchIntentForPackage(e.getKey()) == null) continue;
            } catch (Throwable t) {
                continue;
            }
            UsageStats us = agg == null ? null : agg.get(e.getKey());
            long ms = us == null ? 0 : us.getTotalTimeInForeground();
            String label = ForegroundProbe.label(mCtx, e.getKey());
            out.add(new MusicApps.Usage(e.getKey(), label == null ? e.getValue() : label, ms));
        }
        if (agg == null || agg.isEmpty()) Log.i(TAG, "[SUGGEST] 耳机：UsageStats 无数据（usage appop 可能没开）");
        return out;
    }

    @Override public void attach(Sink sink) {
        mSink = sink;
        mAttachedAt = SystemClock.elapsedRealtime();
        try {
            mAm = (AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
            if (mAm != null) mAm.registerAudioDeviceCallback(mDev, mH);
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 耳机源（设备回调）注册失败: " + t.getClass().getSimpleName());
        }
        try {
            mCtx.registerReceiver(mPlug, new IntentFilter(AudioManager.ACTION_HEADSET_PLUG), null, mH);
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 耳机源（插拔广播）注册失败: " + t.getClass().getSimpleName());
        }
    }

    @Override public void detach() {
        mSink = null;
        try {
            if (mAm != null) mAm.unregisterAudioDeviceCallback(mDev);
        } catch (Throwable ignored) {
        }
        try {
            mCtx.unregisterReceiver(mPlug);
        } catch (Throwable ignored) {
        }
    }
}
