package com.onesay.clock.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.AlarmApi;
import com.onesay.clock.sched.Sched;

import java.util.List;
import java.util.TimeZone;

/**
 * 对外闹钟接口：设 / 开关 / 删 / 查（协议见 {@link AlarmApi}）。
 *
 * <p>exported，但清单里加了 {@code android:permission="com.onesay.clock.permission.ALARM_CONTROL"}（signature 级）：
 * 只有与 OneSay Clock 同一证书签名的 App（OneSay 助手）能投递；第三方 App 的广播被系统直接丢弃。
 * 结果写回有序广播的 resultCode / resultData；变更后与界面操作一样立即重排。日志只记动作与结果码。
 */
public class AlarmApiReceiver extends BroadcastReceiver {
    private static final Object LOCK = new Object();

    @Override public void onReceive(Context c, Intent in) {
        String a = in == null ? null : in.getAction();
        if (!AlarmApi.known(a)) { setResultCode(AlarmApi.RC_UNKNOWN); return; }
        int code;
        String data;
        synchronized (LOCK) {
            Prefs p = new Prefs(c);
            List<Alarm> before = p.alarms();
            AlarmApi.Result r = AlarmApi.apply(before, a,
                    in.getIntExtra(AlarmApi.X_ID, -1), in.getIntExtra(AlarmApi.X_HOUR, -1), in.getIntExtra(AlarmApi.X_MINUTE, -1),
                    in.getIntExtra(AlarmApi.X_DAYS, 0), in.getBooleanExtra(AlarmApi.X_ON, false), in.getStringExtra(AlarmApi.X_LABEL));
            if (r.changed) {
                p.saveAlarms(r.alarms);
                if (AlarmApi.A_CANCEL.equals(a) || (AlarmApi.A_TOGGLE.equals(a) && !in.getBooleanExtra(AlarmApi.X_ON, false))) {
                    com.onesay.clock.core.SnoozeBook b = p.snoozes();   // 删掉 / 关掉的闹钟，挂着的贪睡一并撤（与界面一致）
                    if (b.remove(r.lastId)) p.saveSnoozes(b);
                }
                Sched.rescheduleNow(c, "api");
            }
            code = r.code;
            data = AlarmApi.dump(r.alarms, r.lastId, System.currentTimeMillis(), TimeZone.getDefault());
        }
        L.i(c, "API " + a.substring(a.lastIndexOf('.') + 1) + " rc=" + code);
        if (isOrderedBroadcast()) {
            setResultCode(code);
            setResultData(data);
        }
    }
}
