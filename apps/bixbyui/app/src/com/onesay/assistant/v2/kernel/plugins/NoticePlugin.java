package com.onesay.assistant.v2.kernel.plugins;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.IslandHost;
import com.onesay.assistant.v2.notice.NoticeHooks;
import com.onesay.assistant.v2.notice.NoticeSettings;

/**
 * [notice] 灵动岛消息提醒：定时任务 / 回调结果、日程、定时邮件发送前提醒，统一成 IslandNotice 排队上岛。
 * 开关 bixby_notice.enabled（默认开）/ kill-switch notice.off；关掉后回调中枢回到旧的「完成卡」，日程 / 定时邮件不提醒。
 * 控制台：灵动岛页 › 消息提醒（总开关、各来源开关、提前量、高优先级日程是否不受免打扰限制）。调试入口 --es notice …（只 debug）。
 */
public final class NoticePlugin extends FeaturePlugin {

    public static final String ID = NoticeHooks.OWNER;
    public static final String PAGE = "notice.settings";

    @Override public String id() { return ID; }
    @Override public String title() { return "灵动岛消息提醒"; }
    @Override public String logTag() { return "[NOTICE]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return NoticeSettings.PREFS; }
    @Override public String[] killFiles() { return NoticeSettings.SWITCH.killFiles; }
    @Override public boolean defaultOn() { return NoticeSettings.SWITCH.def; }
    @Override public boolean enabled(Context ctx) { return NoticeSettings.master(ctx); }
    @Override public String[] dependsOn() { return new String[]{"island", "callback"}; }

    @Override public String[] islandActivities() {
        return new String[]{"消息提醒队列：队首一条 +N；点按展开列出全部；左滑 / 点叉忽略；日程「加入」、定时邮件「立即查看 / 推迟 / 取消发送」"};
    }
    @Override public String[] islandActivityKinds() { return new String[]{IslandHost.KIND_NOTICE}; }
    @Override public String consoleCard() { return "灵动岛页一行 + 细项页"; }
    @Override public boolean consoleV2() { return true; }

    @Override public void onDisable(Context ctx) {
        NoticeHooks.island().cleanupNotices();
    }

    /** BUG-I3：kill-switch 撤掉 / 重新启用 → 立即 tick（关闭前在排、没过期的放回来）+ 所有来源重拉。 */
    @Override public void onEnable(Context ctx) {
        if (ctx == null || !NoticeHooks.started()) return;   // 内核装配时的首次启用：面板服务还没起来，由 onServiceCreate 恢复
        refresh(ctx);
    }

    @Override public String consoleSummary(Context ctx) {
        if (NoticeSettings.SWITCH.killed(ctx)) return "已被停用";
        return NoticeSettings.master(ctx) ? "已开启" : "已关闭";
    }

    @Override public void addConsoleEntries(final ConsoleHost host, String page, LinearLayout root) {
        final Activity act = host.activity();
        if (ConsoleHost.PAGE_ISLAND.equals(page)) {
            LinearLayout g = host.group(root, "消息提醒");
            ConsoleHost.Row r = host.linkRow(g, "消息提醒", NoticeSettings.SWITCH.killed(act) ? ConsoleHost.KILLED
                    : (NoticeSettings.master(act) ? "定时任务结果、日程、定时邮件、通话、验证码、麦克风 / 相机……" : "已关闭"), new Runnable() {
                @Override public void run() { host.openPage(PAGE, "消息提醒"); }
            });
            if (NoticeSettings.SWITCH.killed(act)) r.setEnabled(false);
            return;
        }
        if (!PAGE.equals(page)) return;
        final android.content.SharedPreferences sp = NoticeSettings.prefs(act);
        final boolean killed = NoticeSettings.SWITCH.killed(act);
        final ConsoleHost.Row[] subs = new ConsoleHost.Row[7];
        final java.util.List<ConsoleHost.Row> smartRows = new java.util.ArrayList<ConsoleHost.Row>();   //
        ConsoleHost.Row main = host.mainSwitch(root, "灵动岛消息提醒", sp.getBoolean(NoticeSettings.SWITCH.key, NoticeSettings.SWITCH.def),
                new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        NoticeSettings.SWITCH.set(act, on);
                        for (ConsoleHost.Row x : subs) if (x != null) x.setEnabled(on && !killed);
                        for (ConsoleHost.Row x : smartRows) if (x != null) x.setEnabled(on && !killed);
                        refresh(act);
                    }
                });

        LinearLayout src = host.group(root, "来源");
        subs[0] = host.switchRow(src, "定时任务结果", "回调中枢要上岛的结果排进提醒队列", NoticeSettings.source(act, NoticeSettings.K_SRC_CALLBACK),
                toggle(act, sp, NoticeSettings.K_SRC_CALLBACK));
        subs[1] = host.switchRow(src, "日程", "读 Google 日历（连接器没配置时静默不提醒）", NoticeSettings.source(act, NoticeSettings.K_SRC_CALENDAR),
                toggle(act, sp, NoticeSettings.K_SRC_CALENDAR));
        subs[2] = host.switchRow(src, "定时邮件", "定时发邮件的任务到点前提醒；发送仍只在确认卡上", NoticeSettings.source(act, NoticeSettings.K_SRC_MAIL),
                toggle(act, sp, NoticeSettings.K_SRC_MAIL));

