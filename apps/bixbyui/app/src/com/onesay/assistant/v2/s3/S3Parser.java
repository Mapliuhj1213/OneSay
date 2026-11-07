package com.onesay.assistant.v2.s3;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [s3] 定时任务 / 待办的句式解析（纯 Java，JVM 可测；分诊 TriageDecider 与 S3Engine 同源用它）。
 *
 * <h3>接管范围（刻意保守，不改道已有流程）</h3>
 * <ul>
 *   <li><b>新建定时任务</b>：必须有明确的钟点（「8点」「下午3点半」「9:30」）。没有钟点的「提醒我明天交报告」、相对时间的「40分钟后提醒我」、
 *       闹钟类「每天7点叫醒我」都<b>不接管</b>，照旧走原来的闹钟 / 分诊流程；</li>
 *   <li>一次性任务：有「提醒」→ REMINDER；有「帮我 / 给我 / 自动 / 定时」→ AGENT；否则不接管（「明天下午3点开会」是陈述，不是指令）；</li>
 *   <li>重复任务（每天 / 每周X / 工作日 / 周末）：有「提醒」→ REMINDER，否则剩下的话就是到点要跑的指令（AGENT）；</li>
 *   <li>列表 / 取消定时任务；待办的添加（必须带「待办」二字）/ 列表 / 勾销。</li>
 * </ul>
 */
public final class S3Parser {

    private S3Parser() {}

    public enum Type { CREATE, LIST, CANCEL, TODO_ADD, TODO_LIST, TODO_DONE, TODO_CLEAR }

    public static final int KIND_REMINDER = 0, KIND_AGENT = 1;

    /** 一次解析结果。 */
    public static final class Parsed {
        public final Type type;
        public int kind = KIND_REMINDER;
        public int repeat = S3Schedule.ONCE;
        public int weekMask;
        public int hour = -1, minute;
        public long onceAt;
        /** 提醒标题 / 待办内容 / 取消或勾销的检索词。 */
        public String text = "";
        /** AGENT 到点要跑的原话。 */
        public String command = "";
        /** 待办截止（ms，0 = 无）；待办提醒时刻（ms，0 = 不建提醒）。 */
        public long dueAt, remindAt;

        Parsed(Type t) {
            type = t;
        }

        @Override
        public String toString() {
            return type + " kind=" + kind + " repeat=" + repeat + " mask=" + weekMask + " " + hour + ":" + minute
                    + " once=" + onceAt + " text=" + text + " cmd=" + command + " due=" + dueAt;
        }
    }

    // ------------------------------------------------------------------ 正则

    static final String NUM = "[0-9零〇一二两三四五六七八九十]{1,3}";
    static final Pattern TIME = Pattern.compile(
            "(早上|早晨|上午|中午|下午|傍晚|晚上|夜里|凌晨|今晚|明晚|明早|今早)?(" + NUM + ")(?:点钟|点|时|[:：])(半|一刻|三刻|(" + NUM + ")分?)?");
    static final Pattern RELATIVE = Pattern.compile(NUM + "\\s*(分钟|个小时|小时|个钟头|秒)(以?后|之后)");
    static final Pattern ALARM = Pattern.compile("叫醒|叫我起床|闹钟|起床");
    static final Pattern REPEAT_DAILY = Pattern.compile("每天|天天|每日|每晚|每早|每个?早上|每个?晚上");
    static final Pattern REPEAT_WORKDAY = Pattern.compile("每个?工作日|工作日的?|周一到周五");
    static final Pattern REPEAT_WEEKEND = Pattern.compile("每个?周末");
    static final Pattern REPEAT_WEEK = Pattern.compile("每(?:个)?(?:周|星期|礼拜)([一二三四五六日天1-7、和,，]+)");
    static final Pattern DAY_WORD = Pattern.compile("大后天|后天|明天|今天|今晚|明晚|明早|今早");
    static final Pattern WEEKDAY = Pattern.compile("(下下|下个?|这个?|本)?(?:周|星期|礼拜)([一二三四五六日天1-7])");
    static final Pattern MONTH_DAY = Pattern.compile("(" + NUM + ")月(" + NUM + ")(?:日|号)|(?<![月0-9])(" + NUM + ")号");
    static final Pattern REMIND = Pattern.compile("提醒我|提醒一下|提醒|叫我");
    static final Pattern AGENT_MARK = Pattern.compile("帮我|给我|自动|定时");

