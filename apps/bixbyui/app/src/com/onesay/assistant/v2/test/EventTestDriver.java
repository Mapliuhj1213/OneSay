package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.events.EventHooks;

/**
 * [event] 事件任务的上机注入入口（<b>只在 debug 包</b>；TestRunReceiver 受签名级权限保护，adb 需 su）。2026-10-23。
 * 用来测充电事件的防抖、每天上限、充电状态下重启服务不误触发——不用真插拔线、不用 dumpsys battery、不用等 10 分钟。
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es event status"                         # 每条事件任务：今日次数/生效上限、生效防抖、上次触发、失败数；覆盖值；充电基线；最近的 ev: Run
 * su -c "am broadcast -n $R --es event add [--es text '播报今天的待办'] [--ez unplug true]"   # 建一条充电事件任务：照常弹权限卡（勾选后确认才入库）
 * su -c "am broadcast -n $R --es event plug [--ez dry true]"           # 注入「充电插上」（= ACTION_POWER_CONNECTED，非粘性）
 * su -c "am broadcast -n $R --es event unplug [--ez dry true]"         # 注入「充电拔下」
 *        dry = Run 直接记完成（不调模型、不执行）：没有 LLM key 时测防抖 / 上限用，否则 Run 停在待执行 / 失败会挡住下一次（「上一次还没结束」）
 * su -c "am broadcast -n $R --es event guard --ei debounce_sec 20 --ei cap 2"   # 临时把防抖 / 每天上限改小（只在内存，不改任务、不用重新授权、重启即失效）
 * su -c "am broadcast -n $R --es event guard --es text off"            # 恢复任务自己的值
 * su -c "am broadcast -n $R --es event baseline --es text plug|unplug"  # 只记基线（= 注册广播时收到的粘性广播），不触发
 * su -c "am broadcast -n $R --es event restart"                        # 模拟面板服务重启：注销广播 → 忘掉状态记忆 → 重新注册
 * su -c "am broadcast -n $R --es event reset [--el id 3]"              # 清掉今日次数 / 防抖起点（不传 id = 全部）
 * </pre>
 * 日志 tag：{@code EventTestDriver}（结果）、{@code Events}（功能本身：「[EVENT] CHARGING+ → runs=1 skipped=[]（debug 注入）」）。
 * 事件 Run 真跑（不加 dry）时走 S3Runner：没授权 → 等确认 + 岛上提示；有授权但没有 LLM key → 失败计数（3 次自动暂停）。
 */
final class EventTestDriver {

    private static final String TAG = "EventTestDriver";

    private EventTestDriver() {}

    static void handle(final Context ctx, final Intent intent) {
        final String cmd = intent.getStringExtra("event");
        com.onesay.assistant.v2.util.Threads.start("event-test", new Runnable() {
            @Override public void run() {
                try {
                    dispatch(ctx.getApplicationContext(), cmd == null ? "status" : cmd.trim(), intent);
                } catch (Throwable t) {
                    Log.e(TAG, "[EVENT-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        });
    }

    private static void dispatch(Context app, String cmd, Intent it) {
        String text = it.getStringExtra("text");
        String r;
        if ("status".equals(cmd)) {
            r = "";
        } else if ("plug".equals(cmd) || "unplug".equals(cmd)) {
            r = EventHooks.debugCharging(app, "plug".equals(cmd), it.getBooleanExtra("dry", false));
        } else if ("guard".equals(cmd)) {
            if ("off".equals(text)) r = EventHooks.debugGuards(app, -1, -1);
            else r = EventHooks.debugGuards(app, it.getIntExtra("debounce_sec", -1), it.getIntExtra("cap", -1));
        } else if ("baseline".equals(cmd)) {
            r = EventHooks.debugBaseline(app, !"unplug".equals(text));
        } else if ("restart".equals(cmd)) {
            r = EventHooks.debugRestart(app);
        } else if ("reset".equals(cmd)) {
            r = EventHooks.debugResetCounters(app, it.getLongExtra("id", 0L));
        } else if ("add".equals(cmd)) {
            r = EventHooks.debugAddCharging(app, text, !it.getBooleanExtra("unplug", false));
        } else {
            Log.w(TAG, "[EVENT-TEST] 不认识的命令 " + cmd);
            return;
        }
        Log.i(TAG, "[EVENT-TEST] " + cmd + (r.length() > 0 ? " → " + r : "") + "\n" + EventHooks.debugStatus(app));
    }
}
