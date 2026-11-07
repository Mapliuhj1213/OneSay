package com.onesay.assistant.v2.jobs;

/**
 * 定时 / 事件任务的权限项（权限卡逐项勾选）。{@link #preGrantable} = false 的项不能预先授权：
 * 发出邮件（sendDraft）、付款、红卡级操作 —— 每次执行都单独弹卡片确认（用户 10-22 23:57 定）。
 */
public enum Perm {
    READ_TODO("读待办", true),
    READ_CALENDAR("读日历", true),
    READ_MAIL("读邮件", true),
    WEB_SEARCH("联网搜索（含天气）", true),
    READ_DEVICE("读手机状态（电量 / 网络等）", true),
    /** 第 7 项：通知触发（只读白名单 App 的通知，内容按污染数据处理）。放在 READ_DEVICE 后面：encode 按枚举顺序，旧授权串照样解析。 */
    READ_NOTIFICATION("读通知（白名单 App）", true),
    WRITE_CALENDAR("写日历", true),
    /** 2026-10-23 A+B 合并：B 线 conn.google.tasks_create / tasks_update（Google Tasks 新建 / 修改）。按名字存，加在中间不影响旧授权串。 */
    WRITE_TODO("写待办", true),
    MAIL_DRAFT("建邮件草稿", true),
    SYSTEM_SETTINGS("调系统设置", true),
    OPEN_APP("打开应用 / 页面", true),
    SHELL("执行 shell 命令（root）", true),
    MAIL_SEND("发出邮件", false),
    PAYMENT("付款", false),
    RED_ACTION("高风险操作（删除 / 短信 / 电话等）", false),
    /**
     * 远程（MCP）连接器的工具一律不可预授权 —— 等级来自用户手填白名单、名字由远端定，
     * 本机核实不了它会不会对外发送，所以无人值守时每次都在岛上确认。加在末尾：按名字 / 按序存的旧授权串都不受影响。
     */
    REMOTE_TOOL("远程服务器工具（可能对外发送）", false);

    public final String label;
    public final boolean preGrantable;

    Perm(String label, boolean preGrantable) {
        this.label = label;
        this.preGrantable = preGrantable;
    }

    public static Perm parse(String s) {
        if (s == null) return null;
        try {
            return Perm.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
