package com.onesay.assistant.v2.executor;

/**
 * 应用名 → 组件解析与拉起（P1-7）。生产实现是 {@link IntentExecutor}。
 */
public interface AppResolver {
    /** @return "包名/Activity" 或 null（找不到） */
    String resolveComponent(String appName);

    /** @return 是否成功 startActivity */
    boolean launchComponent(String component);
}
