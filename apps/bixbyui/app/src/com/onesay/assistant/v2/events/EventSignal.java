package com.onesay.assistant.v2.events;

/**
 * 一次系统事件（Android 一侧 {@link EventHooks} / {@link EventNotificationListener} 产生，交给 {@link EventEngine#onSignal}）。纯 Java。
 *
 * <p>通知的 {@link #title} / {@link #text} / {@link #appLabel} 是<b>污染数据</b>：只经 {@link NotificationQuarantine} 包进隔离块后作为数据交给模型，
 * 不进日志、不进任务标题、不参与授权。
 */
public final class EventSignal {

    public final EventTask.Source source;
    /** 连上 / 插上 / 收到 = true；断开 / 拔掉 = false。 */
    public final boolean on;
    /** Wi‑Fi SSID / 蓝牙设备名 / 通知的包名；充电为空。 */
    public final String key;
    /** 蓝牙 MAC（其余为空）。 */
    public final String key2;
    public final String appLabel;
    public final String title;
    public final String text;
    public final long at;

    private EventSignal(EventTask.Source source, boolean on, String key, String key2, String appLabel, String title, String text, long at) {
        this.source = source;
        this.on = on;
        this.key = key == null ? "" : key;
        this.key2 = key2 == null ? "" : key2;
        this.appLabel = appLabel == null ? "" : appLabel;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.at = at;
    }

    public static EventSignal charging(boolean plugged, long at) {
        return new EventSignal(EventTask.Source.CHARGING, plugged, "", "", "", "", "", at);
    }

    public static EventSignal wifi(boolean connected, String ssid, long at) {
        return new EventSignal(EventTask.Source.WIFI, connected, normalizeSsid(ssid), "", "", "", "", at);
    }

    public static EventSignal bluetooth(boolean connected, String name, String mac, long at) {
        return new EventSignal(EventTask.Source.BLUETOOTH, connected, name, mac, "", "", "", at);
    }

    public static EventSignal notification(String pkg, String appLabel, String title, String text, long at) {
        return new EventSignal(EventTask.Source.NOTIFICATION, true, pkg, "", appLabel, title, text, at);
    }

    /** WifiInfo.getSSID() 带引号；读不到时是 {@code <unknown ssid>}（Android 8.1 没定位权限 / 定位关着）。 */
    public static String normalizeSsid(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        if ("<unknown ssid>".equalsIgnoreCase(v) || "0x".equals(v)) return "";
        return v;
    }

    /** 不带通知内容的描述（可进日志）。 */
    @Override public String toString() {
        return source + (on ? "+" : "-") + (source == EventTask.Source.NOTIFICATION ? "[" + key + "]" : key.length() > 0 ? "[" + key + "]" : "");
    }
}
