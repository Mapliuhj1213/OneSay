package com.onesay.assistant.v2.apifirst;

/**
 * ★ feat/api-first（2026-11-11）：执行一个意图时可走的路，按优先级从高到低。
 *
 * <pre>
 *   SYSTEM     ① 系统接口 / ContentProvider（闹钟 AlarmClock、日历 CalendarContract、联系人、设置页 Settings …）
 *   KEYED_API  ② 已配置的接口（个人版：和风天气、Tavily 搜索、海纳商品；没有密钥 / 开源版默认关 → 跳过）
 *   DEEPLINK   ③ App 深链 / URL scheme（只打开到对应页面）
 *   BROWSER    ③′ 深链失败时的浏览器 https 链接（同属第 ③ 档）
 *   GUI        ④ 界面自动化（现有的点屏代理 phone.operate）
 * </pre>
 *
 * 思路借鉴自魅族 Aicy 的「接口 / 连接器优先，点屏兜底」（只借思路，Aicy 闭源，代码自写）。
 */
public enum RouteTier {
    SYSTEM(1, "系统接口"),
    KEYED_API(2, "已配置接口"),
    DEEPLINK(3, "App 深链"),
    BROWSER(3, "浏览器链接"),
    GUI(4, "界面操作");

    /** 档位序号（①–④；深链与浏览器链接同属 ③）。 */
    public final int rank;
    public final String label;

    RouteTier(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    /** 「①」「②」「③」「④」。 */
    public String mark() {
        switch (rank) {
            case 1: return "①";
            case 2: return "②";
            case 3: return "③";
            default: return "④";
        }
    }
}
