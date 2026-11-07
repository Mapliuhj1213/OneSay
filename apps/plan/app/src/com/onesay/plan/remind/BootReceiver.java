package com.onesay.plan.remind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 改时间 / 改时区 / 升级后：重排本地提醒。只认这几个系统广播，别的 action 一律忽略。 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Remind.schedule(c);
        }
    }
}
