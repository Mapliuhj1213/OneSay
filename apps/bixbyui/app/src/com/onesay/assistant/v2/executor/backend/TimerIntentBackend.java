package com.onesay.assistant.v2.executor.backend;

import android.content.Intent;
import android.provider.AlarmClock;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;

/**
 * [fix-1123-dexec B32] 倒计时（clock.timer.set）走 App 自己发的系统计时器意图（AlarmClock.ACTION_SET_TIMER，普通权限 SET_ALARM），
 * 不再拼 {@code am start} 交给特权通道。
 *
 * <p>真机（魅族，无 root，只有 Shizuku）：「1分钟后提醒我喝水」→ clock.timer.set 走 shell 的 am，被 Shizuku 白名单挡下，
 * 用户看到内部码。am 能拉起任意组件、发任意广播，不该为了一个计时器放进白名单；App 自己 startActivity 就够了。
 * 单测里没有 Context 时不接（照旧走原来的后端，老测试不受影响）。</p>
 */
public final class TimerIntentBackend implements ExecutorBackend {

    public static final String SIGN = "clock.timer.set";

    @Override
    public String name() {
        return "timer-intent";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return accepts(cap == null ? null : cap.sign, req != null && req.context != null);
    }

    /** 纯函数：只接倒计时，而且要有 Context 才能自己发意图。 */
    public static boolean accepts(String sign, boolean hasContext) {
        return hasContext && SIGN.equals(sign);
    }

    /** 秒数（1..86400）；不合法 = -1。 */
    public static int seconds(org.json.JSONObject args) {
        if (args == null) return -1;
        int s = args.optInt("seconds", -1);
        return s >= 1 && s <= 86400 ? s : -1;
    }

    /** 给人看的时长：90 → 「1 分 30 秒」。 */
    public static String human(int sec) {
        if (sec < 60) return sec + " 秒";
        if (sec < 3600) return (sec / 60) + " 分" + (sec % 60 > 0 ? " " + (sec % 60) + " 秒" : "钟");
        int h = sec / 3600, m = (sec % 3600) / 60;
        return h + " 小时" + (m > 0 ? " " + m + " 分钟" : "");
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        int sec = seconds(req.args);
        if (sec < 0) {
            return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.NEED_PARAM, "倒计时的时长不对，说一下要几分钟", "seconds 不合法",
                    req.cap, req.args));
        }
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String label = req.args == null ? "" : req.args.optString("label", "").trim();
            if (label.length() > 0) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            if (i.resolveActivity(req.context.getPackageManager()) == null) {
                return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.NOT_FOUND_APP, "这台手机上没有能接倒计时的时钟应用",
                        "SET_TIMER 无人接", req.cap, req.args));
            }
            req.context.startActivity(i);
            return BackendOutcome.terminal(ExecutionResult.success(req.cap, req.args, "已开始 " + human(sec) + "倒计时",
                    ExecutionResult.Verification.UNVERIFIED, "SET_TIMER " + sec + "s（App 自己发，不走特权通道）"));
        } catch (Throwable t) {
            return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.EXEC_FAILED, "倒计时没开起来，可以打开时钟自己定一个",
                    "SET_TIMER 失败: " + t.getClass().getSimpleName(), req.cap, req.args));
        }
    }
}
