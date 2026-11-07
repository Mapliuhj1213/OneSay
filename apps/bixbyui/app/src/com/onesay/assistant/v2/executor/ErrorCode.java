package com.onesay.assistant.v2.executor;

/**
 * 结构化执行错误码。
 *
 * 对标成熟产品规范并彻底根治 v1 错误码扁平化问题（BUG-005）：
 * 区分应用不存在、参数缺失、通道超时与回读校验失败等明确原因。
 *
 * <p>（P0-4）：补齐"未配置 / 未安装 / 权限 / 超时 / 用户拒绝 / 取消 / 门禁拒绝 / 未知能力 /
 * 鉴权 / TLS / 网络"等原因。此前它们大多被塞进 {@link #SCHEMA_INVALID}（12 处），上层（UI、AgentLoop）
 * 无法区分"去配置"、"换条路"、"别重试"。每个码现在带：
 * <ul>
 *   <li>{@link #description}：中文短描述（日志/面板）；</li>
 *   <li>{@link #retryable}：同样的调用原样重试<b>有没有可能</b>成功（给模型的换路信号）；</li>
 *   <li>{@link #defaultHint}：用户/模型下一步可以做什么（具体场景可在 ExecutionResult 里覆盖）。</li>
 * </ul>
 * <b>兼容性</b>：只新增、不删除、不改名，已有调用点（router / FloatingSheetService）零改动可编译。
 * 枚举名是 {@code ExecutionResult#toModelObservation()} 对外的稳定协议，<b>不要改名</b>。
 */
public enum ErrorCode {
    /** 执行成功（是否回读核对过见 ExecutionResult.verification） */
    OK("执行成功", false, ""),

    /** 待调用的应用程序未安装或组件无法解析 */
    NOT_FOUND_APP("未找到对应应用程序", false, "确认应用名称，或先安装该应用"),

    /** 语音或调用请求中缺少必要槽位参数 */
    NEED_PARAM("缺少必要参数", false, "向用户追问缺少的参数，不要自行编造默认值"),

    /** 入参违反 JSON Schema 类型或数值边界限制 */
    SCHEMA_INVALID("参数格式不合规", false, "按工具参数说明修正参数后再调用"),

    /** 触发高危操作安全拦截，挂起等待用户二次确认卡 */
    RISK_GATED("高危操作待确认", false, "等待用户在确认卡上授权"),

    /** 用户在安全确认卡中主动取消执行（历史名，新代码请用 {@link #USER_REJECTED}） */
    RISK_REJECTED("用户已取消操作", false, "用户拒绝了该操作，不要重试，换个说法询问用户意图"),

    /** Root 守护执行命令超时（超过 10 秒安全阈值） */
    CHANNEL_TIMEOUT("特权通道执行超时", true, "稍后重试一次；仍超时请检查 root 守护"),

    /** Root 守护进程未启动或文件 IPC 阻塞 */
    CHANNEL_DOWN("特权守护服务离线", false, "root 守护未运行，需要用户重启守护或手机"),

    /** 命令已退出但状态回读未达成期望值（物理未生效） */
    VERIFY_FAILED("状态变更验证失败", true, "操作未生效，可换一种方式（如打开设置页）"),

    /** 底层命令执行返回非零退出码 */
    EXEC_FAILED("底层命令执行失败", true, "换一种方式完成，或如实告知用户"),

    // ------------------------------------------------------------------ 2026-10-07 新增

    /** 功能需要先配置（邮箱、API Key 等）才能用 */
    NOT_CONFIGURED("功能尚未配置", false, "需要用户先完成配置；可改用其它手段（如打开对应应用 + 识屏）"),

    /** 依赖的配套应用未安装（区别于用户点名的应用找不到：{@link #NOT_FOUND_APP}） */
    NOT_INSTALLED("依赖的应用未安装", false, "提示用户安装配套应用，或改用其它手段"),

    /** 缺少系统权限 / 被系统拒绝 */
    PERMISSION_DENIED("缺少权限", false, "需要用户授予权限后再试"),

    /** 网络 / 外部服务超时（区别于 root 通道超时：{@link #CHANNEL_TIMEOUT}） */
    TIMEOUT("操作超时", true, "稍后重试；网络不稳时可改用其它手段"),

    /** 用户在确认卡上明确拒绝 */
    USER_REJECTED("用户拒绝执行", false, "用户拒绝了该操作，不要重试"),

    /** 任务被取消（用户关闭面板 / 新指令打断） */
    CANCELLED("已取消", false, "任务已取消，停止后续步骤"),

    /** 安全门禁直接拒绝（不可信来源触发高权限等），不可通过确认绕过 */
    GATE_DENIED("安全策略拒绝", false, "该操作被安全策略禁止，不要重试，告知用户原因"),

    /** 请求的能力不存在 / 无法分派到任何执行后端 */
    UNKNOWN_CAPABILITY("未知能力", false, "只调用工具列表中存在的工具"),

    /** 凭据错误（授权码/密钥失效）。确定性失败，重试只会触发风控 */
    AUTH_FAILED("鉴权失败", false, "凭据无效或过期，需要用户更新授权码/密钥；不要重试"),

    /** TLS 握手 / 证书校验失败（已不再降级为信任所有证书） */
    TLS_ERROR("安全连接失败", false, "证书校验未通过：检查网络是否被劫持、系统时间是否正确，或更换服务器地址"),

    /** 网络不可达（DNS、连接被拒、被掐断） */
    NETWORK_ERROR("网络不可达", true, "检查网络或代理后重试"),

    /**
     * root 守护按安全策略拒绝了这条命令（exec 守护 v4 回执 rc=77 + DENY，2026-10-25 渗透测试修复）。
     * 命令<b>根本没有执行</b>，和「执行了但失败」不是一回事，原样重试也不会变。
     */
    DAEMON_DENIED("特权守护拒绝执行", false, "命令不在守护白名单内或身份校验未通过，不要重试；换一种方式或如实告知用户");

    public final String description;
    /** 原样重试是否可能成功。 */
    public final boolean retryable;
    /** 默认的下一步提示（面向用户与模型）。 */
    public final String defaultHint;

    ErrorCode(String desc, boolean retryable, String defaultHint) {
        this.description = desc;
        this.retryable = retryable;
        this.defaultHint = defaultHint;
    }
}
