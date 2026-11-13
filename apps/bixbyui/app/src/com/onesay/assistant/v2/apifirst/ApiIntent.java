package com.onesay.assistant.v2.apifirst;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ feat/api-first：路由层看到的一个意图（种类 + 槽位）。纯 Java，JVM 可测。
 *
 * <p>槽位名统一：q（关键词）、dest（目的地）、from / to（出发 / 到达城市或车站）、date（yyyy-MM-dd）、city、
 * hour / minute、title / begin / end（日历）、name（联系人）、page（设置页）、app（用户点名的 App，可空）。
 */
public final class ApiIntent {

    // ---- 系统接口类 ----
    public static final String ALARM_SET = "alarm.set";
    public static final String TIMER_SET = "timer.set";
    public static final String CALENDAR_ADD = "calendar.add";
    public static final String CONTACT_FIND = "contact.find";
    public static final String SETTINGS_OPEN = "settings.open";
    // ---- 查信息类（有接口先用接口）----
    public static final String WEATHER = "weather.now";
    public static final String WEB_SEARCH = "web.search";
    public static final String GOODS_SEARCH = "goods.search";
    public static final String TRAIN_QUERY = "train.query";
    // ---- 打开到页面类 ----
    public static final String NAV_TO = "nav.to";
    public static final String POI_NEARBY = "poi.nearby";
    // ---- 涉及付款（只到深链 / 收银台为止）----
    public static final String TRAIN_BOOK = "train.book";
    public static final String FOOD_ORDER = "food.order";
    public static final String SHOP_BUY = "shop.buy";

    /** 全部种类（工具 JSON 的 enum 就用它）。 */
    public static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList(
            ALARM_SET, TIMER_SET, CALENDAR_ADD, CONTACT_FIND, SETTINGS_OPEN,
            WEATHER, WEB_SEARCH, GOODS_SEARCH, TRAIN_QUERY,
            NAV_TO, POI_NEARBY,
            TRAIN_BOOK, FOOD_ORDER, SHOP_BUY));

    /** 涉及付款的种类：路由只到 App 深链 / 浏览器页为止，绝不进界面自动化，最后一下交给用户。 */
    public static final List<String> PAYMENT_KINDS = Collections.unmodifiableList(Arrays.asList(
            TRAIN_BOOK, FOOD_ORDER, SHOP_BUY));

    public final String kind;
    private final Map<String, String> mSlots;

    public ApiIntent(String kind, Map<String, String> slots) {
        this.kind = kind == null ? "" : kind.trim();
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (slots != null) {
            for (Map.Entry<String, String> e : slots.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String v = e.getValue().trim();
                if (v.length() > 0) m.put(e.getKey(), v);
            }
        }
        mSlots = Collections.unmodifiableMap(m);
    }

    public static ApiIntent of(String kind, String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new ApiIntent(kind, m);
    }

    public String slot(String name) {
        String v = mSlots.get(name);
        return v == null ? "" : v;
    }

    public boolean has(String name) {
        return slot(name).length() > 0;
    }

    public Map<String, String> slots() {
        return mSlots;
    }

    public boolean known() {
        return KINDS.contains(kind);
    }

    public boolean payment() {
        return PAYMENT_KINDS.contains(kind);
    }

    /** 给日志 / 时间轴看的一句话（不含任何密钥）。 */
    @Override public String toString() {
        return kind + mSlots;
    }
}
