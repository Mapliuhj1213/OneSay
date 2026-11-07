package com.onesay.assistant.v2.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机与安装更新广播接收器。
 *
 * 保证设备重启或应用包被更新后自愈并恢复前台待命。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "收到系统广播: " + action);

        // [s3] 定时任务闹钟：口令不对的一律忽略（本接收器是导出的）
        if (com.onesay.assistant.v2.s3.S3Scheduler.ACTION_ALARM.equals(action)) {
            if (!com.onesay.assistant.v2.s3.S3Scheduler.verify(context, intent)) {
                Log.w(TAG, "[S3-ALARM] 口令不对，忽略");
                return;
            }
            com.onesay.assistant.v2.s3.S3Scheduler.startRunner(context, "alarm");
            return;
        }
        // [s3] 改时间 / 改时区：重排闹钟 + 补触发（开机与应用更新在下面一起处理）
        if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            com.onesay.assistant.v2.s3.S3Scheduler.reschedule(context, action);
            com.onesay.assistant.v2.s3.S3Scheduler.startRunner(context, action);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            com.onesay.assistant.v2.s3.S3Scheduler.reschedule(context, action);   // [s3] 闹钟在重启 / 更新后会丢，重排
            com.onesay.assistant.v2.s3.S3Scheduler.startRunner(context, action);  // [s3] 补触发错过不到 1 小时的任务
            Log.i(TAG, "系统就绪，自动唤醒 OneSay WakeService 常驻监听服务");
            Intent it = new Intent(context, com.onesay.assistant.v2.wake.WakeService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(it);
            } else {
                context.startService(it);
            }
        }
    }
}
