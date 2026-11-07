package com.onesay.plan.core;

/**
 * 集中放用户可调设置的出厂默认值和数据保留期（纯 Java）。改默认值只改这里；{@code data/Settings} 读不到用户值时用它们。
 *
 * <p>提醒算法自身的常量（追补窗口、取件提前量、票过期归档）仍在 {@link Reminders}，这里只做引用，免得两处不一致。
 */
public final class Defaults {
    private Defaults() {}

    /** 设置「到点提醒（通知）」默认开。 */
    public static final boolean NOTIFY = true;
    /** 日程默认提前多少分钟提醒（日程自身 lead_min = -1 时用）。 */
    public static final int EVENT_LEAD_MIN = Reminders.DEFAULT_EVENT_LEAD_MIN;
    /** 票提醒档位的设置字符串（逗号分隔分钟）；与 {@link Reminders#DEFAULT_TICKET_LEADS} 一致。 */
    public static final String TICKET_LEADS = "120,30";
    /** 日程软删（OneSay 撤销用）保留多久后物理删除。 */
    public static final long SOFT_DELETE_KEEP_MS = 7L * 86400000L;
}
