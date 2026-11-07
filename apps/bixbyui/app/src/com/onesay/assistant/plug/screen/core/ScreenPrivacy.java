/*
 * [mika.screen 插件] OneSay 自写（规则来自本项目 vision/screenref/UiDumpParser 的打码口径，MIKA-EXEC §6.6）。
 */
package com.onesay.assistant.plug.screen.core;

import java.util.regex.Pattern;

/**
 * 统一的读屏隐私过滤（元素、差异、日志同一把尺）：
 * <ul>
 *   <li>密码框：text 一律不读，名字只用提示语，带 secret；</li>
 *   <li>资源 id 像支付密码 / CVV / PIN 的输入框：留「受保护输入框」占位（模型要知道有这个框好交给用户），不出文字；</li>
 *   <li>文字里像银行卡号（13–19 位）、身份证号（18 位）的串打码，只留后 4 位。</li>
 * </ul>
 */
public final class ScreenPrivacy {
    private ScreenPrivacy() {
    }

    public static final String PROTECTED_NAME = "受保护输入框";

    private static final Pattern PROTECTED_RID = Pattern.compile(
            "(?i).*(password|passwd|pwd|passcode|pin_?code|pinpad|cvv|cvc|security_?code|paypwd|pay_pwd).*");
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9])([1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx])(?![0-9])");
    private static final Pattern CARD = Pattern.compile("(?<![0-9])((?:[0-9][ -]?){12,18}[0-9])(?![0-9])");

    public static boolean protectedField(UiNode n) {
        return n.password || (n.editable && n.rid != null && PROTECTED_RID.matcher(n.rid).matches());
    }

    /** 打码：身份证号、银行卡号只留后 4 位。 */
    public static String mask(String s) {
        if (s == null || s.length() < 13) return s == null ? "" : s;
        String out = replace(ID_CARD, s);
        return replace(CARD, out);
    }

    private static String replace(Pattern p, String s) {
        java.util.regex.Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            String g = m.group(1);
            String digits = g.replaceAll("[^0-9Xx]", "");
            if (p == CARD && digits.length() < 13) continue;
            any = true;
            String tail = digits.substring(Math.max(0, digits.length() - 4));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("****" + tail));
        }
        if (!any) return s;
        m.appendTail(sb);
        return sb.toString();
    }
}
