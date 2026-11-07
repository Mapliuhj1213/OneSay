/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。把 core 接到 OneSay：开关来源、问答终稿、朗读、灵动岛、浮层状态。
 */
package com.onesay.assistant.plug.richout.host;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.plug.api.PlugSwitches;
import com.onesay.assistant.plug.richout.core.RichOutPlugs;
import com.onesay.assistant.plug.richout.core.RichText;

import java.io.File;

/**
 * 宿主入口（静态、JVM 安全：类加载不碰任何 android API）。
 *
 * <p><b>出厂默认按变体</b>：debug 包（{@code BuildVariant.IS_DEBUG}）三个插件默认开，release 包默认关（core 规格里的
 * defaultOn=true 只是「debug 下的默认」，宿主再与变体相与）。{@link #install} 之前（JVM 单测 / 还没起服务）同样按变体裁决，
 * 所以 release 变体的 JVM 单测看到的是改动前的纯文本口径。
 *
 * <p>开关来源（现查不缓存，与 mika-plug 同一套约定）：
 * <ol>
 *   <li>kill 文件存在 → 关：{@code files/plug_<id>.off} 或 {@code <KILL_TMP_ROOT>/bixby_plug_<id>.off}（adb 可写，真机排障即时回落纯文本）；</li>
 *   <li>SharedPreferences {@code bixby_plug} 里的 {@code <id>}（调试入口 {@code --es plug richout=on|off|default} 写）；</li>
 *   <li>都没有 → 出厂默认（debug 开 / release 关）。</li>
 * </ol>
 * 依赖链：richout 关 → richout.diagram / richout.reader 视同关（PlugRegistry）。
 */
public final class RichOutHost {

    private RichOutHost() {}

    /** 与 mika-plug（MikaPlugs.PREFS）同一个 prefs 文件，键 = 插件 id。 */
    public static final String PREFS = "bixby_plug";

    /** 出厂默认：debug 开、release 关（编译期常量）。 */
    static final boolean VARIANT_DEFAULT = com.onesay.assistant.v2.BuildVariant.IS_DEBUG;

    static String killFileName(String id) {
        return "plug_" + id + ".off";
    }

    private static volatile Context sApp;
    private static volatile int sOverlays;

    public static void install(Context ctx) {
        if (ctx == null || sApp != null) return;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        sApp = app;
        RichOutPlugs.setSwitches(new PlugSwitches() {
            @Override public boolean on(String id, boolean def) {
                try {
                    if (new File(app.getFilesDir(), killFileName(id)).exists()) return false;
                    if (new File(com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT, "bixby_" + killFileName(id)).exists()) return false;
                    SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    return sp.contains(id) ? sp.getBoolean(id, def) : (def && VARIANT_DEFAULT);
                } catch (Throwable t) {
                    return def && VARIANT_DEFAULT;
                }
            }
        });
    }

    static boolean installed() {
        return sApp != null;
    }

    private static volatile boolean sTestSwitches;

    /** JVM 单测：注入开关（null = 复原为「没 install、按变体」）。生产代码不调用。 */
    public static void setSwitchesForTest(PlugSwitches s) {
        sTestSwitches = s != null;
        RichOutPlugs.setSwitches(s != null ? s : PlugSwitches.DEFAULTS);
    }

    /**
     * 调试入口 {@code --es plug ...}（与 mika-plug 同语法）：{@code list} / {@code <id>=on|off|default}，逗号分隔多条；
     * id 可省 {@code richout.} 前缀（{@code richout=off}、{@code diagram=off}、{@code reader=default}）。
     * 只认 richout 族的 id，其它 id 原样跳过（交给别的插件族的入口）。返回执行结果 + 三个插件当前状态。
     */
    public static String debugCommand(Context ctx, String cmd) {
        install(ctx);
        StringBuilder out = new StringBuilder();
        Context app = sApp;
        java.util.List<String[]> cmds = parseCommand(cmd);
        if (app != null && !cmds.isEmpty()) {
            SharedPreferences.Editor e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            for (String[] c : cmds) {
                if ("on".equals(c[1])) e.putBoolean(c[0], true);
                else if ("off".equals(c[1])) e.putBoolean(c[0], false);
                else e.remove(c[0]);
                out.append(c[0]).append(" → ").append(c[1]).append('\n');
            }
            e.commit();
        }
        return out.append(RichOutPlugs.registry().statusText()).toString();
    }

