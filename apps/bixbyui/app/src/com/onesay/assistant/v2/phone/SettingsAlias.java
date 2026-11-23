package com.onesay.assistant.v2.phone;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ 1123-dui B3/B22：设置里常用项在不同厂商手机上的叫法（同一项的几个名字）。
 * 代操作找「关于手机」时也认「我的手机」「关于本机」等；按名字都找不到就用设置页顶部的搜索框搜。
 */
public final class SettingsAlias {
    private SettingsAlias() {}

    static final String[][] GROUPS = {
            {"关于手机", "我的手机", "关于本机", "关于设备", "手机信息", "本机信息", "设备信息", "关于平板", "我的设备"},
            {"蓝牙", "蓝牙设置", "Bluetooth"},
            {"显示", "显示和亮度", "显示与亮度", "屏幕显示", "显示与屏幕", "亮度"},
            {"WLAN", "Wi-Fi", "WiFi", "无线网络", "无线局域网"},
            {"声音", "声音和振动", "声音与振动", "提示音和振动"},
            {"电池", "电池和性能", "电量和性能", "省电与电池"},
            {"应用", "应用管理", "应用和服务", "应用程序", "应用设置"},
            {"通知", "通知和状态栏", "通知与状态栏", "通知管理"},
    };

    /** 这个词所在那一组的全部叫法（自己排第一）；不在表里只返回自己。 */
    public static List<String> namesFor(String w) {
        List<String> out = new ArrayList<String>();
        if (w == null) return out;
        String k = w.trim();
        if (k.length() == 0) return out;
        out.add(k);
        for (String[] g : GROUPS) {
            boolean hit = false;
            for (String n : g) if (n.equalsIgnoreCase(k)) { hit = true; break; }
            if (!hit) continue;
            for (String n : g) if (!containsIgnoreCase(out, n)) out.add(n);
        }
        return out;
    }

    /** 任务里提到了表里的设置项 → 给模型的一句提示（别名 + 找不到就搜）；没提到返回空串。 */
    public static String hintFor(String goal) {
        if (goal == null || goal.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        for (String[] g : GROUPS) {
            String hit = null;
            for (String n : g) if (n.length() >= 2 && goal.indexOf(n) >= 0 && !"应用".equals(n) && !"通知".equals(n) && !"显示".equals(n)) { hit = n; break; }
            if (hit == null) continue;
            if (b.length() > 0) b.append('；');
            b.append("「").append(hit).append("」在这台手机上也可能叫");
            int c = 0;
            for (String n : g) {
                if (n.equals(hit)) continue;
                b.append(c++ == 0 ? "" : " / ").append("「").append(n).append("」");
            }
        }
        if (b.length() == 0) return "";
        return "设置项叫法提示：" + b + "。按这些名字在当前页都找不到时，点设置页顶部的搜索框搜名字，别乱翻也别放弃。\n";
    }

    private static boolean containsIgnoreCase(List<String> l, String s) {
        for (String x : l) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }
}
