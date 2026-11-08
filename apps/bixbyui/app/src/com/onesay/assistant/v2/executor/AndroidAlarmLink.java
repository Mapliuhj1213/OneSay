package com.onesay.assistant.v2.executor;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.AlarmClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link AlarmBridge.Link} 的 Android 实现。
 *
 * <ul>
 *   <li>时钟可用 = 装了 {@value AlarmBridge#CLOCK_PKG}、与本 App 同一证书签名（checkSignatures）、
 *       本 App 持有 signature 级权限 {@value AlarmBridge#CLOCK_PERMISSION}（两个 App 都声明它，安装先后不影响授予）。</li>
 *   <li>调用时钟 = 有序广播，{@code setPackage} 锁定时钟包，结果在独立 HandlerThread 上收（调用线程阻塞等，最多 {@link #TIMEOUT_MS}）。
 *       没有接收器（时钟版本太旧）时系统直接回初始结果码 {@link AlarmBridge#RC_NONE}。</li>
 *   <li>系统闹钟 = {@link AlarmClock#ACTION_SET_ALARM} / {@link AlarmClock#ACTION_SHOW_ALARMS}；先 resolveActivity，
 *       没有 App 能接 → {@link AlarmBridge.SystemOutcome#NO_HANDLER}。需要权限 com.android.alarm.permission.SET_ALARM（清单已声明）。</li>
 * </ul>
 * 不要在主线程调用 {@link #callClock}。
 */
final class AndroidAlarmLink implements AlarmBridge.Link {

    private static final String TAG = "AlarmBridge";
    static final long TIMEOUT_MS = 4000L;

    private static HandlerThread sThread;
    private static Handler sHandler;

    private final Context ctx;

    AndroidAlarmLink(Context ctx) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
    }

    private static synchronized Handler handler() {
        if (sHandler == null) {
            sThread = new HandlerThread("alarm-bridge");
            sThread.start();
            sHandler = new Handler(sThread.getLooper());
        }
        return sHandler;
    }

    @Override
    public AlarmBridge.ClockState clockState() {
        PackageManager pm = ctx.getPackageManager();
        try {
            pm.getPackageInfo(AlarmBridge.CLOCK_PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return AlarmBridge.ClockState.MISSING;
        } catch (Throwable t) {
            return AlarmBridge.ClockState.MISSING;
        }
        boolean same = false, perm = false;
        try {
            same = pm.checkSignatures(ctx.getPackageName(), AlarmBridge.CLOCK_PKG) == PackageManager.SIGNATURE_MATCH;
            perm = ctx.checkPermission(AlarmBridge.CLOCK_PERMISSION, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) { }
        if (same && perm) return AlarmBridge.ClockState.READY;
        Log.w(TAG, "OneSay 时钟已安装但不可用：sameSigner=" + same + " permission=" + perm);
        return AlarmBridge.ClockState.UNTRUSTED;
    }

    @Override
    public AlarmBridge.ClockReply callClock(AlarmBridge.ClockCmd cmd) {
        // [companion] 时钟伴侣连接通（同证书 + link.hello 版本 / 能力都对）→ 走 createAlarm / listAlarms / deleteAlarm / toggleAlarm；
        //   结果格式与有序广播的 resultCode / resultData 一致。连接不通（旧时钟没有 link provider）→ 下面的有序广播照旧。
        AlarmBridge.ClockReply viaLink = callClockViaLink(cmd);
        if (viaLink != null) return viaLink;
        Intent i = new Intent(cmd.action).setPackage(AlarmBridge.CLOCK_PKG)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        if (cmd.id > 0) i.putExtra("id", cmd.id);
        if (cmd.hour >= 0) i.putExtra("hour", cmd.hour);
        if (cmd.minute >= 0) i.putExtra("minute", cmd.minute);
        i.putExtra("days", cmd.days);
        i.putExtra("on", cmd.on);
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] code = {AlarmBridge.RC_NONE};
        final String[] data = {null};
        try {
            ctx.sendOrderedBroadcast(i, null, new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent in) {
                    code[0] = getResultCode();
                    data[0] = getResultData();
                    latch.countDown();
                }
            }, handler(), AlarmBridge.RC_NONE, null, null);
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "调用时钟失败: " + t.getClass().getSimpleName());
            return null;
        }
        return new AlarmBridge.ClockReply(code[0], AlarmBridge.parseDumpJson(data[0]));
    }

    AlarmBridge.ClockReply callClockViaLink(AlarmBridge.ClockCmd cmd) {
        try {
            com.onesay.assistant.v2.companion.CompanionLink l = com.onesay.assistant.v2.companion.CompanionLink.get(ctx);
            if (!l.state(com.onesay.assistant.v2.companion.CompanionLink.Peer.CLOCK).ready()) return null;
            String fn = com.onesay.assistant.v2.companion.CompanionAlarm.function(cmd.action);
            if (fn == null) return null;
            android.os.Bundle x = new android.os.Bundle();
            if (cmd.id > 0) x.putInt("id", cmd.id);
            if (cmd.hour >= 0) x.putInt("hour", cmd.hour);
            if (cmd.minute >= 0) x.putInt("minute", cmd.minute);
            x.putInt("days", cmd.days);
            x.putBoolean("on", cmd.on);
            android.os.Bundle b = new com.onesay.assistant.v2.companion.CompanionClient(ctx).clock(fn, x);
            return new AlarmBridge.ClockReply(b.getInt("code", AlarmBridge.RC_NONE), AlarmBridge.parseDumpJson(b.getString("data", null)));
        } catch (Throwable t) {
            Log.w(TAG, "伴侣连接调用时钟失败，改用有序广播: " + t.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public AlarmBridge.SystemOutcome startSystem(AlarmBridge.SystemAlarmSpec spec) {
        Intent i;
        if (spec.set) {
            i = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, spec.minutes)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, spec.skipUi);
            if (spec.message != null) i.putExtra(AlarmClock.EXTRA_MESSAGE, spec.message);
            if (spec.days != null && !spec.days.isEmpty()) {
                i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, new ArrayList<Integer>(spec.days));
            }
        } else {
            i = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (i.resolveActivity(ctx.getPackageManager()) == null) {
                Log.w(TAG, "没有 App 能处理 " + i.getAction());
                return AlarmBridge.SystemOutcome.NO_HANDLER;
            }
            ctx.startActivity(i);
            return AlarmBridge.SystemOutcome.STARTED;
        } catch (ActivityNotFoundException e) {
            return AlarmBridge.SystemOutcome.NO_HANDLER;
        } catch (Throwable t) {
            Log.w(TAG, "启动系统闹钟失败: " + t.getClass().getSimpleName());
            return AlarmBridge.SystemOutcome.FAILED;
        }
    }
}
