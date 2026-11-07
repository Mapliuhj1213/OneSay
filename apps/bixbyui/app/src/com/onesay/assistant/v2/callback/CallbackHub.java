package com.onesay.assistant.v2.callback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;
import com.onesay.assistant.v2.jobs.JobsHooks;

import java.util.Set;
import java.util.TimeZone;

/**
 * 回调中枢的 Android 一侧（lane-a 第 3 项）：把 outbox 里到点的条目按 {@link DeliveryPolicy} 送到 灵动岛 / TTS / 通知。
 * outbox 先写后送、送完标记（幂等键防重复）；过期的由 {@link JobsEngine#dueOutbox} 标成 EXPIRED，不播。
 */
public final class CallbackHub {

    private CallbackHub() {}

    private static final String TAG = "CallbackHub";
    public static final String NOTIF_CHANNEL = "bixby_callback";
    public static final String SETTINGS_PREF = "bixby_callback";

    public static com.onesay.assistant.v2.kernel.IslandHost.Channel island() {
        return com.onesay.assistant.v2.kernel.FeatureKernel.get().island(com.onesay.assistant.v2.kernel.plugins.CallbackPlugin.ID);
    }

    /** 送达时刻的环境（通话 / 勿扰 / 媒体 / 免打扰配置 / 岛）。 */
    public static DeliveryPolicy.Env env(Context ctx) {
        DeliveryPolicy.Env e = new DeliveryPolicy.Env();
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int m = am.getMode();
                e.inCall = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE;
                e.mediaPlaying = am.isMusicActive();
            }
        } catch (Throwable ignored) {
        }
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) e.inCall = true;
        } catch (Throwable ignored) {
        }
        try {
            e.dnd = Settings.Global.getInt(ctx.getContentResolver(), "zen_mode", 0) != 0;
        } catch (Throwable ignored) {
        }
        android.content.SharedPreferences sp = ctx.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE);
        e.quietEnabled = sp.getBoolean("quiet_on", true);
        e.quietStartMin = DeliveryPolicy.parseHm(sp.getString("quiet_start", "23:00"), DeliveryPolicy.QUIET_START_DEFAULT);
        e.quietEndMin = DeliveryPolicy.parseHm(sp.getString("quiet_end", "07:00"), DeliveryPolicy.QUIET_END_DEFAULT);
        com.onesay.assistant.v2.kernel.IslandHost.Channel ch = island();
        e.islandReady = ch.ready() && ch.islandMode();
        return e;
    }

    /**
     * 送出到点的 outbox（任意线程；串行）。第 5 项：
     * <ul>
     *   <li>worth_telling 判为不值得 → SUPPRESSED（只留历史）；</li>
     *   <li>不急的（LOW，或 NORMAL 但此刻通话 / 媒体在播）推迟到下一个送达窗口（只推一次；模板播报不推，它有自己的送达时刻）；</li>
     *   <li>同一轮到点的多条合并成一条送达。</li>
     * </ul>
     * 返回送出的 outbox 条数。
     */
    public static synchronized int deliverDue(Context ctx) {
        Context app = ctx.getApplicationContext();
        JobsEngine eng = JobsHooks.engine(app);
        long now = System.currentTimeMillis();
        DeliveryPolicy.Env env = env(app);
        java.util.List<JobStore.Outbox> ready = new java.util.ArrayList<JobStore.Outbox>();
        for (JobStore.Outbox o : eng.dueOutbox(now)) {
            if (o.worth == 0) {
                o.status = JobStore.Outbox.SUPPRESSED;
                eng.store().updateOutbox(o);
                continue;
            }
            if (!o.digestKey.startsWith("job:") && !DEFERRED.equals(o.deliveredVia) && DigestWindow.shouldDefer(DigestWindow.urgency(o), env)) {
                o.deliverAfter = DigestWindow.nextWindow(now, DigestWindow.DEFAULT_WINDOWS, TimeZone.getDefault());
                o.deliveredVia = DEFERRED;
                if (o.expireAt > 0 && o.expireAt < o.deliverAfter) o.expireAt = o.deliverAfter + JobsEngine.STALE_MS;
                eng.store().updateOutbox(o);
                Log.i(TAG, "[CB] outbox#" + o.id + " 不急 → 推到送达窗口 " + new java.util.Date(o.deliverAfter));
                continue;
            }
            ready.add(o);
        }
        if (ready.isEmpty()) return 0;
        DigestWindow.Merged m = DigestWindow.merge(ready);
        String via = deliver(app, "cb." + ready.get(0).id, m.title, m.text, m.urgency, m.wantSpeak, env, now);
        if (ready.size() > 1) via = "merged " + via;
        for (JobStore.Outbox o : ready) eng.delivered(o, via, now);
        com.onesay.assistant.v2.s3.S3Scheduler.reschedule(app, "cb-deferred");
        return ready.size();
    }

    static final String DEFERRED = "deferred";

    static String deliver(final Context app, final String islandId, final String title0, final String text, final DeliveryPolicy.Urgency u,
                          final boolean wantSpeak, final DeliveryPolicy.Env env, final long now) {
        // ★ mika-plug（mika.outbox，照 Mika Outbox）：先记账再发；同一条（同 id 同内容）已经发过 / 发送结果不明 → 不再发，免得用户收到两份
        if (com.onesay.assistant.v2.mikaplug.MikaPlugs.on(app, com.onesay.assistant.v2.mikaplug.MikaPlugs.OUTBOX)) {
            final String[] via = {""};
            try {
                com.onesay.assistant.plug.outbox.core.Outbox.Record r = com.onesay.assistant.v2.mikaplug.MikaOutbox.get(app).deliver(new com.onesay.assistant.plug.outbox.core.Outbox.Channel() {
                    @Override public String id() {
                        return "callback";
                    }

                    @Override public boolean alive(String peer) {
                        return true;
                    }

                    @Override public com.onesay.assistant.plug.outbox.core.Outbox.Sent send(String peer, String id, String content) {
                        via[0] = deliverNow(app, islandId, title0, text, u, wantSpeak, env, now);
                        return com.onesay.assistant.plug.outbox.core.Outbox.Sent.DELIVERED;
                    }
                }, "user", islandId, (title0 == null ? "" : title0) + "\n" + (text == null ? "" : text));
                if (via[0].length() == 0) Log.i(TAG, "[CB] [MIKA-PLUG] " + islandId + " 已送达过 / 结果不明（" + r.state + "）→ 不重发");
                return via[0].length() > 0 ? via[0] : "outbox-" + r.state.name().toLowerCase();
            } catch (IllegalArgumentException sameIdNewContent) {
                Log.i(TAG, "[CB] [MIKA-PLUG] " + islandId + " 同 id 换了内容 → 按新消息发");
            } catch (Throwable t) {
                Log.w(TAG, "[CB] [MIKA-PLUG] outbox 异常，直接发: " + t);
            }
        }
        return deliverNow(app, islandId, title0, text, u, wantSpeak, env, now);
    }

    static String deliverNow(Context app, String islandId, String title0, String text, DeliveryPolicy.Urgency u, boolean wantSpeak,
                             DeliveryPolicy.Env env, long now) {
        Set<DeliveryPolicy.Channel> ch = DeliveryPolicy.channels(u, wantSpeak, env, DeliveryPolicy.minuteOfDay(now, TimeZone.getDefault()));
        StringBuilder via = new StringBuilder();
        String title = title0 == null || title0.length() == 0 ? "定时任务" : title0;
        if (ch.contains(DeliveryPolicy.Channel.ISLAND)) {
            // ★：岛渠道统一走灵动岛消息提醒（排队 / 去重 / 过期 / 免打扰 / 每日上限）。
            // r2/fixes 7：提醒总开关或「回调」来源关着 = 真的不上岛（只记回调历史），不再退回旧的岛完成卡绕开去重 / 免打扰 / 每日上限
            if (com.onesay.assistant.v2.notice.NoticeHooks.postCallback(app, islandId, title, shortText(text, 120), u)) {
                via.append("notice ");
            } else {
                via.append("island-off ");
            }
        }
        if (ch.contains(DeliveryPolicy.Channel.TTS)) {
            com.onesay.assistant.v2.ui.TtsSpeaker.get(app).speakProactive(text, 400);
            via.append("tts ");
        }
        if (ch.contains(DeliveryPolicy.Channel.NOTIFY)) {
            notify(app, 0x5C000000 | (islandId.hashCode() & 0xFFFFFF), title, text);
            via.append("notify");
        }
        Log.i(TAG, "[CB] " + islandId + " → " + via.toString().trim() + "（" + u + (wantSpeak ? " 要播" : "") + "，通话=" + env.inCall
                + " 勿扰=" + env.dnd + " 媒体=" + env.mediaPlaying + "）");
        return via.toString().trim();
    }

    static String shortText(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static void notify(Context app, int id, String title, String text) {
        notify(app, id, title, text, null);
    }

    /** 带点击动作的通知（第 5 项：「邮件草稿待确认」点开 = 确认卡）。 */
    public static void notify(Context app, int id, String title, String text, android.app.PendingIntent onTap) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(NOTIF_CHANNEL, "定时任务与主动播报", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(app, NOTIF_CHANNEL);
            } else {
                b = new Notification.Builder(app);
            }
            b.setSmallIcon(app.getApplicationInfo().icon).setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true);
            if (onTap != null) b.setContentIntent(onTap);
            nm.notify(id, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "[CB] 通知失败: " + t);
        }
    }
}
