package com.onesay.desktop;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [app-whitelist-1116] 隐私资料识别（纯 Java，三端除 package 行外逐字相同）。只回「是哪几类」，从不回原文，日志也只写类别。
 *
 * <p>认的类别：身份证号（18 位带校验位 / 15 位旧号）、银行卡号（2–6 开头 15–19 位，Luhn 校验）、手机号（大陆 1[3-9] 开头 11 位，可带 +86）、邮箱、
 * 住址（「地址 / 住址 / 收货地址」后面跟内容，或「省 / 市 / 区 / 县 … 路 / 街 / 巷 / 弄 / 道 … 号 / 栋 / 室」这类组合）、
 * 真名（「姓名 / 真实姓名 / 收件人 / 联系人 / 持卡人」后面跟 2–4 个汉字）、证件 / 卡片关键词（居民身份证、公民身份号码、银行卡号、开户行、CVV、护照号…），
 * 以及手机端脱敏后的占位符（[手机号#1]、[身份证#2]…：说明原文是隐私，只是被换掉了）。
 * 全角数字 / 字母先折成半角，数字之间的空格 / 横线 / 零宽字符不影响识别。
 *
 * <p>图片：{@link #image}。有识别出的文字就按文字判，再看证件 / 银行卡 / 地址截图的关键词；<b>没有文字（没做 OCR、OCR 失败、认不出）的一律当隐私</b>
 * （外发要确认，宁可多问）。
 */
public final class PrivacyScan {

    private PrivacyScan() {}

    public static final String ID_CARD = "id_card", BANK_CARD = "bank_card", PHONE = "phone", EMAIL = "email", ADDRESS = "address",
            NAME = "real_name", DOC_WORD = "doc_keyword", PLACEHOLDER = "redacted", IMAGE_UNKNOWN = "image_unknown";

    /** 类别 → 中文（确认卡 / 日志用）。 */
    public static String zh(String kind) {
        if (ID_CARD.equals(kind)) return "身份证号";
        if (BANK_CARD.equals(kind)) return "银行卡号";
        if (PHONE.equals(kind)) return "手机号";
        if (EMAIL.equals(kind)) return "邮箱";
        if (ADDRESS.equals(kind)) return "住址";
        if (NAME.equals(kind)) return "真名";
        if (DOC_WORD.equals(kind)) return "证件 / 银行卡资料";
        if (PLACEHOLDER.equals(kind)) return "已脱敏的个人信息";
        if (IMAGE_UNKNOWN.equals(kind)) return "认不出内容的图片";
        return kind;
    }

    public static String zh(Set<String> kinds) {
        StringBuilder b = new StringBuilder();
        if (kinds != null) for (String k : kinds) {
            if (b.length() > 0) b.append("、");
            b.append(zh(k));
        }
        return b.toString();
    }

    private static final Pattern EMAIL_RE = Pattern.compile("(?<![A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]{1,63}(?:\\.[A-Za-z0-9\\-]{1,63})*\\.[A-Za-z]{2,24}(?![A-Za-z])");
    private static final Pattern ID18 = Pattern.compile("(?<![0-9A-Za-z])[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9A-Za-z])");
    private static final Pattern ID15 = Pattern.compile("(?<![0-9A-Za-z])[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}(?![0-9A-Za-z])");
    private static final Pattern DIGITS = Pattern.compile("(?<![0-9])[2-6]\\d{14,18}(?![0-9])");
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])(?:\\+?86)?1[3-9]\\d{9}(?![0-9])");
    private static final Pattern ADDR_KW = Pattern.compile("(收货地址|家庭住址|居住地址|通讯地址|联系地址|详细地址|现住址|住址|地址)\\s*[:：]?\\s*[\\u4e00-\\u9fa5A-Za-z0-9]{4,}");
    private static final Pattern ADDR_SHAPE = Pattern.compile("[\\u4e00-\\u9fa5]{1,8}(省|市|区|县|州|旗)[\\u4e00-\\u9fa5A-Za-z0-9]{0,20}(路|街|巷|弄|道|胡同|大街|村|镇|乡|小区|花园|苑|公寓|大厦)[\\u4e00-\\u9fa5A-Za-z0-9\\-]{0,20}(号|栋|幢|座|单元|室|楼|层)");
    private static final Pattern NAME_KW = Pattern.compile("(姓名|真实姓名|收件人|收货人|联系人|持卡人|户名|开户名)\\s*[:：]?\\s*[\\u4e00-\\u9fa5·]{2,5}");
    private static final Pattern DOC_KW = Pattern.compile("居民身份证|公民身份号码|身份证号|身份证号码|银行卡号|信用卡号|卡号\\s*[:：]|开户行|开户银行|cvv|cvc|安全码|有效期至|护照号|passport\\s*no|社保卡号|驾驶证号", Pattern.CASE_INSENSITIVE);
    private static final Pattern PH_RE = Pattern.compile("\\[?(手机号|身份证|银行卡|邮箱|验证码|住址|姓名)#\\d{1,4}\\]?");
    /** 图片里常见的证件 / 银行卡 / 地址截图字样。 */
    private static final Pattern IMG_KW = Pattern.compile("中华人民共和国|居民身份证|公民身份|签发机关|有效期限|银联|unionpay|visa|mastercard|debit|credit\\s*card|银行|收货地址|配送地址|快递单|运单号|收件人", Pattern.CASE_INSENSITIVE);

    /** 文字里有哪几类隐私资料（空集合 = 没认出）。 */
    public static Set<String> text(String raw) {
        Set<String> out = new LinkedHashSet<String>();
        if (raw == null || raw.length() == 0) return out;
        String s = fold(raw);
        String glued = glue(s);
        if (PH_RE.matcher(s).find()) out.add(PLACEHOLDER);
        if (ID18.matcher(glued).find() && idOk(glued)) out.add(ID_CARD);
        else if (ID15.matcher(glued).find()) out.add(ID_CARD);
        Matcher d = DIGITS.matcher(glued);
        while (d.find()) {
            String g = d.group();
            if (g.length() == 18 && out.contains(ID_CARD)) continue;
            if (luhn(g)) { out.add(BANK_CARD); break; }
        }
        if (MOBILE.matcher(glued).find()) out.add(PHONE);
        if (EMAIL_RE.matcher(s).find()) out.add(EMAIL);
        if (ADDR_KW.matcher(s).find() || ADDR_SHAPE.matcher(s).find()) out.add(ADDRESS);
        if (NAME_KW.matcher(s).find()) out.add(NAME);
        if (DOC_KW.matcher(s).find()) out.add(DOC_WORD);
        return out;
    }

    public static boolean any(String raw) { return !text(raw).isEmpty(); }

    /**
     * 图片：ocr = 识别出的文字（null / 空 = 没有文字可看）。没有文字 → {@link #IMAGE_UNKNOWN}（当隐私）；有文字 → 文字的类别，
     * 外加证件 / 银行卡 / 地址截图关键词（{@link #DOC_WORD}）。
     */
    public static Set<String> image(String ocr) {
        Set<String> out = new LinkedHashSet<String>();
        if (ocr == null || ocr.trim().length() == 0) {
            out.add(IMAGE_UNKNOWN);
            return out;
        }
        out.addAll(text(ocr));
        if (IMG_KW.matcher(fold(ocr)).find()) out.add(DOC_WORD);
        return out;
    }

    /** 全角 → 半角；去零宽 / 方向控制符。 */
    static String fold(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            else if (c == 0x3000) c = ' ';
            if ((c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2060 && c <= 0x2069) || c == 0xfeff || c == 0x00ad) continue;
            b.append(c);
        }
        return b.toString();
    }

    /** 数字之间的空格 / 横线 / 点去掉（「6222 0212 3456 7890」→ 连着的数字）。 */
    static String glue(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == ' ' || c == '-' || c == '\u00a0') && i > 0 && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)) && Character.isDigit(lastNonSep(s, i))) continue;
            b.append(c);
        }
        return b.toString();
    }

    private static char lastNonSep(String s, int i) {
        for (int k = i - 1; k >= 0; k--) {
            char c = s.charAt(k);
            if (c != ' ' && c != '-' && c != '\u00a0') return c;
        }
        return ' ';
    }

    static boolean idOk(String s) {
        Matcher m = ID18.matcher(s);
        while (m.find()) {
            String id = m.group().toUpperCase(Locale.ROOT);
            int[] w = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
            String c = "10X98765432";
            int sum = 0;
            for (int i = 0; i < 17; i++) sum += (id.charAt(i) - '0') * w[i];
            if (c.charAt(sum % 11) == id.charAt(17)) return true;
        }
        return false;
    }

    static boolean luhn(String d) {
        int sum = 0;
        boolean alt = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (alt) { n *= 2; if (n > 9) n -= 9; }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    /**
     * 给日志用：把一段文字里可能的隐私（邮箱、长数字、手机号）换成「*」。审计日志本来只写类别和应用 id，这里是最后一道保险。
     */
    public static String scrub(String line) {
        if (line == null) return "";
        String s = fold(line);
        s = EMAIL_RE.matcher(s).replaceAll("<邮箱>");
        s = Pattern.compile("(?<![0-9])(?:\\+?86)?1[3-9](?:[ \\-]?\\d){9}(?![0-9])").matcher(s).replaceAll("<号码>");
        s = Pattern.compile("(?<![0-9A-Za-z])\\d(?:[ \\-]?\\d){11,}[0-9Xx]?").matcher(s).replaceAll("<数字>");
        return s;
    }
}