        // ---- 灵动岛智能化：每个新来源独立开关 + 细项（同样受上面的总开关、下面的免打扰 / 每日上限 / 去重 / 全屏规则约束）----
        final java.util.List<ConsoleHost.Row> smart = smartRows;
        LinearLayout sm = host.group(root, "智能上岛");
        smart.add(host.switchRow(sm, "麦克风 / 相机使用中", "别的 App 在用时岛上显示是谁（橙点麦克风、绿点相机）；OneSay 自己录音不提示。识别使用方要 root",
                NoticeSettings.flag(act, NoticeSettings.K_SRC_PRIVACY), toggle(act, sp, NoticeSettings.K_SRC_PRIVACY)));
        smart.add(host.switchRow(sm, "通话", "来电（接听 / 挂断）、通话中（时长 + 静音 / 免提 / 挂断）、未接来电；只显示联系人名，陌生号码打码",
                NoticeSettings.flag(act, NoticeSettings.K_SRC_CALL), toggle(act, sp, NoticeSettings.K_SRC_CALL)));
        boolean nlOn = com.onesay.assistant.v2.notice.IslandSmartHooks.listenerEnabled(act);
        smart.add(host.switchRow(sm, "重要通知上岛", nlOn ? "验证码、通话邀请、闹钟 / 计时器、导航、白名单 App" : "需要在系统「通知使用权」里打开 OneSay",
                NoticeSettings.flag(act, NoticeSettings.K_SRC_NOTIFY), toggle(act, sp, NoticeSettings.K_SRC_NOTIFY)));
        smart.add(host.switchRow(sm, "充电 / 低电量 / 耳机", "插电、充满、电量低于 " + NoticeSettings.lowBattery(act) + "%、耳机连上时短暂显示",
                NoticeSettings.flag(act, NoticeSettings.K_SRC_SYSTEM), toggle(act, sp, NoticeSettings.K_SRC_SYSTEM)));
        smart.add(host.switchRow(sm, "外卖 / 打车进度（实验）", "读美团、饿了么、滴滴的通知抠出阶段和预计时间；读不懂就不显示",
                NoticeSettings.flag(act, NoticeSettings.K_SRC_ERRAND), toggle(act, sp, NoticeSettings.K_SRC_ERRAND)));

        LinearLayout nd = host.group(root, "重要通知细项");
        smart.add(host.switchRow(nd, "验证码", "一键复制，2 分钟后自动清剪贴板", NoticeSettings.flag(act, NoticeSettings.K_N_OTP), toggle(act, sp, NoticeSettings.K_N_OTP)));
        smart.add(host.switchRow(nd, "通话邀请", "微信 / QQ 等的语音、视频通话", NoticeSettings.flag(act, NoticeSettings.K_N_VOIP), toggle(act, sp, NoticeSettings.K_N_VOIP)));
        smart.add(host.switchRow(nd, "闹钟与计时器", "计时器倒计时在岛上走", NoticeSettings.flag(act, NoticeSettings.K_N_ALARM), toggle(act, sp, NoticeSettings.K_N_ALARM)));
        smart.add(host.switchRow(nd, "导航", "高德 / 百度 / 腾讯 / Google 地图的导航提示", NoticeSettings.flag(act, NoticeSettings.K_N_NAV), toggle(act, sp, NoticeSettings.K_N_NAV)));
        smart.add(host.switchRow(nd, "正在播放", "音乐 App 的播放通知（默认关）", NoticeSettings.flag(act, NoticeSettings.K_N_MUSIC), toggle(act, sp, NoticeSettings.K_N_MUSIC)));
        final ConsoleHost.Row[] wlRow = new ConsoleHost.Row[1];
        wlRow[0] = host.actionRow(nd, "白名单 App", NoticeSettings.whitelist(act).isEmpty() ? "没有" : NoticeSettings.whitelist(act).size() + " 个",
                "编辑", new Runnable() {
                    @Override public void run() { editWhitelist(act, sp, wlRow[0]); }
                });
        smart.add(wlRow[0]);

        LinearLayout pv = host.group(root, "敏感内容");
        smart.add(host.switchRow(pv, "显示验证码明文", "关着时只露最后 2 位，复制仍是完整的码", NoticeSettings.flag(act, NoticeSettings.K_REVEAL_OTP),
                toggle(act, sp, NoticeSettings.K_REVEAL_OTP)));
        smart.add(host.switchRow(pv, "显示通知正文", "关着时白名单 App 只显示「有新消息」，通话邀请不显示是谁", NoticeSettings.flag(act, NoticeSettings.K_REVEAL_CONTENT),
                toggle(act, sp, NoticeSettings.K_REVEAL_CONTENT)));
        smart.add(host.switchRow(pv, "显示来电号码", "关着时陌生号码打码（138****5678）", NoticeSettings.flag(act, NoticeSettings.K_REVEAL_NUMBER),
                toggle(act, sp, NoticeSettings.K_REVEAL_NUMBER)));

