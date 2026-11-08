/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (app settings/ProviderPresets.kt) to Java 7; capability/caching
 * hints dropped, the list trimmed to providers reachable without a separate sign-in. Upstream: 8d61573.
 */
package com.onesay.assistant.v2.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 预置服务商（只放公开的官方地址；Key、模型由用户自己填）。{@link #CUSTOM_OPENAI} 是「自定义 OpenAI 兼容」：地址也由用户填。
 * {@link #id} 写进设置，改了会丢用户配置，只增不改。
 */
public final class ProviderPreset {
    public final String id;
    public final String title;
    public final ProviderKind kind;
    /** 官方 base URL；自定义项为空串。 */
    public final String defaultBase;
    /** 建议的模型名（可空 = 让用户填）。 */
    public final String defaultModel;
    /** 「去申请」打开的控制台地址（可空）。 */
    public final String consoleUrl;

    public ProviderPreset(String id, String title, ProviderKind kind, String defaultBase, String defaultModel, String consoleUrl) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.defaultBase = defaultBase == null ? "" : defaultBase;
        this.defaultModel = defaultModel == null ? "" : defaultModel;
        this.consoleUrl = consoleUrl == null ? "" : consoleUrl;
    }

    public static final String DEEPSEEK = "deepseek", DASHSCOPE = "dashscope", MOONSHOT = "moonshot", ZHIPU = "zhipu",
            SILICONFLOW = "siliconflow", OPENROUTER = "openrouter", CUSTOM_OPENAI = "custom_openai",
            ANTHROPIC = "anthropic", GEMINI = "gemini";

    private static final List<ProviderPreset> ALL;

    static {
        List<ProviderPreset> l = new ArrayList<ProviderPreset>();
        l.add(new ProviderPreset(DEEPSEEK, "DeepSeek", ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com", "deepseek-flash",
                "https://platform.deepseek.com/api_keys"));
        l.add(new ProviderPreset(DASHSCOPE, "通义千问 · 百炼（兼容模式）", ProviderKind.OPENAI_COMPAT,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "https://bailian.console.aliyun.com/"));
        l.add(new ProviderPreset(MOONSHOT, "Kimi · 月之暗面", ProviderKind.OPENAI_COMPAT, "https://api.moonshot.cn/v1", "",
                "https://platform.moonshot.cn/console/api-keys"));
        l.add(new ProviderPreset(ZHIPU, "智谱 GLM", ProviderKind.OPENAI_COMPAT, "https://open.bigmodel.cn/api/paas/v4", "",
                "https://open.bigmodel.cn/usercenter/apikeys"));
        l.add(new ProviderPreset(SILICONFLOW, "硅基流动", ProviderKind.OPENAI_COMPAT, "https://api.siliconflow.cn/v1", "",
                "https://cloud.siliconflow.cn/account/ak"));
        l.add(new ProviderPreset(OPENROUTER, "OpenRouter", ProviderKind.OPENAI_COMPAT, "https://openrouter.ai/api/v1", "",
                "https://openrouter.ai/keys"));
        l.add(new ProviderPreset(CUSTOM_OPENAI, "自定义 OpenAI 兼容", ProviderKind.OPENAI_COMPAT, "", "", ""));
        l.add(new ProviderPreset(ANTHROPIC, "Anthropic（Claude）", ProviderKind.ANTHROPIC, "https://api.anthropic.com", "",
                "https://console.anthropic.com/settings/keys"));
        l.add(new ProviderPreset(GEMINI, "Google Gemini", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "",
                "https://aistudio.google.com/apikey"));
        ALL = Collections.unmodifiableList(l);
    }

    public static List<ProviderPreset> all() {
        return ALL;
    }

    public static ProviderPreset get(String id) {
        if (id == null) return null;
        for (ProviderPreset p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    @Override public String toString() {
        return id;
    }
}
