package com.onesay.assistant.v2.phongent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.onesay.assistant.plug.phongent.core.Approver;
import com.onesay.assistant.plug.phongent.core.HostInfo;
import com.onesay.assistant.plug.phongent.core.InputSink;
import com.onesay.assistant.plug.phongent.core.NotificationSource;
import com.onesay.assistant.plug.phongent.core.PlugConfig;
import com.onesay.assistant.plug.phongent.core.PlugLog;
import com.onesay.assistant.plug.phongent.core.Storage;
import com.onesay.assistant.plug.phongent.core.TaskExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * [phongent 适配层] 把 OneSay 接到插件的窄接口上（插件本体在 com.onesay.assistant.plug.phongent.core，不认识 OneSay）。
 * <ul>
 *   <li>{@link TaskExecutor} → OneSay 任务队列（JobQueue 的 q: Run）→ S3Runner 无人值守跑 AgentLoop，<b>照走 ExecutionGate</b>，
 *       红卡类（发消息 / 付款 / 删除…）在灵动岛上要本机确认；通知代回类任务 templateId 标 tainted，从第一步按不可信过闸；</li>
 *   <li>{@link Approver} → 高优先级通知（允许 / 拒绝按钮发给本 App 不导出的 {@link PhongentService}，不注册任何接收器）；</li>
 *   <li>{@link NotificationSource} → {@link PgNotices}（EventNotificationListener 里的一行钩子，只转白名单 App）；</li>
 *   <li>{@link InputSink} → root 读屏助手 FastScreen 的 tap / swipe / key。</li>
 * </ul>
 */
final class PgOneSayHost implements Storage, HostInfo, Approver, TaskExecutor, PlugConfig, PlugLog, InputSink {

    static final String TAG = "Phongent";
    static final String CH_APPROVE = "bixby_phongent_approve";
    static final int NID_PAIR = 9201;
    static final int NID_SCREEN = 9301;

    private final Context mCtx;
    private final SharedPreferences mStore;

