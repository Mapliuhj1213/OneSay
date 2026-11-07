package com.onesay.assistant.v2.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ 11-04 F / G：邮件数量与「有价值的邮件」回答（纯函数，JVM 可测）。
 *
 * <p>F（用户：「有多少邮件」输出格式乱）：第一句给口径清楚的数量（未读 N 封、今天新收 M 封）+ 最近一封；
 * 再只挑 2–4 封有用的（未读、非广告 / 通知 / 验证码 / 订阅），每封一行「N. 标题 — 发件人」；最后问「需要展开说说哪封吗？」。
 * 不出 Markdown 标题 / 粗体 / 多层列表。挑出的几封记住 10 分钟，「展开第一封」接回读那封正文（只读）。
 *
 * <p>G（用户：「我最近有哪些比较有价值的邮件」→ 直接编了标题）：机上日志显示 AgentLoop 第 1 轮就收工，一次邮件工具都没调，
 * 标题全是模型编的。现在这类句子走快路径直接 IMAP 只读取信，本类渲染；循环里的邮件答案再过 {@link #verify}：
 * 列出的标题必须在本轮工具结果里逐字找到，找不到的行删掉；没调过邮件工具就不许列任何邮件。
 */
public final class MailDigest {

    private MailDigest() {}

    public static final int MAX_PICKS = 4;
    public static final String ASK_EXPAND = "需要展开说说哪封吗？";

    public static final class Item {
        public final String from;
        public final String subject;
        public final long time;
        public final boolean seen;
        public final boolean flagged;
        /** ★ 11-04 午：IMAP UID（&lt;=0 = 没有）—「展开第 N 封」按 UID 直读那一封，不再按标题扫最近 200 封。 */
        public final long uid;

        public Item(String from, String subject, long time, boolean seen, boolean flagged) {
            this(from, subject, time, seen, flagged, -1L);
        }

        public Item(String from, String subject, long time, boolean seen, boolean flagged, long uid) {
            this.from = from == null ? "" : from.trim();
            this.subject = subject == null ? "" : subject.trim();
            this.time = time;
            this.seen = seen;
            this.flagged = flagged;
            this.uid = uid;
        }
    }

    static final Pattern PROMO = Pattern.compile(
            "(?i)广告|推广|促销|优惠|折扣|特价|限时|秒杀|红包|领券|抽奖|会员日|退订|订阅|周刊|月刊|日报|简报|newsletter|digest|promotion|promo|sale|deal|"
                    + "验证码|校验码|动态码|登录提醒|登录通知|安全提醒|账号安全|系统通知|通知|提醒您|自动回复|营销|活动|邀请您|welcome|unsubscribe|"
                    + "noreply|no-reply|no_reply|donotreply|do-not-reply|notification|notify|mailer-daemon|marketing|news@|info@|service@|newsletter@"
                    // ★ 11-04 backlog 质量：常见营销推广信关键词 / 发件域（电商大促、上新、福利领取、(AD) 标记、EDM 群发）
                    + "|包邮|满减|立减|直降|爆款|上新|新品首发|大促|狂欢|双11|双十一|黑五|黑色星期五|福利|专享|免费领|限量|清仓|返现|开售|预售|抢购|种草|好物|"
                    + "[(（【\\[]\\s*ad\\s*[)）】\\]]|edm|promo@|offers?@|deals?@|newsletters@|mailchimp|sendgrid|bounce");

    /** 广告 / 通知 / 验证码 / 订阅类（不列）。 */
    public static boolean isPromo(Item m) {
        if (m == null) return true;
        return PROMO.matcher(m.subject).find() || PROMO.matcher(m.from).find();
    }

    /** 挑 2–4 封：非广告通知；加星优先，其次未读，再按时间新到旧。 */
    public static List<Item> pick(List<Item> all, int max) {
        int cap = Math.max(1, Math.min(MAX_PICKS, max));
        List<Item> ok = new ArrayList<Item>();
        if (all != null) for (Item m : all) if (!isPromo(m) && m.subject.length() > 0) ok.add(m);
        java.util.Collections.sort(ok, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.flagged != b.flagged) return a.flagged ? -1 : 1;
                if (a.seen != b.seen) return a.seen ? 1 : -1;
                return a.time == b.time ? 0 : (a.time > b.time ? -1 : 1);
            }
        });
        return ok.size() > cap ? new ArrayList<Item>(ok.subList(0, cap)) : ok;
    }

    static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[*#`_>\\[\\]]+", "").replaceAll("\\s+", " ").trim();
        if (t.length() > max) t = t.substring(0, max);
        return t;
    }

    static String who(String from) {
        String f = from == null ? "" : from.trim();
        Matcher m = Pattern.compile("^\"?([^\"<]+?)\"?\\s*<[^>]+>$").matcher(f);
        if (m.matches()) return clean(m.group(1), 16);
        int at = f.indexOf('@');
        if (at > 0 && f.indexOf(' ') < 0) return clean(f.substring(0, at), 16);
        return clean(f, 16);
    }

    static String when(long t, long now) {
        if (t <= 0) return "";
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        a.setTimeInMillis(t);
        b.setTimeInMillis(now);
        String hm = String.format(Locale.ROOT, "%d:%02d", a.get(java.util.Calendar.HOUR_OF_DAY), a.get(java.util.Calendar.MINUTE));
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)) return "今天 " + hm;
        b.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)) return "昨天 " + hm;
        return (a.get(java.util.Calendar.MONTH) + 1) + "月" + a.get(java.util.Calendar.DAY_OF_MONTH) + "日";
    }

    /** 今天收到几封（items 里）。 */
    public static int today(List<Item> all, long now) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        int n = 0;
        if (all != null) for (Item m : all) if (m.time >= c.getTimeInMillis()) n++;
        return n;
    }

    /**
     * 渲染。valueMode=true（「有价值的邮件」）第一句换成「最近值得看的有 N 封」，仍带未读口径。
     *
     * @param unread  收件箱未读数（IMAP UNSEEN 计数）；&lt;0 = 读不到
     * @param all     最近窗口内的邮件（新到旧），只用于挑选与「今天」计数
     */
    public static String render(int unread, List<Item> all, long now, boolean valueMode) {
        List<Item> picks = pick(all, MAX_PICKS);
        remember(picks, now);
        int today = today(all, now);
        Item latest = all == null || all.isEmpty() ? null : all.get(0);
        StringBuilder sb = new StringBuilder();
        String count = (unread >= 0 ? "未读 " + unread + " 封" : "未读数没读到") + "，今天新收 " + today + " 封";
        if (latest == null) {
            sb.append("收件箱（").append(count).append("），最近没有新邮件。");
            return sb.toString();
        }
        if (valueMode) {
            sb.append(picks.isEmpty() ? "最近的邮件里没挑出值得看的（都是广告或通知）" : "最近值得看的有 " + picks.size() + " 封")
                    .append("（收件箱").append(count).append("）；最近一封是 ").append(who(latest.from))
                    .append(" 的「").append(clean(latest.subject, 30)).append("」").append(when(latest.time, now).length() > 0 ? "（" + when(latest.time, now) + "）" : "").append("。");
        } else {
            sb.append("你的收件箱").append(count).append("（只算收件箱）；最近一封是 ").append(who(latest.from))
                    .append(" 的「").append(clean(latest.subject, 30)).append("」").append(when(latest.time, now).length() > 0 ? "（" + when(latest.time, now) + "）" : "").append("。");
        }
        if (!picks.isEmpty()) {
            if (!valueMode) sb.append("\n值得看的几封：");
            for (int i = 0; i < picks.size(); i++) {
                Item m = picks.get(i);
                sb.append('\n').append(i + 1).append(". ").append(clean(m.subject, 30));
                String w = who(m.from);
                if (w.length() > 0) sb.append(" — ").append(w);
            }
            sb.append("\n其余是广告、通知类，就不列了。");
        } else {
            sb.append("\n其余都是广告、通知类，就不列了。");
        }
        sb.append('\n').append(ASK_EXPAND);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 「展开第 N 封」接续

    private static volatile List<Item> sLast = new ArrayList<Item>();
    private static volatile long sAt;
    public static final long TTL_MS = 10L * 60L * 1000L;

    static void remember(List<Item> picks, long now) {
        sLast = picks == null ? new ArrayList<Item>() : new ArrayList<Item>(picks);
        sAt = now;
    }

    /** 第 n 封（1 起）的标题；过期 / 没有 → null。 */
    public static String subjectAt(int n, long now) {
        List<Item> l = sLast;
        if (n < 1 || n > l.size() || now - sAt > TTL_MS) return null;
        return l.get(n - 1).subject;
    }

    /** ★ 11-04 午：第 n 封（1 起）的 IMAP UID；过期 / 没有 / 没记到 UID → -1。 */
    public static long uidAt(int n, long now) {
        List<Item> l = sLast;
        if (n < 1 || n > l.size() || now - sAt > TTL_MS) return -1L;
        return l.get(n - 1).uid;
    }

    // ------------------------------------------------------------------ ★ 11-04 午：展开一封 = 本地短摘要（不走模型）

    /** 退订 / 网页版 / 免责声明这类模板行（不进摘要）。 */
    static final Pattern BOILER = Pattern.compile(
            "(?i)退订|取消订阅|不愿意?(?:继续)?接收|unsubscribe|查看网页版|在浏览器中查看|浏览器打开|网页版查看|请勿(?:直接)?回复|此邮件(?:由系统|为系统)|系统自动发送|"
                    + "版权所有|copyright|all rights reserved|隐私政策|privacy|免责声明|如果(?:您)?无法(?:正常)?(?:查看|显示)|点击(?:这里|此处)|view (?:in|this) (?:browser|email)|"
                    + "^\\s*(?:https?://\\S+)\\s*$");

    /** 正文 → 2–3 句短摘要（≤ max 字）：去掉模板行和链接，按句号切，整句保留，不加省略号。纯函数。 */
    public static String brief(String body, int max) {
        if (body == null) return "";
        StringBuilder keep = new StringBuilder();
        for (String line : body.split("[\\r\\n]+")) {
            String t = line.replaceAll("https?://\\S+", "").replaceAll("[\\s\\u3000\\u00a0]+", " ").trim();
            if (t.length() < 2 || BOILER.matcher(t).find()) continue;
            if (keep.length() > 0) keep.append(' ');
            keep.append(t);
            if (keep.length() > max * 3) break;
        }
        String all = keep.toString().trim();
        if (all.length() <= max) return all;
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("[^。！？!?；;]+[。！？!?；;]?").matcher(all);
        while (m.find()) {
            String sen = m.group().trim();
            if (sen.length() == 0) continue;
            if (out.length() + sen.length() > max) break;
            out.append(sen);
        }
        if (out.length() == 0) {   // 一句都放不下：按词边界切
            String cut = all.substring(0, max);
            int sp = Math.max(cut.lastIndexOf(' '), Math.max(cut.lastIndexOf('，'), cut.lastIndexOf(',')));
            out.append(sp > max / 2 ? cut.substring(0, sp) : cut);
        }
        return out.toString().trim();
    }

    /** 展开那一封的回答：标题（逐字取自邮件）+ 发件人 / 时间 + 短摘要。纯函数。 */
    public static String expandReply(int n, String from, String subject, long time, String body, long now) {
        StringBuilder sb = new StringBuilder();
        sb.append("第").append(n <= 0 ? 1 : n).append("封：「").append(subject == null ? "" : subject.trim()).append("」");
        String w = who(from);
        String t = when(time, now);
        if (w.length() > 0 || t.length() > 0) {
            sb.append("（");
            if (w.length() > 0) sb.append(w);
            if (w.length() > 0 && t.length() > 0) sb.append("，");
            if (t.length() > 0) sb.append(t);
            sb.append("）");
        }
        String b = brief(body, 120);
        sb.append('\n').append(b.length() > 0 ? "大意：" + b : "这封没有可读的正文（可能只有图片）。");
        return sb.toString();
    }

    static final Pattern EXPAND = Pattern.compile(
            "^(?:展开|展开说说|展开讲讲|详细说说|详细讲讲|说说|讲讲|念念|念一下|读一下|看看|打开)?(?:一下)?第\\s*([一二三四1-4])\\s*(?:封|个|条)(?:邮件|信)?(?:说了什么|讲了什么|是什么|写了什么|的内容|详细说说|展开说说)?$");

    /** 「展开第一封 / 第二封说了什么」→ 1–4；不是 → 0。 */
    public static int expandIndex(String utterance) {
        if (utterance == null) return 0;
        String c = utterance.trim().replaceAll("[。！？!?，,\\s]+$", "").replaceAll("^(?:小比|bixby|Bixby)[，,\\s]*", "");
        Matcher m = EXPAND.matcher(c);
        if (!m.matches()) return 0;
        String d = m.group(1);
        return "一1".contains(d) ? 1 : "二2".contains(d) ? 2 : "三3".contains(d) ? 3 : 4;
    }

    // ------------------------------------------------------------------ G：邮件答案逐字核对

    /** 引号前面紧挨着的邮件线索（同一句内）：「主题是「…」」「张三发来的「…」」「一封邮件「…」」。 */
    static final Pattern QUOTE_CUE = Pattern.compile("(?:主题|标题|来信|发来|发的|寄来|邮件|一封|封信)[^。！？\\n]{0,20}$");
    /** 整段像邮件清单（编号行才算邮件条目）。 */
    static final Pattern LIST_CUE = Pattern.compile("封|发件人|来自|主题| — ");

    /**
     * 答案里「看起来是邮件条目」的标题：前面有邮件线索的「」《》里的文字，或（整段像邮件清单时）编号 / 列表行（去掉发件人尾巴）。
     * ★ 11-04：只认带线索的引号——如实说明里引用的界面名 / 说法（「设置→账户」「打开QQ邮箱」）不是邮件标题，不能拿来核对删掉。
     */
    static List<String> titlesIn(String answer) {
        return titlesIn(answer, answer != null && LIST_CUE.matcher(answer).find());
    }

    static List<String> titlesIn(String answer, boolean listMode) {
        List<String> out = new ArrayList<String>();
        if (answer == null) return out;
        Matcher m = Pattern.compile("[「《]([^」》]{2,60})[」》]").matcher(answer);
        while (m.find()) {
            String before = answer.substring(Math.max(0, m.start() - 24), m.start());
            if (QUOTE_CUE.matcher(before).find()) out.add(m.group(1).trim());
        }
        if (!listMode) return out;
        for (String line : answer.split("\n")) {
            String l = line.trim();
            Matcher lm = Pattern.compile("^(?:\\d+[.、)）]|[-•·*])\\s*(?:\\*\\*)?(?:主题[:：])?\\s*(.+)$").matcher(l);
            if (!lm.matches()) continue;
            String t = lm.group(1).replaceAll("\\*\\*", "").replaceAll("\\s+[—-]+\\s+.*$", "").replaceAll("（[^）]*）$|\\([^)]*\\)$", "").trim();
            t = t.replaceAll("^[「《]|[」》]$", "").trim();
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    /**
     * 核对：邮件条目只能来自本轮工具结果（observations 拼起来的文本）。
     * <ul>
     *   <li>没调过邮件工具（mailCalled=false）而答案里列了邮件 → 整段换成如实说明；</li>
     *   <li>调过：逐行检查，标题在工具结果里找不到的行删掉；全删光 → 如实说明。</li>
     * </ul>
     * 返回核对后的答案（没问题原样返回）。
     */
    public static String verify(String answer, boolean mailCalled, String observations, String failReason) {
        if (answer == null) return null;
        boolean listMode = LIST_CUE.matcher(answer).find();
        List<String> titles = titlesIn(answer, listMode);
        if (titles.isEmpty()) return answer;
        if (!mailCalled) {
            return "我这一轮没有读到你的邮件" + (failReason != null && failReason.length() > 0 ? "（" + failReason + "）" : "")
                    + "，所以不列任何邮件标题，免得说错。你说「我有多少邮件」，我直接去收件箱读。";
        }
        String obs = observations == null ? "" : observations;
        StringBuilder kept = new StringBuilder();
        int dropped = 0, listed = 0;
        for (String line : answer.split("\n")) {
            List<String> ts = titlesIn(line, listMode);
            boolean bad = false;
            for (String t : ts) {
                if (!obs.contains(t)) { bad = true; break; }
            }
            if (!ts.isEmpty()) listed++;
            if (bad) { dropped++; continue; }
            if (kept.length() > 0) kept.append('\n');
            kept.append(line);
        }
        if (dropped == 0) return answer;
        if (dropped >= listed) {
            return "读到的邮件里找不到我刚才要列的那些标题，为免说错就不列了。你说「我有多少邮件」，我按收件箱原样给你列。";
        }
        return kept.toString();
    }

    /** 是不是问邮件的句子（给循环用：邮件意图 + 没调邮件工具 → 不许列条目）。 */
    public static boolean mailQuestion(String q) {
        return q != null && q.matches("(?s).*(邮件|邮箱|收件箱|来信).*");
    }
}
