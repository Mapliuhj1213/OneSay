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
 *   <li>[media-get] 链接下载：「下载这个视频」「把这个链接下到电脑」「用电脑下载 https://…」（{@link Kind#MEDIA}，规则见 {@link PcMedia#voice}）。</li>
 *   <li>[overnight-1119] 交任务给电脑：「让电脑…」「叫电脑帮我…」「电脑帮我…」「交给电脑：…」「用电脑帮我…」；问句（怎么 / 如何 / 能不能…吗）不算（{@link Kind#TASK}，目标见 {@link #taskGoal}）；
 *       查进展：「电脑任务做完了吗」「电脑那边怎么样了」（{@link Kind#TASK_STATUS}）。上面几条窄口令先认。</li>
 * </ul>
 */
public final class PcVoice {

    public enum Kind { NONE, LOCK, SHOT, STATUS, POWER, APPROVAL, MEDIA, TASK, TASK_STATUS }

    private PcVoice() {}

    private static final String PRE = "^(?:请|帮我|麻烦)?(?:让|叫|给|把)?(?:我的)?";
    private static final Pattern LOCK = Pattern.compile(PRE + "(?:电脑(?:锁屏|锁一下屏幕?|锁上|锁一下|锁起来)|(?:锁一下|锁上|锁定|锁)(?:我的)?电脑(?:屏幕)?)$");
    private static final Pattern SHOT = Pattern.compile(PRE + "(?:电脑(?:截图|截个图|截一下图|截屏|截一下屏幕?|截个屏)|(?:截|截一下|截个)(?:我的)?电脑(?:的)?(?:屏幕|屏|图))$");
    private static final Pattern STATUS = Pattern.compile("^(?:我的)?电脑(?:在线吗|在线没|连上了吗|连着吗|开着吗)$");
    private static final Pattern POWER = Pattern.compile(PRE + "电脑(?:关机|睡眠|休眠|关掉)$|^(?:关掉|关闭)(?:我的)?电脑$");

    /** [overnight-1119] 交给电脑的一句目标：「让电脑 / 叫电脑 / 电脑帮我 / 交给电脑 / 在电脑上 / 用电脑」+ 至少 2 个字。 */
    private static final Pattern TASK = Pattern.compile("^(?:请|帮我|麻烦)?(?:(?:让|叫|请)(?:我的)?电脑(?:帮我|给我|去)?|(?:我的)?电脑帮我|(?:交给|发给)(?:我的)?电脑(?:去)?(?:做)?|(?:在|用)(?:我的)?电脑(?:上)?帮我)[：:，,\\s]*(.{2,})$");
    private static final Pattern TASK_STATUS = Pattern.compile("^(?:我的)?电脑(?:那边|上的)?(?:任务|的任务|那个任务|刚才的任务)?(?:做完了吗|做完没|做好了吗|做好没|好了吗|完成了吗|怎么样了|进度怎么样|做到哪了|做到哪一步了)$|^(?:交给|发给)电脑的(?:任务|事)(?:做完了吗|怎么样了|好了吗)$");

    public static Kind of(String q) {
        if (q == null) return Kind.NONE;
        if (PcMedia.voice(q) != null) return Kind.MEDIA; // 可能带一长串链接，先于下面的长度限制
        String s = q.trim().replaceAll("[，。！？,.!?\\s]+$", "").replaceAll("\\s+", "");
        if (s.length() == 0) return Kind.NONE;
        if (s.length() > 20) return taskGoal(q) != null ? Kind.TASK : Kind.NONE;
        if (PcConfirm.anyPending() && PcConfirm.spokenApproval(s)) return Kind.APPROVAL; // 说话不算批准
        if (LOCK.matcher(s).matches()) return Kind.LOCK;
        if (SHOT.matcher(s).matches()) return Kind.SHOT;
        if (STATUS.matcher(s).matches()) return Kind.STATUS;
        if (POWER.matcher(s).matches()) return Kind.POWER;
        if (TASK_STATUS.matcher(s).matches()) return Kind.TASK_STATUS;
        if (taskGoal(q) != null) return Kind.TASK;
        return Kind.NONE;
    }

    /** 「让电脑把下载文件夹里的安装包整理一下」→「把下载文件夹里的安装包整理一下」；不是交任务 → null。关机 / 睡眠不收（要在控制台确认）。 */
    public static String taskGoal(String q) {
        if (q == null) return null;
        String s = q.trim().replaceAll("[。！？.!?\\s]+$", "");
        if (s.length() == 0 || s.length() > 1000) return null;
        java.util.regex.Matcher m = TASK.matcher(s);
        if (!m.matches()) return null;
        String g = m.group(1).trim();
        if (g.length() < 2) return null;
        String flat = s.replaceAll("\\s+", "");
        if (POWER.matcher(flat).matches() || g.matches("^(?:关机|睡眠|休眠|关掉|重启)$")) return null;
        if (g.matches("^(?:在线吗|在线没|连上了吗|连着吗|开着吗)$")) return null;
        if (g.matches("^(?:怎么|怎样|如何|能不能|能否|可不可以|可以吗|为什么|为啥|是什么|是不是).*") || g.matches(".*(?:吗|呢|么)$")) return null; // 问句：照常问答
        return g;
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
