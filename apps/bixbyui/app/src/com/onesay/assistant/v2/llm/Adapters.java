package com.onesay.assistant.v2.llm;

/** 按协议取适配器（无状态单例）。 */
public final class Adapters {
    private Adapters() {}

    private static final ProviderAdapter OPENAI = new OpenAiCompatAdapter();
    private static final ProviderAdapter ANTHROPIC = new AnthropicAdapter();
    private static final ProviderAdapter GEMINI = new GeminiAdapter();

    public static ProviderAdapter of(ProviderKind k) {
        if (k == null) return OPENAI;
        switch (k) {
            case ANTHROPIC: return ANTHROPIC;
            case GEMINI: return GEMINI;
            default: return OPENAI;
        }
    }
}
