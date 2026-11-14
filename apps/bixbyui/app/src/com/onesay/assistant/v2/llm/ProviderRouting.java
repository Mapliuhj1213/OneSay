package com.onesay.assistant.v2.llm;

/**
 * 进程内「主对话服务商」开关（纯 Java）。宿主启动 / 改设置时由 {@code ProviderStore.apply} 设置；
 * 默认 null = 原 DeepSeek 通道不变。{@code LlmClient} 只在发往主对话地址的请求上查这里。
 */
public final class ProviderRouting {
    private ProviderRouting() {}

    private static volatile ProviderRoute sActive;

    public static ProviderRoute active() {
        return sActive;
    }

    /** null = 回到原 DeepSeek 通道。 */
    public static void set(ProviderRoute r) {
        sActive = r;
    }

    /** 主对话用的 Key：走了别家时返回那家的 Key（可能是空串），否则 null（调用方照旧读 DeepSeek Key）。 */
    public static String mainKeyOverride() {
        ProviderRoute r = sActive;
        return r == null ? null : r.key();
    }

    /** 这个地址是否是当前服务商的主机（https）；用于放行带 Key 的请求。 */
    public static boolean allowsHost(String url) {
        ProviderRoute r = sActive;
        if (r == null || url == null || !ProviderUrls.keySafe(url)) return false;
        String h = ProviderUrls.host(url);
        return h != null && h.equals(r.host());
    }
}
