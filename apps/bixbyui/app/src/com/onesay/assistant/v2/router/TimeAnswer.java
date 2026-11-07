package com.onesay.assistant.v2.router;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一般时间问答本地直答（「今天几月几号星期几」「明天星期几」「后天几号」「现在几点几分」「今年是哪一年」
 * 「现在是上午还是下午」「这周五是几号」）。读设备时区（Asia/Shanghai 为默认场景）的本机时钟，确定性计算，不进 LLM / AgentLoop。
 * 只认整句就是时间问句的说法（带别的事，如「明天星期几有会」「今天几号发工资」，不接，交给原流程；LLM 提示词另有【当前时间】块）。
 * 纯 Java，JVM 可测：{@link #answer(String, Calendar)} 显式传「现在」。
 */
public final class TimeAnswer {

    private TimeAnswer() {}

    static final String[] WEEK = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
    private static final String TAIL = "(?:呀|啊|呢|啦|了|吗|来着|是吧)*";

    /** 哪天 + 问什么（几号 / 星期几 / 几月几号 / 日期 / 几月几号星期几）。 */
    static final Pattern DAY_Q = Pattern.compile(
            "^(今天|今日|明天|明日|后天|大后天|昨天|昨日|前天)(?:是)?"
                    + "(几月几号星期几|几月几日星期几|几月几号周几|几号星期几|几号周几|星期几几号|周几几号|几月几号|几月几日|几号|几日|星期几|周几|礼拜几|什么日子|日期|哪天)" + TAIL + "$");
    /** 「这周五 / 下周三 / 本周日 是几号」。 */
    static final Pattern WEEKDAY_Q = Pattern.compile(
            "^(这|本|下|下个|上|上个)?(?:周|星期|礼拜)([一二三四五六日天])(?:是)?(几月几号|几号|几日|哪天|几月几日)" + TAIL + "$");
    static final Pattern CLOCK_Q = Pattern.compile("^(?:现在|当前)?(?:是)?(?:几点几分|几点钟|几点了|几点)" + TAIL + "$|^(?:现在)?(?:时间|几点)是多少" + TAIL + "$");
    static final Pattern YEAR_Q = Pattern.compile("^(?:今年|现在)(?:是)?(?:哪一年|哪年|几几年|多少年|公元几年|哪个年份)" + TAIL + "$");
    static final Pattern AMPM_Q = Pattern.compile("^(?:现在)?(?:是)?(?:上午还是下午|早上还是晚上|白天还是晚上|上午下午)" + TAIL + "$");
    static final Pattern MONTH_Q = Pattern.compile("^(?:现在|这个月|本月)(?:是)?(?:几月|几月份|哪个月)" + TAIL + "$");

    public static String answer(String query) {
        return answer(query, Calendar.getInstance(TimeZone.getDefault()));
    }

    /** null = 不是一般时间问句。 */
    public static String answer(String query, Calendar now) {
        if (query == null || now == null) return null;
        String q = query.trim().replaceAll("[\\s\\p{Punct}，。？！、：；“”‘’\"'`]", "").replaceAll("^(?:嗨|嘿|请问|问一下|告诉我)+", "");
        if (q.length() == 0 || q.length() > 16) return null;
        Matcher m = DAY_Q.matcher(q);
        if (m.matches()) {
            int off = offset(m.group(1));
            Calendar d = (Calendar) now.clone();
            d.add(Calendar.DAY_OF_MONTH, off);
            String what = m.group(2);
            String dayWord = dayWord(m.group(1));
            String date = (d.get(Calendar.MONTH) + 1) + "月" + d.get(Calendar.DAY_OF_MONTH) + "日";
            String week = WEEK[d.get(Calendar.DAY_OF_WEEK) - 1];
            boolean wantWeek = what.contains("星期") || what.contains("周") || what.contains("礼拜");
            boolean wantDate = what.contains("号") || what.contains("日") || what.contains("月") || what.contains("哪天") || what.contains("日期");
            if (off == 0) return "今天是" + d.get(Calendar.YEAR) + "年" + date + "，" + week + "。";
            if (wantWeek && !wantDate) return dayWord + "是" + week + "（" + date + "）。";
            return dayWord + "是" + date + "，" + week + "。";
        }
        m = WEEKDAY_Q.matcher(q);
        if (m.matches()) {
            int target = "一二三四五六日天".indexOf(m.group(2));   // 0 = 周一 … 5 = 周六，6 / 7 = 周日
            int wantMonFirst = target >= 6 ? 6 : target;
            int todayMonFirst = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // 周一 = 0
            String pre = m.group(1) == null ? "" : m.group(1);
            int weekShift = pre.startsWith("下") ? 7 : pre.startsWith("上") ? -7 : 0;
            Calendar d = (Calendar) now.clone();
            d.add(Calendar.DAY_OF_MONTH, wantMonFirst - todayMonFirst + weekShift);
            String label = (pre.length() == 0 ? "这" : pre.replace("个", "")) + "周" + m.group(2).replace("天", "日");
            return label + "是" + (d.get(Calendar.MONTH) + 1) + "月" + d.get(Calendar.DAY_OF_MONTH) + "日" + "。";
        }
        if (CLOCK_Q.matcher(q).matches()) {
            return "现在是 " + two(now.get(Calendar.HOUR_OF_DAY)) + ":" + two(now.get(Calendar.MINUTE)) + "。";
        }
        if (YEAR_Q.matcher(q).matches()) return "今年是" + now.get(Calendar.YEAR) + "年。";
        if (MONTH_Q.matcher(q).matches()) return "现在是" + (now.get(Calendar.MONTH) + 1) + "月。";
        if (AMPM_Q.matcher(q).matches()) {
            int h = now.get(Calendar.HOUR_OF_DAY);
            String part = h < 5 ? "凌晨" : h < 12 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
            return "现在是" + part + " " + two(h) + ":" + two(now.get(Calendar.MINUTE)) + "。";
        }
        return null;
    }

    static int offset(String w) {
        if (w.startsWith("明")) return 1;
        if (w.equals("后天")) return 2;
        if (w.equals("大后天")) return 3;
        if (w.startsWith("昨")) return -1;
        if (w.equals("前天")) return -2;
        return 0;
    }

    static String dayWord(String w) {
        if (w.equals("明日")) return "明天";
        if (w.equals("昨日")) return "昨天";
        if (w.equals("今日")) return "今天";
        return w;
    }

    static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** AgentLoop 用户消息前的一行本机时间（不进系统提示词：保持前缀缓存稳定）。 */
    public static String nowLine(Calendar now) {
        TimeZone tz = now.getTimeZone();
        return "（本机当前时间：" + TimeContext.describe(now) + "，时区 " + (tz == null ? "" : tz.getID()) + "。说到今天 / 明天 / 周几 / 几点一律以此为准。）";
    }
}
