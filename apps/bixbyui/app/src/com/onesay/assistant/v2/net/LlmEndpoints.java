package com.onesay.assistant.v2.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

/**
 * 大模型端点（用户可配置）。在「API 密钥」页填写 base URL：只接受 https，DeepSeek / 百炼兼容模式为预置值。
 *
 * <ul>
 *   <li>DeepSeek（OpenAI 兼容）：base 默认 {@link #DEEPSEEK_PRESET}，请求 {@code <base>/chat/completions}、测试 {@code <base>/models}；</li>
 *   <li>DashScope 兼容模式：base 默认 {@link #DASHSCOPE_PRESET}，请求 {@code <base>/chat/completions}（语音识别 / 识屏 / 看图）。</li>
 * </ul>
 * Key 只发往这里配置的主机：调用方一律从本类取 URL，{@link #keyAllowedFor} 再兜底核对一次（https + 同主机）。
 * 存在 prefs {@code bixby_llm_cfg}（与 DeepSeek Key 同一文件；URL 不是秘密，明文存）。
 */
public final class LlmEndpoints {
    private LlmEndpoints() {}

    public static final String DEEPSEEK_PRESET = "https://api.deepseek.com";
    public static final String DASHSCOPE_PRESET = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    static final String PREF = "bixby_llm_cfg";
    static final String K_DEEPSEEK = "deepseek_base_url", K_DASHSCOPE = "dashscope_base_url";

    /** 仅 JVM 单测（同包）打开：允许把请求发往本机 127.0.0.1 的假服务器。生产代码从不设置。 */
    static volatile boolean sTestAllowLoopback = false;

    private static volatile String sDashscope = DASHSCOPE_PRESET;

    /**
     * 规范化用户填的 base URL：去空白、去末尾 "/"、去误填的 "/chat/completions" 或 "/models"。
     * 不是合法的 https URL（无主机、带用户信息、带查询串 / 片段）→ 返回 null。
     */
    public static String normalize(String in) {
        if (in == null) return null;
        String s = in.trim();
        if (s.length() == 0) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String low = s.toLowerCase(Locale.ROOT);
        if (low.endsWith("/chat/completions")) s = s.substring(0, s.length() - "/chat/completions".length());
        else if (low.endsWith("/models")) s = s.substring(0, s.length() - "/models".length());
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        try {
            URI u = new URI(s);
            if (!"https".equalsIgnoreCase(u.getScheme())) return null;
            if (u.getHost() == null || u.getHost().length() == 0) return null;
            if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) return null;
        } catch (Exception e) {
            return null;
        }
        return s;
    }

    /** 主机名（小写）；不是 URL 返回 null。 */
    public static String host(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 只有 https 且主机与已配置的某个大模型 base 一致时才允许带 Key。
     * ★ console-align：主模型 base 由 {@link MainModelEndpoint} 管（同一个 prefs 键）；debug 包里主模型配成 http 时，同主机的 http 也放行。
     */
    public static boolean keyAllowedFor(String url) {
        if (sTestAllowLoopback && url != null && url.startsWith("http://127.0.0.1:")) return true;
        if (url == null) return false;
        String h = host(url);
        if (h == null) return false;
        if (url.regionMatches(true, 0, "http://", 0, 7)) {
            return MainModelEndpoint.allowHttp() && MainModelEndpoint.base().regionMatches(true, 0, "http://", 0, 7) && h.equals(host(MainModelEndpoint.base()));
        }
        if (!url.regionMatches(true, 0, "https://", 0, 8)) return false;
        return h.equals(host(MainModelEndpoint.base())) || h.equals(host(sDashscope));
    }

    public static String deepseekBase() { return MainModelEndpoint.base(); }
    public static String dashscopeBase() { return sDashscope; }
    public static String deepseekChatUrl() { return MainModelEndpoint.chatUrl(); }
    public static String deepseekModelsUrl() { return MainModelEndpoint.modelsUrl(); }
    public static String dashscopeChatUrl() { return sDashscope + "/chat/completions"; }
    public static String dashscopeModelsUrl() { return sDashscope + "/models"; }

    /** 测试用 / 进程内设置（不落盘）；非法值回落预置。 */
    public static void setForProcess(String deepseekBase, String dashscopeBase) {
        String d = normalize(deepseekBase), q = normalize(dashscopeBase);
        MainModelEndpoint.setForProcess(d != null ? d : DEEPSEEK_PRESET, false);
        sDashscope = q != null ? q : DASHSCOPE_PRESET;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 从 prefs 读进进程（启动时、改设置后调用）。 */
    public static void load(Context c) {
        try {
            SharedPreferences p = sp(c);
            String q = normalize(p.getString(K_DASHSCOPE, DASHSCOPE_PRESET));
            sDashscope = q != null ? q : DASHSCOPE_PRESET;
            MainModelEndpoint.load(c);   // ★ console-align：主模型地址（同一个键 deepseek_base_url；debug 包也认 http）
        } catch (Throwable ignored) { }
        com.onesay.assistant.v2.llm.ProviderStore.apply(c);   // ★ priv-llm：主对话服务商（没选别家 = 原 DeepSeek 通道）
    }

    /** 保存主模型 base（★ console-align：交给 {@link MainModelEndpoint}，同一个键）；非法返回 false 且不改。空串 = 恢复预置。 */
    public static boolean saveDeepseek(Context c, String base) {
        return MainModelEndpoint.save(c, base).ok;
    }

    /** 保存 DashScope 兼容模式 base；非法返回 false 且不改。空串 = 恢复预置。 */
    public static boolean saveDashscope(Context c, String base) {
        String n = (base == null || base.trim().length() == 0) ? DASHSCOPE_PRESET : normalize(base);
        if (n == null) return false;
        sp(c).edit().putString(K_DASHSCOPE, n).apply();
        sDashscope = n;
        return true;
    }
}
