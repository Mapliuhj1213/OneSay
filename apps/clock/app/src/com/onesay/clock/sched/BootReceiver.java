package com.onesay.clock.sched;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.onesay.clock.L;

/** 开机 / 升级 / 改时间 / 改时区 → 先查错过的闹钟（只发通知），再重排（错过 10 分钟内的贪睡会立刻补响，见 SnoozeBook.LATE_MS）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        String a = String.valueOf(in.getAction());
        L.i(c, "SYS " + a);
        Sched.checkMissed(c, a);   // [borrow-1112] 先对账：上次排上的那次没响就补一条通知（不补响）
        Sched.rescheduleNow(c, a.substring(a.lastIndexOf('.') + 1));
    }
}
