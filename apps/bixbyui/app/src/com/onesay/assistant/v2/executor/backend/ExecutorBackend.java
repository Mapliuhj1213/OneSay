package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;

/**
 * 执行后端（P1-7，审计 §5.3-2）。
 *
 * <p>{@code UnifiedExecutor} 只负责：门禁 → Schema 校验 → 组件解析 → 模板填充 → <b>选后端</b> → 回读校验 → 组装回复。
 * "怎么真正执行"由后端决定。新增一种执行方式 = 新增一个后端并注册，不再改 {@code executeInternal} 中段。
 *
 * <p>后端按注册顺序匹配，第一个 {@link #accepts} 返回 true 的执行。
 */
public interface ExecutorBackend {

    /** 日志用名字。 */
    String name();

    /** 是否由本后端执行该能力（只看静态元数据 + 解析出的组件，不得有副作用）。 */
    boolean accepts(Capability cap, ExecRequest req);

    /**
     * 执行。返回 {@link BackendOutcome#terminal} 表示结果已是最终结果（不再做通用回读）；
     * 返回 {@link BackendOutcome#output} 表示执行成功、交给 UnifiedExecutor 做回读校验与回执组装。
     */
    BackendOutcome run(ExecRequest req);
}
