package com.onesay.assistant.v2.mikaplug;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.plug.api.PlugRegistry;
import com.onesay.assistant.plug.api.PlugSpec;
import com.onesay.assistant.plug.api.PlugSwitches;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.phone.MikaExec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★ mika-plug：OneSay 侧的统一插件注册表（规格 + 开关）。插件核心在 com.onesay.assistant.plug.*.core（纯 Java），这里只做宿主适配。
 *
 * <h3>开关（每个插件一个，现查不缓存）</h3>
 * <ol>
 *   <li>调试覆盖（debug 包 {@code --es plug set:<id>=on|off|clear}）优先；</li>
 *   <li>kill 文件 {@code plug_<id>.off}（外部 files 目录或 /data/local/tmp/bixby_plug_<id>.off）存在即关；</li>
 *   <li>否则 SharedPreferences {@code bixby_plug} 的 {@code <id>}，缺省用规格里的出厂默认。</li>
 * </ol>
 * 依赖链上任一插件关 = 本插件视同关（PlugRegistry）。<b>出厂默认</b>：会改执行行为的插件只在 debug 包默认开
 * （{@code BuildVariant.IS_DEBUG}），release 全关；额外调模型 / 新增高权限工具 / 无障碍服务后端的插件两边都默认关。
 */
public final class MikaPlugs {
    private MikaPlugs() {
    }

    public static final String PREFS = "bixby_plug";

    public static final String SCREEN = "mika.screen", REFS = "mika.refs", CHANGEWAIT = "mika.changewait", ACTCHAIN = "mika.actchain",
            EFFECT = "mika.effect", GUARD = "mika.guard", ACTGATE = "mika.actgate", PACING = "mika.pacing", DOSTEPS = "mika.dosteps",
            A11Y = "mika.a11y", LOOPGUARD = "mika.loopguard", EVIDENCE = "mika.evidence", OUTCOME = "mika.outcome",
            STOPSUMMARY = "mika.stopsummary", OUTBOX = "mika.outbox", INTENT = "mika.intent", INBOX = "mika.inbox",
            APPKB = "mika.appkb", ROOTCMD = "mika.rootcmd", SKILL = "mika.skilllearn";
    /** ★ skill-pack：OneSay 自写的技能包（非 Mika 来源，按 PLUGIN-SPEC 用顶层单词 id）；skillpack.llm = LLM 兜底挑技能（默认关）。 */
    public static final String SKILLPACK = "skillpack", SKILLPACK_LLM = "skillpack.llm";

    static final boolean DBG = com.onesay.assistant.v2.BuildVariant.IS_DEBUG;
    static final String M = "app/src/main/java/com/wangzan101/mika/", C = "core/src/main/kotlin/com/wangzan101/mika/core/";

    private static volatile Context sApp;
    private static final Map<String, Boolean> sOverride = new ConcurrentHashMap<String, Boolean>();

