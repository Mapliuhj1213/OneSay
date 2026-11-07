package com.onesay.assistant.v2.router.ctx;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ★ int/1102：各模型上下文窗口（总窗口 = 输入 + 输出，token）。可配表：内置值 + prefs 覆盖（{@code bixby_conv/model_windows}，
 * 「模型=窗口,模型=窗口」）。查不到的模型保守按 {@link #FALLBACK} = 32k 总窗口。
 *
 * <p>内置值来源（2026-11-02 核对，见 INT-1102.md §上下文预算）：
 * <ul>
 *   <li>deepseek-flash / deepseek-v4-pro / deepseek-v4-flash（旧名，路由到 V4.1-Flash）：1M —— api-docs.deepseek.com 模型与价格页；</li>
 *   <li><b>deepseek-chat / deepseek-reasoner</b>：官方更新日志 2026-04-24 条：2026-07-24 停用，现行价格页已不列。int/1102 起代码不再发这个名字
 *       （{@code net.LlmModels} 统一换成 deepseek-flash）；表里留着只为兼容老覆盖配置 → 保守 32k、未核实；</li>
 *   <li>qwen3-vl-plus（识屏）：百炼模型页 help.aliyun.com/zh/model-studio/qwen3-vl-plus（2026-11-02 核对）上下文 262,144、最大输入 260,096、
 *       最大输出 32,768 → 262144，已核实；</li>
 *   <li>qwen-plus / qwen-max / qwen-turbo：代码里没有调用，本轮未核 → 保守 32k，<b>待核实</b>。</li>
 * </ul>
 */
public final class ModelWindows {
    private ModelWindows() {
    }

    public static final int FALLBACK = 32768;

    /** 内置表：模型名（小写）→ {窗口, 是否已核实(1/0)}。 */
    private static final Map<String, int[]> BUILTIN = new LinkedHashMap<String, int[]>();

    static {
        BUILTIN.put("deepseek-flash", new int[]{1048576, 1});
        BUILTIN.put("deepseek-v4-pro", new int[]{1048576, 1});
        BUILTIN.put("deepseek-v4-flash", new int[]{1048576, 1});
        BUILTIN.put("deepseek-chat", new int[]{FALLBACK, 0});
        BUILTIN.put("deepseek-reasoner", new int[]{FALLBACK, 0});
        BUILTIN.put("qwen3-vl-plus", new int[]{262144, 1});
        BUILTIN.put("qwen-plus", new int[]{FALLBACK, 0});
        BUILTIN.put("qwen-max", new int[]{FALLBACK, 0});
        BUILTIN.put("qwen-turbo", new int[]{FALLBACK, 0});
    }

    private static volatile Map<String, Integer> sOverride = new LinkedHashMap<String, Integer>();

    /** 解析「模型=窗口,…」（非法项跳过；窗口 &lt; 4096 视为非法）。纯函数。 */
    public static Map<String, Integer> parse(String spec) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        if (spec == null) return m;
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            try {
                int v = Integer.parseInt(part.substring(eq + 1).trim());
                if (k.length() > 0 && v >= 4096) m.put(k, v);
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    public static void setOverrides(String spec) {
        sOverride = parse(spec);
    }

    public static int window(String model) {
        String k = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        Integer o = sOverride.get(k);
        if (o != null) return o;
        int[] b = BUILTIN.get(k);
        return b != null ? b[0] : FALLBACK;
    }

    /** 这个模型的窗口是否已从官方文档核实（覆盖值按用户配置算「已核实」）。 */
    public static boolean verified(String model) {
        String k = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (sOverride.containsKey(k)) return true;
        int[] b = BUILTIN.get(k);
        return b != null && b[1] == 1;
    }

    public static String table() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : BUILTIN.entrySet())
            sb.append(e.getKey()).append('=').append(window(e.getKey())).append(verified(e.getKey()) ? "" : "（待核实，保守）").append('\n');
        for (Map.Entry<String, Integer> e : sOverride.entrySet())
            if (!BUILTIN.containsKey(e.getKey())) sb.append(e.getKey()).append('=').append(e.getValue()).append("（prefs 覆盖）\n");
        return sb.toString();
    }
}
