package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.agenttools.AgentToolset;
import com.onesay.assistant.v2.agenttools.GuiFold;
import com.onesay.assistant.v2.agenttools.ToolTiers;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.fastscreen.FastScreen;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.phone.PhoneAgent;
import com.onesay.assistant.v2.phone.PhoneHooks;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ★ r2/fast-screen：常驻读屏助手预热 + AgentLoop 工具 {@code phone.operate}（在某个 App 里替用户做多步操作）。
 * <ul>
 *   <li>服务启动 / 每次唤醒：预热助手（已在跑就只是一次 ping，约 10 ms）；</li>
 *   <li>phone.operate{goal, app}：进入执行模式（岛取代小窗 + 防误触护罩），{@link PhoneAgent} 按模型调用次数 / 总时长 / 无进展限预算，
 *       敏感步骤在岛上确认；返回的是任务结果文字。</li>
 * </ul>
 */
public final class PhonePlugin extends FeaturePlugin {

    public static final String SIGN = "phone.operate";

    private static volatile Capability sCap;

    static Capability cap() {
        if (sCap != null) return sCap;
        // ★ arch/tool-tiers（A 路）：去掉「在设置里查东西 / 在地图里搜地点 / 在浏览器里查资料」这类揽活例子
        //   （它们都有接口、查信息或深链的做法），写明这是四档里的最后一档。
        sCap = new Capability.Builder(SIGN,
                "【点界面·最后一档】在手机上的某个 App 界面里替用户完成多步点按（读屏→点按→输入中文→滚动→确认结果）。"
                        + "只有接口、查信息、深链（打开到页）都不适用或都试过失败、或用户明确要你去点界面时才用；"
                        + "一次只交给它一个 App 和一个明确目标，已知的数据写进 goal。付款 / 登录 / 发送等敏感步骤会暂停让用户确认")
                .domain("phone").category("app")
                .verbs("操作", "帮我", "在").entities("app")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                .executor("custom")
                .schema(new ParamsSchema()
                        .addString("goal", true, null)
                        .addString("app", false, ""))
                .reply("已执行")
                .build();
        return sCap;
    }

    private static volatile Capability sEntry;

    /**
     * ★ arch/tool-tiers（A 路）：GUI 档折叠时代替 phone.operate 的入口（短说明、无副作用）。
     * 调用后本任务的 GUI 档展开（{@link AgentToolset#afterStep}），AgentLoop 重新导出工具表时 phone.operate 才出现。
     */
    static Capability entryCap() {
        if (sEntry != null) return sEntry;
        sEntry = new Capability.Builder(ToolTiers.SIGN_GUI_ENTRY,
                "【点界面·入口】接口、查信息、深链都不适用或都试过失败时调用：写明原因，调用后会把在 App 界面里替用户点按的工具 phone.operate 加进你的工具列表")
                .domain("phone").category("app")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("custom")
                .schema(new ParamsSchema()
                        .addString("why", true, null)
                        .addString("app", false, ""))
                .reply("已展开")
                .build();
        return sEntry;
    }

    /** 本轮该挂哪一个：功能关 → 都不挂；GUI 档展开 → phone.operate；否则 → 入口 gui.unfold。纯函数，JVM 可测。 */
    static Capability toolFor(boolean enabled, String query) {
        if (!enabled) return null;
        AgentToolset.Session s = AgentToolset.currentFor(query);
        boolean expanded = s != null ? s.guiExpanded()
                : GuiFold.initial(query, null, true).expanded;   // 没有会话（别处调用）：只看原话
        return expanded ? cap() : entryCap();
    }

    @Override public int priority(FeatureHook hook) {
        return super.priority(hook);
    }

    @Override public void onServiceCreate(FloatingSheetService svc) {
        com.onesay.assistant.v2.prompt.PromptSource.init(svc);   // [arch-a] 提示词资源文件预读用的 Context
        FastScreen.get(svc).warmAsync();
    }

    @Override public void onWakeEarly(FloatingSheetService svc) {
        FastScreen.get(svc).warmAsync();
    }

