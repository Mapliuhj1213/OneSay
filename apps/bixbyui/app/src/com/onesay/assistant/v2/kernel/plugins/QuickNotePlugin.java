package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.quicknote.QuickNoteHooks;
import com.onesay.assistant.v2.quicknote.QuickNoteIntent;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;

import org.json.JSONObject;

/**
 * [quicknote] S2+「记一记」插件（；<b>2026-10-11 晚起默认开</b>（用户要长按记一记真正可用），开关 bixby_quicknote.enabled / kill-switch quicknote.off，
 * 控制台「记一记」卡可关）。
 *
 * <ul>
 *   <li>长按 Bixby 键：守护 v7 写 trigger「long …」→ WakeService 撤销唤醒窗 → {@link #onKeyGesture} → 记当前屏幕；
 *       插件开着时 App 会建 files/bixby.gesture_v2，守护才改写长按（守护还没部署时用调试入口或语音「记一记」触发）；</li>
 *   <li>语音：「记一记」= 记当前屏幕；「刚才记一记的是什么」「记一记列表」「删掉刚记一记的」；</li>
 *   <li>只读屏 + 本地分类 + 存 S2 记忆库，不联网、不截图、不触发任何副作用；验证码 / 密码 / 卡号页不记。</li>
 * </ul>
 */
public final class QuickNotePlugin extends FeaturePlugin {

    public static final FeatureSwitch SWITCH = new FeatureSwitch("bixby_quicknote", "enabled", true, "quicknote.off");

    @Override public String id() { return "quicknote"; }
    @Override public String title() { return "记一记（长按 Bixby 键）"; }
    @Override public String logTag() { return "[QUICKNOTE]"; }
    @Override public String acceptanceModule() { return "quicknote"; }
    @Override public String prefsName() { return SWITCH.prefs; }
    @Override public String[] killFiles() { return SWITCH.killFiles; }
    @Override public boolean defaultOn() { return SWITCH.def; }
    @Override public boolean enabled(Context ctx) { return SWITCH.on(ctx); }
    @Override public String[] dependsOn() { return new String[]{"s2", "screenref"}; }
    @Override public String debugExtra() { return "quicknote"; }

    /** 排在迁移插件之后（不抢 S2「记一下」，只认带「记一记」的句子）。 */
    @Override public int priority(FeatureHook hook) { return 8000; }

    @Override public boolean wantsKeyGesture(Context ctx, String gesture) {
        if ("hold".equals(gesture)) return ctx != null && com.onesay.assistant.v2.quicknote.QuickNoteSettings.get(ctx, com.onesay.assistant.v2.quicknote.QuickNoteSettings.KEY_HOLD);
        return "long".equals(gesture);
    }

    @Override public String[] islandActivities() {
        return new String[]{"记录中：紧凑岛流光（长按瞬间，无声）", "结果卡：标题 + 摘要 + 最多 3 个动作，6 s 后收起；没记 → 展开原因",
                "码胶囊：取件码 / 取餐码 / 券码常驻（复用 S1 取件码卡），关掉 = 已取"};
    }
    @Override public String[] islandActivityKinds() {
        return new String[]{com.onesay.assistant.v2.kernel.IslandHost.KIND_NOTE, com.onesay.assistant.v2.kernel.IslandHost.KIND_PICKUP};
    }
    @Override public String consoleCard() { return "记一记卡（开关 + 条数）"; }

    /** [R12] 控制台 v2：记一记设置页（记一记列表右上角「设置」进来；原控制台「记一记」卡的 5 个开关）+ 首页「记一记」大卡。 */
    @Override public boolean consoleV2() { return true; }

    @Override public String[] consoleTile(Context ctx) {
        java.util.List<com.onesay.assistant.v2.quicknote.QuickNoteEntry> all = QuickNoteHooks.book(ctx).list();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long dayStart = c.getTimeInMillis();
        int today = 0, codes = 0;
        for (com.onesay.assistant.v2.quicknote.QuickNoteEntry e : all) {
            if (e.createdAt >= dayStart) today++;
            if (e.hasCode() && e.codeState == com.onesay.assistant.v2.quicknote.QuickNoteEntry.CODE_ON_ISLAND) codes++;
        }
        return new String[]{String.valueOf(all.size()), "今天 " + today + " 条" + (codes > 0 ? " · 取件码 " + codes : "")};
    }