    static final Pattern LIST = Pattern.compile(
            "^(今天|明天|今晚|这周|最近)?(我)?(还)?(有|现在有)(哪些|什么|几个)(定时任务|提醒)$"   // ★ backlog #5：「今天有什么提醒」（原被日程追问「「什么」几点提醒」）
                    + "|^(我)?(有|现在有)?(哪些|什么|几个)?(定时任务|提醒)(有哪些|都有什么|有几个|列表)?$|^(看看|列一下|列出|查看|查一下)(我的)?(定时任务|提醒)$|^我的(定时任务|提醒)(有哪些)?$|^我(都)?(设|定)了(哪些|什么)(定时任务|提醒)$");
    /**
     * ★ AL-07（2026-11-04）：「不要提醒我了」「别提醒我了」「别再提醒了」「不用提醒我」「停止提醒」= 明确的取消指令（整句没有别的宾语），
     * S3 本地取消直达，不进 AgentLoop。「别提醒我开会」（后面还有事）不在内 —— 那是否定式命令，分诊照旧落 ASK。
     */
    static final Pattern STOP_REMIND = Pattern.compile(
            "^(?:你)?(?:先)?(?:不要|别|不用|甭|不必|不需要)(?:再)?(?:给我|帮我)?提醒(?:我)?(?:了|啦|吧)?$"
                    + "|^(?:停止|停掉|关掉|关闭|取消)(?:掉)?(?:所有|全部)?(?:的)?提醒(?:了|吧)?$");

    /** ★ backlog #5：倒装取消「明天的提醒取消」「吃药那个提醒删了吧」。 */
    static final Pattern CANCEL2 = Pattern.compile("^(.{0,12}?)(的|那个|这个|那条)?(提醒|定时任务)(取消掉?|删掉|删了|删除|不要了|关掉|关了|撤掉)(吧|了)?$");
    /** ★ backlog #5：改已有提醒的时间 / 内容。 */
    static final Pattern REMIND_EDIT = Pattern.compile("提醒(?!我)(的时间)?(改到|改成|改为|挪到|推迟到?|延后到?|提前到?|换到|调到)");

    /** 纯函数：整句是不是「别提醒我了」这类取消提醒。分诊用（不算否定式命令）。 */
    public static boolean isStopRemind(String utterance) {
        return STOP_REMIND.matcher(norm(utterance).replaceAll("[，,。！!]", "")).matches();
    }

    static final Pattern CANCEL = Pattern.compile("^(取消|删掉|删除|关掉|停掉|不用|别再|撤掉)(.*?)(那个|这个|那条)?(定时任务|提醒|任务)?(了)?$");
    static final Pattern TODO_ADD = Pattern.compile("^(记个|记一个|加个|加一个|添加|新建|建个|建一个|写个)?待办(事项)?[：:，, ]*(.+)$");
    static final Pattern TODO_ADD2 = Pattern.compile("^(把)?(.+?)(加到|加进|记到|放进)待办(里|列表)?$");
    static final Pattern TODO_LIST = Pattern.compile("^(我的待办(事项)?(有哪些|列表)?|待办(事项)?(有哪些|列表)|(看看|列一下|查看)(我的)?待办|还有(哪些|什么)待办|我(还)?有(哪些|什么)待办(事项)?)$");
    /** ★ int/1102 审计：「清空待办」「删除所有待办」「把待办全删了」= 批量（目标 = 全部未完成待办），走确认卡。 */
    static final Pattern TODO_CLEAR = Pattern.compile(
            "^(删除|删掉|清空|清除|清掉|删了|清理|全部勾掉|全部完成|勾掉)(所有|全部|所有的|全部的)?(的)?待办(事项|列表)?$"
                    + "|^(把)?(所有|全部)?(的)?待办(事项|列表)?(全|都|全部|统统)(删了|删掉|删除|清空|清掉|勾掉|完成)$"
                    + "|^(所有|全部)待办(事项)?(都)?(删了|删掉|删除|清空|清掉)$");
    static final Pattern TODO_DONE = Pattern.compile("^(勾掉|划掉|完成)(待办)?(.+)$|^(.+?)(做完了|搞定了|完成了|办完了|弄好了|已经做了)$");

