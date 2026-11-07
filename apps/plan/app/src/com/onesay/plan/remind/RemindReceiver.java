package com.onesay.plan.remind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟到点 / 通知按钮 / 开机 / 改时间时区：都在后台线程里处理（goAsync）。不导出，外部发不进来（开机等系统广播除外）。 */
public final class RemindReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context ctx, final Intent i) {
        final PendingResult pr = goAsync();
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String a = i.getAction();
                    if (Remind.ACTION_FIRE.equals(a)) Remind.fire(c);
                    else if (Remind.ACTION_DONE.equals(a) || Remind.ACTION_TAKEN.equals(a) || Remind.ACTION_COPY.equals(a)) Remind.action(c, i);
                    else Remind.scheduleNow(c);
                } finally {
                    pr.finish();
                }
            }
        }, "plan-remind-rx").start();
    }
}
