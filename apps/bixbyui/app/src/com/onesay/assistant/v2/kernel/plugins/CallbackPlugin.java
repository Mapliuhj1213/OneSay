package com.onesay.assistant.v2.kernel.plugins;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.callback.CallbackHub;
import com.onesay.assistant.v2.callback.DeliveryPolicy;
import com.onesay.assistant.v2.jobs.DailyDigest;
import com.onesay.assistant.v2.jobs.JobMachine;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;
import com.onesay.assistant.v2.jobs.JobsHooks;
import com.onesay.assistant.v2.jobs.PermissionCards;
import com.onesay.assistant.v2.jobs.ReliabilityCheck;
import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.kernel.IslandHost;

import java.util.List;

/**
 * [callback] 回调中枢（lane-a 第 3 项）：定时任务结果按 灵动岛 / TTS / 通知 送达（outbox 先写后送），通话 / 勿扰 / 免打扰时段不出声；
 * 内置「每日播报」模板；控制台「定时任务与主动播报」页（免打扰时段、定时可靠性自检卡、模板、任务列表 / 重新授权）。
 * 开关 bixby_callback.enabled（默认开）/ kill-switch callback.off；关掉时 S3 结果回到旧的「一条通知」。
 */
public final class CallbackPlugin extends FeaturePlugin {

    public static final String ID = "callback";
    public static final String PAGE = "callback.jobs";
    public static final String PAGE_HISTORY = "callback.history";
    public static final FeatureSwitch SWITCH = new FeatureSwitch(CallbackHub.SETTINGS_PREF, "enabled", true, "callback.off");