        LinearLayout lead = host.group(root, "提前量");
        subs[3] = host.actionRow(lead, "日程提前", NoticeSettings.calLead(act) + " 分钟", "修改", new Runnable() {
            @Override public void run() { editMinutes(act, sp, NoticeSettings.K_CAL_LEAD, 0, 120, subs[3]); }
        });
        subs[4] = host.actionRow(lead, "定时邮件提前", NoticeSettings.mailLead(act) + " 分钟", "修改", new Runnable() {
            @Override public void run() { editMinutes(act, sp, NoticeSettings.K_MAIL_LEAD, 1, 60, subs[4]); }
        });

        LinearLayout q = host.group(root, "免打扰");
        subs[5] = host.switchRow(q, "高优先级日程照常提醒", "有会议链接 / 有参会人 / 航班、高铁、面试这类日程，免打扰时段也提醒",
                NoticeSettings.quietHighCalendar(act), toggle(act, sp, NoticeSettings.K_QUIET_CAL_HIGH));
        android.content.SharedPreferences cb = act.getSharedPreferences(com.onesay.assistant.v2.callback.CallbackHub.SETTINGS_PREF, Context.MODE_PRIVATE);
        subs[6] = host.valueRow(q, "免打扰时段", "沿用「定时任务与主动播报」里的设置（时段里的提醒压到结束再出）",
                cb.getBoolean("quiet_on", true) ? cb.getString("quiet_start", "23:00") + "–" + cb.getString("quiet_end", "07:00") : "已关闭",
                ConsoleHost.TONE_MUTED);
        smart.add(host.switchRow(q, "麦克风 / 相机照常显示", "免打扰时段里有 App 在用也显示（安全提示）", NoticeSettings.flag(act, NoticeSettings.K_QUIET_PRIVACY),
                toggle(act, sp, NoticeSettings.K_QUIET_PRIVACY)));
        smart.add(host.switchRow(q, "通话照常显示", "免打扰时段里来电 / 通话中照常显示（未接来电仍压到结束）", NoticeSettings.flag(act, NoticeSettings.K_QUIET_CALL),
                toggle(act, sp, NoticeSettings.K_QUIET_CALL)));
        for (ConsoleHost.Row x : subs) x.setEnabled(main.isOn() && !killed);
        for (ConsoleHost.Row x : smart) if (x != null) x.setEnabled(main.isOn() && !killed);
        if (killed) {
            main.setEnabled(false);
            host.footer(root, ConsoleHost.KILLED);
        }
        host.footer(root, "岛上一次显示一条，其余显示「+N」；点按展开列出全部，左滑或点叉忽略。正在回答或岛上有任务时只排队，不打断。通话中不自动展开。");
    }

    static ConsoleHost.Toggle toggle(final Context ctx, final android.content.SharedPreferences sp, final String key) {
        return new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                sp.edit().putBoolean(key, on).apply();
                refresh(ctx);
            }
        };
    }

    /** 主开关 / 来源开关改动后重新拉取提醒源（后台线程）。设备能力页也调用这里，与原设置页一致。 */
    public static void refresh(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("notice-console", new Runnable() {
            @Override public void run() { NoticeHooks.repoll(app); }
        });
    }

    /** 白名单 App：逗号分隔的包名（只收合法包名）。 */
    static void editWhitelist(final Activity act, final android.content.SharedPreferences sp, final ConsoleHost.Row row) {
        final EditText et = new EditText(act);
        et.setHint("com.tencent.mm, com.tencent.mobileqq");
        et.setText(sp.getString(NoticeSettings.K_N_WHITELIST, ""));
        new AlertDialog.Builder(act).setTitle("这些 App 的通知上岛（包名，逗号分隔）").setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        java.util.Set<String> s = NoticeSettings.parseWhitelist(et.getText().toString());
                        StringBuilder b = new StringBuilder();
                        for (String p : s) b.append(b.length() > 0 ? "," : "").append(p);
                        sp.edit().putString(NoticeSettings.K_N_WHITELIST, b.toString()).apply();
                        row.setSub(s.isEmpty() ? "没有" : s.size() + " 个");
                    }
                }).setNegativeButton("取消", null).show();
    }

    static void editMinutes(final Activity act, final android.content.SharedPreferences sp, final String key, final int lo, final int hi,
                            final ConsoleHost.Row row) {
        final EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint(lo + "–" + hi);
        new AlertDialog.Builder(act).setTitle("提前多少分钟").setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            int v = Integer.parseInt(et.getText().toString().trim());
                            if (v < lo || v > hi) return;
                            sp.edit().putInt(key, v).apply();
                            row.setSub(v + " 分钟");
                            refresh(act);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }).setNegativeButton("取消", null).show();
    }

    @Override public String debugStatus(Context ctx) { return NoticeSettings.dump(ctx); }

    /** debug 包调试 / 上机注入入口 --es notice …（见 test/NoticeTestDriver）。 */
    @Override public String debugExtra() { return "notice"; }
}
