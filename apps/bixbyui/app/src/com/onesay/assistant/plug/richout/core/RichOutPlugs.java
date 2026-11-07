/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.* 与 plug.api。
 */
package com.onesay.assistant.plug.richout.core;

import com.onesay.assistant.plug.api.PlugRegistry;
import com.onesay.assistant.plug.api.PlugSpec;
import com.onesay.assistant.plug.api.PlugSwitches;

/**
 * 富内容输出插件的规格、开关裁决、上限和提示词规则。
 *
 * <ul>
 *   <li>{@code richout}（总开关，默认开）：回答按 Markdown 分块原生渲染；关 = 面板、提示词、朗读全部回到改动前的纯文本口径；</li>
 *   <li>{@code richout.diagram}（依赖 richout，默认开）：svg / mermaid / chart 用沙箱 WebView 画；关 = 图当代码显示；</li>
 *   <li>{@code richout.reader}（依赖 richout，默认开）：链接在应用内阅读器打开；关 = 链接只能长按复制，不跳转。</li>
 * </ul>
 * 宿主在启动时 {@link #setSwitches} 接上 SharedPreferences / kill 文件；JVM 单测里是出厂默认。
 */
public final class RichOutPlugs {

    private RichOutPlugs() {}

    public static final String ID = "richout";
    public static final String ID_DIAGRAM = "richout.diagram";
    public static final String ID_READER = "richout.reader";

    public static final PlugSpec SPEC = new PlugSpec(ID, 1, "富内容回答",
            "回答支持列表 / 表格 / 代码块 / 链接（原生渲染，纯文本照旧）", true, null, null);
    public static final PlugSpec SPEC_DIAGRAM = new PlugSpec(ID_DIAGRAM, 1, "回答里的图",
            "svg / mermaid / chart 在沙箱 WebView 里画（禁网络、禁文件、无 JS 接口）", true, new String[] {ID}, null);
    public static final PlugSpec SPEC_READER = new PlugSpec(ID_READER, 1, "应用内打开链接",
            "回答里的链接在应用内阅读器打开（阅读模式 / 原网页）", true, new String[] {ID}, null);

    private static final PlugRegistry REG = new PlugRegistry(PlugSwitches.DEFAULTS)
            .register(SPEC).register(SPEC_DIAGRAM).register(SPEC_READER);

    public static PlugRegistry registry() {
        return REG;
    }

    public static void setSwitches(PlugSwitches s) {
        REG.setSwitches(s);
    }

    public static boolean on() {
        return REG.on(ID);
    }

    public static boolean diagramsOn() {
        return REG.on(ID_DIAGRAM);
    }

    public static boolean readerOn() {
        return REG.on(ID_READER);
    }

    // ------------------------------------------------------------------ 上限

    /** 正文（围栏外）字数上限：沿用问答通道原来的 500。 */
    public static final int MAX_PROSE = 500;
    /** 整个回答（含代码 / 图源码）上限。 */
    public static final int MAX_TOTAL = 8000;
    /** 富内容开着时问答通道的 max_tokens（原 1024，代码 / 图源码要更多）。 */
    public static final int ASK_MAX_TOKENS = 2048;
    /** 一个回答最多画几张图（多的当代码显示）。 */
    public static final int MAX_DIAGRAMS = 4;
    public static final int MAX_MERMAID_CHARS = 8000;
    public static final int MAX_CHART_CHARS = 20000;
    /** 代码超过这么多行默认折叠。 */
    public static final int CODE_FOLD_LINES = 16;
    /** 图卡片在面板里的最大高度（dp），点开全屏看全图。 */
    public static final int DIAGRAM_MAX_HEIGHT_DP = 320;

    // ------------------------------------------------------------------ 提示词

    /** 问答通道（ask.output 模板第 3 条）——插件开。 */
    public static final String ASK_RULE_RICH =
            "默认用短段落纯文本；只在确实有帮助时用 Markdown：要点多用短列表，对比用表格，"
            + "用户要代码就给代码块（```后写语言名），需要画图时用 ```svg（≤20KB，viewBox 宽≤800，不写 script、不引外部资源）、"
            + "```mermaid（流程图 / 时序图 / 饼图，≤60 行）或 ```chart（Chart.js 的 JSON 配置 {type,data,options}，不写函数，≤200 个数据点）。"
            + "给网址用 [文字](https://…)。不用 HTML、不用表情符号。代码和图不算在字数里，正文仍要简短。";
    /** 问答通道——插件关（原文）。 */
    public static final String ASK_RULE_PLAIN = "不使用 Markdown 标题、代码块、项目符号。";