    // ------------------------------------------------------------------ 入口

    public static String norm(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("[。！!？?~～\\s]+$", "").replaceAll("^(请|麻烦)?(你)?(帮我)?(设(置)?(一)?个?)?", "");
        t = t.replaceAll("^(小比|bixby|Bixby)[，, ]*", "");
        return t.trim();
    }

    /** 列表 / 取消 / 待办句式（不读库；「X做完了」由 S3Engine 再按库里的待办核实）。null = 不是 S3 句子。 */
    public static Parsed parse(String utterance, long now, TimeZone tz) {
        String q = norm(utterance);
        if (q.length() == 0) return null;
        if (q.endsWith("吗") || q.endsWith("么") || q.endsWith("没有") || q.endsWith("呢")) {
            // 问句：只认列表类
            String q2 = q.replaceAll("(吗|么|没有|呢)$", "");
            if (TODO_LIST.matcher(q2).find()) return new Parsed(Type.TODO_LIST);
            if (LIST.matcher(q2).find()) return new Parsed(Type.LIST);
            return null;
        }
        if (TODO_LIST.matcher(q).find()) return new Parsed(Type.TODO_LIST);
        if (LIST.matcher(q).find()) return new Parsed(Type.LIST);
        if (TODO_CLEAR.matcher(q).matches()) return new Parsed(Type.TODO_CLEAR);
        Matcher m = TODO_DONE.matcher(q);
        boolean doneShape = m.find();
        if (!doneShape) {
            m = TODO_ADD.matcher(q);
            if (m.find()) return todoAdd(m.group(3), now, tz);
            m = TODO_ADD2.matcher(q);
            if (m.find()) return todoAdd(m.group(2), now, tz);
        }
        if (doneShape) {
            String t = m.group(3) != null ? m.group(3) : m.group(4);
            t = clean(t.replaceAll("^(待办|那个|这个)", "").replaceAll("(那个|这个|那条)?(待办)?$", ""));
            if (t.length() > 0 && t.length() <= 30) {
                Parsed p = new Parsed(Type.TODO_DONE);
                p.text = t;
                return p;
            }
        }
        // ★ backlog #5：「把吃药的提醒改到9点」—— 本地不支持改已有提醒，绝不当成新建（原先被建成「把吃药的改到」）→ 不接（交回原流程）
        if (REMIND_EDIT.matcher(q).find()) return null;
        Matcher mc2 = CANCEL2.matcher(q);
        if (mc2.matches()) {   // ★ backlog #5：「明天的提醒取消」「吃药那个提醒删了吧」
            Parsed p = new Parsed(Type.CANCEL);
            p.text = clean(mc2.group(1).replaceAll("(的|那个|这个|那条)$", ""));
            return p;
        }
        if (STOP_REMIND.matcher(q.replaceAll("[，,。！!]", "")).matches()) {
            Parsed p = new Parsed(Type.CANCEL);
            p.text = "";
            return p;
        }
        boolean cancelVerb = q.matches("^(取消|删掉|删除|关掉|停掉|撤掉|不用|别再).*");
        Parsed c = cancelVerb ? null : parseCreate(q, now, tz);
        if (c != null) return c;
        m = CANCEL.matcher(q);
        if (m.find() && (m.group(4) != null || q.contains("那个") || q.contains("提醒") || q.contains("定时"))) {
            String t = clean(m.group(2).replaceAll("(的|那个|这个|那条)$", "").replaceAll("^(提醒我|提醒)", ""));
            Parsed p = new Parsed(Type.CANCEL);
            p.text = t;
            return p;
        }
        return null;
    }

    /** 「X做完了」只有在库里有对应待办时才接管（S3Engine 调）；分诊用这个判断「明确说了待办」的勾销句。 */
    public static boolean explicitTodoDone(String utterance) {
        String q = norm(utterance);
        return q.contains("待办") && TODO_DONE.matcher(q).find();
    }

    // ------------------------------------------------------------------ 新建