    @Override public void appendAgentTools(Context ctx, JSONArray tools, String query) {
        if (ctx == null) return;
        Capability c = toolFor(PhoneHooks.enabled(ctx), query);
        if (c == null) return;
        try {
            JSONArray mine = CapabilityRegistry.getInstance().exportToolsSchema(java.util.Collections.singletonList(c));
            for (int i = 0; i < mine.length(); i++) tools.put(mine.get(i));
        } catch (Throwable ignored) {
        }
    }

    @Override public Capability describeForAgent(Context ctx, String name) {
        if (name == null) return null;
        if (name.equals(SIGN) || name.equals(SIGN.replace('.', '_')) || name.equals("phone__operate")) return cap();
        if (name.equals(ToolTiers.SIGN_GUI_ENTRY) || name.equals("gui__unfold") || name.equals("gui_unfold")) return entryCap();
        return null;
    }

    @Override public ExecutionResult executeForAgent(Context ctx, Capability c, JSONObject args) {
        if (c == null) return null;
        if (ToolTiers.SIGN_GUI_ENTRY.equals(c.sign)) {
            // 入口本身不做事：展开由 AgentLoop 步后 AgentToolset.afterStep 完成（重新导出时 appendAgentTools 换成 phone.operate）
            String why = args == null ? "" : args.optString("why", "");
            return ExecutionResult.success(c, args, "点界面工具已展开", ExecutionResult.Verification.VERIFIED,
                    "{\"unfolded\":\"phone.operate\",\"why\":" + JSONObject.quote(why) + "}");
        }
        if (!SIGN.equals(c.sign)) return null;
        String goal = args == null ? "" : args.optString("goal", "");
        String app = PhoneHooks.normalizePkg(args == null ? "" : args.optString("app", ""));   // ★ 11-04 B：「三星浏览器（com.sec…）」→ 包名，开局直接拉起目标 App
        PhoneAgent.Result r = PhoneHooks.runBlocking(ctx, goal, app, false, true);
        String text = r.status == PhoneAgent.Status.DONE ? r.answer : (r.status == PhoneAgent.Status.STOPPED ? "已停止：" : "没完成：") + r.reason;
        String detail = "模型 " + r.modelCalls + " 次、动作 " + r.actions + " 个、" + (r.ms / 1000) + " 秒";
        if (r.status == PhoneAgent.Status.DONE) return ExecutionResult.success(c, args, text, ExecutionResult.Verification.VERIFIED, detail);
        // ★ arch/integrate：失败细分（登录 / 验证 / 付款 → needs_user；没 Key / 读屏不可用 / 模型无响应 → blocked；预算 / 卡住 / 没找到 → not_done）
        com.onesay.assistant.v2.agent.PhoneFailure f = com.onesay.assistant.v2.agent.PhoneFailure.classify(r.status == PhoneAgent.Status.STOPPED, r.reason, r.answer);
        return ExecutionResult.failed(f.code, text, detail + "；" + f.tag(), f.hint, c, args);
    }

    @Override public String id() { return "phone"; }
    @Override public String title() { return "替你操作 App"; }
    @Override public String logTag() { return "[PHONE]"; }
    @Override public String prefsName() { return PhoneHooks.PREF; }
    /** 新插件：由内核门控（总开关 = 偏好 + phone.off）。 */
    @Override public boolean kernelGated() { return true; }
    @Override public String acceptanceModule() { return null; }   // 真机验收走 debug 直测入口（TestRunReceiver phone/fs/exec），见 FAST-SCREEN.md
    @Override public String[] killFiles() { return new String[]{"phone.off"}; }
    @Override public boolean consoleV2() { return true; }
    @Override public boolean enabled(Context ctx) { return PhoneHooks.enabled(ctx); }

    @Override public String debugStatus(Context ctx) {
        return "enabled=" + enabled(ctx) + " fastscreen=" + FastScreen.enabled(ctx) + " " + FastScreen.get(ctx).status() + " busy=" + PhoneHooks.busy()
                + " replay=" + PhoneHooks.replayEnabled(ctx) + " " + PhoneHooks.replayStore(ctx).stats();
    }