    @Override public String id() { return ID; }
    @Override public String title() { return "定时任务与主动播报"; }
    @Override public String logTag() { return "[CB]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return SWITCH.prefs; }
    @Override public String[] killFiles() { return SWITCH.killFiles; }
    @Override public boolean defaultOn() { return SWITCH.def; }
    @Override public boolean enabled(Context ctx) { return SWITCH.on(ctx); }
    @Override public String[] dependsOn() { return new String[]{"s3", "island"}; }

    /** 2026-10-23 A+B 合并：启动时注册 B 线邮件适配器（MailDraftPort / MailReadPort）与监控桥（WatchPorts）。 */
    @Override public void onInit(Context ctx) {
        com.onesay.assistant.v2.conn.port.LaneBMailPorts.ensure(ctx);
        com.onesay.assistant.v2.jobs.WatchBridgeHooks.install(ctx);
    }

    /** 发送类工具的拦截要排在连接器（500，补 content_hash）前面：先改成起草 + 确认卡。 */
    @Override public int priority(com.onesay.assistant.v2.kernel.FeatureHook hook) {
        if (hook == com.onesay.assistant.v2.kernel.FeatureHook.AGENT_PRECHECK) return 400;
        return super.priority(hook);
    }

    @Override public String[] islandActivities() {
        return new String[]{"定时任务结果 / 每日播报（完成卡）", "任务等确认：越权调用被拦下、需要重新授权（失败卡）"};
    }
    @Override public String[] islandActivityKinds() { return new String[]{IslandHost.KIND_BG}; }
    @Override public String consoleCard() { return "功能与权限一行 + 细项页"; }
    @Override public boolean consoleV2() { return true; }

    // ------------------------------------------------------------------ 第 4 项：任务队列口令（L0）

    /**
     * r2/p0 P0-1（M5-1）：L0 <b>只做识别</b>，不起草、不入队、不起线程。
     *
     * <p>L0 在同一句话里会被调用多次：分诊 {@code triage → safeL0}、礼貌框架剥离后的核心短语重试 {@code safeL0(d.core)}、
     * 唤醒热路径的离线探针 {@code canHandle / isCommitWorthy / splitWakeCommand}（拿半句转写试探），以及队列 / 定时任务
     * 在后台跑 {@code runAgentLoop → triage} 时。原先识别到「给 X 发邮件…」就在这里 {@code OutboundMailHooks.begin}，
     * 识别到「排队 …」就在这里 {@code enqueue} + 起 queue-pump —— 会重复起草、半句入队、后台跑队列项时按「voice」来源再起草一份。
     *
     * <p>现在这里只返回带 {@link #EFFECT} 标记的 {@code ask.reply}；真正的起草 / 入队在面板派发时（{@link #dispatch}，
     * 每条最终指令只派发一次）由 {@link #applyEffect} 做，回复文字也在那时按实际结果填好。
     */
    @Override public com.onesay.assistant.v2.router.Invocation l0(Context ctx, String raw) {
        return recognize(ctx, raw);
    }

    /** 参数里的副作用标记：mail = 起草邮件；queue = 队列口令。只有 {@link #applyEffect} 读它。 */
    public static final String EFFECT = "cb_effect";
    static final String DONE = "cb_done";

    /** 纯识别（可在任意线程、任意次数调用）：不读写队列、不起草、不起线程。 */
    static com.onesay.assistant.v2.router.Invocation recognize(Context ctx, String raw) {
        if (raw == null) return null;
        // 第 5 项：「给张三发邮件，…」→ 大模型起草 + 存草稿 + 确认卡（唯一的对外发送路径）；起草在派发时做
        String[] mail = com.onesay.assistant.v2.callback.OutboundMail.parseCommand(raw);
        if (mail != null) {
            org.json.JSONObject a = new org.json.JSONObject();
            try {
                a.put("text", "好，我先起草给" + mail[0] + "的邮件，存进草稿箱后弹卡片给你确认，你点「发送」才会发。");
                a.put("query", raw.trim());
                a.put("queue", "MAIL");
                a.put(EFFECT, "mail");
                a.put("mail_who", mail[0]);
                a.put("mail_intent", mail[1].length() > 0 ? mail[1] : raw);
            } catch (Exception ignored) {
            }
            return new com.onesay.assistant.v2.router.Invocation("ask.reply", a, 1.0, com.onesay.assistant.v2.router.Invocation.RouteSource.L0_RULE, raw);
        }
        com.onesay.assistant.v2.jobs.JobQueue.Parsed p = com.onesay.assistant.v2.jobs.JobQueue.parse(raw);
        if (p == null) return null;
        org.json.JSONObject args = new org.json.JSONObject();
        try {
            args.put("text", p.command == null ? "好的。" : "排上了：" + p.command);   // 占位；派发时按入队结果改写
            args.put("query", raw.trim());
            args.put("queue", p.verb.name());
            args.put(EFFECT, "queue");
        } catch (Exception ignored) {
        }
        return new com.onesay.assistant.v2.router.Invocation("ask.reply", args, 1.0, com.onesay.assistant.v2.router.Invocation.RouteSource.L0_RULE, raw);
    }

    /** 副作用出口（JVM 单测替换成计数桩；生产 = {@link #REAL}）。 */
    interface Effects {
        boolean mailReady(Context ctx);
        void beginMail(Context ctx, String raw, String who, String intent);
        String queue(Context ctx, com.onesay.assistant.v2.jobs.JobQueue.Parsed p);
    }

    static final Effects REAL = new Effects() {
        @Override public boolean mailReady(Context ctx) { return com.onesay.assistant.v2.callback.OutboundMailHooks.mailReady(ctx); }
        @Override public void beginMail(Context ctx, String raw, String who, String intent) {
            com.onesay.assistant.v2.callback.OutboundMailHooks.begin(ctx, raw, who, intent, "voice");
        }
        @Override public String queue(Context ctx, com.onesay.assistant.v2.jobs.JobQueue.Parsed p) { return handleQueue(ctx, p); }
    };

    static volatile Effects sEffects = REAL;

    /**
     * 执行阶段：对 {@link #recognize} 产出的调用做一次真正的副作用，并把回复文字改成实际结果。
     * 返回 false = 不是本插件的调用（不动）。调用方保证每条最终指令只调一次（面板 {@code dispatch}）。
     */
    static boolean applyEffect(Context ctx, com.onesay.assistant.v2.router.Invocation inv) {
        if (inv == null || inv.args == null || !"ask.reply".equals(inv.sign)) return false;
        String eff = inv.args.optString(EFFECT, "");
        if (eff.length() == 0) return false;
        // 同一个调用对象只做一次：分诊 5 s 复用会把同一个 L0 结果再交出来（与迁移前「分诊复用 → 不再起草」一致），
        // 也防面板两条汇入路径把同一条结果派发两次
        if (inv.args.optBoolean(DONE, false)) return true;
        try {
            inv.args.put(DONE, true);
        } catch (Exception ignored) {
        }
        Effects fx = sEffects;
        String raw = inv.args.optString("query", inv.originalQuery == null ? "" : inv.originalQuery);
        String text;
        if ("mail".equals(eff)) {
            boolean ready = fx.mailReady(ctx);
            if (ready) fx.beginMail(ctx, raw, inv.args.optString("mail_who"), inv.args.optString("mail_intent", raw));
            text = ready ? inv.args.optString("text") : com.onesay.assistant.v2.callback.OutboundMailHooks.NO_MAILBOX;
        } else if ("queue".equals(eff)) {
            com.onesay.assistant.v2.jobs.JobQueue.Parsed p = com.onesay.assistant.v2.jobs.JobQueue.parse(raw);
            if (p == null) return false;
            text = fx.queue(ctx, p);
        } else {
            return false;
        }
        try {
            inv.args.put("text", text);
        } catch (Exception ignored) {
        }
        return true;
    }

    /** 面板派发：做副作用、改好文字，再交回通用 ask.reply 呈现（返回 false = 不认领，走通用路径）。 */
    @Override public boolean dispatch(com.onesay.assistant.v2.ui.FloatingSheetService svc, com.onesay.assistant.v2.router.Invocation inv, String command) {
        if (applyEffect(svc, inv)) android.util.Log.i("CallbackPlugin", "[CB] 派发时执行 " + inv.args.optString(EFFECT) + " / " + inv.args.optString("queue"));
        return false;
    }

    @Override public String l0Log(com.onesay.assistant.v2.router.Invocation inv) {
        return "[QUEUE] 队列口令 → " + (inv.args == null ? "" : inv.args.optString("queue"));
    }

    @Override public boolean claims(Context ctx, String query) {
        return com.onesay.assistant.v2.jobs.JobQueue.parse(query) != null || com.onesay.assistant.v2.callback.OutboundMail.parseCommand(query) != null;
    }

    /**
     * 第 5 项：AgentLoop（含定时任务 / 队列）想调任何「发出邮件」类工具 → 不执行，改为起草 + 确认卡。
     * 这样没有任何不经确认的发送路径（模型拿到的观测会明确告诉它不要重试）。
     */
    @Override public String agentPrecheck(Context ctx, String name, org.json.JSONObject args) {
        if (com.onesay.assistant.v2.jobs.PermissionAnalyzer.forTool(name, null) != com.onesay.assistant.v2.jobs.Perm.MAIL_SEND) return null;
        String[] un = com.onesay.assistant.v2.callback.OutboundMailHooks.UNATTENDED.get();
        android.util.Log.i("CallbackPlugin", "[MAIL-OUT] 拦下发送类工具 " + name + " → 起草 + 确认卡（" + (un == null ? "agent" : un[0]) + "）");
        return com.onesay.assistant.v2.callback.OutboundMailHooks.divertAgentSend(ctx, args, un == null ? "agent" : un[0], un == null ? null : un[1]);
    }

    static String handleQueue(Context ctx, com.onesay.assistant.v2.jobs.JobQueue.Parsed p) {
        final Context app = ctx.getApplicationContext();
        com.onesay.assistant.v2.jobs.JobQueue q = JobsHooks.queue(app);
        long now = System.currentTimeMillis();
        String text;
        switch (p.verb) {
            case STATUS:
                return q.statusReply();
            case CLEAR: {
                int n = 0;
                for (JobStore.Run r : q.waiting()) if (q.cancel(r.id, now)) n++;
                JobsHooks.queueIsland(app);
                return n == 0 ? "队列里没有在排的。" : "取消了 " + n + " 个排队的任务（正在做的那个不动）。";
            }
            default: {
                JobStore.Run r = q.enqueue(p.command, now);
                if (r == null) return "队列满了（最多 " + com.onesay.assistant.v2.jobs.JobQueue.MAX + " 个），等前面的做完再排。";
                if (p.verb == com.onesay.assistant.v2.jobs.JobQueue.Verb.JUMP) q.jump(r.id, now);
                int pos = q.position(r.id);
                text = p.verb == com.onesay.assistant.v2.jobs.JobQueue.Verb.JUMP
                        ? "插队了：" + p.command + (pos <= 1 ? "，下一个就做。" : "。")
                        : "排上了：" + p.command + (pos <= 1 && q.active() == null ? "，马上开始。" : "，前面还有 " + (pos - 1 + (q.active() != null ? 1 : 0)) + " 个。");
            }
        }
        com.onesay.assistant.v2.util.Threads.start("queue-pump", new Runnable() {
            @Override public void run() { JobsHooks.pumpQueue(app); }
        });
        return text;
    }

    @Override public String consoleSummary(Context ctx) {
        try {
            int n = 0;
            for (JobStore.Job j : JobsHooks.engine(ctx).store().jobs()) if (j.enabled) n++;
            return n == 0 ? "还没有模板任务" : n + " 个模板任务";
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public void addConsoleEntries(final ConsoleHost host, String page, LinearLayout root) {
        final Activity act = host.activity();
        if (ConsoleHost.PAGE_FEATURES.equals(page)) {
            final boolean killed = SWITCH.killed(act);
            ConsoleHost.Row r = host.switchLinkRow(root, "定时任务与主动播报", killed ? ConsoleHost.KILLED : "结果上岛 / 播报 / 通知，免打扰时段不出声",
                    act.getSharedPreferences(SWITCH.prefs, Context.MODE_PRIVATE).getBoolean(SWITCH.key, SWITCH.def),
                    new ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) { SWITCH.set(act, on); }
                    },
                    new Runnable() {
                        @Override public void run() { host.openPage(PAGE, "定时任务与主动播报"); }
                    });
            if (killed) r.setEnabled(false);
            return;
        }
        if (PAGE_HISTORY.equals(page)) {
            addHistory(host, root);
            return;
        }
        if (!PAGE.equals(page)) return;
        final android.content.SharedPreferences sp = act.getSharedPreferences(CallbackHub.SETTINGS_PREF, Context.MODE_PRIVATE);

        LinearLayout hg = host.group(root, null);
        host.linkRow(hg, "回调历史", "最近送达 / 合并 / 过期 / 没打扰的结果", new Runnable() {
            @Override public void run() { host.openPage(PAGE_HISTORY, "回调历史"); }
        });

        // ---- 免打扰
        LinearLayout g = host.group(root, "免打扰");
        host.switchRow(g, "免打扰时段不出声", "通话中、勿扰模式开着时也一律不出声（只上岛 + 通知）", sp.getBoolean("quiet_on", true),
                new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) { sp.edit().putBoolean("quiet_on", on).apply(); }
                });
        final ConsoleHost.Row[] q = new ConsoleHost.Row[2];
        q[0] = host.actionRow(g, "开始", sp.getString("quiet_start", "23:00"), "修改", new Runnable() {
            @Override public void run() { editTime(act, sp, "quiet_start", "23:00", q[0]); }
        });
        q[1] = host.actionRow(g, "结束", sp.getString("quiet_end", "07:00"), "修改", new Runnable() {
            @Override public void run() { editTime(act, sp, "quiet_end", "07:00", q[1]); }
        });

