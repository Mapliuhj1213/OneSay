/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (app settings/ProviderConfig.Kind) to Java 7; the OpenAI Responses
 * kind is not carried over. Upstream: 8d61573.
 */
package com.onesay.assistant.v2.llm;

/** 自带 Key 的大模型服务商说的接口协议（每种一个适配器）。 */
public enum ProviderKind {
    /** OpenAI Chat Completions 兼容（DeepSeek、通义千问 / 百炼兼容模式、Kimi、智谱、硅基流动、OpenRouter、自建服务…）。 */
    OPENAI_COMPAT,
    /** Anthropic Messages API。 */
    ANTHROPIC,
    /** Google Gemini generateContent 原生接口。 */
    GEMINI
}
