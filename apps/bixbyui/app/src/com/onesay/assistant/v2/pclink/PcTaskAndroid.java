package com.onesay.assistant.v2.pclink;

import android.content.Context;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * [overnight-1119] 手机 → 电脑任务（pc_task / pc_task_status）的 Android 一侧：语音口令和控制台共用。
 * 电脑立刻回「收到了」，在后台按这台手机的权限一步步做（每一步照电脑上的规则判，危险的那一下在电脑或这台手机上弹卡）；
 * 做完电脑推一条通知回手机（要手机上开着「在手机上弹通知」），也可以随时问「电脑任务做完了吗」。阻塞，放后台线程调。
 */
public final class PcTaskAndroid {

    private PcTaskAndroid() {}

    public static String handOff(Context c, String goal) {
        String g = goal == null ? "" : goal.trim();
        if (g.length() == 0) return "要电脑做什么？比如「让电脑把桌面上的截图整理到一个文件夹」。";
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("goal", g.length() > 1000 ? g.substring(0, 1000) : g);
        PcLanClient.Result r = PcLink.runNow(c, "pc_task", a, 2000, 15000);
        return line(r, true);
    }

    public static String status(Context c) {
        PcLanClient.Result r = PcLink.runNow(c, "pc_task_status", null, 2000, 10000);
        return line(r, false);
    }

    /** 电脑回话 → 一句给人看 / 念的话（纯 Java，JVM 可测）。 */
    @SuppressWarnings("unchecked")
    public static String line(PcLanClient.Result r, boolean start) {
        if (r == null) return "电脑没回应。";
        if (!r.reached()) {
            if ("unpaired".equals(r.fail) || "off".equals(r.fail)) return r.text + "。";
            if ("auth".equalsIgnoreCase(r.fail)) return "电脑拒绝了连接，配对可能失效了，请重新扫码。";
            return "电脑不在线（局域网里没找到），任务没交出去。";
        }
        if (!r.ok) {
            Object why = r.data.get("reason_zh");
            String t = r.text.length() > 0 ? r.text : "电脑没接这个任务";
            return why != null && String.valueOf(why).length() > 0 && !t.contains(String.valueOf(why)) ? t + "（" + why + "）" : t;
        }
        if (start) return r.text.length() > 0 ? r.text : "电脑收到了，开始做。做完会把结果发回手机。";
        StringBuilder b = new StringBuilder(r.text);
        Object lines = r.data.get("step_lines");
        if (lines instanceof List && !((List<Object>) lines).isEmpty()) {
            List<Object> l = (List<Object>) lines;
            b.append("\n");
            int from = Math.max(0, l.size() - 6);
            if (from > 0) b.append("…前面还有 ").append(from).append(" 步\n");
            for (int i = from; i < l.size(); i++) b.append(l.get(i)).append(i == l.size() - 1 ? "" : "\n");
        }
        return b.toString();
    }
}
