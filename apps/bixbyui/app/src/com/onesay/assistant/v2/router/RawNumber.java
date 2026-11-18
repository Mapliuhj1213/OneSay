package com.onesay.assistant.v2.router;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ rel-1118 B02 / B06：快路径和 L0 都在「去标点」的句子上匹配，「2.5分钟」变「25分钟」、「-5秒」变「5秒」、「1.5」变「15」。
 * 这里在<b>原句</b>上看一眼：带负号 / 小数的数值类命令不在本地直接执行（交给模型或追问），
 * 只有倒计时的小数分钟 / 小时能精确换成整秒时才本地算（「倒计时2.5分钟」= 150 秒）。纯函数，开源版可直接挑走。
 */
public final class RawNumber {

    private RawNumber() {}

    private static final String CN = "零〇一二两三四五六七八九十";
    private static final Pattern SIGNED = Pattern.compile("(?<![0-9" + CN + "])[-−－负]\\s*[0-9" + CN + "]");
    private static final Pattern DECIMAL = Pattern.compile("[0-9][.．·][0-9]");
    /** 「一点五」= 1.5（「七点五分」「八点半」「十点一刻」是钟点，不算）。 */
    private static final Pattern CN_DECIMAL = Pattern.compile("[" + CN + "]点[零〇一二两三四五六七八九]+(?!分(?!钟)|刻|半|[0-9" + CN + "])");

    /** 原句里有负数 / 小数（clock = 闹钟类：「X点Y」是钟点，不按小数算）。 */
    public static boolean suspicious(String raw, boolean clock) {
        if (raw == null) return false;
        if (SIGNED.matcher(raw).find() || DECIMAL.matcher(raw).find()) return true;
        return !clock && CN_DECIMAL.matcher(raw).find();
    }

    /** 命中结果里有数值参数（这些才需要看原句）。 */
    public static boolean numeric(JSONObject args) {
        return args != null && (args.has("level") || args.has("seconds") || args.has("hour") || args.has("minute"));
    }

    public static boolean isClock(String sign) {
        return sign != null && sign.startsWith("clock.alarm") || "alarm.cancel".equals(sign) || "alarm.toggle".equals(sign);
    }

    private static final Pattern DEC_TIMER = Pattern.compile("([0-9]+)[.．]([0-9]+)\\s*(秒钟|秒|分钟|分|个小时|小时|个钟头|钟头)");
    private static final Pattern CN_DEC_TIMER = Pattern.compile("([" + CN + "]+)点([零〇一二两三四五六七八九]+)(秒钟|秒|分钟|个小时|小时|个钟头|钟头)");

    /** 「倒计时2.5分钟」→ 150；秒带小数、负数、换不成整秒 → null（不本地执行）。 */
    public static Long decimalTimerSeconds(String raw) {
        if (raw == null || SIGNED.matcher(raw).find()) return null;
        Matcher m = DEC_TIMER.matcher(raw);
        String ip, fp, unit;
        if (m.find()) {
            ip = m.group(1);
            fp = m.group(2);
            unit = m.group(3);
        } else {
            m = CN_DEC_TIMER.matcher(raw);
            if (!m.find()) return null;
            Long a = NumberParser.parse(m.group(1));
            if (a == null) return null;
            StringBuilder f = new StringBuilder();
            for (char c : m.group(2).toCharArray()) {
                Long d = NumberParser.parse(String.valueOf(c));
                if (d == null) return null;
                f.append(d);
            }
            ip = String.valueOf(a);
            fp = f.toString();
            unit = m.group(3);
        }
        if (unit.startsWith("秒") || fp.length() > 3) return null;
        long mul = unit.startsWith("分") ? 60 : 3600;
        long scale = 1;
        for (int i = 0; i < fp.length(); i++) scale *= 10;
        long num = Long.parseLong(ip) * scale + Long.parseLong(fp);
        if ((num * mul) % scale != 0) return null;
        long sec = num * mul / scale;
        return sec <= 0 || sec > 86400 ? null : sec;
    }
}
