package com.onesay.assistant.v2.companion;

/** [companion] 旧闹钟广播动作 ↔ 连接函数名（时钟 core/LinkContract 里有同一张表）。纯 Java。 */
public final class CompanionAlarm {
    private CompanionAlarm() {}

    static final String[][] MAP = {
            {"com.onesay.clock.action.ALARM_SET", CompanionContract.F_CREATE_ALARM},
            {"com.onesay.clock.action.ALARM_QUERY", CompanionContract.F_LIST_ALARMS},
            {"com.onesay.clock.action.ALARM_CANCEL", CompanionContract.F_DELETE_ALARM},
            {"com.onesay.clock.action.ALARM_TOGGLE", CompanionContract.F_TOGGLE_ALARM},
    };

    /** 广播动作 → 函数名；不认识返回 null。 */
    public static String function(String action) {
        for (String[] m : MAP) if (m[0].equals(action)) return m[1];
        return null;
    }

    /** 函数名 → 广播动作；不认识返回 null。 */
    public static String action(String fn) {
        for (String[] m : MAP) if (m[1].equals(fn)) return m[0];
        return null;
    }
}
