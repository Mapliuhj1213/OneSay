package com.onesay.assistant.v2.llm;

/**
 * 「主对话」此刻走哪家：协议 + 规范化后的 base + 模型 + 取 Key 的方式（Key 每次现取，改了立即生效）。
 * {@link ProviderRouting#active()} 为 null = 沿用原来的 DeepSeek 通道，行为与引入本层之前完全一致。
 */
public final class ProviderRoute {

    public interface KeySource {
        String key();
    }

    public final String providerId;
    public final ProviderKind kind;
    public final String base;
    public final String model;
    private final KeySource mKey;

    public ProviderRoute(String providerId, ProviderKind kind, String base, String model, KeySource key) {
        this.providerId = providerId;
        this.kind = kind;
        this.base = base;
        this.model = model == null ? "" : model;
        this.mKey = key;
    }

    public String key() {
        String k = mKey == null ? null : mKey.key();
        return k == null ? "" : k.trim();
    }

    public ProviderAdapter adapter() {
        return Adapters.of(kind);
    }

    public String host() {
        return ProviderUrls.host(base);
    }

    @Override public String toString() {
        return providerId + "/" + kind + "/" + model;   // 不含 Key
    }
}