    PgOneSayHost(Context ctx) {
        mCtx = ctx.getApplicationContext();
        mStore = mCtx.getSharedPreferences("bixby_phongent_store", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ Storage

    @Override public Map<String, String> all() {
        Map<String, String> m = new HashMap<String, String>();
        for (Map.Entry<String, ?> e : mStore.getAll().entrySet()) if (e.getValue() instanceof String) m.put(e.getKey(), (String) e.getValue());
        return m;
    }

    @Override public boolean put(String key, String value) {
        return mStore.edit().putString(key, value).commit();
    }

    @Override public boolean remove(String key) {
        return mStore.edit().remove(key).commit();
    }

    // ------------------------------------------------------------------ HostInfo

    @Override public String deviceName() {
        return PgSettings.deviceName(mCtx);
    }

    @Override public String rename(String name) {
        return PgSettings.rename(mCtx, name);
    }

    @Override public boolean localBusy() {
        try {
            return com.onesay.assistant.v2.phone.PhoneHooks.busy();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean notificationAccess() {
        try {
            String s = Settings.Secure.getString(mCtx.getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(mCtx.getPackageName() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean listenerConnected() {
        return PgNotices.connected;
    }

    // ------------------------------------------------------------------ PlugConfig

    @Override public boolean tailscaleOnly() {
        return PgSettings.get(mCtx, PgSettings.KEY_TS_ONLY);
    }

    @Override public boolean allowLoopback() {
        return false;   // 生产不放回环：本机其它 App 不能冒充主控
    }

    @Override public boolean relayNotifications() {
        return PgSettings.get(mCtx, PgSettings.KEY_RELAY);
    }

    @Override public boolean screenSharing() {
        return PgSettings.get(mCtx, PgSettings.KEY_SCREEN);
    }

    // ------------------------------------------------------------------ PlugLog

    @Override public void i(String msg) {
        Log.i(TAG, msg);
    }

    @Override public void w(String msg) {
        Log.w(TAG, msg);
    }

    // ------------------------------------------------------------------ TaskExecutor → JobQueue

    @Override public long submit(String text, boolean tainted) {
        com.onesay.assistant.v2.jobs.JobQueue q = com.onesay.assistant.v2.jobs.JobsHooks.queue(mCtx);
        if (q.waiting().size() + (q.active() == null ? 0 : 1) >= com.onesay.assistant.v2.jobs.JobQueue.MAX) return -1;
        // 与 JobQueue.enqueue 同口径，但 templateId 在入库前就定好（避免入库和标 tainted 之间被抽走先跑）
        com.onesay.assistant.v2.jobs.JobStore.Run r = new com.onesay.assistant.v2.jobs.JobStore.Run();
        String c = text.trim();
        String brief = c.length() > 24 ? c.substring(0, 24) : c;
        r.owner = com.onesay.assistant.v2.jobs.JobQueue.OWNER_PREFIX + "远程·" + brief;
        r.title = "远程：" + c;
        r.command = c;
        r.createdAt = System.currentTimeMillis();
        r.updatedAt = r.createdAt;
        r.maxAttempts = 1;
        if (tainted) r.templateId = com.onesay.assistant.v2.events.EventEngine.RUN_TPL_TAINTED;
        long id = com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx).store().addRun(r);
        Log.i(TAG, "[PG] 远程任务入队 run#" + id + (tainted ? "（tainted）" : ""));
        com.onesay.assistant.v2.jobs.JobsHooks.pumpQueue(mCtx);
        return id;
    }

    @Override public RunView view(long runId) {
        com.onesay.assistant.v2.jobs.JobStore.Run r = com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx).store().run(runId);
        if (r == null) return null;
        return new RunView(r.state.name(), r.step, r.result, r.error);
    }

    @Override public boolean cancel(long runId) {
        com.onesay.assistant.v2.jobs.JobStore.Run r = com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx).store().run(runId);
        if (r == null) return false;
        if (r.state == com.onesay.assistant.v2.jobs.JobMachine.State.PENDING || r.state == com.onesay.assistant.v2.jobs.JobMachine.State.RETRY_WAIT) {
            return com.onesay.assistant.v2.jobs.JobsHooks.queue(mCtx).cancel(runId, System.currentTimeMillis());
        }
        com.onesay.assistant.v2.s3.S3Runner s = com.onesay.assistant.v2.s3.S3Runner.get();
        if (s == null) return false;
        s.onBgStop("a" + runId);   // S3Runner 无人值守回调的取消位（含等确认中）
        return true;
    }

    // ------------------------------------------------------------------ Approver（通知 + 不导出服务）

    private NotificationManager nm() {
        return (NotificationManager) mCtx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override public boolean canPrompt() {
        NotificationManager nm = nm();
        return nm != null && (Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled());
    }

    private Notification.Builder builder() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm().createNotificationChannel(new NotificationChannel(CH_APPROVE, "多设备连接确认", NotificationManager.IMPORTANCE_HIGH));
            return new Notification.Builder(mCtx, CH_APPROVE);
        }
        @SuppressWarnings("deprecation") Notification.Builder b = new Notification.Builder(mCtx).setPriority(Notification.PRIORITY_HIGH);
        return b;
    }

    private PendingIntent action(String act, String id, int req) {
        Intent it = new Intent(mCtx, PhongentService.class).setAction(act).putExtra("id", id == null ? "" : id);
        return PendingIntent.getService(mCtx, req, it, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    @Override public void askPair(String requestId, String name) {
        String body = "允许后，" + name + " 可以给这台手机派任务、查看任务状态" + (relayNotifications() ? "和微信 / QQ 通知预览" : "")
                + "。发消息、付款等动作仍要在本机确认。只允许你自己的设备。";
        nm().notify(NID_PAIR, builder().setSmallIcon(mCtx.getApplicationInfo().icon)
                .setContentTitle(name + " 请求连接 OneSay").setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true).setDefaults(Notification.DEFAULT_ALL)
                .addAction(new Notification.Action.Builder(0, "拒绝", action(PhongentService.ACT_PAIR_DENY, requestId, 9202)).build())
                .addAction(new Notification.Action.Builder(0, "允许连接", action(PhongentService.ACT_PAIR_ALLOW, requestId, 9203)).build())
                .build());
    }

    @Override public void dismissPair(String requestId) {
        nm().cancel(NID_PAIR);
    }

    @SuppressWarnings("deprecation")
    @Override public void askScreen(String name) {
        String body = name + " 想查看这台手机的屏幕（约 4 帧/秒），允许后在没有任务时还可以点按、滑动、返回、回桌面。没人看 30 秒自动停，最长 10 分钟。";
        nm().notify(NID_SCREEN, builder().setSmallIcon(mCtx.getApplicationInfo().icon)
                .setContentTitle(name + " 请求查看屏幕").setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true).setDefaults(Notification.DEFAULT_ALL)
                .addAction(new Notification.Action.Builder(0, "拒绝", action(PhongentService.ACT_SCREEN_DENY, null, 9302)).build())
                .addAction(new Notification.Action.Builder(0, "允许本次", action(PhongentService.ACT_SCREEN_ALLOW, null, 9303)).build())
                .build());
    }

    @Override public void dismissScreen() {
        nm().cancel(NID_SCREEN);
    }

    // ------------------------------------------------------------------ InputSink → FastScreen（root 读屏助手）

    @Override public boolean tap(int x, int y) {
        return com.onesay.assistant.v2.fastscreen.FastScreen.get(mCtx).tap(x, y);
    }

    @Override public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        return com.onesay.assistant.v2.fastscreen.FastScreen.get(mCtx).swipe(x1, y1, x2, y2, ms);
    }

    @Override public boolean key(int code) {
        return com.onesay.assistant.v2.fastscreen.FastScreen.get(mCtx).key(code);
    }
}
