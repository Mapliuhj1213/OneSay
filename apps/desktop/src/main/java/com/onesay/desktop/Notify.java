package com.onesay.desktop;

import java.awt.TrayIcon;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Windows 通知：所有提醒走这一条通道，右下角弹出并记进「通知」页。
 * 类别：reminder 提醒（计划/触发器回调）、auto 自动任务回调、rain 降水、app 手机转来的提醒、task 任务完成、pc 本机（测试通知等）。
 * 降水电脑不自己查，只弹手机或计划转来的提醒。
 *
 * <p>[overnight-1119] 用户两次要求：<b>审批不进通知</b>。通知只留提示类（任务完成、触发器回调结果、自动任务结果）。
 * 确认卡不弹 Windows 通知（不进通知中心），只在 OneSay 窗口 / 任务卡里点，次数记在「设置 › 权限审批记录」；
 * 这里再加一道兜底：标题或正文像审批 / 确认的，{@link #post} 一律不收（不存、不弹）。
 */
public final class Notify {
    public static final Set<String> KINDS = Set.of("reminder", "auto", "rain", "app", "task", "pc");
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
            case "task": return "任务完成";
            default: return Flavor.name();
        }
    }

    public Map<String, Object> post(String kind, String title, String text, String from) {
        if (!"task".equals(kind) && PermStats.looksLikeApproval(title, text)) { log.accept("通知：审批类不进通知（" + clip(title == null ? "" : title, 30) + "）"); return null; }
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
