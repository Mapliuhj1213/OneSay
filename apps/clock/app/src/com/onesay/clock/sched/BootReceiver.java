package com.onesay.clock.sched;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.onesay.clock.L;

/** 开机 / 升级 / 改时间 / 改时区 → 重排（错过 10 分钟内的贪睡会立刻补响，见 SnoozeBook.LATE_MS）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        String a = String.valueOf(in.getAction());
        L.i(c, "SYS " + a);
        Sched.rescheduleNow(c, a.substring(a.lastIndexOf('.') + 1));
    }
}