        // ---- 定时可靠性自检
        LinearLayout rel = host.group(root, "定时可靠性");
        final ConsoleHost.Row[] rows = new ConsoleHost.Row[5];
        String[] names = {"Doze 白名单", "后台运行（appops）", "前台服务", "下一次闹钟", "最近 7 天"};
        for (int i = 0; i < rows.length; i++) rows[i] = host.valueRow(rel, names[i], "检测中…", "", ConsoleHost.TONE_MUTED);
        // 第 7 项：省电白名单只在 debug 变体生效（默认开、启动时自动加）；加之前记录原状态，一键还原回到原状态并关掉自动
        final boolean pwOk = com.onesay.assistant.v2.jobs.PowerWhitelistHooks.variantSupported();
        host.actionRow(rel, "加入省电白名单", pwOk ? "root：Doze 白名单 + 允许后台运行（先记录原状态）" : "只在 debug 包生效", "加入", new Runnable() {
            @Override public void run() { runPower(host, true); }
        });
        host.actionRow(rel, "一键还原", "按记录的原状态撤销，并关闭自动加入", "还原", new Runnable() {
            @Override public void run() { runPower(host, false); }
        });
        host.footer(root, "三星「设备维护 › 电池 › 应用程序电源监控」里的休眠名单读不到，请手动把 OneSay 设为「不休眠」。");
        com.onesay.assistant.v2.util.Threads.start("cb-reliability", new Runnable() {
            @Override public void run() {
                final List<ReliabilityCheck.Item> items = probe(act.getApplicationContext());
                host.ui(new Runnable() {
                    @Override public void run() {
                        for (int i = 0; i < rows.length && i < items.size(); i++) {
                            ReliabilityCheck.Item it = items.get(i);
                            rows[i].setSub(it.detail);
                            rows[i].setValue(it.ok == 1 ? "正常" : it.ok == 0 ? "注意" : "未知",
                                    it.ok == 1 ? ConsoleHost.TONE_OK : it.ok == 0 ? ConsoleHost.TONE_WARN : ConsoleHost.TONE_MUTED);
                        }
                    }
                });
            }
        });

