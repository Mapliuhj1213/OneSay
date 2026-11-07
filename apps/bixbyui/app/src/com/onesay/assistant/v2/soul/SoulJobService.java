package com.onesay.assistant.v2.soul;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * [soul] 每日记忆整理（研究 §6.3-3）：JobScheduler 周期任务，约束「充电 + 设备空闲 + 有网」，每天最多真正跑一次
 * （{@link SoulEngine#consolidateDue}）。整理是纯规则（不调模型），失败只记日志，不影响主流程。不做每小时任务。
 */
public final class SoulJobService extends JobService {

    private static final String TAG = "Soul";
    public static final int JOB_ID = 0x5011;

    public static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, SoulJobService.class))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24L * 3600000L)
                .setPersisted(false)
                .build();
        int r = js.schedule(info);
        Log.i(TAG, "[SOUL] 每日整理已排程 result=" + r);
    }

    @Override public boolean onStartJob(final JobParameters params) {
        com.onesay.assistant.v2.util.Threads.start("soul-consolidate", new Runnable() {
            @Override public void run() {
                try {
                    SoulHooks.consolidate(getApplicationContext(), false);
                } catch (Throwable t) {
                    Log.w(TAG, "[SOUL] 每日整理异常（忽略）: " + t.getMessage());
                } finally {
                    jobFinished(params, false);
                }
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
