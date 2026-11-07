package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.v2.island.IslandSettings;
import com.onesay.assistant.v2.s1.S1Hooks;

/**
 * [island] 真机验收用的调试驱动（<b>只在 debug 包</b>；TestRunReceiver 受签名级权限保护，adb 需 su）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es island status"                 # 形态（ISLAND/NONE）、开关、当前视图、全屏 / 暂避
 * su -c "am broadcast -n $R --es island geom"                   # [ISLAND-TEST] geom：锚点、窗口 x/y/w/h、density、状态栏 px（v3：y = 状态栏 + 2dp）
 * su -c "am broadcast -n $R --es island run [--es text '打车去公司']"   # 演示任务：紧凑运行（不跑真实任务）
 * su -c "am broadcast -n $R --es island step [--es text 确认上车点]"   # 推进一步 → 步骤轻提醒 PULSE_STEP 2.4 s
 * su -c "am broadcast -n $R --es island key [--es text 司机已接单]"     # 关键提醒 ALERT_KEY 4 s（召回暂避）
 * su -c "am broadcast -n $R --es island wait"                   # 待确认 → 立即展开确认卡
 * su -c "am broadcast -n $R --es island allow|deny"             # 岛上确认卡：允许 / 拒绝
 * su -c "am broadcast -n $R --es island done"                   # 完成回执 3 s 后移除
 * su -c "am broadcast -n $R --es island fail [--es text 原因]"  # 失败展开 6 s → 紧凑失败 5 min
 * su -c "am broadcast -n $R --es island pickup|dual"            # 取件码 / 双活动最小态（任务 + 码尾）
 * su -c "am broadcast -n $R --es island tap|longpress|collapse|outside|tuck|copy|next|prev|dismiss|stop|retry|close|reset"
 * su -c "am broadcast -n $R --es island fullscreen --es text on|off|auto"   # 模拟全屏（auto = 交给探针）
 * su -c "am broadcast -n $R --es island remind|remindlate"      # [s3] 提醒到点（remindlate：晚了 25 分钟）
 * su -c "am broadcast -n $R --es island bg|bgwait|bgdone|bgfail"   # [s3] 后台任务：运行 / 确认卡 / 完成 / 失败
 * su -c "am broadcast -n $R --es island mode --es text island|off|auto"   # off = 同 island.off：不画浮层（旧写法 capsule 同 off）
 * su -c "am broadcast -n $R --es island set --es kv 'enabled=0'"      # 改开关（enabled / fullscreen / tuck）
 *
 * 日志 tag：{@code IslandTestDriver}（结果）、{@code Island}（功能本身）。
 */
final class IslandTestDriver {

    private static final String TAG = "IslandTestDriver";

    private IslandTestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("island");
        final String text = intent.getStringExtra("text");
        final String kv = intent.getStringExtra("kv");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatch(ctx, cmd == null ? "status" : cmd.trim(), text, kv);
                } catch (Throwable t) {
                    Log.e(TAG, "[ISLAND-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        });
    }

    private static void dispatch(Context ctx, String cmd, String text, String kv) {
        if ("status".equals(cmd)) {
            Log.i(TAG, "[ISLAND-TEST] status " + S1Hooks.debugIsland());
        } else if ("geom".equals(cmd)) {
            Log.i(TAG, "[ISLAND-TEST] geom " + S1Hooks.debugGeom());
        } else if ("set".equals(cmd) && kv != null && kv.contains("=")) {
            String[] p = kv.split("=", 2);
            boolean on = "1".equals(p[1].trim()) || "true".equalsIgnoreCase(p[1].trim());
            IslandSettings.set(ctx, p[0].trim(), on);
            S1Hooks.refreshIslandMode("debug-set");
            Log.i(TAG, "[ISLAND-TEST] set " + p[0].trim() + "=" + on + " → " + IslandSettings.dump(ctx));
        } else {
            Log.i(TAG, "[ISLAND-TEST] " + cmd + " → " + S1Hooks.debugIslandDemo(cmd, text));
        }
    }
}