    /** 注册表（debug 决定行为类插件的出厂默认；JVM 单测用 build(true/false) 各建一份）。 */
    public static PlugRegistry build(boolean debug) {
        PlugRegistry r = new PlugRegistry(SWITCHES);
        r.register(new PlugSpec(SCREEN, 1, "读屏对齐 Mika", "多窗口读树、@eN 编号、0–999 坐标、隐私过滤、剪枝、TreeQuality、给模型 ≤6000 字 JSON", debug, null,
                new String[]{M + "cap/phone/a11y/ScreenReader.kt", C + "uistate/ScreenPort.kt", C + "uistate/TreeQuality.kt", C + "uistate/ScreenText.kt"}));
        r.register(new PlugSpec(REFS, 1, "稳定编号", "RefStabilizer + relocate：相邻两屏没变的元素沿用编号", debug, new String[]{SCREEN},
                new String[]{C + "uistate/RefStabilizer.kt", C + "uistate/Accuracy.kt", M + "cap/phone/a11y/NodeIds.kt"}));
        r.register(new PlugSpec(CHANGEWAIT, 1, "动作后等稳定", "ChangeWait 树版：事件安静 400 ms 且树变了即返回，≤ 2.5 s", debug, new String[]{SCREEN},
                new String[]{M + "cap/phone/a11y/ChangeWait.kt"}));
        r.register(new PlugSpec(ACTCHAIN, 1, "动作降级链", "祖先 ACTION_CLICK → 手势 → 注入；SET_TEXT 读回核对；粘贴后还原剪贴板", debug, new String[]{SCREEN},
                new String[]{M + "cap/phone/a11y/AndroidScreen.kt (click/touch/scroll/typeText/inputText)", C + "uistate/Accuracy.kt (typeAndVerify)"}));
        r.register(new PlugSpec(EFFECT, 1, "动作效果判定", "InterfaceDiff → done / small_change / no_effect / place_changed + 读回", debug, new String[]{SCREEN},
                new String[]{C + "uistate/ActionEffect.kt", C + "uistate/EffectTuning.kt", C + "uistate/InterfaceUpdate.kt"}));
        r.register(new PlugSpec(GUARD, 1, "卡住阶梯", "OperationGuards 阶梯提醒 / 叫停 + StuckAdvice", debug, new String[]{EFFECT},
                new String[]{C + "guard/GuardRules.kt", C + "guard/OperationGuards.kt", C + "agent/StuckAdvice.kt", C + "agent/StopRules.kt"}));
        r.register(new PlugSpec(ACTGATE, 1, "动作守卫", "RiskGate：发送 / 提交 / 付款 / 删除先问，屏上有金额按花钱", debug, null,
                new String[]{C + "action/RiskJudge.kt", C + "action/ActionExecutor.kt"}));
        r.register(new PlugSpec(PACING, 1, "外发节奏", "同一 App 连续对外动作随机间隔（8–20 s 起，≤ 30 s）", debug, null,
                new String[]{C + "action/OutwardPacing.kt"}));
        r.register(new PlugSpec(DOSTEPS, 1, "Mika 步骤写法", "接受 ref @eN、0–999 坐标、do_steps / tool+args、end_not_done", debug, new String[]{SCREEN},
                new String[]{C + "agent/BatchRunner.kt (字段与停止条件)"}));
        r.register(new PlugSpec(A11Y, 1, "无障碍服务后端（B2）", "可选后端：AccessibilityService 读树 / 手势 / 全局返回；需手动在系统设置里打开", false, new String[]{SCREEN},
                new String[]{M + "cap/phone/a11y/ScreenAccessibility.kt", M + "a11y/MikaAccessibilityService.kt", "app/src/main/res/xml/accessibility_service.xml"}));
        r.register(new PlugSpec(LOOPGUARD, 1, "主循环守卫", "AgentLoop 工具调用的连续失败 / 读循环阶梯 + StopRules 收尾", debug, null,
                new String[]{C + "guard/OperationGuards.kt", C + "agent/StopRules.kt"}));
        r.register(new PlugSpec(EVIDENCE, 1, "证据账本", "读到的材料进账（脱敏、截短、去回显），回答来源按账本挑", debug, null,
                new String[]{C + "verify/EvidenceLedger.kt"}));
        r.register(new PlugSpec(OUTCOME, 1, "结局判定", "回答交出后后台请一次模型判 done / partial / not_possible（多一次模型调用）", false, null,
                new String[]{C + "agent/OutcomeJudge.kt", "core/src/main/resources/prompts/roles/outcome_judge.md"}));
        r.register(new PlugSpec(STOPSUMMARY, 1, "停止交代", "用户中途停止时按记录拼几行「做了什么、没做完什么」", debug, null,
                new String[]{C + "agent/StopSummary.kt"}));
        r.register(new PlugSpec(OUTBOX, 1, "送达去重", "先记账再发送，结果不明不自动重发（主动播报 / 定时结果）", debug, null,
                new String[]{C + "run/Outbox.kt"}));
        r.register(new PlugSpec(INTENT, 1, "意图闸", "分诊模糊句：聊 / 查 / 做（DISCUSS / READ / ACT）替原 LLM 兜底", debug, null,
                new String[]{C + "agent/IntentGate.kt", "core/src/main/resources/prompts/roles/intent_gate.md"}));
        r.register(new PlugSpec(INBOX, 1, "插话收件箱", "任务跑着时再说一句 = 追加给当前任务，不打断", debug, null,
                new String[]{C + "run/RunInbox.kt"}));
        r.register(new PlugSpec(APPKB, 1, "App 知识包", "APP.md / SITE.md：开局按相关度注入 ≤ 3 份，深链只放行注入过的", debug, null,
                new String[]{C + "knowledge/FrontMatter.kt", C + "knowledge/KnowledgeIndex.kt", "core/src/main/resources/knowledge/apps/{wechat,qqmail,android-settings,chrome}/APP.md"}));
        r.register(new PlugSpec(ROOTCMD, 1, "root 命令白名单", "run_command 改为 root shell 白名单：只读命令直接跑，危险命令拒绝，其余要确认", false, null,
                new String[]{C + "sandbox (思路)", "core/src/main/resources/prompts/tools/run_command.md"}));
        r.register(new PlugSpec(SKILL, 1, "技能自学（草稿）", "成功任务沉淀为草稿技能（status: draft），只落盘到 debug 目录给人审，不自动启用；release 不写", false, null,
                new String[]{C + "knowledge/AppKnowledgeLearning.kt (接口形状)"}));
        // ★ skill-pack（OneSay 自写，无上游文件）
        r.register(new PlugSpec(SKILLPACK, 1, "技能包", "按任务模式自动加载常用技能（assets/skill_*.md）：规则打分零延迟，Top-2、≤1500 字，多轮保持 / 换话题卸载", debug, null,
                new String[0]));
        r.register(new PlugSpec(SKILLPACK_LLM, 1, "技能包 · LLM 兜底", "规则没命中时请一次模型从技能目录里挑（多一次模型调用）", false, new String[]{SKILLPACK},
                new String[0]));
        return r;
    }

