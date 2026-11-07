package com.onesay.plan.core;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 主界面页签与跨天刷新的纯逻辑（从 ui/MainActivity 摘出，JVM 可测；行为与 0.1.4 相同）。
 *
 * <ul>
 *   <li>没指定页签的打开（桌面图标、OneSay「打开计划」）一律进「今天」；指定了合法页签就去那页；不认识的页签保持当前页。</li>
 *   <li>从后台回来（非本 App 内部跳转）回到「今天」。</li>
 *   <li>前台时在下一个本地 0 点 + 1 秒重画；跨天时回到顶部。</li>
 * </ul>
 */
public final class TabNav {
    private TabNav() {}

    /** 打开计划 App 时用的 intent extra 名：{@code tab}。值见 {@link #TABS}。 */
    public static final String EXTRA_TAB = "tab";
    public static final String TODAY = "today";
    /** 页签顺序（底部栏从左到右）；这些字符串也是 {@link #EXTRA_TAB} 的合法取值。 */
    public static final String[] TABS = {TODAY, Contract.T_EVENTS, Contract.T_TODOS, Contract.T_TICKETS, Contract.T_PICKUPS, Contract.T_AUTOMATIONS};

    /** 收到打开意图后的页签：extra 为 null → 今天；合法 → extra；否则保持 current。 */
    public static String resolve(String current, String extraTab) {
        if (extraTab == null) return TODAY;
        for (String x : TABS) if (x.equals(extraTab)) return extraTab;
        return current;
    }

    /** onRestart：本 App 内部跳转回来保持当前页，否则回到今天。 */
    public static String afterRestart(String current, boolean internalNav) {
        return internalNav ? current : TODAY;
    }

    /** 某时刻在 tz 下是哪一天（年 * 1000 + 年内第几天），只用来比较是否跨天。 */
    public static long dayKey(long now, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(now);
        return k.get(Calendar.YEAR) * 1000L + k.get(Calendar.DAY_OF_YEAR);
    }

    /** 距下一个本地 0 点 + 1 秒还有多少毫秒（至少 1000）。 */
    public static long delayToNextMidnight(long now, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(now);
        k.add(Calendar.DAY_OF_MONTH, 1);
        k.set(Calendar.HOUR_OF_DAY, 0);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 1);
        k.set(Calendar.MILLISECOND, 0);
        return Math.max(1000L, k.getTimeInMillis() - now);
    }
}