    /** 旧版内置问答提示词第 3 条——插件关（原文）。 */
    public static final String LEGACY_RULE_PLAIN = "用纯文本，禁止 Markdown 符号（* # ` 等）、禁止编号符号堆砌、禁止表情符号。";

    /** AgentLoop 系统提示 iron_rules 第 4 条——插件开。 */
    public static final String AGENT_RULE_RICH =
            "最终答复适合屏幕阅读：先给结论、短段落；需要时可用列表、表格、```代码块，或 ```svg / ```mermaid / ```chart 画图，"
            + "链接写成 [文字](https://…)；不要输出 JSON。";
    /** AgentLoop——插件关（原文）。 */
    public static final String AGENT_RULE_PLAIN = "最终答复适合屏幕阅读：先给结论、短段落，不要 Markdown、不要代码块、不要输出 JSON。";

    public static String askRule() {
        return on() ? ASK_RULE_RICH : ASK_RULE_PLAIN;
    }

    // ------------------------------------------------------------------ 只放行表格（2026-11-03 用户拍板：出表格不用问直接出）

    /**
     * 富内容总开关关着（release 出厂）、但用户这句明确要表格时，问答第 3 条换成这句：只许一个 Markdown 表格，
     * 其它 Markdown / 链接 / 代码 / HTML / 图一律不放行。
     */
    public static final String ASK_RULE_TABLE_ONLY =
            "用户明确要求用表格：用一个 Markdown 表格呈现（第一行表头，第二行 |---| 分隔，最多 10 行 6 列），"
            + "单元格只写纯文本短语；表格前最多一句结论。除这个表格外不使用任何 Markdown（标题、列表、加粗、代码块、链接），"
            + "不用 HTML、不画图、不用表情符号。";
    /** 表格回答的总长上限（表格 + 前后一两句）。 */
    public static final int MAX_TABLE_TOTAL = 2400;
    public static final int MAX_TABLE_ROWS = 12;
    public static final int MAX_TABLE_COLS = 6;
    public static final int MAX_TABLE_CELL = 60;

    /** 明确要表格：「用表格」「以表格形式」「列个表」「做个表对比」「做成表格」「对比表」……（「列表」「表情」「手表」「WPS表格」不算）。 */
    private static final java.util.regex.Pattern WANT_TABLE = java.util.regex.Pattern.compile(
            "(用|以|拿)(个|一个|张|一张)?表格|表格(的)?(形式|方式|来)|(做|整理|列|弄|汇总|排|画|写)成(一个|一张)?表(格)?(?![情白达示演扬态哥姐弟妹盘带链])"
            + "|(做|列|出|画|来|弄|整)(个|一个|张|一张)表(格)?(?![情白达示演扬态哥姐弟妹盘带链])"
            + "|(做|出|画|列)表格|(对比|比较|参数|配置|价格)(一览)?表(?![情白达示演扬态哥姐弟妹盘带链])"
            + "|(?i:\\bin a table\\b|\\bas a table\\b|\\btable\\b.{0,12}\\b(compare|comparison)\\b)");
    private static final java.util.regex.Pattern NO_TABLE = java.util.regex.Pattern.compile(
            "(不要|别|不用|无需|不需要|甭)(用|做|列|出|画)?(个|一个|张)?表(格)?");
    /** 表格不是回答形态、而是要操作文件 / App（「做个表格发给张三」「打开WPS表格」「用表格记账」交给原路由）。 */
    private static final java.util.regex.Pattern TABLE_AS_FILE = java.util.regex.Pattern.compile(
            "(?i)发给|发送|分享给|保存|导出|下载|文件|excel|wps|word|打开|新建|记账|记录我");

    /** 纯函数：这句话是不是明确要「用表格回答」。 */
    public static boolean wantsTable(String q) {
        if (q == null || q.length() == 0) return false;
        if (NO_TABLE.matcher(q).find()) return false;
        if (TABLE_AS_FILE.matcher(q).find()) return false;
        return WANT_TABLE.matcher(q).find();
    }

    public static String legacyRule() {
        return on() ? ASK_RULE_RICH : LEGACY_RULE_PLAIN;
    }

    public static String agentRule() {
        return on() ? AGENT_RULE_RICH : AGENT_RULE_PLAIN;
    }
}
