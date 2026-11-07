package com.onesay.assistant.v2.router;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ int/1102 审计：<b>上一轮感知</b>的改值追问（「改成八点」「再大一点」「调到 10」）。
 *
 * <p>审计里这两句都走了 AMBIGUOUS → LLM 分类 → AgentLoop（8 s 起）。它们的意思完全由上一轮决定：
 * 上一轮刚设了 7 点闹钟 →「改成八点」= 删掉 7 点那个、设 8 点；上一轮刚调了媒体音量 →「再大一点」= 媒体音量再加一档。
 *
 * <h3>怎么做</h3>
 * <ol>
 *   <li>{@link #record}：UnifiedExecutor 每次<b>成功</b>执行一个可改值的单步能力后记下 (签名, 参数, 时刻)；</li>
 *   <li>{@link #resolve}：分诊第 0 层（{@link FastPathTriage}）在快路径模板之后问这里：句子是改值形状、且
 *       {@link #TTL_MS} 内有上一轮 → 引用上一轮的能力和参数，只改句中给的值，零 LLM 出 Invocation（必要时两步计划）；</li>
 *   <li>改值形状但没有可改的上一轮 → 返回 null；分诊规则 {@code 1f-followup-edit} 判 CLARIFY（本地问一句「要改哪一项」），
 *       不进 AgentLoop。</li>
 * </ol>
 * 纯 Java（JVM 可测），Java 7 语法。
 */
public final class FollowupEdit {

    private FollowupEdit() {}

    /** 上一轮有效期：3 分钟（再晚的「改成八点」多半不是接着说的）。 */
    public static final long TTL_MS = 3 * 60 * 1000L;

    /** 上一轮（不可变）。 */
    public static final class Turn {
        public final String sign;
        public final JSONObject args;
        public final long at;

        public Turn(String sign, JSONObject args, long at) {
            this.sign = sign;
            this.args = copy(args);
            this.at = at;
        }

        @Override public String toString() {
            return sign + args;
        }
    }

    private static volatile Turn sLast;

    /** 可改值的能力（其余能力执行成功不覆盖上一轮，免得「查一下电量」把闹钟上下文冲掉）。 */
    static boolean editable(String sign) {
        if (sign == null) return false;
        return sign.equals("clock.alarm.set") || sign.equals("clock.timer.set") || sign.startsWith("volume.media.")
                || sign.startsWith("volume.ring.") || sign.startsWith("volume.notif.") || sign.equals("volume.alarm.set")
                || sign.startsWith("settings.brightness.") || sign.equals("settings.screentimeout.set");
    }

    public static void record(String sign, JSONObject args, long now) {
        if (!editable(sign) || sign.endsWith(".get") || sign.endsWith(".state")) return;
        sLast = new Turn(sign, args, now);
    }

    public static Turn last(long now) {
        Turn t = sLast;
        return t != null && now - t.at <= TTL_MS && now >= t.at ? t : null;
    }

    public static void clearForTest() {
        sLast = null;
    }

    // ------------------------------------------------------------------ 句式

    static final String NUM = "[0-9零一二两三四五六七八九十百]+";
    /** 「改成八点」「改到8点半」「那就晚上9点吧」「换成七点十五」。 */
    static final Pattern TIME = Pattern.compile(
            "^(?:那)?(?:就)?(?:改成|改到|改为|换成|换到|调成|调到|设成|设为|那就|还是)?(早上|上午|中午|下午|傍晚|晚上|凌晨|早晨|清晨|夜里)?"
                    + "(" + NUM + ")(?:点|:)(半|一刻|三刻|" + NUM + "分?)?(?:钟)?(?:吧|啊|呢|好了)?$");
    /** 「改成十分钟」「换成30秒」（计时器 / 息屏）。 */
    static final Pattern DURATION = Pattern.compile(
            "^(?:那)?(?:就)?(?:改成|改到|改为|换成|调成|调到|设成|设为|那就)(" + NUM + ")(秒|秒钟|分钟|分|小时)(?:吧|好了)?$");
    /** 「再大一点」「大点声」「再亮点」「再小一些」。 */
    static final Pattern MORE = Pattern.compile("^(?:再|还要|还是|稍微|再稍微)?(?:调)?(大|高|响|亮|长)(?:一)?(?:点|些|点儿|点声|一点|一些)(?:吧|啊)?$");
    static final Pattern LESS = Pattern.compile("^(?:再|还要|还是|稍微|再稍微)?(?:调)?(小|低|轻|暗|短)(?:一)?(?:点|些|点儿|点声|一点|一些)(?:吧|啊)?$");
    /** 「改成10」「调到50%」（单个整数参数的 set 能力）。 */
    static final Pattern VALUE = Pattern.compile("^(?:那)?(?:就)?(?:改成|改到|改为|调成|调到|设成|设为)(" + NUM + ")(%|格)?(?:吧)?$");

    /** 是不是改值追问的形状（不看上一轮）。分诊规则用它判 CLARIFY。 */
    public static boolean isEditShape(String normalized) {
        if (normalized == null) return false;
        String q = normalized.trim();
        if (q.length() == 0 || q.length() > 16) return false;
        // 不带改字的纯钟点（「八点」）不算：太短、太像别的意思
        Matcher t = TIME.matcher(q);
        boolean timeEdit = t.matches() && (q.contains("改") || q.contains("换") || q.contains("调") || q.contains("设") || q.startsWith("那"));
        return timeEdit || DURATION.matcher(q).matches() || MORE.matcher(q).matches() || LESS.matcher(q).matches()
                || VALUE.matcher(q).matches();
    }

    /**
     * @return 引用上一轮改值后的 Invocation（单步或两步计划）；不是改值句 / 没有上一轮 / 上一轮改不了这个值 → null
     */
    public static Invocation resolve(String utterance, Turn last) {
        if (utterance == null || last == null) return null;
        String q = TriageDecider.normalize(utterance);
        if (!isEditShape(q)) return null;
        String s = last.sign;
        try {
            Matcher m = TIME.matcher(q);
            if (m.matches() && s.equals("clock.alarm.set")) return editAlarm(utterance, last, m);
            m = DURATION.matcher(q);
            if (m.matches()) {
                Integer n = num(m.group(1));
                if (n == null) return null;
                String u = m.group(2);
                int sec = u.startsWith("秒") ? n : u.equals("小时") ? n * 3600 : n * 60;
                if (s.equals("clock.timer.set")) return one("clock.timer.set", last, "seconds", sec, utterance);
                if (s.equals("settings.screentimeout.set")) return one("settings.screentimeout.set", last, "seconds", sec, utterance);
                return null;
            }
            boolean more = MORE.matcher(q).matches();
            boolean less = LESS.matcher(q).matches();
            if (more || less) return step(utterance, last, more);
            m = VALUE.matcher(q);
            if (m.matches()) {
                Integer n = num(m.group(1));
                if (n == null) return null;
                boolean pct = "%".equals(m.group(2)) || q.contains("百分之") || utterance.contains("%") || utterance.contains("％");
                if (s.startsWith("volume.") && (s.endsWith(".set") || s.endsWith(".up") || s.endsWith(".down") || s.endsWith(".mute"))) {
                    String fam = s.substring(0, s.lastIndexOf('.'));
                    int lv = pct ? Math.round(n * 15 / 100f) : n;
                    return one(fam + ".set", last, "level", clamp(lv, 0, 15), utterance);
                }
                if (s.startsWith("settings.brightness.")) {
                    int lv = pct ? Math.round(n * 255 / 100f) : n;
                    return one("settings.brightness.set", last, "level", clamp(lv, 1, 255), utterance);
                }
                if (s.equals("clock.alarm.set") && n >= 0 && n <= 23) {
                    return editAlarmTo(utterance, last, n, 0, false);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 「改成八点」：上一轮闹钟 7:00 → 删 7 点那个 + 设 8 点（保留重复日）。没写时段时沿用上一轮的上下午。 */
    static Invocation editAlarm(String utterance, Turn last, Matcher m) throws Exception {
        String period = m.group(1);
        Integer h = num(m.group(2));
        if (h == null || h > 24) return null;
        int min = 0;
        String mm = m.group(3);
        if (mm != null) {
            if (mm.equals("半")) min = 30;
            else if (mm.equals("一刻")) min = 15;
            else if (mm.equals("三刻")) min = 45;
            else {
                Integer x = num(mm.replace("分", ""));
                if (x == null || x > 59) return null;
                min = x;
            }
        }
        int hour = h % 24;
        boolean explicit = period != null;
        if (explicit) {
            if ((period.equals("下午") || period.equals("傍晚") || period.equals("晚上") || period.equals("夜里")) && hour < 12) hour += 12;
            if (period.equals("中午") && hour < 11) hour += 12;
        }
        return editAlarmTo(utterance, last, hour, min, explicit);
    }

    static Invocation editAlarmTo(String utterance, Turn last, int hour, int min, boolean explicitPeriod) throws Exception {
        int oldH = last.args.optInt("hour", -1);
        if (!explicitPeriod && oldH >= 12 && hour < 12) hour += 12;   // 上一轮是晚上 10 点 →「改成十一点」= 23 点
        JSONObject set = copy(last.args);
        set.put("hour", hour);
        set.put("minute", min);
        Invocation newSet = new Invocation("clock.alarm.set", set, 1.0, Invocation.RouteSource.L0_RULE, utterance);
        if (oldH < 0 || (oldH == hour && last.args.optInt("minute", 0) == min)) return newSet;
        JSONObject del = new JSONObject();
        del.put("hour", oldH);
        List<Invocation> steps = new ArrayList<Invocation>();
        steps.add(new Invocation("alarm.cancel", del, 1.0, Invocation.RouteSource.L0_RULE, utterance));
        steps.add(newSet);
        return Invocation.plan(steps, Invocation.RouteSource.L0_RULE, utterance);
    }

    /** 「再大一点」：按上一轮能力族加减一档。 */
    static Invocation step(String utterance, Turn last, boolean more) throws Exception {
        String s = last.sign;
        if (s.startsWith("volume.media.")) {
            return new Invocation(more ? "volume.media.up" : "volume.media.down", new JSONObject(), 1.0, Invocation.RouteSource.L0_RULE, utterance);
        }
        if (s.startsWith("settings.brightness.")) {
            return new Invocation(more ? "settings.brightness.up" : "settings.brightness.down", new JSONObject(), 1.0,
                    Invocation.RouteSource.L0_RULE, utterance);
        }
        if ((s.startsWith("volume.ring.") || s.startsWith("volume.notif.") || s.equals("volume.alarm.set")) && last.args.has("level")) {
            String fam = s.substring(0, s.lastIndexOf('.'));
            int lv = last.args.optInt("level", 8) + (more ? 2 : -2);
            return one(fam + ".set", last, "level", clamp(lv, 0, 15), utterance);
        }
        if (s.equals("settings.screentimeout.set") && last.args.has("seconds")) {
            int sec = last.args.optInt("seconds", 30);
            return one(s, last, "seconds", clamp(more ? sec * 2 : sec / 2, 5, 3600), utterance);
        }
        return null;
    }

    private static Invocation one(String sign, Turn last, String key, int v, String utterance) throws Exception {
        JSONObject a = sign.equals(last.sign) ? copy(last.args) : new JSONObject();
        a.put(key, v);
        return new Invocation(sign, a, 1.0, Invocation.RouteSource.L0_RULE, utterance);
    }

    static Integer num(String t) {
        Long v = NumberParser.parse(t);
        return v == null || v > 100000 || v < 0 ? null : Integer.valueOf(v.intValue());
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    static JSONObject copy(JSONObject o) {
        JSONObject c = new JSONObject();
        if (o == null) return c;
        try {
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                c.put(k, o.opt(k));
            }
        } catch (Exception ignored) {
        }
        return c;
    }
}
