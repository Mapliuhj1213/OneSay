package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.kernel.IslandHost;

/**
 * [brief] 晨间播报 v2 的简报状态上灵动岛（2026-10-22）。开关 bixby_brief.enabled（默认开）/ kill-switch brief.off，
 * 控制台「功能与权限 › 晨间简报上灵动岛」可关。接收端 {@link com.onesay.assistant.v2.brief.BriefStateReceiver}，
 * 解析 / 来源校验 / 上岛计划在 {@link com.onesay.assistant.v2.brief.BriefState}。只显示，不控制 v2。
 */
public final class BriefPlugin extends FeaturePlugin {

    public static final String ID = "brief";
    public static final FeatureSwitch SWITCH = new FeatureSwitch("bixby_brief", "enabled", true, "brief.off");

    @Override public String id() { return ID; }
    @Override public String title() { return "晨间简报上灵动岛"; }
    @Override public String logTag() { return "[BRIEF]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return SWITCH.prefs; }
    @Override public String[] killFiles() { return SWITCH.killFiles; }
    @Override public boolean defaultOn() { return SWITCH.def; }
    @Override public boolean enabled(Context ctx) { return SWITCH.on(ctx); }
    @Override public String[] dependsOn() { return new String[]{"island"}; }

    /** 2026-10-23（lane-a B-9）：内核比对到开关翻成关（含 kill-file）→ 撤下岛上的简报（撤下不受开关门控）。 */
    @Override public void onDisable(Context ctx) {
        com.onesay.assistant.v2.brief.BriefIsland.clear();
    }

    // ------------------------------------------------------------------ lane-a 第 2 项：4 条简报语音规则（L0）

    /** L0 排在最前：简报在播时「停 / 跳过」必须先归简报。 */
    @Override public int priority(com.onesay.assistant.v2.kernel.FeatureHook hook) {
        return hook == com.onesay.assistant.v2.kernel.FeatureHook.L0 || hook == com.onesay.assistant.v2.kernel.FeatureHook.CLAIMS ? 5 : 1000;
    }

    static com.onesay.assistant.v2.brief.BriefControl.Parsed parse(String raw) {
        long now = System.currentTimeMillis();
        return com.onesay.assistant.v2.brief.BriefControl.parse(raw,
                com.onesay.assistant.v2.brief.BriefIsland.isActive(now), com.onesay.assistant.v2.brief.BriefIsland.isPlayingNow(now));
    }

    /**
     * 再播一遍 / 跳过 / 详细说说 / 停 → 本地回一句；root 广播给 v2 在面板派发时做（{@link #dispatch}）。
     * r2/p0 P0-1：L0 只做识别 —— 分诊 / 核心短语重试 / 唤醒半句探针会对同一句话多次调用 L0，
     * 原先每调一次就往 v2 发一次广播（「跳过」可能连跳两条）。
     */
    @Override public com.onesay.assistant.v2.router.Invocation l0(Context ctx, String raw) {
        com.onesay.assistant.v2.brief.BriefControl.Parsed p = parse(raw);
        if (p == null) return null;
        org.json.JSONObject args = new org.json.JSONObject();
        try {
            args.put("text", com.onesay.assistant.v2.brief.BriefControl.ackText(p));
            args.put("query", raw == null ? "" : raw.trim());
            args.put("brief_cmd", p.toString());
            args.put("brief_effect", true);
        } catch (Exception ignored) {
        }
        return new com.onesay.assistant.v2.router.Invocation("ask.reply", args, 1.0, com.onesay.assistant.v2.router.Invocation.RouteSource.L0_RULE, raw);
    }

    /** 执行阶段（每个调用对象只做一次）：重新按当前简报状态解析，再 root 广播给 v2。返回 true = 做了。 */
    static boolean applyEffect(Context ctx, com.onesay.assistant.v2.router.Invocation inv) {
        if (inv == null || inv.args == null || !"ask.reply".equals(inv.sign) || !inv.args.optBoolean("brief_effect", false)) return false;
        if (inv.args.optBoolean("brief_done", false)) return false;
        try {
            inv.args.put("brief_done", true);
        } catch (Exception ignored) {
        }
        com.onesay.assistant.v2.brief.BriefControl.Parsed p = parse(inv.args.optString("query"));
        if (p == null) return false;   // 识别后简报已结束：不再发
        if (p.cmd == com.onesay.assistant.v2.brief.BriefControl.Cmd.DETAIL) {
            com.onesay.assistant.v2.brief.BriefIsland.markDetailRequested(System.currentTimeMillis());
        }
        com.onesay.assistant.v2.brief.BriefRemote.send(ctx, p.cmd, p.index);
        return true;
    }

    /** 面板派发：发广播后交回通用 ask.reply 呈现（不认领）。 */
    @Override public boolean dispatch(com.onesay.assistant.v2.ui.FloatingSheetService svc, com.onesay.assistant.v2.router.Invocation inv, String command) {
        applyEffect(svc, inv);
        return false;
    }

    @Override public String l0Log(com.onesay.assistant.v2.router.Invocation inv) {
        return "[BRIEF] 简报语音控制 → " + (inv.args == null ? "" : inv.args.optString("brief_cmd"));
    }

    @Override public boolean claims(Context ctx, String query) {
        return parse(query) != null;
    }

    @Override public String[] islandActivities() {
        return new String[]{"晨间简报：生成中 / 播放中（紧凑运行态）→ 已生成 / 播完了（3 s 消失）/ 失败（展开原因）"};
    }
    @Override public String[] islandActivityKinds() { return new String[]{IslandHost.KIND_BG}; }
    @Override public String consoleCard() { return "功能与权限一行开关"; }
    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (!ConsoleHost.PAGE_FEATURES.equals(page)) return;
        final android.app.Activity act = host.activity();
        final boolean killed = SWITCH.killed(act);
        ConsoleHost.Row r = host.switchRow(root, "晨间简报上灵动岛",
                killed ? ConsoleHost.KILLED : "「晨间播报 v2」生成、播放时在岛上显示进度",
                act.getSharedPreferences(SWITCH.prefs, Context.MODE_PRIVATE).getBoolean(SWITCH.key, SWITCH.def),
                new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        if (!on) com.onesay.assistant.v2.brief.BriefIsland.clear();   // 先拿掉（关掉后内核会丢弃本插件的岛操作）
                        SWITCH.set(act, on);
                    }
                });
        if (killed) r.setEnabled(false);
    }

    @Override public String debugStatus(Context ctx) { return "enabled=" + enabled(ctx) + " switch=" + SWITCH; }
}