    static Parsed parseCreate(String q, long now, TimeZone tz) {
        if (RELATIVE.matcher(q).find() || ALARM.matcher(q).find()) return null;
        // 钟点要在去掉「每周一三五 / 周一 / 10月20号」之后再找：否则「每周一9点」的「一9」会被当成 19 点
        String qt = REPEAT_WEEK.matcher(q).replaceAll("，");
        qt = WEEKDAY.matcher(qt).replaceAll("，");
        qt = MONTH_DAY.matcher(qt).replaceAll("，");
        Matcher tm = TIME.matcher(qt);
        if (!tm.find()) return null;
        int hour = cn(tm.group(2));
        if (hour < 0 || hour > 24) return null;
        int minute = 0;
        String mm = tm.group(3);
        if (mm != null) {
            if ("半".equals(mm)) minute = 30;
            else if ("一刻".equals(mm)) minute = 15;
            else if ("三刻".equals(mm)) minute = 45;
            else if (tm.group(4) != null) minute = cn(tm.group(4));
        }
        if (minute < 0 || minute > 59) return null;
        String part = tm.group(1);
        hour = applyPart(hour, part);
        if (hour < 0 || hour > 23) return null;

        Parsed p = new Parsed(Type.CREATE);
        p.hour = hour;
        p.minute = minute;

        String rest = qt;
        Matcher wm = REPEAT_WEEK.matcher(q);
        if (REPEAT_WORKDAY.matcher(q).find()) {
            p.repeat = S3Schedule.WORKDAY;
            rest = REPEAT_WORKDAY.matcher(rest).replaceFirst("");
        } else if (REPEAT_WEEKEND.matcher(q).find()) {
            p.repeat = S3Schedule.WEEKLY;
            p.weekMask = (1 << 5) | (1 << 6);
            rest = REPEAT_WEEKEND.matcher(rest).replaceFirst("");
        } else if (wm.find()) {
            int mask = 0;
            for (char ch : wm.group(1).toCharArray()) {
                int b = weekBit(ch);
                if (b >= 0) mask |= 1 << b;
            }
            if (mask == 0) return null;
            p.repeat = S3Schedule.WEEKLY;
            p.weekMask = mask;
        } else if (REPEAT_DAILY.matcher(q).find()) {
            p.repeat = S3Schedule.DAILY;
            rest = REPEAT_DAILY.matcher(rest).replaceFirst("");
        }
        rest = rest.replace(tm.group(0), "");

        if (p.repeat == S3Schedule.ONCE) {
            long at = onceAt(q, hour, minute, part, now, tz);
            if (at <= 0) return null;
            p.onceAt = at;
            rest = DAY_WORD.matcher(rest).replaceAll("");
        }
        rest = rest.replaceAll("^(的|在|到|，|,|、|时候|的时候|准时)+", "").replaceAll("^[，, ]+", "");

        Matcher rm = REMIND.matcher(rest);
        if (rm.find()) {
            p.kind = KIND_REMINDER;
            String title = rest.substring(rm.end());
            if (clean(title).length() == 0) title = rest.substring(0, rm.start());
            p.text = clean(title.replaceAll("^(一下|下|要|去|该|记得)+", ""));
            if (p.text.length() == 0) p.text = "到时间了";
            return p;
        }
        boolean agentMark = AGENT_MARK.matcher(rest).find();
        if (p.repeat == S3Schedule.ONCE && !agentMark) return null;
        String cmd = clean(rest.replaceAll("^(都|就|自动|定时|帮我|给我)+", ""));
        if (cmd.length() < 2) return null;
        p.kind = KIND_AGENT;
        p.command = cmd;
        p.text = cmd;
        return p;
    }

