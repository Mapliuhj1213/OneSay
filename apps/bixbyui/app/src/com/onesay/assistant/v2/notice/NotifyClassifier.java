package com.onesay.assistant.v2.notice;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * · 重要通知上岛的筛选（纯 Java，JVM 可测）。输入是 NotificationListener 拿到的一条通知的几个字段，输出是要不要上岛、上成什么样。
 *
 * <p>筛选顺序（命中一个就停）：
 * <ol>
 *   <li><b>验证码</b>（任意 App；正文里有「验证码 / 校验码 / 动态码 / code」且能抠出 4–8 位码）→ 高优先级，「复制」一键复制；默认打码；</li>
 *   <li><b>外卖 / 打车</b>（美团、饿了么、滴滴的常驻通知，交给 {@link ErrandParser}；解析不出阶段就不显示）；</li>
 *   <li><b>语音 / 视频通话邀请</b>（category = call，微信 / QQ 等；系统拨号盘的来电走 {@link CallNotices}，这里跳过）；</li>
 *   <li><b>闹钟 / 计时器</b>（category = alarm 或时钟 App；计时器带倒计时）；</li>
 *   <li><b>导航</b>（category = navigation 或地图 App 的常驻通知）；</li>
 *   <li><b>正在播放</b>（MediaStyle；默认关）；</li>
 *   <li><b>白名单 App</b>（用户配置；默认只显示「App 名 · 有新消息」，正文打码）。</li>
 * </ol>
 * 其余一律不上岛。内容是污染数据：不进日志，去重键只用通知 key 的哈希。
 */
public final class NotifyClassifier {

    private NotifyClassifier() {}

    /** 一条通知（只取需要的字段）。 */
    public static final class Notif {
        public String pkg = "", label = "", title = "", text = "", category = "", template = "";
        /** sbn.getKey() 的哈希（去重 / 撤下用；原 key 里可能带 tag，不直接用）。 */
        public String keyHash = "";
        public boolean ongoing;
        public long when;
        public boolean usesChronometer, chronoCountDown;
    }

    /** 筛选用的开关（来自 NoticeSettings；单测直接构造）。 */
    public static final class Prefs {
        public boolean otp = true, voip = true, alarm = true, nav = true, music = false, errand = false;
        public boolean revealOtp = false, revealContent = false;
        public Set<String> whitelist = new java.util.HashSet<String>();
    }

    public enum Type { OTP, ERRAND, VOIP, ALARM, TIMER, NAV, MUSIC, WHITELIST }

    /** 系统拨号盘 / 通话服务：来电与未接由电话状态（{@link CallNotices}）负责，通知这边不重复。 */
    static final Set<String> DIALERS = set("com.android.dialer", "com.google.android.dialer",
            "com.android.server.telecom", "com.android.incallui", "com.android.phone");
    static final Set<String> CLOCKS = set("com.android.deskclock", "com.google.android.deskclock");
    static final Set<String> MAPS = set("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map", "com.google.android.apps.maps",
            "com.sogou.map.android.maps");

    static Set<String> set(String... a) {
        return new java.util.HashSet<String>(java.util.Arrays.asList(a));
    }

    // ------------------------------------------------------------------ 验证码

    static final Pattern OTP_HINT = Pattern.compile("(验证码|校验码|动态码|动态密码|确认码|安全码|激活码|认证码|短信码|verification code|security code|one[- ]time|\\bOTP\\b|\\bcode\\b)",
            Pattern.CASE_INSENSITIVE);
    /** 候选码：4–8 位，纯数字或字母数字混合（混合时至少含一个数字）。前后不能紧挨数字 / 字母。 */
    static final Pattern OTP_TOKEN = Pattern.compile("(?<![0-9A-Za-z])([0-9]{4,8}|(?=[A-Za-z]*[0-9])[0-9A-Za-z]{4,8})(?![0-9A-Za-z])");
    /** 「【淘宝】」「[Google]」：短信签名（发送方，不是秘密）。 */
    static final Pattern SIGNATURE = Pattern.compile("[【\\[]([^】\\]]{1,12})[】\\]]");

