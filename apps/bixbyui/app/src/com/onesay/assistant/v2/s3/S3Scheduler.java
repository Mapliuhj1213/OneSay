package com.onesay.assistant.v2.s3;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.security.SecureRandom;

/**
 * [s3] 闹钟排期（Android）：<b>只排一个闹钟</b> = 所有任务里最早的下一次触发（{@link S3Engine#earliestNext}），到点后
 * {@link S3Runner} 一次处理所有到期 / 补触发 / 错过的任务，再排下一个。这样不用维护 N 个 PendingIntent。
 *
 * <ul>
 *   <li>{@code AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)}（Doze 下也能叫醒；系统对它有约 9 分钟一次的频率限制 风险）；</li>
 *   <li>目标：已有的 {@code BootReceiver}（显式 Intent，action {@link #ACTION_ALARM}）—— Manifest 不新增组件；</li>
 *   <li>BootReceiver 是导出的（系统要投递开机广播），所以闹钟 Intent 带一个本机随机口令 {@link #EXTRA_NONCE}，口令不对的广播一律忽略，
 *       第三方 App 没法让任务提前触发；</li>
 *   <li>重排时机：新建 / 取消 / 触发后（引擎 onChange）、BOOT_COMPLETED、MY_PACKAGE_REPLACED（应用更新）、TIME_SET、TIMEZONE_CHANGED。</li>
 * </ul>
 */
public final class S3Scheduler {

    private static final String TAG = "S3";
    public static final String ACTION_ALARM = "com.onesay.assistant.v2.action.S3_ALARM";
    public static final String EXTRA_NONCE = "s3_nonce";
    private static final String PREF = "bixby_s3_sched";
    private static final String KEY_NONCE = "nonce";
    private static final String KEY_NEXT = "next_at";

    private S3Scheduler() {}

    static String nonce(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String n = sp.getString(KEY_NONCE, null);
        if (n == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            n = sb.toString();
            sp.edit().putString(KEY_NONCE, n).apply();
        }
        return n;
    }

    /** 闹钟 Intent 的口令是否正确（BootReceiver 调）。 */
    public static boolean verify(Context ctx, Intent it) {
        if (it == null) return false;
        String got = it.getStringExtra(EXTRA_NONCE);
        return got != null && got.equals(nonce(ctx));
    }

    private static PendingIntent pi(Context ctx) {
        Intent it = new Intent(ACTION_ALARM);
        it.setClassName(ctx.getPackageName(), "com.onesay.assistant.v2.ui.BootReceiver");
        it.putExtra(EXTRA_NONCE, nonce(ctx));
        return PendingIntent.getBroadcast(ctx, 7301, it, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 按库重排唯一的闹钟（任意线程）。 */
    public static void reschedule(Context ctx, String why) {
        try {
            S3Engine e = S3Hooks.engine(ctx);
            long next = e.earliestNext();
            // lane-a：定时任务 v2（模板预生成 / 重试 / 待送达 outbox）共用这一个闹钟
            long jobs = com.onesay.assistant.v2.jobs.JobsHooks.earliestNext(ctx);
            if (jobs > 0 && (next <= 0 || jobs < next)) next = jobs;
            // ★：灵动岛消息提醒（日程 / 定时邮件提前量、免打扰结束、过期、日历轮询）也共用这一个闹钟
            long notice = com.onesay.assistant.v2.notice.NoticeHooks.earliestNext(ctx);
            if (notice > 0 && (next <= 0 || notice < next)) next = notice;
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent p = pi(ctx);
            am.cancel(p);
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_NEXT, next).apply();
            if (next <= 0) {
                Log.i(TAG, "[S3-ALARM] 没有待触发的任务，闹钟已清（" + why + "）");
                return;
            }
            long at = Math.max(next, System.currentTimeMillis() + 1000L);
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, p);
            Log.i(TAG, "[S3-ALARM] 已排闹钟 at=" + at + "（" + new java.util.Date(at) + "，" + why + "）");
        } catch (Throwable t) {
            Log.w(TAG, "[S3-ALARM] 排闹钟失败: " + t.getMessage());
        }
    }

    public static long scheduledAt(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_NEXT, -1L);
    }

    /** 唤起面板服务处理到期任务（BootReceiver / 调试调）。 */
    public static void startRunner(Context ctx, String why) {
        try {
            Intent it = new Intent(ctx, com.onesay.assistant.v2.ui.FloatingSheetService.class);
            it.setAction(com.onesay.assistant.v2.ui.FloatingSheetService.ACTION_S3_FIRE);
            it.putExtra("why", why);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(it);
            else ctx.startService(it);
        } catch (Throwable t) {
            Log.w(TAG, "[S3-ALARM] 启动面板服务失败: " + t.getMessage());
        }
    }
}
