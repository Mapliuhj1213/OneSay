package com.onesay.assistant.v2.askonce;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ int/1102「问一次、记住」（用户 2026-11-03 01:16 拍板）：有歧义的默认第一次用到时出一张选项芯片卡，选中即记成偏好，以后不再问；
 * 设置 › 记忆 › 常用默认 可以改。纯函数（JVM 单测），状态（待回答的问题、偏好）由 {@link AskOnceHooks} 持有。
 *
 * <table>
 *   <tr><td>只说「给某人发消息」</td><td>{@link #K_MSG}：微信 / 短信 / QQ → 把原话改写成带渠道的说法再走原路由</td></tr>
 *   <tr><td>购物类多步任务</td><td>{@link #K_SHOP}：浏览器跑腿（网页版，停在付款前）/ 直接操作 App（GUI）</td></tr>
 *   <tr><td>「看看我的邮件」</td><td>每次先问想看哪类（未读 / 重要 / 最新 / 某人发的 / 搜内容），再按答案读 —— 这是筛选，不记偏好</td></tr>
 * </table>
 * 提醒默认几点、「今天」已过怎么办在本地日程引擎里（SchedEngine.remindDateOnly，同一套芯片）；打车 / 听歌 App 用默认应用角色（RoleResolver 选一次即默认）。
 */
public final class AskOnce {

    private AskOnce() {}

    public static final String PREFS = "bixby_askonce";
    public static final String K_MSG = "msg_channel", K_SHOP = "shop_mode";
    public static final String MSG_WECHAT = "微信", MSG_SMS = "短信", MSG_QQ = "QQ";
    public static final String SHOP_BROWSER = "browser", SHOP_GUI = "gui";
    /** 追问有效期（和日程追问一样短：过了就当没问过，原话照常走）。 */
    public static final long PENDING_MS = 3L * 60000L;

    public enum Topic { MSG_CHANNEL, SHOP_MODE, MAIL_KIND, MAIL_FROM, MAIL_KW, MSG_FALLBACK }

    public static final List<String> MSG_CHIPS = Arrays.asList(MSG_WECHAT, MSG_SMS, MSG_QQ);
    public static final String CHIP_SHOP_BROWSER = "浏览器跑腿", CHIP_SHOP_GUI = "直接操作App";
    public static final List<String> SHOP_CHIPS = Arrays.asList(CHIP_SHOP_BROWSER, CHIP_SHOP_GUI);
    public static final String CHIP_MAIL_UNREAD = "未读", CHIP_MAIL_FLAGGED = "重要", CHIP_MAIL_LATEST = "最新3封",
            CHIP_MAIL_FROM = "某人发的", CHIP_MAIL_KW = "搜内容";
    public static final List<String> MAIL_CHIPS = Arrays.asList(CHIP_MAIL_UNREAD, CHIP_MAIL_FLAGGED, CHIP_MAIL_LATEST, CHIP_MAIL_FROM, CHIP_MAIL_KW);

    /** 一次追问：给用户看的话 + 芯片。 */
    public static final class Ask {
        public final Topic topic;
        public final String text;
        public final List<String> chips;
        public final String original;

        Ask(Topic topic, String text, List<String> chips, String original) {
            this.topic = topic;
            this.text = text;
            this.chips = chips;
            this.original = original;
        }

        @Override public String toString() {
            return topic + " " + chips;
        }
    }

    /** 回答的结果：要记的偏好（可 null）+ 接着要执行的话（可 null）+ 或者再追问一句（可 null）。 */
    public static final class Answer {
        public String prefKey, prefVal;
        public String resubmit;
        public Ask next;

        @Override public String toString() {
            return "pref=" + prefKey + ":" + prefVal + " resubmit=" + resubmit + " next=" + next;
        }
    }

    // ================================================================== 发消息没说渠道

    /** 「给老王发消息说我到了」「跟妈妈发个消息」「给张三留个言」——没点渠道。 */
    static final Pattern MSG = Pattern.compile(
            "^(?:请|麻烦你?)?(?:帮我|你)?(?:给|跟|和|向)([^，,。说发]{1,10}?)(?:发|留|捎)(?:个|条|一条|一个|句|一句)?(?:消息|留言|言|话)(?:说|讲|告诉[他她它]|问[他她它]?)?[，,：: ]*(.*)$");
    /** 已经点了渠道（含短信）：不问。 */
    static final Pattern MSG_EXPLICIT = Pattern.compile("微信|weixin|wechat|qq|钉钉|飞书|企业微信|邮件|邮箱|短信|whatsapp|telegram|line|私信|彩信", Pattern.CASE_INSENSITIVE);

    /** 是不是「发消息但没说渠道」；是 → {收件人, 正文}，否 → null。 */
    public static String[] msgNoChannel(String q) {
        if (q == null) return null;
        String s = q.trim().replaceAll("[。！!？?]+$", "");
        if (MSG_EXPLICIT.matcher(s).find()) return null;
        Matcher m = MSG.matcher(s);
        if (!m.matches()) return null;
        String who = m.group(1).trim();
        if (who.length() == 0 || who.matches("(?:我|你|自己)")) return null;
        return new String[]{who, m.group(2) == null ? "" : m.group(2).trim()};
    }

    /** 按渠道把原话改写成原路由认得的说法（微信 / QQ → GUI 多步并在发送前确认；短信 → sms.send 确认卡）。 */
    public static String msgRewrite(String q, String channel) {
        String[] p = msgNoChannel(q);
        if (p == null || channel == null) return null;
        String body = p[1];
        if (MSG_QQ.equals(channel)) return "用QQ给" + p[0] + "发消息" + (body.length() > 0 ? "说" + body : "");
        if (MSG_SMS.equals(channel)) return body.length() > 0 ? "给" + p[0] + "发短信说" + body : "给" + p[0] + "发短信";
        return "给" + p[0] + "发微信" + (body.length() > 0 ? "说" + body : "");
    }

    public static String msgChannelOf(String answer) {
        if (answer == null) return null;
        String a = answer.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.contains("微信") || a.contains("wechat") || a.contains("weixin")) return MSG_WECHAT;
        if (a.contains("短信") || a.contains("信息") || a.contains("sms")) return MSG_SMS;
        if (a.contains("qq")) return MSG_QQ;
        return null;
    }

    // 点名「发微信」但手机上没装微信 → 不进多步循环白查，直接问改用短信 / QQ（这一次，不记成默认）
    static final Pattern WX_SEND = Pattern.compile(
            "^(?:请|麻烦你?)?(?:帮我|你)?(?:用微信)?(?:给|跟|和|向)([^，,。说发]{1,10}?)(?:发|回)(?:个|条|一条|句|一句)?(?:微信|消息)(?:消息)?(?:说|讲|告诉[他她它]|问[他她它]?)?[，,：: ]*(.*)$");

    /** 点名用微信发给某人（「给老王发微信说我到了」「用微信给老王发消息说…」）→ {收件人, 正文}；否则 null。 */
    public static String[] wechatSend(String q) {
        if (q == null) return null;
        String s = q.trim().replaceAll("[。！!？?]+$", "");
        if (!s.contains("微信") || s.contains("企业微信")) return null;
        Matcher m = WX_SEND.matcher(s);
        if (!m.matches()) return null;
        if (!s.substring(0, m.start(2)).contains("微信")) return null;   // 「微信」在正文里（「发消息说微信上见」）不算
        String who = m.group(1).trim();
        if (who.length() == 0 || who.matches("(?:我|你|自己)")) return null;
        return new String[]{who, m.group(2) == null ? "" : m.group(2).trim()};
    }

    public static Ask askWechatMissing(String original, String who, boolean qqInstalled) {
        List<String> chips = qqInstalled ? Arrays.asList(MSG_SMS, MSG_QQ) : Arrays.asList(MSG_SMS);
        return new Ask(Topic.MSG_FALLBACK, "这台手机没装微信。改用" + (qqInstalled ? "短信还是QQ" : "短信") + "发给" + who + "？", chips, original);
    }

    static String rewriteParts(String who, String body, String channel) {
        if (MSG_QQ.equals(channel)) return "用QQ给" + who + "发消息" + (body.length() > 0 ? "说" + body : "");
        if (MSG_SMS.equals(channel)) return body.length() > 0 ? "给" + who + "发短信说" + body : "给" + who + "发短信";
        return null;
    }

    public static Ask askMsg(String original) {
        return new Ask(Topic.MSG_CHANNEL, "用什么发？选一次就记住，以后「发消息」都用它（设置 › 记忆 › 常用默认 可以改）。", MSG_CHIPS, original);
    }

    // ================================================================== 购物：浏览器跑腿还是直接操作 App

    public static String shopModeOf(String answer) {
        if (answer == null) return null;
        String a = answer.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.contains("浏览器") || a.contains("网页") || a.contains("跑腿") || a.contains("网站")) return SHOP_BROWSER;
        if (a.contains("app") || a.contains("应用") || a.contains("直接操作") || a.contains("客户端") || a.contains("手机上")) return SHOP_GUI;
        return null;
    }

    public static Ask askShop(String original) {
        return new Ask(Topic.SHOP_MODE, "购物这类多步任务怎么做？浏览器跑腿 = 用网页版加购物车、停在付款前；直接操作App = 在 App 里一步步点、下单前停下等你确认。"
                + "选一次就记住（设置 › 记忆 › 常用默认 可以改）。", SHOP_CHIPS, original);
    }

    public static String shopLabel(String v) {
        if (SHOP_BROWSER.equals(v)) return "浏览器跑腿";
        if (SHOP_GUI.equals(v)) return "直接操作 App";
        return null;
    }

    // ================================================================== 邮件：先问想看哪类

    /** 没说哪类的读邮件（「看看我的邮件」「读一下邮件」「邮件」）。「有新邮件吗」= 未读、「最新邮件」= 最新，不问。 */
    static final Pattern MAIL_GENERIC = Pattern.compile(
            "^(?:请|麻烦你?)?(?:帮我)?(?:看看|看一下|看下|查看|查一下|查查|读一下|读读|念一下|念念|打开看看)?(?:我的)?(?:邮件|收件箱|邮箱里的邮件|邮箱里有什么|收件箱里有什么|收件箱有什么)(?:吧)?$"
                    + "|^(?:读|看|念|查)邮件$");

    public static boolean mailGeneric(String q) {
        if (q == null) return false;
        String s = q.trim().replaceAll("[。！!？?]+$", "");
        return MAIL_GENERIC.matcher(s).matches();
    }

    public static Ask askMail(String original) {
        return new Ask(Topic.MAIL_KIND, "想看哪类邮件？可以按类型（未读 / 重要）、发件人，或者搜内容。", MAIL_CHIPS, original);
    }

    static final Pattern ANS_FROM = Pattern.compile("^(?:看看|看)?(.{1,24}?)(?:发来|发给我|寄来|发)的(?:邮件|信)?$");
    static final Pattern ANS_KW = Pattern.compile("^(?:搜|找|查)(?:一下)?(?:关于|有关)?(.{1,30}?)(?:的邮件)?$|^(?:关于|有关|提到)(.{1,30}?)(?:的邮件|的)?$");

    /** 回答「想看哪类」。 */
    public static Answer answerMail(Topic t, String ans) {
        if (ans == null) return null;
        String a = ans.trim().replaceAll("[。！!？?]+$", "");
        if (a.length() == 0) return null;
        Answer r = new Answer();
        if (t == Topic.MAIL_FROM) {
            if (a.length() > 24) return null;
            r.resubmit = "看看" + a.replaceAll("(?:发来|发给我|发)?的?(?:邮件|信)?$", "") + "发的邮件";
            return r;
        }
        if (t == Topic.MAIL_KW) {
            if (a.length() > 30) return null;
            Matcher k = ANS_KW.matcher(a);
            String kw = k.matches() ? (k.group(1) != null ? k.group(1) : k.group(2)) : a;
            r.resubmit = "搜一下邮件里的" + kw;
            return r;
        }
        if (a.contains("未读") || a.contains("没读") || a.contains("没看") || a.equals("新的")) {
            r.resubmit = "看看未读邮件";
            return r;
        }
        if (a.contains("重要") || a.contains("星标") || a.contains("标星")) {
            r.resubmit = "看看重要邮件";
            return r;
        }
        if (a.contains("最新") || a.contains("最近") || a.matches("(?:都行|随便|全部|所有|都看|都可以|不限)")) {
            r.resubmit = "看看最新邮件";
            return r;
        }
        if (a.equals(CHIP_MAIL_FROM) || a.matches("(?:按)?发件人|某人|谁发的|某个人")) {
            r.next = new Ask(Topic.MAIL_FROM, "谁发的？说名字或邮箱地址。", null, null);
            return r;
        }
        if (a.equals(CHIP_MAIL_KW) || a.matches("(?:按)?(?:内容|关键词|关键字)|搜(?:一下)?(?:内容)?")) {
            r.next = new Ask(Topic.MAIL_KW, "要搜什么内容？说个关键词，比如「发票」「面试」。", null, null);
            return r;
        }
        Matcher f = ANS_FROM.matcher(a);
        if (f.matches()) {
            r.resubmit = "看看" + f.group(1) + "发的邮件";
            return r;
        }
        Matcher k = ANS_KW.matcher(a);
        if (k.matches()) {
            r.resubmit = "搜一下邮件里的" + (k.group(1) != null ? k.group(1) : k.group(2));
            return r;
        }
        return null;
    }

    // ================================================================== 统一回答入口

    /**
     * 待回答的问题收到一句话：是回答 → 偏好 + 接着执行的话；不是回答（说了别的事）→ null（调用方放弃追问，原话照常走）。
     */
    public static Answer answer(Ask pending, String ans) {
        if (pending == null || ans == null) return null;
        switch (pending.topic) {
            case MSG_CHANNEL: {
                if (ans.trim().length() > 8) return null;
                String ch = msgChannelOf(ans);
                if (ch == null) return null;
                Answer r = new Answer();
                r.prefKey = K_MSG;
                r.prefVal = ch;
                r.resubmit = msgRewrite(pending.original, ch);
                return r;
            }
            case MSG_FALLBACK: {
                if (ans.trim().length() > 8) return null;
                String ch = msgChannelOf(ans);
                String[] p = wechatSend(pending.original);
                if (ch == null || MSG_WECHAT.equals(ch) || p == null || !pending.chips.contains(ch)) return null;
                Answer r = new Answer();   // 只这一次改用，不记成默认
                r.resubmit = rewriteParts(p[0], p[1], ch);
                return r;
            }
            case SHOP_MODE: {
                if (ans.trim().length() > 12) return null;
                String m = shopModeOf(ans);
                if (m == null) return null;
                Answer r = new Answer();
                r.prefKey = K_SHOP;
                r.prefVal = m;
                r.resubmit = pending.original;
                return r;
            }
            default:
                return answerMail(pending.topic, ans);
        }
    }

    /** 设置页用：所有偏好的显示名。 */
    public static String msgLabel(String v) {
        return v == null ? null : MSG_CHIPS.contains(v) ? v : null;
    }

    /** 「播放音乐 / 放首歌」这类泛指（「继续播放」「播放」不算：那是接着放）。 */
    static final Pattern MUSIC_GENERIC = Pattern.compile("音乐|歌");

    /** 纯函数：这句「播放」在按了播放键还没声音时，要不要改开默认音乐 App。 */
    public static boolean musicFallbackWanted(String cmd) {
        return cmd != null && MUSIC_GENERIC.matcher(cmd).find() && !cmd.contains("继续") && !cmd.contains("接着") && !cmd.contains("恢复");
    }

    /** 只带话题的追问（邮件「谁发的」「搜什么」；单测也用）。 */
    public static Ask followUp(Topic t) {
        return new Ask(t, "", null, null);
    }
}
