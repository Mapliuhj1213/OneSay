package com.onesay.assistant.v2.events;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsHooks;

import java.util.TimeZone;

/**
 * lane-a 第 7 项事件触发的 Android 胶水：引擎单例、动态注册广播（充电 / Wi‑Fi / 蓝牙）、通知入口、把新建的 Run 交给 S3Runner 执行。
 *
 * <p>为什么动态注册：Android 8 起 ACTION_POWER_CONNECTED / NETWORK_STATE_CHANGED 不在隐式广播豁免名单里，清单里静态注册收不到。
 * 接收器挂在面板服务（S3Runner 所在进程）的生命周期上：服务在 = 能收到事件；服务被杀 = 收不到（可靠性靠省电白名单 + 前台服务）。
 * 粘性广播（注册时立刻投递的当前状态）只记基线，不触发。
 */
public final class EventHooks {

    private EventHooks() {}

    private static final String TAG = "Events";
    private static volatile EventEngine sEngine;
    private static BroadcastReceiver sReceiver;
    /** 上一个连上的 SSID（断开广播里没有 SSID，用它补出「断开某 SSID」）。 */
    private static volatile String sLastSsid = "";

    public static EventEngine engine(Context ctx) {
        EventEngine e = sEngine;
        if (e == null) {
            synchronized (EventHooks.class) {
                e = sEngine;
                if (e == null) {
                    Context app = ctx.getApplicationContext();
                    com.onesay.assistant.v2.jobs.SqliteJobStore js = com.onesay.assistant.v2.jobs.SqliteJobStore.get(app, com.onesay.assistant.v2.jobs.SqliteJobStore.DB_PROD);
                    e = new EventEngine(com.onesay.assistant.v2.planner.PlannerHooks.events(app, new SqliteEventStore(js)), JobsHooks.engine(app), TimeZone.getDefault());   // ★ planner
                    sEngine = e;
                }
            }
        }
        return e;
    }

    /** 面板服务 onCreate（S3Runner.onServiceCreate 里调）。 */
    public static synchronized void onServiceCreate(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (sReceiver != null) return;
        sReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent it) {
                try {
                    handle(app, it, isInitialStickyBroadcast());
                } catch (Throwable t) {
                    Log.w(TAG, "[EVENT] 处理广播异常: " + t);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            app.registerReceiver(sReceiver, f);
            Log.i(TAG, "[EVENT] 已注册充电 / Wi‑Fi / 蓝牙广播");
        } catch (Throwable t) {
            Log.w(TAG, "[EVENT] 注册广播失败: " + t);
            sReceiver = null;
        }
    }

    public static synchronized void onServiceDestroy(Context ctx) {
        if (sReceiver == null) return;
        try {
            ctx.getApplicationContext().unregisterReceiver(sReceiver);
        } catch (Throwable ignored) {
        }
        sReceiver = null;
    }

