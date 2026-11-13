package com.onesay.assistant.v2.events;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 通知触发（lane-a 第 7 项）。系统「通知使用权」里要手动打开（或 root：{@code cmd notification allow_listener} 上机清单）。
 *
 * <ul>
 *   <li>先按包名过滤：不在任何事件任务白名单里的 App，<b>连标题正文都不读</b>；</li>
 *   <li>跳过自己、常驻（ongoing）通知、分组摘要；</li>
 *   <li>内容是污染数据：不打日志，只交给 {@link EventHooks#onNotification} → {@link EventEngine}（关键词匹配 + 隔离块）。</li>
 * </ul>
 */
public class EventNotificationListener extends NotificationListenerService {

    private static final String TAG = "EventsNL";

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        // 灵动岛智能化：重要通知上岛（自己的开关 / 筛选 / 打码在 IslandSmartHooks 里；异常不影响下面的事件任务）
        com.onesay.assistant.v2.notice.IslandSmartHooks.onNotificationPosted(this, sbn);
        // 2026-11-02 phongent：执行端没运行时第一行就返回；只转白名单 App（微信 / QQ）的预览给已授权主控
        com.onesay.assistant.v2.phongent.PgNotices.onPosted(this, sbn);
        try {
            if (sbn == null) return;
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) return;
            if (!EventHooks.wantsPackage(this, pkg)) return;
            Notification n = sbn.getNotification();
            if (n == null || sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle x = n.extras;
            if (x == null) return;
            CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) text = x.getCharSequence(Notification.EXTRA_TEXT);
            String label = pkg;
            try {
                label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
            } catch (Throwable ignored) {
            }
            EventHooks.onNotification(this, pkg, label, title == null ? "" : title.toString(), text == null ? "" : text.toString());
        } catch (Throwable t) {
            Log.w(TAG, "[EVENT-NL] 处理通知异常: " + t.getClass().getSimpleName());
        }
    }

    /** ★ int/1102 审计「清除所有通知」：已连上的监听实例（没授权通知使用权 / 未连上 = null）。 */
    private static volatile EventNotificationListener sConnected;

    /** 清掉所有可清除的通知（系统语义同下拉栏「全部清除」：常驻通知不动）。@return false = 通知使用权没开 / 没连上 */
    public static boolean clearAll() {
        EventNotificationListener l = sConnected;
        if (l == null) return false;
        try {
            l.cancelAllNotifications();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[EVENT-NL] 清除通知失败: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** ★ compat-vyrse：当前通知快照（只在 VYRSE 兼容开着、电脑要求读通知时调）。没授权 / 没连上 = null。 */
    public static android.service.notification.StatusBarNotification[] activeSnapshot() {
        EventNotificationListener l = sConnected;
        if (l == null) return null;
        try {
            return l.getActiveNotifications();
        } catch (Throwable t) {
            Log.w(TAG, "[EVENT-NL] 读当前通知失败: " + t.getClass().getSimpleName());
            return null;
        }
    }

    @Override public void onListenerConnected() {
        sConnected = this;
        com.onesay.assistant.v2.phongent.PgNotices.connected = true;
    }

    @Override public void onListenerDisconnected() {
        if (sConnected == this) sConnected = null;
        com.onesay.assistant.v2.phongent.PgNotices.connected = false;
    }

    /**：常驻通知（导航 / 外卖 / 计时器……）被移除时撤掉岛上对应那条。事件任务不关心移除。 */
    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        com.onesay.assistant.v2.notice.IslandSmartHooks.onNotificationRemoved(this, sbn);
    }
}
