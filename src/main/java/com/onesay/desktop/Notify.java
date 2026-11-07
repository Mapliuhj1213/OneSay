package com.onesay.desktop;

import java.awt.TrayIcon;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Windows 通知：所有提醒走这一条通道，右下角弹出并记进「通知」页。
 * 类别：reminder 提醒（计划/触发器）、auto 自动任务回调、rain 降水、app 手机 App 通知、pc 本机。
 * 降水电脑不自己查，只弹手机或计划转来的提醒。
 */
public final class Notify {
    public static final Set<String> KINDS = Set.of("reminder", "auto", "rain", "app", "pc");
    private final Store store;
    private final Consumer<String> log;
    private volatile TrayIcon tray;

    public Notify(Store store, Consumer<String> log) { this.store = store; this.log = log; }

    public void attach(TrayIcon icon) { this.tray = icon; }

    public static String label(String kind) {
        switch (kind) {
            case "reminder": return "提醒";
            case "auto": return "自动任务";
            case "rain": return "降水";
            case "app": return "手机通知";
            default: return "OneSay";
        }
    }

    public Map<String, Object> post(String kind, String title, String text, String from) {
        if (!KINDS.contains(kind)) kind = "app";
        title = clip(title == null || title.isBlank() ? label(kind) : title, 80);
        text = clip(text == null ? "" : text, 500);
        Map<String, Object> n = new TreeMap<>();
        n.put("kind", kind);
        n.put("title", title);
        n.put("text", text);
        n.put("from", from == null ? "" : from);
        n.put("at", System.currentTimeMillis());
        n.put("read", false);
        Map<String, Object> saved = store.add("notes", n, Store.MAX_NOTES);
        TrayIcon t = tray;
        if (t != null) {
            TrayIcon.MessageType mt = kind.equals("rain") || kind.equals("reminder") ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO;
            try { t.displayMessage(title, text.isEmpty() ? label(kind) : text, mt); } catch (Exception ignored) { }
        }
        log.accept("通知［" + label(kind) + "］" + title);
        return saved;
    }

    static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }
}