    static void handle(Context app, Intent it, boolean sticky) {
        String a = it.getAction();
        if (a == null) return;
        long now = System.currentTimeMillis();
        EventSignal s = null;
        if (Intent.ACTION_POWER_CONNECTED.equals(a)) s = EventSignal.charging(true, now);
        else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) s = EventSignal.charging(false, now);
        else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) {
            NetworkInfo ni = it.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            if (ni == null) return;
            if (ni.isConnected()) {
                String ssid = "";
                WifiInfo wi = it.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                if (wi != null) ssid = wi.getSSID();
                if (EventSignal.normalizeSsid(ssid).length() == 0) {
                    try {   // Android 8.1：没有定位权限 / 定位关着时读到 <unknown ssid>
                        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                        if (wm != null && wm.getConnectionInfo() != null) ssid = wm.getConnectionInfo().getSSID();
                    } catch (Throwable ignored) {
                    }
                }
                String n = EventSignal.normalizeSsid(ssid);
                if (n.length() > 0 && !n.equals(sLastSsid) && sLastSsid.length() > 0 && !sticky) {
                    dispatch(app, EventSignal.wifi(false, sLastSsid, now));   // 直接从 A 换到 B：先补一个「断开 A」
                }
                sLastSsid = n;
                s = EventSignal.wifi(true, n, now);
            } else if (ni.getState() == NetworkInfo.State.DISCONNECTED) {
                s = EventSignal.wifi(false, sLastSsid, now);
                sLastSsid = "";
            } else {
                return;   // CONNECTING / DISCONNECTING 等中间态
            }
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
            BluetoothDevice d = it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String name = "", mac = "";
            if (d != null) {
                try {
                    name = d.getName();
                    mac = d.getAddress();
                } catch (Throwable ignored) {
                }
            }
            s = EventSignal.bluetooth(BluetoothDevice.ACTION_ACL_CONNECTED.equals(a), name, mac, now);
        }
        if (s == null) return;
        if (sticky) {
            engine(app).baseline(s);
            return;
        }
        dispatch(app, s);
    }

    /** 通知入口（{@link EventNotificationListener}）：白名单前置过滤在监听器里已经做过一次，这里再由引擎按任务精确匹配。 */
    public static void onNotification(Context ctx, String pkg, String label, String title, String text) {
        dispatch(ctx.getApplicationContext(), EventSignal.notification(pkg, label, title, text, System.currentTimeMillis()));
    }

    /** 某个包有没有任务关心（通知监听前置过滤：不关心就连内容都不读）。 */
    public static boolean wantsPackage(Context ctx, String pkg) {
        try {
            return engine(ctx).wantsPackage(pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    static void dispatch(final Context app, final EventSignal s) {
        com.onesay.assistant.v2.util.Threads.start("event-" + s.source.name().toLowerCase(java.util.Locale.ROOT), new Runnable() {
            @Override public void run() {
                fire(app, s, false, "");
            }
        });
    }

    /** 一次事件 → 引擎 → 执行新建的 Run（调用方线程）。dry = 只 debug 注入用：Run 直接记完成、不执行。 */
    static EventEngine.Outcome fire(Context app, EventSignal s, boolean dry, String via) {
        EventEngine e = engine(app);
        EventEngine.Outcome o = e.onSignal(s, System.currentTimeMillis(), null);
        // 日志只写源 / 方向 / 包名和跳过原因，不写通知内容
        if (!o.runs.isEmpty() || !o.skipped.isEmpty() || via.length() > 0) Log.i(TAG, "[EVENT] " + s + " → " + o + via);
        if (o.runs.isEmpty()) return o;
        com.onesay.assistant.v2.s3.S3Runner r = com.onesay.assistant.v2.s3.S3Runner.get();
        for (JobStore.Run run : o.runs) {
            if (dry) {
                e.debugCompleteDry(run, System.currentTimeMillis());
                Log.i(TAG, "[EVENT] run#" + run.id + " 空跑 → " + run.state + "（debug：不调模型、不执行）");
            } else if (r != null) {
                r.execEvent(run);
            } else {
                Log.i(TAG, "[EVENT] 面板服务没起来，run#" + run.id + " 留在待执行，服务起来后跑");
            }
        }
        return o;
    }

    // ================================================================== debug 上机注入（只 debug 包；release 一律直接返回）

    static final String RELEASE_REFUSE = "只在 debug 包可用";

    /**
     * 注入一次「充电插上 / 拔下」（等同系统的 ACTION_POWER_CONNECTED / DISCONNECTED，非粘性）：走真实的状态去重 → 条件 → 防抖 → 每天上限 → Run。
     * dry = Run 直接记完成（没有 LLM key 时测防抖 / 上限用）。返回结果说明。
     */
    public static String debugCharging(Context ctx, boolean plugged, boolean dry) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return RELEASE_REFUSE;
        EventEngine.Outcome o = fire(ctx.getApplicationContext(), EventSignal.charging(plugged, System.currentTimeMillis()), dry,
                "（debug 注入" + (dry ? "，空跑" : "") + "）");
        return o.toString();
    }

    /** 只记基线（等同注册广播时收到的粘性广播），不触发。 */
    public static String debugBaseline(Context ctx, boolean plugged) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return RELEASE_REFUSE;
        engine(ctx).baseline(EventSignal.charging(plugged, System.currentTimeMillis()));
        Log.i(TAG, "[EVENT] 基线 CHARGING" + (plugged ? "+" : "-") + "（debug 注入，不触发）");
        return "baseline=" + plugged;
    }

    /** 临时把所有事件任务的防抖 / 每天上限改小（debounceSec / cap &lt; 0 = 恢复任务自己的值）。只在内存里，重启即失效。 */
    public static String debugGuards(Context ctx, long debounceSec, int cap) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return RELEASE_REFUSE;
        EventEngine e = engine(ctx);
        e.setDebugGuards(debounceSec < 0 ? -1L : debounceSec * 1000L, cap < 0 ? -1 : Math.max(1, cap));
        Log.i(TAG, "[EVENT] debug 覆盖：" + e.debugGuards());
        return e.debugGuards();
    }

    /** 模拟面板服务重启：注销广播 → 忘掉状态去重记忆 → 重新注册（粘性广播重新记基线；充电广播不是粘性的，不会触发）。 */
    public static String debugRestart(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return RELEASE_REFUSE;
        Context app = ctx.getApplicationContext();
        onServiceDestroy(app);
        engine(app).forgetStates();
        onServiceCreate(app);
        Log.i(TAG, "[EVENT] 模拟服务重启完成（debug）");
        return "restarted";
    }

    /** 清掉今日次数 / 上次触发时刻（id &lt;= 0 = 全部）。 */
    public static String debugResetCounters(Context ctx, long id) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return RELEASE_REFUSE;
        int n = engine(ctx).resetCounters(id);
        Log.i(TAG, "[EVENT] 清零 " + n + " 条任务的今日次数 / 防抖起点（debug）");
        return "reset " + n;
    }

    /** 状态：每条事件任务的条件 / 开关 / 今日次数 / 生效的防抖与上限、debug 覆盖、充电基线、最近的 ev: Run。 */
    public static String debugStatus(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return RELEASE_REFUSE;
        EventEngine e = engine(ctx);
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("覆盖=").append(e.debugGuards())
                .append(" 充电基线=").append(e.lastState(EventSignal.charging(true, now)));
        for (EventTask t : e.store().all()) {
            sb.append("\n  ev:").append(t.id).append(' ').append(t.describeTrigger())
                    .append(t.enabled ? "" : " [关]").append(t.paused ? " [暂停:" + t.pausedReason + "]" : "")
                    .append(" 今日=").append(t.firesToday).append('/').append(e.effectiveDailyCap(t)).append("(").append(t.dayKey).append(")")
                    .append(" 防抖=").append(e.effectiveDebounceMs(t) / 1000L).append("s")
                    .append(" 上次=").append(t.lastFireAt > 0 ? ((now - t.lastFireAt) / 1000L) + "s前" : "无")
                    .append(" 失败=").append(t.failures).append('/').append(t.maxFailures);
        }
        int shown = 0;
        java.util.List<JobStore.Run> runs = e.jobs().store().runs();
        for (int i = runs.size() - 1; i >= 0 && shown < 8; i--) {
            JobStore.Run r = runs.get(i);
            if (!EventEngine.isEventRun(r)) continue;
            sb.append("\n  run#").append(r.id).append(' ').append(r.owner).append(' ').append(r.state.label);
            shown++;
        }
        return sb.toString();
    }

    /** 建一条充电事件任务：照常过权限卡（不绕过授权），卡片确认后才入库。返回卡片说明。 */
    public static String debugAddCharging(Context ctx, String command, boolean onPlug) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return RELEASE_REFUSE;
        EventTask t = new EventTask();
        t.source = EventTask.Source.CHARGING;
        t.onConnect = onPlug;
        t.command = command == null || command.trim().length() == 0 ? "播报今天的待办" : command.trim();
        String bad = EventEngine.invalidReason(t);
        if (bad != null) return bad;
        EventEngine.Pending p = engine(ctx).prepare(t);
        com.onesay.assistant.v2.jobs.PermissionCards.show(ctx.getApplicationContext(),
                com.onesay.assistant.v2.jobs.PermissionCards.forNewEvent(p, System.currentTimeMillis()));
        return "已弹权限卡：" + t.describeTrigger() + "：" + t.command;
    }

    /** S3Runner 跑完一条事件 Run 后调：连续失败计数 / 自动暂停。 */
    public static void onRunDone(Context ctx, JobStore.Run run) {
        try {
            if (engine(ctx).recordOutcome(run)) {
                EventTask t = engine(ctx).store().get(com.onesay.assistant.v2.jobs.JobsEngine.parseIdPublic(run.owner));
                String msg = "「" + run.title + "」" + (t != null ? t.pausedReason : "连续失败，已自动暂停") + "，去控制台「事件任务」里恢复";
                com.onesay.assistant.v2.callback.CallbackHub.notify(ctx.getApplicationContext(), (int) (0x5E000000L + (run.id & 0xFFFFFF)), "事件任务已暂停", msg);
                Log.i(TAG, "[EVENT] " + run.owner + " 连续失败，自动暂停");
            }
        } catch (Throwable t) {
            Log.w(TAG, "[EVENT] 记录结果失败: " + t);
        }
    }
}