    @Override public void addConsoleEntries(final com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (!com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_QUICKNOTE.equals(page)) return;
        final android.app.Activity act = host.activity();
        final boolean killed = SWITCH.killed(act);
        final java.util.List<com.onesay.assistant.v2.kernel.ConsoleHost.Row> subs = new java.util.ArrayList<com.onesay.assistant.v2.kernel.ConsoleHost.Row>();
        com.onesay.assistant.v2.kernel.ConsoleHost.Row main = host.mainSwitch(root, "长按 Bixby 键 = 记一记",
                act.getSharedPreferences(SWITCH.prefs, Context.MODE_PRIVATE).getBoolean(SWITCH.key, SWITCH.def),
                new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        SWITCH.set(act, on);
                        com.onesay.assistant.v2.wake.WakeService.syncGestureFlag(act);   // 改完立即同步守护用的手势标记
                        for (com.onesay.assistant.v2.kernel.ConsoleHost.Row r : subs) r.setEnabled(on && !killed);
                    }
                });
        android.widget.LinearLayout g = host.group(root, "细项");
        subs.add(sub(host, g, act, "锁屏时按住 ≥2 秒 = 随口记", "说一句话就记下", com.onesay.assistant.v2.quicknote.QuickNoteSettings.KEY_HOLD));
        subs.add(sub(host, g, act, "文字太少时看图", "会上传截图给视觉模型", com.onesay.assistant.v2.quicknote.QuickNoteSettings.KEY_VL));
        subs.add(sub(host, g, act, "订单自动记账", "订单页记下时顺便记一笔账", com.onesay.assistant.v2.quicknote.QuickNoteSettings.KEY_LEDGER));
        subs.add(sub(host, g, act, "保存截图缩略图", "只存本机私有目录", com.onesay.assistant.v2.quicknote.QuickNoteSettings.KEY_THUMBS));
        for (com.onesay.assistant.v2.kernel.ConsoleHost.Row r : subs) r.setEnabled(main.isOn() && !killed);
        if (killed) {
            main.setEnabled(false);
            host.footer(root, com.onesay.assistant.v2.kernel.ConsoleHost.KILLED);
        }
        host.footer(root, "长按 Bixby 键（≥0.8 秒，无声，岛上出「记录中」）或说「记一记」：读当前屏幕 → 本机分类 → 存进记一记。"
                + "按住多久都只记屏幕、不会唤醒助手；锁屏时按住 ≥2 秒 = 随口记。邮件里有待办 / 截止 / 会议会给「提醒」「加日程」。"
                + "验证码 / 密码 / 卡号页不记、不截图。");
    }

    private static com.onesay.assistant.v2.kernel.ConsoleHost.Row sub(com.onesay.assistant.v2.kernel.ConsoleHost host, android.widget.LinearLayout g,
                                                             final android.app.Activity act, String title, String sub, final String key) {
        return host.switchRow(g, title, sub, com.onesay.assistant.v2.quicknote.QuickNoteSettings.get(act, key), new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                com.onesay.assistant.v2.quicknote.QuickNoteSettings.set(act, key, on);
            }
        });
    }


    @Override public boolean onKeyGesture(Context ctx, String gesture) {
        if ("hold".equals(gesture)) {
            if (!wantsKeyGesture(ctx, "hold")) return false;
            return QuickNoteHooks.onHold(ctx);   // ★ R11：同一次长按已在记屏幕 → 吸收，不开面板；锁屏时才随口记
        }
        if (!"long".equals(gesture)) return false;
        QuickNoteHooks.captureAndSave(ctx, "long");
        return true;
    }

    /** 按住 ≥ 2 s 后开的收音窗：下一句是随口记的内容。 */
    @Override public boolean interceptCommand(com.onesay.assistant.v2.ui.FloatingSheetService svc, String command) {
        return QuickNoteHooks.takeSuikouji(svc, command);
    }

    @Override public Invocation route(final Context ctx, final String query, L2LLMCompiler l2) {
        QuickNoteIntent.Kind k = QuickNoteIntent.of(query);
        if (k == QuickNoteIntent.Kind.NONE) return null;
        long now = System.currentTimeMillis();
        String text;
        switch (k) {
            case RUN:
                com.onesay.assistant.v2.util.Threads.start("quicknote-voice", new Runnable() {
                    @Override public void run() { QuickNoteHooks.captureAndSave(ctx, "voice"); }
                });
                text = "好，面板收起后把当前屏幕记一记。";
                break;
            case TICKET:   // ★ 票类口令、计划 App 不在（在的话 PlannerPlugin 285 已接走）：识屏记一记，认票 / 取票码照记一记口径
                com.onesay.assistant.v2.util.Threads.start("quicknote-ticket", new Runnable() {
                    @Override public void run() { QuickNoteHooks.captureAndSave(ctx, "voice"); }
                });
                text = "好，面板收起后把这张票记一记。";
                break;
            case SUIKOUJI: {
                final String body = QuickNoteIntent.suikoujiText(query);
                if (body.length() == 0) {
                    text = "想随口记什么？比如「随口记 明早八点交房租」。";
                    break;
                }
                String why = com.onesay.assistant.v2.s2.SensitiveGuard.reason(body);
                if (why != null) {
                    text = "这句不记：" + why + "。";
                    break;
                }
                com.onesay.assistant.v2.util.Threads.start("quicknote-suikouji", new Runnable() {
                    @Override public void run() { QuickNoteHooks.saveVoice(ctx, body, com.onesay.assistant.v2.quicknote.QuickNoteEntry.SRC_SUIKOUJI); }
                });
                text = "随口记好了：" + body;
                break;
            }
            case OPEN:
                com.onesay.assistant.v2.quicknote.QuickNoteHooks.openDetail(ctx.getApplicationContext(), -1);
                text = "已打开记一记。";
                break;
            case RECALL: QuickNoteHooks.housekeep(ctx); text = QuickNoteHooks.book(ctx).recallReply(now); break;
            case LIST: QuickNoteHooks.housekeep(ctx); text = QuickNoteHooks.book(ctx).listReply(now, 5); break;
            case CATEGORY: {
                QuickNoteHooks.housekeep(ctx);
                String cat = com.onesay.assistant.v2.quicknote.QuickNoteSearch.categoryWord(query.replace("记一记", ""));
                text = QuickNoteHooks.book(ctx).categoryReply(cat == null ? "码" : cat, now, 5);
                break;
            }
            case SEARCH:
            case SEARCH_SOFT: {
                QuickNoteHooks.housekeep(ctx);
                String r = com.onesay.assistant.v2.quicknote.QuickNoteRecall.answer(QuickNoteHooks.book(ctx), query, now, java.util.TimeZone.getDefault());
                if (r == null) {
                    if (k == QuickNoteIntent.Kind.SEARCH_SOFT) return null;   // 记一记里没有：留给 S2
                    r = com.onesay.assistant.v2.quicknote.QuickNoteRecall.missReply(query);
                }
                text = r;
                break;
            }
            default: text = QuickNoteHooks.book(ctx).deleteLastReply(); break;
        }
        JSONObject args = new JSONObject();
        try {
            args.put("text", text);
            args.put("query", query.trim());
        } catch (Exception ignored) {
        }
        return new Invocation("ask.reply", args, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    /** 「把这个记一记」带指代词，不能让识屏 DEIXIS 先接走。 */
    @Override public boolean bypassScreenRef(Context ctx, String command) { return QuickNoteIntent.of(command) != QuickNoteIntent.Kind.NONE; }

    @Override public String routeLog() { return "[ROUTE-QUICKNOTE] 记一记接管: "; }
    @Override public boolean claims(Context ctx, String query) {
        QuickNoteIntent.Kind k = QuickNoteIntent.of(query);
        if (k == QuickNoteIntent.Kind.NONE) return false;
        if (k != QuickNoteIntent.Kind.SEARCH_SOFT) return true;
        try {
            return ctx != null && com.onesay.assistant.v2.quicknote.QuickNoteRecall.answer(QuickNoteHooks.book(ctx), query, System.currentTimeMillis(),
                    java.util.TimeZone.getDefault()) != null;
        } catch (Throwable t) {
            return false;
        }
    }
    @Override public String debugStatus(Context ctx) { return "enabled=" + enabled(ctx) + " switch=" + SWITCH; }
}