    /**
     * 从正文里抠验证码（纯函数）。没有验证码提示词 → null。多个候选取离提示词最近的；排除像年份 / 金额 / 手机号的（金额带小数点或「元」，手机号 ≥ 9 位本来就不匹配）。
     */
    public static String extractOtp(String body) {
        if (body == null || body.length() == 0) return null;
        Matcher h = OTP_HINT.matcher(body);
        if (!h.find()) return null;
        int hintAt = h.start(), hintEnd = h.end();
        Matcher m = OTP_TOKEN.matcher(body);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        while (m.find()) {
            String tok = m.group(1);
            int st = m.start(1), en = m.end(1);
            // 金额：后面紧跟「元」或前面是小数点 / 货币符号
            if (en < body.length() && body.charAt(en) == '元') continue;
            if (en + 1 < body.length() && body.charAt(en) == '.' && Character.isDigit(body.charAt(en + 1))) continue;   // 200.00
            if (st > 1 && body.charAt(st - 1) == '.' && Character.isDigit(body.charAt(st - 2))) continue;
            if (st > 0 && (body.charAt(st - 1) == '¥' || body.charAt(st - 1) == '$')) continue;
            // 「5 分钟内有效」这类时长：后面紧跟「分钟 / 分 / 秒 / 小时」
            String after = body.substring(en, Math.min(body.length(), en + 3)).trim();
            if (after.startsWith("分") || after.startsWith("秒") || after.startsWith("小时") || after.startsWith("min")) continue;
            // 字母数字混合码：数字必须过半（「iPhone12」「A7K93Q」这类更像型号，不算）
            if (!tok.matches("[0-9]+")) {
                int dg = 0;
                for (int i = 0; i < tok.length(); i++) if (Character.isDigit(tok.charAt(i))) dg++;
                if (dg * 2 <= tok.length()) continue;
            }
            int dist = st >= hintEnd ? st - hintEnd : hintAt - en;
            if (dist < 0) dist = 0;
            if (dist < bestDist) {
                bestDist = dist;
                best = tok;
            }
        }
        return best;
    }

    /** 打码（纯函数）：只露最后 2 位。 */
    public static String maskCode(String code) {
        if (code == null || code.length() == 0) return "";
        int keep = Math.min(2, code.length() - 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < code.length() - keep; i++) b.append('•');
        return b.append(code.substring(code.length() - keep)).toString();
    }

    /** 短信签名【xxx】，没有就用 App 名。 */
    static String senderOf(Notif n, String body) {
        Matcher m = SIGNATURE.matcher(body == null ? "" : body);
        if (m.find()) return IslandNotice.clip(m.group(1), 12);
        return n.label == null || n.label.length() == 0 ? n.pkg : n.label;
    }

    // ------------------------------------------------------------------ 主入口

    /** 分类结果：要上岛的提醒 + 类型。null = 不上岛。 */
    public static final class Result {
        public final Type type;
        public final IslandNotice notice;

        Result(Type type, IslandNotice notice) {
            this.type = type;
            this.notice = notice;
        }
    }