        // ---- 内置模板
        LinearLayout tg = host.group(root, "内置模板");
        host.actionRow(tg, DailyDigest.TITLE, "每天 7:30 播报今日待办、未读重要邮件标题、天气（提前 5 分钟生成）", "添加", new Runnable() {
            @Override public void run() {
                JobsEngine e = JobsHooks.engine(act);
                PermissionCards.Card c = PermissionCards.forNewJob(e.prepare(DailyDigest.newJob(7, 30)), System.currentTimeMillis());
                PermissionCards.show(act, c);
            }
        });

        host.actionRow(tg, com.onesay.assistant.v2.jobs.MyThingsExport.TITLE, "每天 6:30 把今日待办和未读重要邮件标题发给晨间播报 v2（不出声，只记历史）", "添加", new Runnable() {
            @Override public void run() {
                JobsEngine e = JobsHooks.engine(act);
                PermissionCards.Card c = PermissionCards.forNewJob(e.prepare(com.onesay.assistant.v2.jobs.MyThingsExport.newJob(6, 30)), System.currentTimeMillis());
                PermissionCards.show(act, c);
            }
        });

        // ---- 第 7 项：事件任务
        com.onesay.assistant.v2.events.EventConsole.addGroup(host, root);

        // ---- 任务 / 等确认
        LinearLayout jg = host.group(root, "我的模板任务");
        final JobsEngine e = JobsHooks.engine(act);
        List<JobStore.Job> jobs = e.store().jobs();
        if (jobs.isEmpty()) host.valueRow(jg, "还没有", "在上面添加一个模板", "", ConsoleHost.TONE_MUTED);
        for (final JobStore.Job j : jobs) {
            host.actionRow(jg, j.title, com.onesay.assistant.v2.s3.S3Schedule.describe(j.repeat, j.hour, j.minute, j.weekMask, j.onceAt,
                    System.currentTimeMillis(), java.util.TimeZone.getDefault()) + (e.validGrant(j.grantKey()) == null ? " · 需要重新授权" : ""),
                    "删除", new Runnable() {
                        @Override public void run() {
                            e.delete(j.id);
                            com.onesay.assistant.v2.s3.S3Scheduler.reschedule(act, "job-delete");
                            host.toast("已删除：" + j.title);
                        }
                    });
        }
        LinearLayout wg = host.group(root, "等你确认");
        int n = 0;
        for (final JobStore.Run r : JobStore.Util.inState(e.store().runs(), JobMachine.State.WAITING_CONFIRM)) {
            n++;
            host.actionRow(wg, r.title, r.waitReason, "重新授权", new Runnable() {
                @Override public void run() {
                    PermissionCards.Card c = PermissionCards.forReauth(e, r.owner, System.currentTimeMillis());
                    if (c == null) host.toast("这条任务已经不在了");
                    else PermissionCards.show(act, c);
                }
            });
        }
        if (n == 0) host.valueRow(wg, "没有", "越权被拦下、需要重新授权的任务会出现在这里", "", ConsoleHost.TONE_MUTED);
    }

    /** 回调历史页（第 5 项）：最近 50 条 outbox，新的在前。 */
    static void addHistory(ConsoleHost host, LinearLayout root) {
        List<JobStore.Outbox> all = JobsHooks.engine(host.activity()).store().outbox();
        LinearLayout g = host.group(root, "最近 50 条");
        int n = 0;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        for (int i = all.size() - 1; i >= 0 && n < 50; i--, n++) {
            JobStore.Outbox o = all.get(i);
            String[] st = com.onesay.assistant.v2.callback.CallbackHistory.status(o);
            host.valueRow(g, (o.title.length() > 0 ? o.title : "定时任务") + " · " + f.format(new java.util.Date(o.createdAt)),
                    com.onesay.assistant.v2.callback.CallbackHistory.line(o), st[0],
                    "ok".equals(st[1]) ? ConsoleHost.TONE_OK : "warn".equals(st[1]) ? ConsoleHost.TONE_WARN : ConsoleHost.TONE_MUTED);
        }
        if (n == 0) host.valueRow(g, "还没有", "定时任务的结果会记在这里", "", ConsoleHost.TONE_MUTED);
        host.footer(root, "通知、灵动岛、播报送达的都会留一条；过期（超过送达时刻 30 分钟）的不播，判为「没必要说」的只留在这里。");
    }

    static List<ReliabilityCheck.Item> probe(Context app) {
        String out = null;
        try {
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r = com.onesay.assistant.v2.executor.ShellScript.exec(   // v4：拆成单条
                    new com.onesay.assistant.v2.executor.ShellExecutor(app), ReliabilityCheck.probeSteps(app.getPackageName()));
            if (r != null && !r.isDown && !r.isTimeout) out = r.output;
        } catch (Throwable ignored) {
        }
        boolean fgs = false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningServiceInfo s : am.getRunningServices(100)) {
                if (s.service.getClassName().equals("com.onesay.assistant.v2.wake.WakeService") && s.foreground) fgs = true;
            }
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        return ReliabilityCheck.evaluate(out, app.getPackageName(), fgs, com.onesay.assistant.v2.s3.S3Scheduler.scheduledAt(app), now,
                JobsHooks.engine(app).reliability(now, 7));
    }

    static void runPower(final ConsoleHost host, final boolean apply) {
        final Context app = host.activity().getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("cb-power", new Runnable() {
            @Override public void run() {
                final String msg = apply ? com.onesay.assistant.v2.jobs.PowerWhitelistHooks.apply(app) : com.onesay.assistant.v2.jobs.PowerWhitelistHooks.restore(app);
                host.ui(new Runnable() {
                    @Override public void run() { host.toast(msg); }
                });
            }
        });
    }

    static void editTime(Activity act, final android.content.SharedPreferences sp, final String key, final String def, final ConsoleHost.Row row) {
        final EditText et = new EditText(act);
        et.setText(sp.getString(key, def));
        new AlertDialog.Builder(act).setTitle("时间（HH:MM）").setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int m = DeliveryPolicy.parseHm(et.getText().toString(), -1);
                        if (m < 0) return;
                        String v = DeliveryPolicy.formatHm(m);
                        sp.edit().putString(key, v).apply();
                        row.setSub(v);
                    }
                }).setNegativeButton("取消", null).show();
    }

    @Override public String debugStatus(Context ctx) {
        return "enabled=" + enabled(ctx) + " switch=" + SWITCH;
    }

    /** 2026-10-23：debug 包事件任务（充电）注入入口 --es event …（见 test/EventTestDriver；release 不编译驱动，EventHooks 的 debug 方法也直接返回）。 */
    @Override public String debugExtra() { return "event"; }
}
