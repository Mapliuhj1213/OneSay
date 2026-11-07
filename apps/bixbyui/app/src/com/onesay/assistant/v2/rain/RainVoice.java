package com.onesay.assistant.v2.rain;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [rain] 语音：哪些问句走分钟级降水 + 回答怎么说（纯 Java，JVM 全测）。
 *
 * <h3>接哪些</h3>
 * 「会下雨吗」「什么时候下雨」「雨什么时候停」「要带伞吗」「天气怎么样」（含下雪的同类说法）。
 * <b>不接</b>（留给原来的联网问答）：问别的地方（「北京会下雨吗」——除非就是常用城市）；问超出两小时的时段
 * （今天 / 今晚 / 明天 / 周末 / 下午……：分钟级只有 2 小时，答不全）；提醒 / 闹钟 / 记一下这类办事句。
 *
 * <h3>怎么说</h3>
 * 先用自己从 24 个点算出来的大白话直接回答问的那件事（「现在在下小雨，大概 35 分钟后停」），
 * 再接和风的 summary（「间歇性降雨还将持续90分钟」）——summary 里的分钟数和前一句重复时不再念。
 * 没雨：「未来两小时不会下雨。」查不到如实说（{@link #failure}）。
 */
public final class RainVoice {

    private RainVoice() {}

    public enum Q { WILL, WHEN_START, WHEN_STOP, UMBRELLA, GENERAL }

    public enum Fail { NO_KEY, NO_LOCATION, NO_COVERAGE, NETWORK, KEY_REJECTED, BAD_DATA, OFF }

    static final Pattern STRIP = Pattern.compile("[\\s，。？！?!,.、~～…]+");
    static final Pattern LEAD = Pattern.compile("^(?:那个|请问|问一下|我想问一下|我问一下|帮我看看|帮我看一下|帮我查查|帮我查一下|查一下|查查|看看|看一下|你知道|你说|麻烦你)+",
            Pattern.CASE_INSENSITIVE);
    /** 办事句：交给提醒 / 闹钟 / 记一记。 */
    static final Pattern ERRAND = Pattern.compile("提醒|闹钟|记一下|记下|记得|别忘|备忘|待办|日程|买|下单|播放|放一首|歌");
    /** 超出 2 小时的时段词：交给原来的联网问答（日预报）。 */
    static final Pattern FAR = Pattern.compile("今天|今日|今儿|今晚|今夜|晚上|夜里|半夜|凌晨|早上|上午|中午|下午|傍晚|明天|明早|明晚|后天|大后天|周[一二三四五六日天末]|星期|礼拜|下周|这周|本周|这个月|下个月|[0-9一二两三四五六七八九十]+号|[0-9一二两三四五六七八九十]+点|一整天|这几天|最近|未来[0-9一二三四五六七八九十]+天");

    static final String W = "(?:雨|雪)";
    static final Pattern P_STOP = Pattern.compile(
            W + "(?:还|会|要)?(?:什么时候|啥时候|几点|多久|多长时间|几分钟)(?:才|会|能)?(?:停|不下)"
                    + "|" + W + "(?:还|还会|还要|要)(?:下|持续)(?:多久|多长时间|几分钟|到什么时候)"
                    + "|(?:什么时候|啥时候|几点|多久)(?:才|会|能)?停" + W
                    + "|" + W + "(?:快|会)?停(?:了)?(?:吗|么|嘛|没|没有)"
                    + "|" + W + "会停吗|" + W + "能停吗|" + W + "停了没");
    static final Pattern P_START = Pattern.compile(
            "(?:什么时候|啥时候|几点|多久|多长时间|几分钟)(?:以后|后|之后)?(?:会|才|开始|要|就)?(?:开始)?下" + W
                    + "|" + W + "(?:什么时候|啥时候|几点)(?:来|开始|下)"
                    + "|(?:还有)?多久(?:会|就)?下" + W);
    static final Pattern P_WILL = Pattern.compile(
            "(?:会|要)(?:不会|不要)?下" + W + "(?:吗|么|嘛|不|没)?$"
                    + "|会不会下" + W + "|下不下" + W + "|要不要下" + W + "|是不是(?:要|快)?下" + W
                    + "|(?:要|快)下" + W + "了(?:吗|么|嘛)?$"
                    + "|(?:现在|外面|外边|这边|我这)?(?:在)?下" + W + "(?:了)?(?:吗|么|嘛|没|没有)$"
                    + "|(?:现在|外面|外边|待会|等会|一会儿?|马上)?(?:有|会有)" + W + "(?:吗|么|嘛)$|有没有" + W);
    static final Pattern P_UMB = Pattern.compile("(?:要|需要|用不用|需不需要|要不要|得|该不该|应不应该|用)(?:带|拿)(?:把|个)?(?:雨)?伞|(?:带|拿)(?:把|个)?(?:雨)?伞(?:吗|么|嘛|不)$");
    static final Pattern P_GEN = Pattern.compile("^(?:现在|外面|外边|这边|我这边?|当前|此刻)?(?:的)?天气(?:怎么样|怎样|如何|咋样|好不好|好吗|情况)?(?:啊|呀|呢|哇)?$");

    /** 规范化：去标点空白、去开头客套 / 唤醒词、去句尾语气词。 */
    static String norm(String q) {
        if (q == null) return "";
        String s = STRIP.matcher(q.trim()).replaceAll("");
        s = LEAD.matcher(s).replaceFirst("");
        s = s.replaceAll("(?:啊|呀|呢|哇|呗|哈)+$", "");
        return s;
    }

    /**
     * 这句话问的是不是分钟级降水；不是 → null。
     *
     * @param homeCity 常用城市（问句里只带这个城市名也算本地；可空）
     */
    public static Q classify(String query, String homeCity) {
        String s = norm(query);
        if (s.length() == 0 || s.length() > 24) return null;
        if (ERRAND.matcher(s).find() || FAR.matcher(s).find()) return null;
        String forPlace = s;
        String c = homeCity == null ? "" : homeCity.trim().replaceAll("市$", "");
        if (c.length() > 0) forPlace = forPlace.replace(c + "市", "").replace(c, "");
        if (com.onesay.assistant.v2.search.WeatherQuery.hasPlace(forPlace.replace("我这", "").replace("这边", ""), "")) return null;
        if (P_STOP.matcher(s).find()) return Q.WHEN_STOP;
        if (P_START.matcher(s).find()) return Q.WHEN_START;
        if (P_UMB.matcher(s).find()) return Q.UMBRELLA;
        if (P_WILL.matcher(s).find()) return Q.WILL;
        if (P_GEN.matcher(s).matches()) return Q.GENERAL;
        return null;
    }

    /** 实况（/v7/weather/now）：只在「天气怎么样」时顺带拿；拿不到 = null。 */
    public static final class Now {
        public final String temp, text;

        public Now(String temp, String text) {
            this.temp = temp == null ? "" : temp.trim();
            this.text = text == null ? "" : text.trim();
        }

        String phrase() {
            StringBuilder sb = new StringBuilder("现在");
            if (temp.length() > 0) sb.append(' ').append(temp).append(" 度");
            if (text.length() > 0) sb.append(temp.length() > 0 ? "，" : "").append(text);
            return sb.length() > 2 ? sb.toString() : "";
        }

        public static Now parse(String json) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                if (!"200".equals(o.optString("code"))) return null;
                org.json.JSONObject n = o.optJSONObject("now");
                if (n == null) return null;
                return new Now(n.optString("temp", ""), n.optString("text", ""));
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 回答（大白话）。a 不能为 null（拿不到数据走 {@link #failure}）。 */
    public static String answer(Q q, RainAnalysis a, String summary, Now now) {
        if (q == null) q = Q.WILL;
        String core = core(q, a);
        StringBuilder sb = new StringBuilder();
        if (q == Q.GENERAL && now != null && now.phrase().length() > 0) sb.append(now.phrase()).append("。");
        sb.append(core);
        String sum = cleanSummary(summary);
        if (a.state != RainAnalysis.State.DRY && sum.length() > 0 && !redundant(sum, core)) sb.append(sum).append("。");
        return sb.toString();
    }

    static String core(Q q, RainAnalysis a) {
        String w = a.what();
        String kind = a.intensity();
        switch (a.state) {
            case DRY:
                if (q == Q.UMBRELLA) return "未来两小时不会下雨，不用带伞。";
                if (q == Q.WHEN_STOP) return "现在没在下雨，未来两小时也不会下。";
                return "未来两小时不会下雨。";
            case STARTS: {
                String when = a.startInMin <= 2 ? "马上就要下" + kind + "了" : "大概 " + a.startInMin + " 分钟后开始下" + kind;
                StringBuilder sb = new StringBuilder();
                if (q == Q.WHEN_STOP) sb.append("现在还没下，");
                sb.append(when);
                if (a.intermittent) sb.append("，会下下停停");
                else if (a.stopInMin > 0 && a.stopInMin - a.startInMin <= 30) sb.append("，下一会儿就停");
                if (q == Q.UMBRELLA) sb.append("，出门记得带伞");
                return sb.append("。").toString();
            }
            case STOPS: {
                StringBuilder sb = new StringBuilder("现在在下").append(kind);
                if (q == Q.UMBRELLA) sb.append("，要带伞");
                if (a.stopInMin <= 5) sb.append("，马上就停");
                else sb.append("，大概 ").append(a.stopInMin).append(" 分钟后停");
                if (a.intermittent) {
                    sb.append("，不过之后还会再下");
                    if (a.lastWetEndMin > 0 && a.lastWetEndMin < a.horizonMin) sb.append("，差不多 ").append(a.lastWetEndMin).append(" 分钟后才停透");
                }
                return sb.append("。").toString();
            }
            default: {   // CONTINUES
                StringBuilder sb = new StringBuilder("现在在下").append(kind);
                if (q == Q.UMBRELLA) sb.append("，一定要带伞");
                sb.append("，未来两小时都不会停");
                return sb.append("。").toString();
            }
        }
    }

    /** summary 去掉首尾标点，空白合一。 */
    static String cleanSummary(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("[。．.！!]+$", "").replaceAll("\\s+", "");
        return t.length() > 60 ? "" : t;
    }

    static final Pattern NUM = Pattern.compile("\\d+");

    /**
     * summary 和前一句说的是同一件事 → 重复（不念）：summary 的第一个分钟数已经在前一句里（「15分钟后开始下雨…」vs「大概 15 分钟后开始下小雨」）。
     * summary 没数字：只说「无降水」才算重复。「间歇性降雨还将持续90分钟」的 90 前一句没有 → 照念。
     */
    static boolean redundant(String summary, String core) {
        Set<String> nums = new HashSet<String>();
        Matcher m = NUM.matcher(core);
        while (m.find()) nums.add(m.group());
        Matcher s = NUM.matcher(summary);
        if (s.find()) return nums.contains(s.group());
        return summary.contains("无降水") || summary.contains("不会下");
    }

    /** 查不到时怎么说（如实）。 */
    public static String failure(Fail f) {
        if (f == null) f = Fail.NETWORK;
        switch (f) {
            case NO_KEY: return "分钟级降水还没配好，缺和风天气的 key，在控制台「功能 › 分钟级降水」里填上就能用。";
            case OFF: return "分钟级降水在设置里关着，打开就能问我会不会下雨。";
            case NO_LOCATION: return "我拿不到你现在的位置，没法查附近会不会下雨。";
            case NO_COVERAGE: return "你这里没有分钟级降水数据，和风天气这项只覆盖国内。";
            case KEY_REJECTED: return "和风天气把请求拒了，可能是 key 不对或者额度用完了，去控制台看看。";
            case BAD_DATA: return "和风天气这次没给出能用的数据，等会儿再问我一次吧。";
            default: return "查降水的时候网络出错了，等会儿再问我一次吧。";
        }
    }
}