    public static Result classify(Notif n, Prefs p, long now) {
        if (n == null || p == null || n.pkg == null || PrivacyNotices.OWN_PACKAGES.contains(n.pkg)) return null;
        String title = n.title == null ? "" : n.title, text = n.text == null ? "" : n.text;
        String body = (title + " " + text).trim();
        String cat = n.category == null ? "" : n.category.toLowerCase(Locale.ROOT);

        // 1. 验证码（不看包名：短信、邮件、各 App 自己发的都算）
        if (p.otp && !n.ongoing) {
            String code = extractOtp(body);
            if (code != null) {
                String who = senderOf(n, body);
                IslandNotice x = new IslandNotice(IslandNotice.Source.NOTIFY, "nl:otp:" + n.keyHash, IslandNotice.Priority.HIGH,
                        "验证码 · " + who, p.revealOtp ? code : maskCode(code) + "（点「复制」）");
                x.sourceTag = "验证码";
                x.persist = false;   // 验证码不落库
                x.expireAt = now + 5L * 60000L;
                x.action(new NoticeAction("复制", NoticeAction.Kind.COPY_TEXT, code));
                x.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
                return new Result(Type.OTP, x);
            }
        }

        // 2. 外卖 / 打车（可选）
        if (p.errand && ErrandParser.supports(n.pkg)) {
            IslandNotice e = ErrandParser.parse(n.pkg, n.label, title, text, n.keyHash, now);
            return e == null ? null : new Result(Type.ERRAND, e);   // 认得包但解析不出 → 安静不显示
        }

        // 3. 语音 / 视频通话邀请（系统拨号盘的交给电话状态）
        if (p.voip && "call".equals(cat) && !DIALERS.contains(n.pkg)) {
            IslandNotice x = new IslandNotice(IslandNotice.Source.NOTIFY, "nl:voip:" + n.keyHash, IslandNotice.Priority.HIGH,
                    appName(n) + " · " + (p.revealContent && title.length() > 0 ? title : "通话邀请"), p.revealContent ? text : "");
            x.sourceTag = "通话";
            x.tone = "call";
            x.persist = false;
            x.expireAt = now + 2L * 60000L;
            x.action(new NoticeAction("打开", NoticeAction.Kind.OPEN_APP, n.pkg));
            x.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
            return new Result(Type.VOIP, x);
        }

        // 4. 闹钟 / 计时器
        boolean clock = CLOCKS.contains(n.pkg);
        if (p.alarm && ("alarm".equals(cat) || clock)) {
            boolean timer = n.chronoCountDown || body.matches("(?s).*(计时器|倒计时|秒表|[Tt]imer|[Ss]topwatch).*");
            if (!timer && !"alarm".equals(cat) && !body.matches("(?s).*(闹钟|[Aa]larm|贪睡|稍后提醒).*")) return null;
            IslandNotice x = new IslandNotice(IslandNotice.Source.NOTIFY, "nl:" + (timer ? "timer:" : "alarm:") + n.keyHash,
                    timer ? IslandNotice.Priority.NORMAL : IslandNotice.Priority.HIGH,
                    timer ? (title.length() > 0 ? title : "计时器") : "闹钟 · " + (title.length() > 0 ? title : appName(n)), timer ? "" : text);
            x.sourceTag = timer ? "计时器" : "闹钟";
            x.persist = false;
            if (n.usesChronometer && n.when > 0) {
                x.chronoBase = n.when;
                x.countdown = n.chronoCountDown;
            }
            x.expireAt = now + (timer ? 3L * 3600000L : 15L * 60000L);
            x.action(new NoticeAction("打开", NoticeAction.Kind.OPEN_APP, n.pkg));
            return new Result(timer ? Type.TIMER : Type.ALARM, x);
        }

        // 5. 导航
        if (p.nav && ("navigation".equals(cat) || (MAPS.contains(n.pkg) && n.ongoing))) {
            if (body.length() == 0) return null;
            IslandNotice x = new IslandNotice(IslandNotice.Source.NOTIFY, "nl:nav:" + n.keyHash, IslandNotice.Priority.NORMAL,
                    title.length() > 0 ? title : text, title.length() > 0 ? text : "");
            x.sourceTag = "导航";
            x.persist = false;
            x.expireAt = now + 6L * 3600000L;
            x.action(new NoticeAction("打开", NoticeAction.Kind.OPEN_APP, n.pkg));
            return new Result(Type.NAV, x);
        }

        // 6. 正在播放（默认关）
        if (p.music && (n.template != null && n.template.endsWith("MediaStyle") || "transport".equals(cat))) {
            if (title.length() == 0) return null;
            IslandNotice x = new IslandNotice(IslandNotice.Source.NOTIFY, "nl:music:" + n.pkg, IslandNotice.Priority.LOW,
                    "♪ " + title, text);
            x.sourceTag = "正在播放";
            x.persist = false;
            x.expireAt = now + 6L * 3600000L;
            x.action(new NoticeAction("打开", NoticeAction.Kind.OPEN_APP, n.pkg));
            return new Result(Type.MUSIC, x);
        }

        // 7. 白名单 App（常驻 / 进度类不算消息）
        if (!n.ongoing && p.whitelist != null && p.whitelist.contains(n.pkg) && body.length() > 0) {
            IslandNotice x = new IslandNotice(IslandNotice.Source.NOTIFY, "nl:wl:" + n.keyHash, IslandNotice.Priority.NORMAL,
                    appName(n) + (p.revealContent && title.length() > 0 ? " · " + title : " · 有新消息"), p.revealContent ? text : "");
            x.sourceTag = appName(n);
            x.persist = false;
            x.expireAt = now + 30L * 60000L;
            x.action(new NoticeAction("打开", NoticeAction.Kind.OPEN_APP, n.pkg));
            x.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
            return new Result(Type.WHITELIST, x);
        }
        return null;
    }

    static String appName(Notif n) {
        return n.label == null || n.label.length() == 0 ? n.pkg : IslandNotice.clip(n.label, 10);
    }

    /** 通知被移除时要不要撤岛上那条：验证码不撤（用户可能已经划掉短信但还要输入），其余跟着撤。 */
    public static boolean retractOnRemoved(String dedupKey) {
        return dedupKey != null && dedupKey.startsWith("nl:") && !dedupKey.startsWith("nl:otp:");
    }
}