    /** feat/suggest 起 public：suggest 插件族（v2.suggest.SuggestPlugs）共用同一套开关来源（prefs bixby_plug + plug_&lt;id&gt;.off + 调试覆盖）。 */
    public static final PlugSwitches SWITCHES = new PlugSwitches() {
        @Override public boolean on(String id, boolean def) {
            Boolean o = sOverride.get(id);
            if (o != null) return o.booleanValue();
            Context c = sApp;
            if (c == null) return def;
            try {
                if (FeatureSwitch.killFilePresent(c, "plug_" + id + ".off")) return false;
                SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                return sp.getBoolean(id, def);
            } catch (Throwable t) {
                return def;
            }
        }
    };

    /** 必须排在 SWITCHES 之后（静态初始化顺序）。 */
    public static final PlugRegistry REG = build(DBG);

    public static void attach(Context ctx) {
        if (ctx != null && sApp == null) sApp = ctx.getApplicationContext();
    }

    public static boolean on(Context ctx, String id) {
        attach(ctx);
        return REG.on(id);
    }

    public static boolean on(String id) {
        return REG.on(id);
    }

    /** 调试覆盖：null = 清除。 */
    public static void override(String id, Boolean v) {
        if (v == null) sOverride.remove(id);
        else sOverride.put(id, v);
    }

    public static void clearOverrides() {
        sOverride.clear();
    }

    public static void set(Context ctx, String id, boolean on) {
        attach(ctx);
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(id, on).apply();
    }

    /** 执行层插件快照（全关 = null，PhoneAgent 走原行为）。 */
    public static MikaExec phoneExec(Context ctx) {
        attach(ctx);
        MikaExec.Flags f = flags(REG);
        return f.any() ? new MikaExec(f) : null;
    }

