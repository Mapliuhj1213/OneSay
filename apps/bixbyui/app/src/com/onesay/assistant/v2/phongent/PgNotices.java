package com.onesay.assistant.v2.phongent;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import com.onesay.assistant.plug.phongent.core.NotificationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * [phongent 适配层] {@link NotificationSource}：OneSay 已有的通知监听（EventNotificationListener）里加一行
 * {@link #onPosted}。插件没 attach（总开关关 / 服务没起）时第一行就返回，连包名之外的字段都不读。
 * 字段提取（MessagingStyle 每条 / TEXT_LINES / BIG_TEXT / TEXT）口径照 phongent MessageListener。
 */
public final class PgNotices implements NotificationSource {

    static volatile Sink sSink;
    /** 通知监听连着没有（EventNotificationListener.onListenerConnected / Disconnected 置位）。 */
    public static volatile boolean connected;

    @Override public void attach(Sink sink) {
        sSink = sink;
    }

    @Override public void detach() {
        sSink = null;
    }

    public static void onPosted(Context ctx, StatusBarNotification sbn) {
        Sink sink = sSink;
        if (sink == null || sbn == null) return;
        try {
            String pkg = sbn.getPackageName();
            if (!PgSettings.relayApp(pkg)) return;   // 白名单外：不读标题正文
            if (!PgSettings.get(ctx, PgSettings.KEY_RELAY)) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle x = n.extras;
            if (x == null) return;
            CharSequence t = x.getCharSequence(Notification.EXTRA_TITLE);
            String title = t == null ? "" : t.toString();
            List<Object[]> entries = new ArrayList<Object[]>();
            Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null) {
                for (Parcelable p : msgs) {
                    if (!(p instanceof Bundle)) continue;
                    CharSequence text = ((Bundle) p).getCharSequence("text");
                    if (text == null || text.toString().trim().length() == 0) continue;
                    entries.add(new Object[]{text.toString(), ((Bundle) p).getLong("time", sbn.getPostTime())});
                }
            }
            if (entries.isEmpty()) {
                CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                String text = null;
                if (lines != null && lines.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (CharSequence l : lines) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(l);
                    }
                    text = sb.toString();
                }
                if (text == null || text.trim().length() == 0) {
                    CharSequence b = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
                    if (b == null) b = x.getCharSequence(Notification.EXTRA_TEXT);
                    text = b == null ? "" : b.toString();
                }
                if (title.length() == 0 && text.length() == 0) return;
                entries.add(new Object[]{text, sbn.getPostTime()});
            }
            String app = "com.tencent.mm".equals(pkg) ? "微信" : "com.tencent.mobileqq".equals(pkg) ? "QQ" : pkg;
            final PendingIntent pi = n.contentIntent;
            Opener opener = pi == null ? null : new Opener() {
                @Override public void open() throws Exception {
                    pi.send();
                }
            };
            String shortcut = Build.VERSION.SDK_INT >= 26 ? n.getShortcutId() : null;
            sink.post(new Notice(sbn.getKey(), pkg, app, title, shortcut, entries, opener));
        } catch (Throwable ignored) {
            // 第三方原文：不进日志
        }
    }
}
