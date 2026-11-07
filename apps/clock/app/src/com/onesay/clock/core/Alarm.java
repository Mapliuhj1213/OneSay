package com.onesay.clock.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一个闹钟（纯数据）。days：bit0=周日 … bit6=周六；0 = 只响一次。 */
public final class Alarm {
    public int id;
    public int hour = 7, minute = 0;
    public int days = 0;
    public boolean on = true;
    public String label = "";
    /** 智能播报闹钟。 */
    public boolean brief = true;
    public boolean snoozeOn = true;
    public int snoozeMin = 5;
    public boolean vibrate = true;
    /** 铃声 uri；空 = 系统默认闹钟铃声。 */
    public String sound = "";

    public boolean repeats() { return days != 0; }

    public boolean onDay(int dow0) { return (days & (1 << dow0)) != 0; }

    public JSONObject toJson() throws Exception {
        return new JSONObject().put("id", id).put("h", hour).put("m", minute).put("days", days).put("on", on)
                .put("label", label).put("brief", brief).put("snz", snoozeOn).put("snzMin", snoozeMin)
                .put("vib", vibrate).put("sound", sound);
    }

    public static Alarm fromJson(JSONObject o) {
        Alarm a = new Alarm();
        a.id = o.optInt("id");
        a.hour = clamp(o.optInt("h", 7), 0, 23);
        a.minute = clamp(o.optInt("m", 0), 0, 59);
        a.days = o.optInt("days", 0) & 0x7F;
        a.on = o.optBoolean("on", true);
        a.label = o.optString("label", "");
        a.brief = o.optBoolean("brief", true);
        a.snoozeOn = o.optBoolean("snz", true);
        a.snoozeMin = clamp(o.optInt("snzMin", 5), 1, 60);
        a.vibrate = o.optBoolean("vib", true);
        a.sound = o.optString("sound", "");
        return a;
    }

    public static String listToJson(List<Alarm> as) throws Exception {
        JSONArray a = new JSONArray();
        for (Alarm x : as) a.put(x.toJson());
        return a.toString();
    }

    public static List<Alarm> listFromJson(String s) {
        List<Alarm> out = new ArrayList<Alarm>();
        if (s == null || s.length() == 0) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(fromJson(o));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static final String[] DAY = {"日", "一", "二", "三", "四", "五", "六"};

    /** 列表上的重复描述：「每天」「工作日」「周末」「一 三 五」「只响一次」。 */
    public static String daysLabel(int days) {
        if (days == 0) return "只响一次";
        if (days == 0x7F) return "每天";
        if (days == 0x3E) return "工作日";
        if (days == 0x41) return "周末";
        StringBuilder sb = new StringBuilder("每周");
        for (int i = 1; i <= 7; i++) { int d = i % 7; if ((days & (1 << d)) != 0) sb.append(DAY[d]); }
        return sb.toString();
    }
}