    /** 纯函数（JVM 可测）：解析出 richout 族的 [id, on|off|default]；非 richout 族 / 看不懂的跳过。 */
    public static java.util.List<String[]> parseCommand(String arg) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        if (arg == null) return out;
        for (String part : arg.split(",")) {
            String t = part.trim();
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String id = t.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            String v = t.substring(eq + 1).trim().toLowerCase(java.util.Locale.ROOT);
            String norm = "on".equals(v) || "true".equals(v) || "1".equals(v) ? "on"
                    : "off".equals(v) || "false".equals(v) || "0".equals(v) ? "off"
                    : "default".equals(v) || "reset".equals(v) ? "default" : null;
            if (norm == null) continue;
            if (!id.equals(RichOutPlugs.ID) && !id.startsWith(RichOutPlugs.ID + ".")) {
                if (id.indexOf('.') >= 0) continue;   // mika.xxx 之类：别的插件族
                String full = RichOutPlugs.ID + "." + id;
                if (RichOutPlugs.registry().spec(full) == null) continue;
                id = full;
            }
            if (RichOutPlugs.registry().spec(id) == null) continue;
            out.add(new String[] {id, norm});
        }
        return out;
    }

    /** 总开关（宿主一律走这里，不直接用 RichOutPlugs.on()：没 install 时也按变体裁决）。 */
    public static boolean on() {
        return (sApp != null || sTestSwitches || VARIANT_DEFAULT) && RichOutPlugs.on();
    }

    public static boolean diagramsOn() {
        return on() && RichOutPlugs.diagramsOn();
    }

    public static boolean readerOn() {
        return on() && RichOutPlugs.readerOn();
    }

    // ------------------------------------------------------------------ 提示词

    /** 问答模板 ask.output 第 3 条：插件开 → 富内容规则；关 → 原文。 */
    public static String askRule() {
        return on() ? RichOutPlugs.ASK_RULE_RICH : RichOutPlugs.ASK_RULE_PLAIN;
    }

    /** 把渲染好的问答提示词里第 3 条换成富内容规则（插件关 → 原样返回）；模板被热改过、找不到原句 → 在末尾补一条。 */
    public static String patchAskPrompt(String prompt) {
        if (prompt == null || !on()) return prompt;
        if (prompt.contains(RichOutPlugs.ASK_RULE_PLAIN)) return prompt.replace(RichOutPlugs.ASK_RULE_PLAIN, RichOutPlugs.ASK_RULE_RICH);
        if (prompt.contains(RichOutPlugs.LEGACY_RULE_PLAIN)) return prompt.replace(RichOutPlugs.LEGACY_RULE_PLAIN, RichOutPlugs.ASK_RULE_RICH);
        return prompt + "\n【输出格式】" + RichOutPlugs.ASK_RULE_RICH + "\n";
    }

    /** 问答通道 max_tokens：插件开 2048（代码 / 图源码要更多），关 = 原值。 */
    public static int askMaxTokens(int plain) {
        return on() ? Math.max(plain, RichOutPlugs.ASK_MAX_TOKENS) : plain;
    }

    /** 插件开时，问答回答整段被 ``` 包住（且不是 ```json）不再剥围栏 —— 那是代码块本身。 */
    public static boolean keepFence(String content) {
        if (content == null || !on()) return false;
        String t = content.trim();
        return t.startsWith("```") && !t.startsWith("```json") && !t.startsWith("```JSON");
    }

    // ------------------------------------------------------------------ 只放行表格（2026-11-03 用户拍板：出表格不用问直接出）
    //
    // 富内容总开关关着（release 出厂）时，用户这句明确要表格（RichOutPlugs.wantsTable）→ 本轮问答只放行一个原生表格卡：
    // 提示词第 3 条换成 ASK_RULE_TABLE_ONLY，终稿用 RichText.tableOnly 消毒（单元格纯文本、无链接 / 代码 / HTML / 图），
    // 面板按表头签名认出这是放行过的表格回答，用 TableLayout 画（不开 WebView、不执行任何脚本）。没要表格 = 原样纯文本。

    private static volatile boolean sTableTurn;
    private static final java.util.LinkedList<String> sTableSigs = new java.util.LinkedList<String>();

    /** 问答通道每轮开头调用（ask / askStream）：记下这句是否明确要表格。 */
    public static void beginAskTurn(String query) {
        sTableTurn = RichOutPlugs.wantsTable(query);
    }

    /** 本轮是「富内容关 + 明确要表格」。 */
    public static boolean tableOnlyTurn() {
        return sTableTurn && !on();
    }

    /** 本轮问答提示词第 3 条换成「只许一个表格」（不是表格轮 → 原样）。 */
    public static String patchAskPromptTable(String prompt) {
        if (prompt == null || !tableOnlyTurn()) return prompt;
        if (prompt.contains(RichOutPlugs.ASK_RULE_PLAIN)) return prompt.replace(RichOutPlugs.ASK_RULE_PLAIN, RichOutPlugs.ASK_RULE_TABLE_ONLY);
        if (prompt.contains(RichOutPlugs.LEGACY_RULE_PLAIN)) return prompt.replace(RichOutPlugs.LEGACY_RULE_PLAIN, RichOutPlugs.ASK_RULE_TABLE_ONLY);
        return prompt + "\n【输出格式】" + RichOutPlugs.ASK_RULE_TABLE_ONLY + "\n";
    }

    /** 表格轮终稿：消毒后的「一句 + 一个表格」；模型没给表格 → null（调用方照纯文本口径）。产出表格时登记表头签名。 */
    public static String polishTableOnly(String raw) {
        String t = RichText.tableOnly(raw, RichOutPlugs.MAX_TABLE_ROWS, RichOutPlugs.MAX_TABLE_COLS,
                RichOutPlugs.MAX_TABLE_CELL, RichOutPlugs.MAX_TABLE_TOTAL);
        if (t == null) return null;
        String sig = RichText.tableSignature(t);
        if (sig != null) {
            synchronized (sTableSigs) {
                sTableSigs.remove(sig);
                sTableSigs.addFirst(sig);
                while (sTableSigs.size() > 8) sTableSigs.removeLast();
            }
        }
        return t;
    }

    /** 富内容关时：这段回答是放行过的表格回答（形态是 tableOnly 的产物 + 表头签名登记过）。富内容开时恒 false（走原逻辑）。 */
    public static boolean tableGranted(String text) {
        if (text == null || on() || !RichText.isTableAnswer(text)) return false;
        String sig = RichText.tableSignature(text);
        if (sig == null) return false;
        synchronized (sTableSigs) {
            return sTableSigs.contains(sig);
        }
    }

    /** JVM 单测：清掉表格轮状态。 */
    public static void resetTableForTest() {
        sTableTurn = false;
        synchronized (sTableSigs) {
            sTableSigs.clear();
        }
    }

    // ------------------------------------------------------------------ 问答终稿（L2LLMCompiler 用）

    /** 插件开时的终稿裁剪（保留 Markdown、代码 / 图整块保留）。 */
    public static String polish(String raw) {
        return RichText.polish(raw, RichOutPlugs.MAX_PROSE, RichOutPlugs.MAX_TOTAL);
    }

    // ------------------------------------------------------------------ 灵动岛 / 一行摘要

    /** 灵动岛不显示富内容：插件开时把回答压成一行纯文字（代码 / 图换成〔代码〕〔图〕）；关时原样。 */
    public static String islandText(String answer) {
        if (answer == null) return answer;
        if (tableGranted(answer)) return RichText.plainLine(answer, 120);   // 表格轮：岛上一行〔表格〕
        if (!on() || !RichText.needsRich(answer)) return answer;
        return RichText.plainLine(answer, 120);
    }

    // ------------------------------------------------------------------ 朗读

    /**
     * 回答朗读计划。插件关 / 没有富内容特征 → null（照旧）。
     * 开着且有 Markdown → spoken 是去 Markdown 的整句（代码 / 图换成一句提示）；cue 非空 = 有代码 / 图 / 表格。
     */
    public static RichText.Speech speechPlan(String answer) {
        if (answer == null) return null;
        if (tableGranted(answer)) return RichText.speech(answer);   // 表格轮：不念表格，念「表格在屏上了」
        if (!on() || !RichText.needsRich(answer)) return null;
        return RichText.speech(answer);
    }

    // ------------------------------------------------------------------ 浮层（阅读器 / 全屏看图）

    /** 阅读器 / 全屏看图浮层开着：面板别把浮层上的触摸当成「点了面板外面」收起自己。 */
    public static boolean overlayOpen() {
        return sOverlays > 0;
    }

    static synchronized void overlayOpened() {
        sOverlays++;
    }

    static synchronized void overlayClosed() {
        if (sOverlays > 0) sOverlays--;
    }
}
