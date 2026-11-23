package com.onesay.assistant.v2.events;

import java.util.List;

/**
 * ★ 1123d B34 / B35：事件任务「选 Wi‑Fi / 选蓝牙设备」列表是空的时候，告诉用户为什么、怎么办（纯函数，JVM 可测）。
 * Android 10 起看 Wi‑Fi 名要定位权限并打开定位；蓝牙关着时系统不给看已配对设备。
 */
public final class EventPickHint {
    private EventPickHint() {}

    public static final String WIFI_NEED_LOCATION = "看不到 Wi‑Fi 名：系统规定要先允许 OneSay 使用定位，并打开手机定位。允许后再点一次；也可以选「列表里没有，自己填…」。";
    public static final String WIFI_EMPTY = "这会儿没读到 Wi‑Fi 名。可以先连上那个 Wi‑Fi 再点一次，或者选「列表里没有，自己填…」。";
    public static final String BT_OFF = "蓝牙关着，系统不给看已配对的设备。打开蓝牙再点一次，或者选「列表里没有，自己填…」。";
    public static final String BT_EMPTY = "没读到已配对的蓝牙设备。可以先在系统蓝牙里配对，或者选「列表里没有，自己填…」。";

    /** 列表里有能选的真名字吗（去掉空串和「&lt;unknown ssid&gt;」）。 */
    public static boolean hasReal(List<String> names) {
        if (names == null) return false;
        for (String n : names) {
            if (n == null) continue;
            String v = n.trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1).trim();
            if (v.length() > 0 && !"<unknown ssid>".equalsIgnoreCase(v)) return true;
        }
        return false;
    }

    /**
     * @param wifi        true = Wi‑Fi，false = 蓝牙
     * @param radioOn     蓝牙开着（Wi‑Fi 时不看）
     * @param locationOk  已有定位权限且定位开着（蓝牙时不看）
     * @return 列表空时给用户的一句话；有可选项返回 null
     */
    public static String emptyHint(boolean wifi, List<String> names, boolean radioOn, boolean locationOk) {
        if (hasReal(names)) return null;
        if (wifi) return locationOk ? WIFI_EMPTY : WIFI_NEED_LOCATION;
        return radioOn ? BT_EMPTY : BT_OFF;
    }
}
