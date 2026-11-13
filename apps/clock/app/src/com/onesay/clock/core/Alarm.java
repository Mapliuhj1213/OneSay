package com.onesay.clock.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个闹钟（纯数据）。days：bit0=周日 … bit6=周六；0 = 只响一次。
 * [borrow-1112] 重复规则（{@link RepeatRule}）与暂停：rule 缺省 = WEEKLY（就是原来的 days 语义）。新字段只有非默认值时才写进 JSON，
 * 旧数据读进来、存回去逐字节不变（CoreTest repeatCompat 钉住）。
 */
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

    // ---- [borrow-1112] 重复规则（思路来源：Wake Me / WeckMich，GPL-3.0，只读思路、代码自写）
    /** 规则：{@link RepeatRule#WEEKLY}（默认，按 days）/ BIWEEKLY 大小周 / MONTHLY_NTH 每月第 n 个周几 / EVERY_N 每隔 N 天。 */
    public int rule = RepeatRule.WEEKLY;
    /** 大小周：起始日所在那一周（周一开始）用 days，下一周用 daysB，此后交替。 */
    public int daysB = 0;
    /** 起始日 yyyyMMdd（大小周定哪周算「本周」、每隔 N 天从哪天数起）；0 = 未设。 */
    public int anchor = 0;
    /** 每月第 n 个：1–5；-1 = 最后一个。 */
    public int nth = 1;
    /** 每月第 n 个周几：0=周日 … 6=周六。 */
    public int nthDow = 1;
    /** 每隔 N 天：2–365。 */
    public int everyN = 2;
    // ---- [borrow-1112] 暂停而不删除
    /** 暂停到哪天：这一天（yyyyMMdd）起恢复响铃，之前的都跳过；0 = 没暂停。 */
    public int resume = 0;
    /** 单独跳过的日期（yyyyMMdd，升序）。「跳过接下来 N 次」写成具体日期，改规则也不会挪到别的日子。 */
    public List<Integer> skips = new ArrayList<Integer>();

    /** 是否重复：WEEKLY 看 days（原语义）；其它规则一律重复。 */
    public boolean repeats() { return rule != RepeatRule.WEEKLY || days != 0; }

    /** 有没有生效的暂停设置（只对重复闹钟有意义；单次闹钟忽略暂停）。 */
    public boolean hasPause() { return resume > 0 || !skips.isEmpty(); }

    public boolean onDay(int dow0) { return (days & (1 << dow0)) != 0; }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject().put("id", id).put("h", hour).put("m", minute).put("days", days).put("on", on)
                .put("label", label).put("brief", brief).put("snz", snoozeOn).put("snzMin", snoozeMin)
                .put("vib", vibrate).put("sound", sound);
        // [borrow-1112] 新字段只在非默认时写：旧闹钟存回去和原来一字不差（降级回旧版也读得懂，旧版忽略未知键）
        if (rule != RepeatRule.WEEKLY) {
            o.put("rule", rule).put("daysB", daysB).put("anchor", anchor).put("nth", nth).put("nthDow", nthDow).put("everyN", everyN);
        }
        if (resume > 0) o.put("resume", resume);
        if (!skips.isEmpty()) {
            JSONArray k = new JSONArray();
            for (int d : skips) k.put(d);
            o.put("skips", k);
        }
        return o;
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
        // [borrow-1112] 缺键 = 旧数据 = WEEKLY；坏值夹回合法范围；不认识的规则号退回 WEEKLY（不让闹钟消失）
        int r = o.optInt("rule", RepeatRule.WEEKLY);
        a.rule = RepeatRule.known(r) ? r : RepeatRule.WEEKLY;
        a.daysB = o.optInt("daysB", 0) & 0x7F;
        a.anchor = RepeatRule.validDay(o.optInt("anchor", 0)) ? o.optInt("anchor", 0) : 0;
        int n = o.optInt("nth", 1);
        a.nth = n == -1 ? -1 : clamp(n, 1, 5);
        a.nthDow = clamp(o.optInt("nthDow", 1), 0, 6);
        a.everyN = clamp(o.optInt("everyN", 2), 2, 365);
        a.resume = RepeatRule.validDay(o.optInt("resume", 0)) ? o.optInt("resume", 0) : 0;
        JSONArray k = o.optJSONArray("skips");
        if (k != null) {
            for (int i = 0; i < k.length() && a.skips.size() < Pause.MAX_SKIPS; i++) {
                int d = k.optInt(i, 0);
                if (RepeatRule.validDay(d) && !a.skips.contains(d)) a.skips.add(d);
            }
            java.util.Collections.sort(a.skips);
        }
        if (a.rule != RepeatRule.WEEKLY && a.anchor == 0 && (a.rule == RepeatRule.BIWEEKLY || a.rule == RepeatRule.EVERY_N)) a.rule = RepeatRule.WEEKLY;
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