    // ------------------------------------------------------------------ ★ smart-replay：控制台（功能行 + 细项页：回放开关 / 一键清空）

    static final String PAGE = "phone";

    @Override public void addConsoleEntries(final com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        final android.app.Activity act = host.activity();
        boolean killed = com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(act, "phone.off");
        if (com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_FEATURES.equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleRows.featureRow(host, root, title(), "在 App 界面里替你点按；付款 / 发送 / 删除等步骤先问你", killed,
                    mainPref(act), PAGE, title());
            return;
        }
        if (!PAGE.equals(page)) return;
        com.onesay.assistant.v2.kernel.ConsoleRows.detailPage(host, root, "使用替你操作 App", killed, mainPref(act),
                new String[]{"记住成功的步骤，下次直接照做", "控屏状态颜色"},
                new String[]{"同一句话、同一个 App 再说一次时先按上次的步骤做，画面对不上就交回模型；只存在本机",
                        "替你操作时屏幕边框和灵动岛描边按谁在控屏变色：蓝紫 = 助手在操作，琥珀 = 等你确认，绿 = 你接手了"},
                new com.onesay.assistant.v2.kernel.ConsoleRows.Pref[]{new com.onesay.assistant.v2.kernel.ConsoleRows.Pref() {
                    @Override public boolean get() { return PhoneHooks.replayPref(act); }
                    @Override public void set(boolean on) { PhoneHooks.setReplay(act, on); }
                }, new com.onesay.assistant.v2.kernel.ConsoleRows.Pref() {   // ★ borrow-1112
                    @Override public boolean get() {
                        return act.getSharedPreferences(com.onesay.assistant.v2.exec.ControlColor.PREF, Context.MODE_PRIVATE)
                                .getBoolean(com.onesay.assistant.v2.exec.ControlColor.KEY, com.onesay.assistant.v2.exec.ControlColor.DEFAULT_ON);
                    }
                    @Override public void set(boolean on) { com.onesay.assistant.v2.exec.ControlColor.SWITCH.set(act, on); }
                }},
                new boolean[]{PhoneHooks.replayKilled(act), com.onesay.assistant.v2.exec.ControlColor.SWITCH.killed(act)}, null);
        android.widget.LinearLayout g = host.group(root, "记住的步骤");
        final com.onesay.assistant.v2.kernel.ConsoleHost.Row count = host.valueRow(g, "已记住", "最多 " + com.onesay.assistant.v2.phone.SmartReplay.MAX_PATHS
                + " 条，久不用的先删", PhoneHooks.replayStore(act).size() + " 条", com.onesay.assistant.v2.kernel.ConsoleHost.TONE_NORMAL);
        host.actionRow(g, "清空记住的步骤", "删掉后会重新从头记", "清空", new Runnable() {
            @Override public void run() {
                host.confirm("清空记住的步骤？", "所有记住的成功步骤都会删掉，下次重新由模型来做。", "清空", new Runnable() {
                    @Override public void run() {
                        PhoneHooks.clearReplay(act);
                        count.setValue("0 条", com.onesay.assistant.v2.kernel.ConsoleHost.TONE_NORMAL);
                        host.toast("已清空");
                    }
                });
            }
        });
        host.footer(root, "付款 / 转账类任务不记也不照做；验证码、密码、个人信息不存原文，做到那一步交回模型。需要你确认的步骤照做时也会再问你。");
    }

    private static com.onesay.assistant.v2.kernel.ConsoleRows.Pref mainPref(final Context ctx) {
        return new com.onesay.assistant.v2.kernel.ConsoleRows.Pref() {
            @Override public boolean get() {
                return ctx.getSharedPreferences(PhoneHooks.PREF, Context.MODE_PRIVATE).getBoolean(PhoneHooks.KEY_ENABLED, true);
            }

            @Override public void set(boolean on) {
                ctx.getSharedPreferences(PhoneHooks.PREF, Context.MODE_PRIVATE).edit().putBoolean(PhoneHooks.KEY_ENABLED, on).apply();
            }
        };
    }
}
