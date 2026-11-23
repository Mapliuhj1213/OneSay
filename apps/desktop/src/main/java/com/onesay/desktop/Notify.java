package com.onesay.desktop;

import java.awt.TrayIcon;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Windows 通知：所有提醒走这一条通道，右下角弹出并记进「通知」页。
 * 类别：reminder 提醒（计划/触发器回调）、auto 自动任务回调、rain 降水、app 手机转来的提醒、task 任务结果（按结束状态显示：任务完成 / 需要你操作 / 没做成…）、pc 本机（测试通知等）。
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
            case "task": return "任务结果"; // [preview-1120] 以前写死「任务完成」，需要你操作 / 没做成也挂着「任务完成」
            default: return Flavor.name();
        }
    }

    /** [preview-1120] 任务通知按结束状态给标签：完成 / 有警告 / 没做成 / 需要你操作 / 已停止……；别的类别同 {@link #label(String)}。 */
    public static String label(String kind, String state) {
        if (!"task".equals(kind) || state == null || state.isBlank()) return label(kind);
        switch (state) {
            case Outcomes.DONE: return "任务完成";
            case Outcomes.WARN: return "有警告";
            case Outcomes.FAILED: return "没做成";
            case Outcomes.NEEDS_USER: return "需要你操作";
            default: return Outcomes.zh(state);
        }
    }

    public Map<String, Object> post(String kind, String title, String text, String from) { return post(kind, title, text, from, null); }

    /** state：任务通知的结束状态（{@link Outcomes} 的 done / warn / failed / needs_user …），通知页按它显示标签。 */
    public Map<String, Object> post(String kind, String title, String text, String from, String state) {
        if (!"task".equals(kind) && PermStats.looksLikeApproval(title, text)) { log.accept("通知：审批类不进通知（" + clip(title == null ? "" : title, 30) + "）"); return null; }
        if (!KINDS.contains(kind)) kind = "app";
        title = clip(title == null || title.isBlank() ? label(kind) : title, 80);
        text = clip(text == null ? "" : text, 500);
        Map<String, Object> n = new TreeMap<>();
        n.put("kind", kind);
        n.put("title", title);
        n.put("text", text);
        n.put("from", from == null ? "" : from);
        if ("task".equals(kind) && state != null && !state.isBlank()) n.put("state", state);
        n.put("at", System.currentTimeMillis());
        n.put("read", false);
        Map<String, Object> saved = store.add("notes", n, Store.MAX_NOTES);
        TrayIcon t = tray;
        if (t != null) {
            TrayIcon.MessageType mt = kind.equals("rain") || kind.equals("reminder") ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO;
            try { t.displayMessage(title, text.isEmpty() ? label(kind, state) : text, mt); } catch (Exception ignored) { }
        }
        log.accept("通知［" + label(kind, state) + "］" + title);
        return saved;
    }

    static String clip(String s, int n) { return TextClip.clip(s, n); } // [fix-1123-misc] 按字符截，不切半个字
}
