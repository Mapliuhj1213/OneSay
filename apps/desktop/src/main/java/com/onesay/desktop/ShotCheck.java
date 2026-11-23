package com.onesay.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [overnight-1119] 截图拿来当证据前先核对：画面是不是要的那个。
 *
 * <p>用户 11-19 手测：「关掉 MuMu 的 root 并截图证明」，截到的是模拟器桌面的游戏广告页，模型却说「上面这张就是关闭 root 后的画面」。
 * 现在：配了看图模型就让它最后单独回一行「核对：符合 / 不符合（原因）/ 看不出」；没配就用 OCR 读出的文字找目标里点名的字样
 * （「」里的词、uid=0 这类），找不到就算不符合；画面像广告页（立即下载、礼包、首充…）也算不符合。
 * 不符合时步骤标黄，结果里明说「截到的不是目标画面」，并告诉模型不许拿这张图当证据。
 */
public final class ShotCheck {
    private ShotCheck() {}

    public enum Verdict { MATCH, MISMATCH, UNKNOWN }

    public static final class Result {
        public final Verdict verdict;
        public final String why;

        Result(Verdict v, String why) {
            this.verdict = v;
            this.why = why == null ? "" : why;
        }

        public String id() { return verdict == Verdict.MATCH ? "yes" : verdict == Verdict.MISMATCH ? "no" : "unknown"; }
    }

    /** 给看图模型的核对要求（接在描述要求后面）。 */
    public static String visionAsk(String goal) {
        if (goal == null || goal.isBlank()) return "";
        return "\n最后单独一行写核对结论，三选一：「核对：符合」/「核对：不符合（一句原因）」/「核对：看不出」——判断这张截图是不是「" + goal.trim()
                + "」要看的画面。广告页、桌面、加载页、锁屏、别的 App、和目标无关的弹窗都算不符合。";
    }

    static final Pattern VISION_LINE = Pattern.compile("核对[:：]\\s*(符合|不符合|看不出|不确定)[（(]?([^\\n）)]*)");

    public static Result fromVision(String answer) {
        if (answer == null) return new Result(Verdict.UNKNOWN, "");
        Matcher m = VISION_LINE.matcher(answer);
        Result r = null;
        while (m.find()) {
            String v = m.group(1);
            String why = m.group(2) == null ? "" : m.group(2).trim();
            r = v.equals("符合") ? new Result(Verdict.MATCH, "") : v.equals("不符合") ? new Result(Verdict.MISMATCH, why) : new Result(Verdict.UNKNOWN, why);
        }
        return r == null ? new Result(Verdict.UNKNOWN, "看图模型没给核对结论") : r;
    }

    static final Pattern QUOTED = Pattern.compile("[「“\"'『]([^」”\"'』]{1,40})[」”\"'』]");
    static final Pattern ASCII_TOKEN = Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_.\\-]*=[A-Za-z0-9_()]+|uid=\\d+|[A-Za-z][A-Za-z0-9_.\\-]{2,})(?![A-Za-z0-9_])");
    static final Pattern STOP = Pattern.compile("(?i)^(the|and|with|screen|screenshot|shot|show|has|have|for|pc|android|adb|mumu|png|jpg|ok|is|are|not)$");
    static final Pattern AD = Pattern.compile("立即下载|下载游戏|开始游戏|领取礼包|礼包|首充|开服|充值|新服|限时福利|广告|试玩|觉醒|传奇|仙侠|装备回收");

    /** 目标里点名要看到的字样（「」里的词、ASCII 标识如 uid=0 / root / Mindustry）。 */
    public static List<String> expected(String goal) {
        List<String> out = new ArrayList<>();
        if (goal == null) return out;
        Matcher q = QUOTED.matcher(goal);
        while (q.find()) { String t = q.group(1).trim(); if (!t.isEmpty() && !out.contains(t)) out.add(t); }
        Matcher a = ASCII_TOKEN.matcher(goal);
        while (a.find()) {
            String t = a.group(1);
            if (STOP.matcher(t).matches()) continue;
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    static String norm(String s) { return s == null ? "" : s.replaceAll("[\\s\\u00a0]+", "").toLowerCase(Locale.ROOT); }

    /** 只有 OCR 文字时的核对。 */
    public static Result fromOcr(String goal, String ocrText) {
        String text = norm(ocrText);
        boolean ad = AD.matcher(ocrText == null ? "" : ocrText).find() && (goal == null || !AD.matcher(goal).find());
        List<String> want = expected(goal);
        if (!want.isEmpty()) {
            List<String> hit = new ArrayList<>();
            for (String w : want) if (text.contains(norm(w))) hit.add(w);
            if (!hit.isEmpty() && !ad) return new Result(Verdict.MATCH, "OCR 读到了「" + String.join("」「", hit) + "」");
            if (hit.isEmpty()) return new Result(Verdict.MISMATCH, "画面里没读到「" + String.join("」「", want) + "」" + (ad ? "，看起来是广告页" : ""));
        }
        if (ad) return new Result(Verdict.MISMATCH, "画面像是广告页（读到了下载 / 礼包这类字样）");
        if (text.isEmpty()) return new Result(Verdict.UNKNOWN, "OCR 没读到文字，没法核对");
        return new Result(Verdict.UNKNOWN, "OCR 只能读文字，没法确认画面就是目标");
    }

    /** 给模型和任务卡的一句。 */
    public static String line(Result r) {
        switch (r.verdict) {
            case MATCH: return "核对：截图和目标一致" + (r.why.isEmpty() ? "" : "（" + r.why + "）");
            case MISMATCH: return "核对：截到的不是目标画面" + (r.why.isEmpty() ? "" : "（" + r.why + "）") + "。不要拿这张图当证据：先把要看的画面调出来（打开对应程序 / 等命令输出出来 / 关掉广告）再截一次，或者如实告诉用户没截到。";
            default: return "核对：没法确认这张图就是目标画面" + (r.why.isEmpty() ? "" : "（" + r.why + "）") + "。给用户看时要说明没核对上，不要说「这就是」。";
        }
    }
}
