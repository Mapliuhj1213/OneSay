package com.onesay.clock.sched;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.core.SnoozeBook;
import com.onesay.clock.gen.GenService;
import com.onesay.clock.ring.RingService;

/** 不导出：只接自己的 PendingIntent。FIRE → 响铃服务；PREGEN/RETRY → 生成服务。 */
public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        String a = in.getAction() == null ? "" : in.getAction();
        int id = in.getIntExtra("id", 0);
        long at = in.getLongExtra("at", 0L);
        boolean snooze = in.getBooleanExtra("snooze", false);
        if (Sched.A_PREGEN.equals(a) || Sched.A_RETRY.equals(a)) {
            L.i(c, (Sched.A_RETRY.equals(a) ? "RETRY" : "PREGEN") + " id=" + id + " forAlarmAt=" + at);
            GenService.start(c, at, Sched.A_RETRY.equals(a) ? "retry" : "pregen");
            return;
        }
        if (!Sched.A_FIRE.equals(a)) return;
        if (snooze) {
            SnoozeBook b = new Prefs(c).snoozes();
            if (!b.matches(id, at)) {                         // 贪睡已被停止/删除：不响，但照常重排
                L.i(c, "FIRE snooze#" + id + " 已不在贪睡记录里，忽略");
                Sched.rescheduleNow(c, "stale-snooze");
                return;
            }
        }
        L.i(c, "FIRE id=" + id + " at=" + at + " snooze=" + snooze);
        boolean brief = Sched.onFired(c, id, at, snooze);
        RingService.start(c, id, at, snooze, brief, false);
    }
}
