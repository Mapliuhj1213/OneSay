package com.onesay.assistant.v2.pclink;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * [pclink] 语音口令（规则，纯 Java）：只认很窄的几句，避免误伤「电脑」相关的普通问答。
 * <ul>
 *   <li>锁屏：「电脑锁屏」「让电脑锁屏」「把电脑锁上」「锁一下电脑」「锁定电脑」；</li>
 *   <li>截图：「电脑截图」「让电脑截个图」「截一下电脑屏幕」；</li>
 *   <li>在线：「电脑在线吗」「电脑连上了吗」；</li>
 *   <li>关机 / 睡眠：只回一句「去控制台点，要二次确认」，不执行。</li>
 *   <li>[approve-not-block] 有确认卡在等时说「允许 / 好的 / 批准…」：不算批准（{@link Kind#APPROVAL}），只回一句「要在屏幕上点」。</li>
 * </ul>
 */
public final class PcVoice {

    public enum Kind { NONE, LOCK, SHOT, STATUS, POWER, APPROVAL }

    private PcVoice() {}

    private static final String PRE = "^(?:请|帮我|麻烦)?(?:让|叫|给|把)?(?:我的)?";
    private static final Pattern LOCK = Pattern.compile(PRE + "(?:电脑(?:锁屏|锁一下屏幕?|锁上|锁一下|锁起来)|(?:锁一下|锁上|锁定|锁)(?:我的)?电脑(?:屏幕)?)$");
    private static final Pattern SHOT = Pattern.compile(PRE + "(?:电脑(?:截图|截个图|截一下图|截屏|截一下屏幕?|截个屏)|(?:截|截一下|截个)(?:我的)?电脑(?:的)?(?:屏幕|屏|图))$");
    private static final Pattern STATUS = Pattern.compile("^(?:我的)?电脑(?:在线吗|在线没|连上了吗|连着吗|开着吗)$");
    private static final Pattern POWER = Pattern.compile(PRE + "电脑(?:关机|睡眠|休眠|关掉)$|^(?:关掉|关闭)(?:我的)?电脑$");

    public static Kind of(String q) {
        if (q == null) return Kind.NONE;
        String s = q.trim().replaceAll("[，。！？,.!?\\s]+$", "").replaceAll("\\s+", "");
        if (s.length() == 0 || s.length() > 20) return Kind.NONE;
        if (PcConfirm.anyPending() && PcConfirm.spokenApproval(s)) return Kind.APPROVAL; // 说话不算批准
        if (LOCK.matcher(s).matches()) return Kind.LOCK;
        if (SHOT.matcher(s).matches()) return Kind.SHOT;
        if (STATUS.matcher(s).matches()) return Kind.STATUS;
        if (POWER.matcher(s).matches()) return Kind.POWER;
        return Kind.NONE;
    }

    /** 「baidu.com」→「https://baidu.com」；不是 http(s) → null。 */
    public static String normalizeUrl(String s) {
        String u = s == null ? "" : s.trim();
        if (u.length() == 0 || u.contains(" ")) return null;
        String low = u.toLowerCase(Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            if (low.contains("://") || low.startsWith("javascript:") || low.startsWith("file:")) return null;
            u = "https://" + u;
        }
        return u.length() > 9 ? u : null;
    }

}
