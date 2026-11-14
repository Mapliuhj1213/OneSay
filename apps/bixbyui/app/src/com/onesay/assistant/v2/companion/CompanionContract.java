package com.onesay.assistant.v2.companion;

/**
 * [companion] 伴侣连接（companion link）契约 —— 助手 ↔ 计划 App ↔ 时钟 App 共用的一份版本化接口。
 * 计划 App 的 core/LinkContract、时钟 App 的 core/LinkContract 与本类逐项一致（CompanionContractTest 读三边源码比对）。
 * 设计文档：docs/COMPANION_LINK.md。
 *
 * <h3>通道</h3>
 * <ul>
 *   <li>计划 / 时钟：signature 权限保护的 ContentProvider {@code call(method, arg, extras)}，方法名按 AppFunctions 风格
 *       （createTask / deleteTask / createAlarm …），结果 Bundle：{@link #K_OK} / {@link #K_CODE} / {@link #K_DATA}（JSON 字符串）。</li>
 *   <li>助手：CONTROL 权限保护的有序广播 {@link #ACTION_QUERY}（extra {@link #K_METHOD}），结果写回 resultCode / resultData（JSON）——
 *       助手清单不放 provider（debug / release 两包的 authority 会撞）。</li>
 *   <li>握手：{@link #M_HELLO} 返回 iface / version / min_client / caps / descriptor（OACP 兼容的能力清单 JSON）。</li>
 * </ul>
 * 纯 Java，JVM 可测。
 */
public final class CompanionContract {
    private CompanionContract() {}

    /** 接口名（握手时双方核对，防止把同名 authority 的别家 provider 当成伴侣）。 */
    public static final String IFACE = "onesay.companion";
    /** 本端实现的接口版本。不兼容的改动才 +1；加方法只加 caps，不改版本。 */
    public static final int VERSION = 1;
    /** 本端能接受的对端最低版本。 */
    public static final int MIN_PEER_VERSION = 1;

    // ---- 包名 / authority / 权限（助手侧视角）----
    public static final String PLAN_PKG = "com.onesay.plan";
    /** 计划 App 沿用已有的数据 provider（表 CRUD 是 v0，call() 函数是 v1）。 */
    public static final String PLAN_AUTHORITY = "com.onesay.plan.provider";
    public static final String PLAN_PERMISSION = "com.onesay.plan.permission.ACCESS";
    public static final String CLOCK_PKG = "com.onesay.clock";
    public static final String CLOCK_AUTHORITY = "com.onesay.clock.link";
    /** 时钟 link provider 复用已有的 signature 权限（助手清单里早已 uses-permission + 同名声明）。 */
    public static final String CLOCK_PERMISSION = "com.onesay.clock.permission.ALARM_CONTROL";
    /** 助手 release 包名；debug 变体 = 它 + {@link #DEBUG_SUFFIX}。 */
    public static final String ASSISTANT_PKG = "com.onesay.assistant";
    public static final String DEBUG_SUFFIX = ".debug";
    /** 计划 / 时钟清单里的 meta-data：构建时用占位符 ${assistantAppId} 填成配对的助手包名。 */
    public static final String META_ASSISTANT = "com.onesay.link.ASSISTANT";

    /** 助手的 CONTROL 权限名随包名走（debug 包 = com.onesay.assistant.debug.permission.CONTROL）。 */
    public static String assistantControlPermission(String assistantPkg) {
        return (assistantPkg == null || assistantPkg.length() == 0 ? ASSISTANT_PKG : assistantPkg) + ".permission.CONTROL";
    }

    // ---- 助手侧广播 ----
    public static final String ACTION_QUERY = "com.onesay.link.action.QUERY";

    // ---- 方法 ----
    public static final String M_HELLO = "link.hello";
    // 计划
    public static final String F_CREATE_TASK = "createTask", F_LIST_TASKS = "listTasks", F_UPDATE_TASK = "updateTask",
            F_COMPLETE_TASK = "completeTask", F_DELETE_TASK = "deleteTask", F_CREATE_EVENT = "createEvent", F_LIST_EVENTS = "listEvents";
    // 时钟
    public static final String F_CREATE_ALARM = "createAlarm", F_LIST_ALARMS = "listAlarms", F_DELETE_ALARM = "deleteAlarm",
            F_TOGGLE_ALARM = "toggleAlarm", F_GET_BRIEF_STATUS = "getBriefStatus";
    // 助手
    public static final String F_GET_ASSISTANT_STATUS = "getAssistantStatus";

    public static final String[] PLAN_CAPS = {F_CREATE_TASK, F_LIST_TASKS, F_UPDATE_TASK, F_COMPLETE_TASK, F_DELETE_TASK, F_CREATE_EVENT, F_LIST_EVENTS};
    public static final String[] CLOCK_CAPS = {F_CREATE_ALARM, F_LIST_ALARMS, F_DELETE_ALARM, F_TOGGLE_ALARM, F_GET_BRIEF_STATUS};
    public static final String[] ASSISTANT_CAPS = {F_GET_ASSISTANT_STATUS};

    /** 助手路由要求计划 App 至少有这些（删 / 改 / 勾 / 列）。 */
    public static final String[] PLAN_REQUIRED = {F_LIST_TASKS, F_DELETE_TASK, F_UPDATE_TASK, F_COMPLETE_TASK};
    /** 助手要求时钟至少有这些。 */
    public static final String[] CLOCK_REQUIRED = {F_LIST_ALARMS, F_CREATE_ALARM, F_DELETE_ALARM, F_TOGGLE_ALARM};

    // ---- Bundle / extras 键 ----
    public static final String K_IFACE = "iface", K_VERSION = "version", K_MIN_CLIENT = "min_client", K_CAPS = "caps",
            K_APP_VERSION = "app_version", K_DESCRIPTOR = "descriptor", K_CLIENT_VERSION = "client_version", K_CLIENT_PKG = "client_pkg";
    public static final String K_OK = "ok", K_CODE = "code", K_DATA = "data", K_ERROR = "error", K_METHOD = "method";
    public static final String X_ID = "id", X_TITLE = "title", X_DUE_AT = "due_at", X_DONE = "done", X_START_AT = "start_at",
            X_END_AT = "end_at", X_LOCATION = "location", X_FROM = "from", X_TO = "to", X_INCLUDE_DONE = "include_done",
            X_HOUR = "hour", X_MINUTE = "minute", X_DAYS = "days", X_ON = "on", X_LABEL = "label";

    /** 结果码：与时钟 AlarmApi 一致（1 成功 / 2 参数不对 / 3 找不到 / 4 不认识）。 */
    public static final int RC_OK = 1, RC_BAD_ARGS = 2, RC_NOT_FOUND = 3, RC_UNKNOWN = 4;

    /** caps 用逗号串传（Bundle 跨进程最稳）。 */
    public static String joinCaps(String[] caps) {
        StringBuilder sb = new StringBuilder();
        for (String c : caps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        return sb.toString();
    }
}