    static long onceAt(String q, int hour, int minute, String part, long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        boolean explicitDay = false;
        Matcher dm = DAY_WORD.matcher(q);
        String dayWord = part != null && (part.startsWith("明") || part.startsWith("今")) ? part : null;
        if (dm.find()) dayWord = dm.group(0);
        if (dayWord != null) {
            explicitDay = true;
            if (dayWord.startsWith("明")) c.add(Calendar.DAY_OF_MONTH, 1);
            else if ("后天".equals(dayWord)) c.add(Calendar.DAY_OF_MONTH, 2);
            else if ("大后天".equals(dayWord)) c.add(Calendar.DAY_OF_MONTH, 3);
        } else {
            Matcher md = MONTH_DAY.matcher(q);
            Matcher wd = WEEKDAY.matcher(q);
            if (md.find()) {
                explicitDay = true;
                if (md.group(1) != null) {
                    int mon = cn(md.group(1)), day = cn(md.group(2));
                    if (mon < 1 || mon > 12 || day < 1 || day > 31) return -1L;
                    c.set(Calendar.MONTH, mon - 1);
                    c.set(Calendar.DAY_OF_MONTH, day);
                    c.set(Calendar.HOUR_OF_DAY, hour);
                    c.set(Calendar.MINUTE, minute);
                    if (c.getTimeInMillis() <= now) c.add(Calendar.YEAR, 1);
                    return c.getTimeInMillis();
                }
                int day = cn(md.group(3));
                if (day < 1 || day > 31) return -1L;
                c.set(Calendar.DAY_OF_MONTH, day);
                c.set(Calendar.HOUR_OF_DAY, hour);
                c.set(Calendar.MINUTE, minute);
                if (c.getTimeInMillis() <= now) c.add(Calendar.MONTH, 1);
                return c.getTimeInMillis();
            } else if (wd.find()) {
                explicitDay = true;
                int bit = weekBit(wd.group(2).charAt(0));
                if (bit < 0) return -1L;
                String pre = wd.group(1);
                int todayBit = S3Schedule.bitOf(c.get(Calendar.DAY_OF_WEEK));
                int delta = bit - todayBit;
                if (pre != null && pre.startsWith("下下")) delta += 14;
                else if (pre != null && pre.startsWith("下")) delta += 7;
                c.add(Calendar.DAY_OF_MONTH, delta);
                c.set(Calendar.HOUR_OF_DAY, hour);
                c.set(Calendar.MINUTE, minute);
                if (c.getTimeInMillis() <= now && (pre == null || !pre.startsWith("下"))) c.add(Calendar.DAY_OF_MONTH, 7);
                return c.getTimeInMillis();
            }
        }
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        if (c.getTimeInMillis() <= now) {
            if (explicitDay) return -1L;   // 「今天8点」已经过了：不猜
            // 没说哪天：下午 3 点已过 → 明天；没说上下午的「8点」已过而 20 点没过 → 今晚
            if (part == null && hour < 12) {
                c.set(Calendar.HOUR_OF_DAY, hour + 12);
                if (c.getTimeInMillis() > now) return c.getTimeInMillis();
                c.set(Calendar.HOUR_OF_DAY, hour);
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    static Parsed todoAdd(String body, long now, TimeZone tz) {
        String t = clean(body);
        if (t.length() == 0) return null;
        Parsed p = new Parsed(Type.TODO_ADD);
        // 截止：「周五前交报告」「明天之前」「10月12日前」「明天下午3点前」
        Pattern due = Pattern.compile("((?:下下|下个?|这个?|本)?(?:周|星期|礼拜)[一二三四五六日天1-7]|大后天|后天|明天|今天|今晚|" + NUM + "月" + NUM + "(?:日|号)|"
                + NUM + "号)((?:早上|上午|中午|下午|晚上)?" + NUM + "(?:点|:|：)(?:半|" + NUM + "分?)?)?(之前|以前|前)");
        Matcher m = due.matcher(t);
        if (m.find()) {
            String dayExpr = m.group(1);
            Matcher tm = m.group(2) != null ? TIME.matcher(m.group(2)) : null;
            int hour = 23, minute = 59;
            String part = null;
            boolean timed = false;
            if (tm != null && tm.find()) {
                hour = applyPart(cn(tm.group(2)), tm.group(1));
                part = tm.group(1);
                minute = tm.group(3) == null ? 0 : "半".equals(tm.group(3)) ? 30 : tm.group(4) != null ? cn(tm.group(4)) : 0;
                timed = true;
            }
            long at = onceAt(dayExpr, Math.max(0, Math.min(23, hour)), Math.max(0, Math.min(59, minute)), part, now, tz);
            if (at > 0) {
                p.dueAt = at;
                if (timed) {
                    p.remindAt = at - 30L * 60L * 1000L;   // 有钟点：提前 30 分钟提醒
                } else {
                    Calendar c = Calendar.getInstance(tz);
                    c.setTimeInMillis(at);
                    c.set(Calendar.HOUR_OF_DAY, 9);
                    c.set(Calendar.MINUTE, 0);
                    p.remindAt = c.getTimeInMillis();   // 只有日期：当天 9:00 提醒
                }
                if (p.remindAt <= now) p.remindAt = 0L;
                t = clean(t.replace(m.group(0), ""));
            }
        }
        if (t.length() == 0) return null;
        p.text = t.length() > 40 ? t.substring(0, 40) : t;
        return p;
    }

    // ------------------------------------------------------------------ 小工具

    static int applyPart(int hour, String part) {
        if (part == null) return hour == 24 ? 0 : hour;
        if (part.equals("下午") || part.equals("傍晚") || part.equals("晚上") || part.equals("今晚") || part.equals("明晚") || part.equals("夜里")) {
            return hour < 12 ? hour + 12 : hour;
        }
        if (part.equals("中午")) return hour <= 3 ? hour + 12 : hour;
        if (part.equals("凌晨")) return hour == 12 ? 0 : hour;
        return hour == 24 ? 0 : hour;
    }

    static int weekBit(char ch) {
        switch (ch) {
            case '一': case '1': return 0;
            case '二': case '2': return 1;
            case '三': case '3': return 2;
            case '四': case '4': return 3;
            case '五': case '5': return 4;
            case '六': case '6': return 5;
            case '日': case '天': case '7': return 6;
            default: return -1;
        }
    }

    /** 0–99 的中文 / 阿拉伯数字。 */
    public static int cn(String s) {
        if (s == null || s.length() == 0) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
        }
        String d = "零一二三四五六七八九";
        int ten = s.indexOf('十');
        if (ten >= 0) {
            int hi = ten == 0 ? 1 : digit(s.charAt(ten - 1), d);
            int lo = ten == s.length() - 1 ? 0 : digit(s.charAt(ten + 1), d);
            if (hi < 0 || lo < 0) return -1;
            return hi * 10 + lo;
        }
        int v = 0;
        for (char ch : s.toCharArray()) {
            int x = digit(ch, d);
            if (x < 0) return -1;
            v = v * 10 + x;
        }
        return v;
    }

    private static int digit(char ch, String d) {
        if (ch == '两') return 2;
        if (ch == '〇') return 0;
        if (ch >= '0' && ch <= '9') return ch - '0';
        return d.indexOf(ch);
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("^[，,、：:。 ]+|[，,、：:。 ]+$", "").replaceAll("^(一下)", "").trim();
    }

    /** 两段文字的相似度（字二元组重合，0..1）：取消 / 勾销按它找目标。 */
    public static double similarity(String a, String b) {
        String x = a == null ? "" : a.replaceAll("[\\s，,。的了那个这个提醒任务定时我]", "");
        String y = b == null ? "" : b.replaceAll("[\\s，,。的了那个这个提醒任务定时我]", "");
        if (x.length() == 0 || y.length() == 0) return 0;
        if (y.contains(x) || x.contains(y)) return 1.0;
        java.util.Set<String> bx = grams(x), by = grams(y);
        double bigram = 0;
        if (!bx.isEmpty() && !by.isEmpty()) {
            int hit = 0;
            for (String g : bx) if (by.contains(g)) hit++;
            bigram = hit / (double) Math.min(bx.size(), by.size());
        }
        // 字重合（「读邮件」↔「读一下QQ邮箱未读」：读、邮 命中 2/3）—— 口语点名常常只说两三个关键字
        int ch = 0;
        for (int i = 0; i < x.length(); i++) if (y.indexOf(x.charAt(i)) >= 0) ch++;
        double chars = x.length() >= 2 ? ch / (double) x.length() : 0;
        return Math.max(bigram, chars);
    }

    private static java.util.Set<String> grams(String s) {
        java.util.Set<String> g = new java.util.HashSet<String>();
        if (s.length() == 1) g.add(s);
        for (int i = 0; i + 1 < s.length(); i++) g.add(s.substring(i, i + 2));
        return g;
    }
}
