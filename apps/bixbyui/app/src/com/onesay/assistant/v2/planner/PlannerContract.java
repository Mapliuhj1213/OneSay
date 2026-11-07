package com.onesay.assistant.v2.planner;

/**
 * [planner] 计划 App（com.onesay.plan）后门契约的 OneSay 侧副本——与计划 App core/Contract.java 逐项一致
 * （PlannerContractTest 读两边源码比对）。接口文档：work/bixby-plan/repo/docs/provider-api.md。
 */
public final class PlannerContract {
    private PlannerContract() {}

    public static final String PKG = "com.onesay.plan";
    public static final String AUTHORITY = "com.onesay.plan.provider";
    public static final String BASE_URI = "content://" + AUTHORITY;
    public static final String PERMISSION = "com.onesay.plan.permission.ACCESS";
    public static final String ACTION_CHANGED = "com.onesay.plan.action.DATA_CHANGED";
    public static final String ACTION_OPEN = "com.onesay.plan.action.OPEN";
    public static final String EXTRA_TABLE = "table", EXTRA_OP = "op", EXTRA_ID = "id", EXTRA_BY = "by";

    public static final String T_EVENTS = "events", T_TODOS = "todos", T_TICKETS = "tickets", T_PICKUPS = "pickups",
            T_AUTOMATIONS = "automations", T_RUNS = "runs";
    /** ★ feat/shortcuts：快捷指令定义 / 执行记录（计划 App 结构版本 3）。格式见 shortcut/ShortcutModel。 */
    public static final String T_SHORTCUTS = "shortcuts", T_SHORTCUT_RUNS = "shortcut_runs";
    public static final String P_TODAY = "today";
    public static final String M_PING = "ping", M_IMPORT_BUNDLE = "import_bundle", M_LOG_RUN = "log_run", M_SHORTCUT_ACTIONS = "shortcut_actions";
    public static final String MODE_TIME = "time", MODE_TRIGGER = "trigger";
    public static final String ACT_REMIND = "remind", ACT_AGENT = "agent", ACT_BUILTIN = "builtin";
    public static final String BUNDLE_FORMAT = "bixbyui-raw-v1";
    public static final int SCHEMA_VERSION = 2;
    /** 有快捷指令表的最低结构版本（ping 返回的 schema 小于它 = 计划 App 太旧，快捷指令不可用）。 */
    public static final int SCHEMA_SHORTCUTS = 3;

    /** OneSay 写进去的自动任务 owner（计划 App 界面新建的也记成 bixbyui，表示「由 OneSay 执行」）。 */
    public static final String OWNER_S3 = "bixbyui.s3", OWNER_EVENTS = "bixbyui.events";
}
