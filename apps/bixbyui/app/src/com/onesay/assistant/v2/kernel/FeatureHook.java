package com.onesay.assistant.v2.kernel;

/**
 * [kernel] 插件内核里所有挂钩点（2026-10-09 第 2 阶段）。
 *
 * <p>每个挂钩点的调用顺序由插件的 {@link FeaturePlugin#priority(FeatureHook)} 决定（小的先调，相同按注册顺序）。
 * 迁移进来的功能用优先级精确复刻了迁移前宿主代码里的硬编码顺序，{@code FeatureKernelTest} 逐条断言。
 */
public enum FeatureHook {
    /** 分诊开关信号（{@link TriageSwitches}）。只读，顺序无关。 */
    TRIAGE,
    /** L0 直达规则（L0RuleRouter.route 最前面，在模糊匹配之前）。第一个非 null 的胜出。 */
    L0,
    /** 路由层本地接管（UnifiedRouter 补槽之后、分诊之前）。第一个非 null 的胜出。 */
    TAKEOVER,
    /** 「不进 AgentLoop」声明（UnifiedRouter.shouldUseAgentLoop）。任一为真即不进。 */
    CLAIMS,
    /** 面板输入拦截（FloatingSheetService.processCommand 最前面）。第一个返回 true 的吃掉这句话。 */
    INTERCEPT,
    /** 跳过识屏指代（任一为真就不交给 ScreenRefHooks）。 */
    BYPASS_SCREENREF,
    /** 按签名接管派发（dispatchInvocation）。第一个返回 true 的接管。 */
    DISPATCH,
    /** 能力描述兜底（CapabilityRegistry 查不到时）。第一个非 null 的胜出。 */
    DESCRIBE,
    /** 问答类回复上屏之后（ask.reply / chat.reply / clarify）。全部调用。 */
    REPLY_SHOWN,
    /** 单步执行成功之后。全部调用。 */
    EXECUTED,
    /** 单步执行失败之后。全部调用。 */
    EXEC_FAILED,
    /** 面板服务 onCreate。全部调用。 */
    SERVICE_CREATE,
    /** 面板服务 onDestroy。全部调用。 */
    SERVICE_DESTROY,
    /** 新一次唤醒、识屏采集之前（ACTION_LISTEN）。全部调用。 */
    WAKE_EARLY,
    /** 新一次唤醒、识屏采集开始之后。全部调用。 */
    WAKE,
    /** 任一能力执行完（单步和 AgentLoop 每一步，成功失败都调；用于习惯记账等）。全部调用。 */
    AFTER_EXEC,
    /** AgentLoop 候选工具追加（识屏工具之后）。全部调用。 */
    AGENT_TOOLS,
    /** AgentLoop 工具描述兜底（识屏之后）。第一个非 null 的胜出。 */
    AGENT_DESCRIBE,
    /** AgentLoop 工具 App 内执行（识屏之后）。第一个非 null 的胜出。 */
    AGENT_EXECUTE,
    /** 执行后端（UnifiedExecutor：邮件后端之后、Intent / Shell 兜底之前）。按顺序全部收集。 */
    BACKENDS,
    /** 控制台卡片（MainActivity：体验开关卡之后、自测输出区之前）。全部调用。 */
    CONSOLE,

    // ---- 第 4 阶段（D-3a）：识屏挂钩整体进内核 ----
    /** AgentLoop 组装用户消息时改写内容（[screenref] 指代命中时附上屏幕上下文）。按顺序链式调用。 */
    AGENT_PROMPT,
    /** AgentLoop 每个工具调用过闸之前的参数兜底 / 拦截（[screenref] screen.* 参数校验）。第一个非 null 的胜出（= 本步不执行，返回值即观测）。 */
    AGENT_PRECHECK,
    /** 面板输入的「屏幕指代」接管（INTERCEPT 之后，且没有任何插件声明 BYPASS_SCREENREF 时）。第一个返回 true 的吃掉这句话。 */
    SCREEN_REFERENCE,
    /** 用户切到文字输入（[screenref] 立刻归还焦点）。全部调用。 */
    TEXT_MODE,
    /** 圈选（S1 圈一下）出结果后给圈选区域出动作芯片（[screenref]）。第一个返回 &gt;0 的胜出。 */
    REGION_CHIPS,
    /** 灵动岛活动（{@link IslandHost}）：只用于声明与 {@code kernel status}，不是分发挂钩。 */
    ISLAND,

    // ---- 2026-10-11 K L2：按键手势（长按 = 记一记，由 hijack_daemon v7 写 trigger「long …」）----
    KEY_GESTURE
}
