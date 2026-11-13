package com.onesay.plan.core;

/**
 * 计划 App 对外契约（Provider 接口）。纯 Java 常量，OneSay 侧的 PlannerContract 与它逐项一致（见 docs/provider-api.md）。
 *
 * <pre>
 * authority   com.onesay.plan.provider
 * 表          content://com.onesay.plan.provider/{events|todos|tickets|pickups|automations|runs|shortcuts|shortcut_runs}[/&lt;_id&gt;]
 * 聚合（只读）content://com.onesay.plan.provider/today
 * 二维码      content://com.onesay.plan.provider/tickets/&lt;_id&gt;/qr   （openFile "r" 读 PNG/JPG；"w" 写）
 * call        ping / import_bundle / import_file / log_run / shortcut_actions
 * 广播        com.onesay.plan.action.DATA_CHANGED（receiverPermission = ACCESS）extras: table, op, id, by
 * 权限        com.onesay.plan.permission.ACCESS（signature）
 * </pre>
 */
public final class Contract {
    private Contract() {}

    public static final String PKG = "com.onesay.plan";
    public static final String AUTHORITY = "com.onesay.plan.provider";
    public static final String BASE_URI = "content://" + AUTHORITY;
    /** signature 级：只有与计划 App 同一证书签名的包（OneSay release）会被系统授予。 */
    public static final String PERMISSION = "com.onesay.plan.permission.ACCESS";
    public static final String ACTION_CHANGED = "com.onesay.plan.action.DATA_CHANGED";
    /** 自动任务到点 / 定义变化，交给 OneSay 重新排程（与 ACTION_CHANGED 相同权限）。 */
    public static final String EXTRA_TABLE = "table", EXTRA_OP = "op", EXTRA_ID = "id", EXTRA_BY = "by";
    public static final String OP_INSERT = "insert", OP_UPDATE = "update", OP_DELETE = "delete", OP_IMPORT = "import";

    public static final String T_EVENTS = "events", T_TODOS = "todos", T_TICKETS = "tickets", T_PICKUPS = "pickups",
            T_AUTOMATIONS = "automations", T_RUNS = "runs";
    /** 快捷指令（见 docs/SHORTCUTS.md）：定义在 shortcuts，执行记录在 shortcut_runs；执行由 OneSay 负责。 */
    public static final String T_SHORTCUTS = "shortcuts", T_SHORTCUT_RUNS = "shortcut_runs";
    public static final String[] TABLES = {T_EVENTS, T_TODOS, T_TICKETS, T_PICKUPS, T_AUTOMATIONS, T_RUNS, T_SHORTCUTS, T_SHORTCUT_RUNS};
    public static final String P_TODAY = "today";

    public static final String M_PING = "ping", M_IMPORT_BUNDLE = "import_bundle", M_IMPORT_FILE = "import_file", M_LOG_RUN = "log_run";
    /** OneSay 推送快捷指令动作清单（arg = JSON，含每个动作在这台设备上能不能用、不能用的原因），计划 App 存成 files/shortcut_actions.json。 */
    public static final String M_SHORTCUT_ACTIONS = "shortcut_actions";

    /** 每张表都有的列。 */
    public static final String C_ID = "_id", C_CREATED = "created_at", C_UPDATED = "updated_at", C_SOURCE = "source", C_SRC_KEY = "src_key",
            C_NOTE = "note";

    // 自动任务
    public static final String MODE_TIME = "time", MODE_TRIGGER = "trigger";
    public static final String ACT_REMIND = "remind", ACT_AGENT = "agent", ACT_BUILTIN = "builtin";
    /** 触发器种类（mode=trigger）。前四种 OneSay 现已能检测（events 包）；其余为预留，OneSay 未检测时显示「助手暂不支持」。 */
    public static final String[] TRIGGERS = {"charging", "wifi", "bluetooth", "notification", "sms", "clip", "screenshot", "app_open",
            "screen_on", "location", "headset", "battery_low", "missed_call", "web_watch"};
    public static final String[] TRIGGERS_DETECTED_TODAY = {"charging", "wifi", "bluetooth", "notification", "web_watch"};

    public static final String[] TICKET_KINDS = {"train", "flight", "bus", "show", "movie", "other"};
    public static final String[] RUN_STATUS = {"ok", "fail", "skip", "missed", "running"};

    // 快捷指令
    /** shortcut_runs.status：requested（计划 App 点击 / 桌面快捷方式发起，等 OneSay 认领）→ queued（OneSay 已排队）→ 结果。 */
    public static final String SC_REQUESTED = "requested", SC_QUEUED = "queued", SC_RUNNING = "running", SC_OK = "ok", SC_FAIL = "fail",
            SC_FALLBACK_OK = "fallback_ok", SC_FALLBACK_FAIL = "fallback_fail", SC_CANCELLED = "cancelled", SC_DENIED = "denied", SC_STALE = "stale";
    public static final String[] SC_STATUS = {SC_REQUESTED, SC_QUEUED, SC_RUNNING, SC_OK, SC_FAIL, SC_FALLBACK_OK, SC_FALLBACK_FAIL, SC_CANCELLED,
            SC_DENIED, SC_STALE};
    /** 触发方式（shortcut_runs.trigger_info / shortcuts.triggers_json 的键）。chat = 对话里命中。 */
    public static final String TRIG_VOICE = "voice", TRIG_TAP = "tap", TRIG_LAUNCHER = "launcher", TRIG_TIME = "time";
    public static final String[] SC_TRIGGERS = {TRIG_VOICE, TRIG_TAP, TRIG_LAUNCHER, TRIG_TIME, "chat", ""};
    /** 定时触发 = 一条 automations（mode=time, action_type=agent），src_key = 这个前缀 + 快捷指令 id。 */
    public static final String SC_TIME_SRC_PREFIX = "shortcut:time:";
    /** steps_json / triggers_json / history_json 的格式版本（docs/SHORTCUTS.md §2）。 */
    public static final int SHORTCUT_FORMAT = 1;

    /** 迁移导入包格式（OneSay 原样导出各库行，映射在计划 App 侧 {@link RawImport}）。 */
    public static final String BUNDLE_FORMAT = "bixbyui-raw-v1";

    /** 2：events 加 deleted_at（OneSay 撤销删除用的软删）。3：加 shortcuts / shortcut_runs（快捷指令）。4：todos 加 strong（[borrow-1112] 强提醒）。 */
    public static final int SCHEMA_VERSION = 4;
}
