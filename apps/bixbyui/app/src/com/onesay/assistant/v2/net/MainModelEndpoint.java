package com.onesay.assistant.v2.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

/**
 * ★ console-align 2026-11-12：主模型接口地址可改（OpenAI 兼容，默认 DeepSeek；用户 11-12 20:00 定）。
 *
 * <ul>
 *   <li>「设置 › 模型与密钥 › 主模型 › 接口地址」填 base（到 /v1 为止，也接受整条 …/chat/completions），<b>留空 = 默认</b>
 *       {@link #DEFAULT_BASE}，与改前完全一样；</li>
 *   <li>只接受 https；<b>debug 包</b>额外接受 http（本地 / 代理测 5xx、超时，好看 {@link LlmFallback} 切备用）；
 *       不能带用户名密码、? 查询串、# 片段；</li>
 *   <li>请求时现取（{@link LlmClient} 对 {@link LlmClient#DEEPSEEK_URL} 换成 {@link #chatUrl()}），改了马上生效，不用重启；</li>
 *   <li>日志只记主机名（{@link #logLine}），不记 Key、不记路径。</li>
 * </ul>
 * 存在 prefs {@code bixby_llm_cfg / deepseek_base_url}（与开源版既有键同名，旧值照读；URL 不是秘密，明文存）。
 * 模型名用既有的 {@code bixby_conv / chat_model}（{@link LlmModels}），本类只做校验。纯 Java 部分 JVM 可测。
 */
public final class MainModelEndpoint {

    private MainModelEndpoint() {}

    public static final String DEFAULT_BASE = "https://api.deepseek.com";
    public static final String PREF = "bixby_llm_cfg", K_BASE = "deepseek_base_url";
    static final int MAX_LEN = 300;

    private static volatile String sBase = DEFAULT_BASE;
    private static volatile boolean sAllowHttp = false;

    /** 校验结果：ok 时 base 是规范化后的值（空输入 = 默认）；否则 error 是给用户看的一句话。 */
    public static final class Check {
        public final boolean ok;
        public final String base, error;

        Check(boolean ok, String base, String error) {
            this.ok = ok;
            this.base = base;
            this.error = error;
        }
    }

    /**
     * 规范化 + 校验用户填的地址。去空白、去末尾 "/"、去误填的 "/chat/completions" 或 "/models"。
     *
     * @param allowHttp 只有 debug 包传 true
     */
    public static Check check(String input, boolean allowHttp) {
        String s = input == null ? "" : input.trim();
        if (s.length() == 0) return new Check(true, DEFAULT_BASE, null);
        if (s.length() > MAX_LEN) return new Check(false, null, "地址太长");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch <= ' ' || ch == 0x7F) return new Check(false, null, "地址里不能有空格");
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String low = s.toLowerCase(Locale.ROOT);
        if (low.endsWith("/chat/completions")) s = s.substring(0, s.length() - "/chat/completions".length());
        else if (low.endsWith("/models")) s = s.substring(0, s.length() - "/models".length());
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        URI u;
        try {
            u = new URI(s);
        } catch (Exception e) {
            return new Check(false, null, "地址格式不对");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https"), http = scheme.equals("http");
        if (!https && !(http && allowHttp)) {
            return new Check(false, null, allowHttp ? "地址要以 https:// 或 http:// 开头" : "地址要以 https:// 开头");
        }
        if (u.getRawUserInfo() != null) return new Check(false, null, "地址里不能带用户名或密码");
        if (u.getHost() == null || u.getHost().length() == 0) return new Check(false, null, "地址里没有主机名");
        if (u.getRawQuery() != null || u.getRawFragment() != null) return new Check(false, null, "地址里不能带 ? 或 # 后面的参数");
        // scheme 统一小写，其余原样
        s = scheme + s.substring(scheme.length());
        return new Check(true, s, null);
    }

    /** 模型名校验：空 = 默认；字母或数字开头，只含字母、数字和 . _ - : / @ +，最长 100。返回 null = 合法，否则是原因。 */
    public static String checkModel(String model) {
        String m = model == null ? "" : model.trim();
        if (m.length() == 0) return null;
        if (m.length() > 100) return "模型名太长";
        if (!m.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]*")) return "模型名只能用字母、数字和 . _ - : / @ +";
        return null;
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

    public static String base() {
        return sBase;
    }

    public static String chatUrl() {
        return sBase + "/chat/completions";
    }

    public static String modelsUrl() {
        return sBase + "/models";
    }

    public static boolean isDefault() {
        return DEFAULT_BASE.equals(sBase);
    }

    public static boolean allowHttp() {
        return sAllowHttp;
    }

    /** 设置页那一行的摘要：默认 / 主机名（http 时注明「调试」）。 */
    public static String summary() {
        if (isDefault()) return "默认（DeepSeek）";
        String h = host(sBase);
        return (h == null ? sBase : h) + (sBase.regionMatches(true, 0, "http://", 0, 7) ? " · http（仅调试包）" : "");
    }

    /** 日志一行：只有主机名。 */
    public static String logLine() {
        return "[LLM] 主模型地址 " + (isDefault() ? "默认" : "自定义") + " host=" + host(sBase);
    }

    /**
     * 进程内设置（测试 / 读 prefs 后）。不合法回落默认；http 只在 allowHttp 时认。
     */
    public static void setForProcess(String base, boolean allowHttp) {
        sAllowHttp = allowHttp;
        Check c = check(base, allowHttp);
        sBase = c.ok ? c.base : DEFAULT_BASE;
    }

    // ------------------------------------------------------------------ Android（prefs）

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 这个包是不是可调试的（debug 包）：只有它允许 http。 */
    public static boolean debuggable(Context c) {
        try {
            return (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 prefs 读进进程（启动时、改设置后）。release 包里存着的 http 地址不认，回落默认。 */
    public static void load(Context c) {
        try {
            setForProcess(sp(c).getString(K_BASE, ""), debuggable(c));
        } catch (Throwable ignored) {
        }
    }

    /** 保存地址；不合法不改、返回原因。空串 = 恢复默认（删键）。 */
    public static Check save(Context c, String input) {
        boolean dbg = debuggable(c);
        Check r = check(input, dbg);
        if (!r.ok) return r;
        SharedPreferences.Editor e = sp(c).edit();
        if (DEFAULT_BASE.equals(r.base)) e.remove(K_BASE);
        else e.putString(K_BASE, r.base);
        e.apply();
        sAllowHttp = dbg;
        sBase = r.base;
        return r;
    }
}
