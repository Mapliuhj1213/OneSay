package com.onesay.assistant.v2.quicknote;

import com.onesay.assistant.v2.s2.SensitiveGuard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * [quicknote] ★ R11 记一记的「整屏敏感」判定（纯 Java，JVM 全测）。
 *
 * <p>R10 是逐行套 {@link SensitiveGuard#reason}：任何一行带「密码」、或一串像密码的无空格串 → 整屏拒收。
 * 真机（2026-10-11 23:12，QQ 邮箱读信页）因此误拒了两封普通邮件：
 * <ul>
 *   <li>谷歌安全提醒邮件：发件人 / 退信地址 {@code <3jyh…@…bounces.google.com>} 被当成「疑似密码」；</li>
 *   <li>视觉兜底的文字里「账号 1234509876@qq.com」——QQ 邮箱地址里的 10 位数字被当成「证件号码」。</li>
 * </ul>
 * R11：先把邮箱地址 / 网址（连同两边的尖括号、逗号）从行里拿掉再判；邮件 App 里只在「像表单」的密码行
 * （「请输入密码」「密码：」「支付密码」…）或带一串码的行才拒，正文里谈到「密码」的句子不算密码页。
 * 验证码 / 卡号 / 证件号 / 密钥照旧一律拒（任何 App）。
 */
public final class ScreenSensitivity {

    private ScreenSensitivity() {}

    static final Pattern EMAIL = Pattern.compile("<?[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}>?");
    static final Pattern URL = Pattern.compile("(?i)<?(?:https?://|www\\.)[^\\s<>\"，。]+>?");
    /**
     * ★ 浏览器地址栏网址（URL 只认 http/https/www 时漏掉的）：任意协议头（file:// content:// chrome:// about: …）、
     * 带端口或路径的 IP / localhost、带路径或端口的裸域名（example.com/a、foo.cn:8080）。
     * 真机：Chrome 地址栏「127.0.0.1:8765/…」、三星浏览器「file:///…」被当成「疑似密码」整屏拒收。
     */
    static final Pattern URL_MORE = Pattern.compile(
            "(?i)<?(?:"
            + "[a-z][a-z0-9+.\\-]{1,15}://[^\\s<>\"，。]*"
            + "|(?:about|data|javascript|blob|intent|mailto|tel):[^\\s<>\"，。]+"
            + "|(?<![A-Za-z0-9.])(?:\\d{1,3}(?:\\.\\d{1,3}){3}|localhost|\\[[0-9a-f:]+\\])(?::\\d{1,5})?(?:[/?#][^\\s<>\"，。]*)?"
            + "|(?<![A-Za-z0-9.@])(?:[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(?::\\d{1,5}(?:[/?#][^\\s<>\"，。]*)?|[/?#][^\\s<>\"，。]*)"
            + ")>?");
    /** 方向控制符（地址栏常在开头塞 U+200E / U+200F 等）：判之前去掉。 */
    static final Pattern BIDI = Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");

    /** 表单样式的密码行：输入 / 设置 / 确认 / 原 / 新 … 密码，或「密码：」，或整行就是「密码」。 */
    static final Pattern PASSWORD_FORM = Pattern.compile(
            "(输入|填写|设置|确认|重复|原|新|旧|当前|登录|支付|交易|取款|独立|授权)\\s*密码|密码\\s*[:：]|^\\s*(密码|口令|password|Password|PIN)\\s*$");
    /** 行里有一串像「码」的东西（4 位以上数字，或字母数字混合 6 位以上）。 */
    static final Pattern CODEISH = Pattern.compile("(?<![A-Za-z0-9])(?:\\d{4,}|(?=[A-Za-z0-9]*\\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{6,})(?![A-Za-z0-9])");

    /** 整页里有没有「密码」类字样（邮件 App 里「形似密码」的一串只有在这时才算数）。 */
    static final Pattern PASSWORD_WORD = Pattern.compile("(?i)密码|口令|password|passcode|passwd|\\bpin\\b");

    /** 去掉方向符、邮箱地址、网址（含地址栏 URL_MORE；判敏感前用；不改存进去的正文）。 */
    public static String stripAddresses(String line) {
        if (line == null) return "";
        String s = BIDI.matcher(line).replaceAll("");
        s = URL.matcher(s).replaceAll(" ");
        s = URL_MORE.matcher(s).replaceAll(" ");
        s = EMAIL.matcher(s).replaceAll(" ");
        return s.replaceAll("[<>,;，；]+\\s*$", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * 整屏原因（给人看）；不敏感返回 null。
     *
     * @param mailApp 是否邮件 App（{@link MailDigest#isMailApp}）
     */
    public static String pageReason(boolean mailApp, List<String> lines) {
        if (lines == null) return null;
        boolean pwWord = false;
        if (mailApp) {
            for (String raw : lines) if (raw != null && PASSWORD_WORD.matcher(raw).find()) { pwWord = true; break; }
        }
        for (String raw : lines) {
            String l = stripAddresses(raw);
            if (l.length() == 0) continue;
            String r = SensitiveGuard.reason(l);
            if (r == null) continue;
            if (mailApp && "疑似密码".equals(r) && !pwWord) {
                // ★ R11 真机复核（10-12 21:53，谷歌安全提醒邮件）：只是「形似密码」的一串（图片名 Icon_recovery_x2_20_20、
                //   头像地址 ACg8oc…=s96-c），整封邮件里没有一个「密码 / password」字样 → 不是密码页
                continue;
            }
            if (mailApp && "密码".equals(r) && !PASSWORD_FORM.matcher(l).find() && !CODEISH.matcher(l).find()) {
                continue;   // 邮件正文里「如果不是你本人操作，请修改密码」这类句子：不是密码页
            }
            return r;
        }
        return null;
    }
}