    public static MikaExec.Flags flags(PlugRegistry r) {
        MikaExec.Flags f = new MikaExec.Flags();
        f.screen = r.on(SCREEN);
        f.allWindows = f.screen;
        f.refs = r.on(REFS);
        f.changewait = r.on(CHANGEWAIT);
        f.actchain = r.on(ACTCHAIN);
        f.effect = r.on(EFFECT);
        f.guard = r.on(GUARD);
        f.actgate = r.on(ACTGATE);
        f.pacing = r.on(PACING);
        f.dosteps = r.on(DOSTEPS);
        f.selfPrefix = "com.onesay.assistant";
        f.a11y = r.on(A11Y);
        return f;
    }

    public static String status() {
        return REG.statusText();
    }

    /** 清掉某插件的持久开关（回到出厂默认）。 */
    public static void reset(Context ctx, String id) {
        attach(ctx);
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply();
    }

    /**
     * 调试命令解析（debug 入口 {@code --es plug ...} 用）：{@code list} / {@code <id>=on|off|default}，逗号分隔可多条；
     * id 可省 {@code mika.} 前缀，{@code all=off|default} 作用于全部插件。返回 [id, "on"/"off"/"default"] 列表；
     * 未知 id 或值返回 [原文, "?"]。纯函数，JVM 可测。
     */
    public static java.util.List<String[]> parseCommand(PlugRegistry reg, String arg) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        if (arg == null) return out;
        for (String part : arg.split(",")) {
            String t = part.trim();
            if (t.length() == 0 || "list".equalsIgnoreCase(t)) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) { out.add(new String[]{t, "?"}); continue; }
            String id = t.substring(0, eq).trim(), v = t.substring(eq + 1).trim().toLowerCase(java.util.Locale.ROOT);
            String norm = "on".equals(v) || "true".equals(v) || "1".equals(v) ? "on"
                    : "off".equals(v) || "false".equals(v) || "0".equals(v) ? "off"
                    : "default".equals(v) || "reset".equals(v) ? "default" : null;
            if ("all".equals(id)) {
                if (norm == null || "on".equals(norm)) { out.add(new String[]{t, "?"}); continue; }   // 不许一键全开（含 B2 / run_command）
                for (PlugSpec sp : reg.specs()) out.add(new String[]{sp.id, norm});
                continue;
            }
            if (reg.spec(id) == null && !id.startsWith("mika.")) id = "mika." + id;   // ★ skill-pack：先认完整 id（skillpack），再补 mika. 前缀
            if (norm == null || reg.spec(id) == null) { out.add(new String[]{t, "?"}); continue; }
            out.add(new String[]{id, norm});
        }
        return out;
    }

    /** debug 入口：执行 {@link #parseCommand}（写 prefs，持久），返回执行结果 + 当前全部插件状态。 */
    public static String debugCommand(Context ctx, String arg) {
        attach(ctx);
        StringBuilder b = new StringBuilder();
        // ★ feat/suggest：suggest / suggest.<场景> 交给 SuggestPlugs（同一套开关来源），all=… 两边都作用
        String[] split = com.onesay.assistant.v2.suggest.SuggestPlugs.splitCommand(arg);
        arg = split[0];
        for (String[] c : parseCommand(REG, arg)) {
            if ("?".equals(c[1])) b.append("看不懂：").append(c[0]).append('\n');
            else {
                if ("default".equals(c[1])) reset(ctx, c[0]);
                else set(ctx, c[0], "on".equals(c[1]));
                b.append(c[0]).append(" → ").append(c[1]).append('\n');
            }
        }
        b.append(com.onesay.assistant.v2.suggest.SuggestPlugs.applyCommand(ctx, split[1]));
        return b.append(status()).append(com.onesay.assistant.v2.suggest.SuggestPlugs.status()).toString();
    }
}
