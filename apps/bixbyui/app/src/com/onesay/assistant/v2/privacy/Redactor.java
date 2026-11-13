package com.onesay.assistant.v2.privacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ borrow-1112：发给云端模型前的本地脱敏（纯 Java，JVM 可测）。
 *
 * <p>读屏文字 / 用户原话 / 工具结果里的手机号、身份证号、银行卡号、邮箱、验证码，发出去之前换成稳定占位符
 * （{@code [手机号#1]}、{@code [身份证#1]}、{@code [银行卡#1]}、{@code [邮箱#1]}、{@code [验证码#1]}）：
 * <ul>
 *   <li><b>同一值同一占位符</b>，一个 Redactor 对应一次任务（任务内有效）；不同任务换新实例，编号从 1 重来；</li>
 *   <li>模型要输入 / 引用时写占位符，本地 {@link #restore} 换回真实值（输入框里填的、工具参数里的、给用户看的答复里的）；</li>
 *   <li>映射<b>只在内存</b>：不落盘、不进日志（{@link #toString} 只给条数）。</li>
 * </ul>
 * 规则型识别（不含 NER 模型）：身份证 18 位校验码对上才算；银行卡 16–19 位且 Luhn 校验通过才算；手机号 1[3-9] 开头 11 位
 * （可带 +86 / 空格 / 短横）；验证码只认「验证码 / 校验码 / 动态码 / 确认码 / code」附近的 4–8 位数字。
 * 截图（图片）里的文字本地没有 OCR，脱不了，见报告。
 *
 * <p>思路来源：android-remote-control-mcp（https://github.com/danielealbano/android-remote-control-mcp，GPL-3.0-or-later）的
 * Privacy Mode（识别个人信息、换成稳定占位符、输入占位符时在手机本地换回）。只读了它的说明文档，<b>没有看也没有用它的代码</b>，
 * 实现是按这个思路自己写的。
 */
public final class Redactor {

    public enum Kind {
        PHONE("手机号"), ID_CARD("身份证"), BANK_CARD("银行卡"), EMAIL("邮箱"), CODE("验证码");

        public final String zh;

        Kind(String zh) {
            this.zh = zh;
        }
    }

    static final Pattern EMAIL = Pattern.compile("(?<![A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]{1,63}(?:\\.[A-Za-z0-9\\-]{1,63})*\\.[A-Za-z]{2,24}(?![A-Za-z])");
    static final Pattern ID18 = Pattern.compile("(?<![0-9A-Za-z])([1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx])(?![0-9A-Za-z])");
    /** 16–19 位，可每 4 位一组用空格 / 短横隔开。 */
    static final Pattern CARD = Pattern.compile("(?<![0-9])((?:\\d{4}[ \\-]?){3}\\d{4}(?:[ \\-]?\\d{1,3})?)(?![0-9])");
    static final Pattern PHONE = Pattern.compile("(?<![0-9])((?:\\+?86[ \\-]?)?1[3-9]\\d[ \\-]?\\d{4}[ \\-]?\\d{4})(?![0-9])");
    static final Pattern CODE = Pattern.compile("(验证码|校验码|动态码|确认码|动态密码|短信码|verification code|code)[^0-9]{0,12}?(?<![0-9])(\\d{4,8})(?![0-9])",
            Pattern.CASE_INSENSITIVE);
    /** 占位符（带不带方括号都认）。 */
    static final Pattern PLACEHOLDER = Pattern.compile("\\[?(手机号|身份证|银行卡|邮箱|验证码)#(\\d{1,4})\\]?");

    private final Map<String, String> mValueToTag = new LinkedHashMap<String, String>();
    private final Map<String, String> mTagToValue = new LinkedHashMap<String, String>();
    private final int[] mSeq = new int[Kind.values().length];

    /** 换掉文本里的个人信息（null 原样返回）。 */
    public synchronized String redact(String text) {
        if (text == null || text.length() == 0) return text;
        String s = text;
        s = replace(s, EMAIL, 0, Kind.EMAIL);
        s = replaceChecked(s, ID18, 1, Kind.ID_CARD);
        s = replaceChecked(s, CARD, 1, Kind.BANK_CARD);
        s = replace(s, PHONE, 1, Kind.PHONE);
        s = replace(s, CODE, 2, Kind.CODE);
        return s;
    }

    /** 把占位符换回真实值（认不出的占位符原样留着）。 */
    public synchronized String restore(String text) {
        if (text == null || text.length() == 0 || mTagToValue.isEmpty()) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String v = mTagToValue.get(m.group(1) + "#" + m.group(2));
            m.appendReplacement(b, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** 文本里有没有占位符。 */
    public static boolean hasPlaceholder(String text) {
        return text != null && PLACEHOLDER.matcher(text).find();
    }

    public synchronized int size() {
        return mTagToValue.size();
    }

    /** 清空（任务结束）。 */
    public synchronized void clear() {
        mValueToTag.clear();
        mTagToValue.clear();
        for (int i = 0; i < mSeq.length; i++) mSeq[i] = 0;
    }

    /** 不暴露任何值。 */
    @Override public synchronized String toString() {
        return "Redactor(" + mTagToValue.size() + " 项)";
    }

    // ------------------------------------------------------------------

    private String tag(Kind k, String raw) {
        String norm = normalize(k, raw);
        String key = k.name() + ":" + norm;
        String t = mValueToTag.get(key);
        if (t == null) {
            t = k.zh + "#" + (++mSeq[k.ordinal()]);
            mValueToTag.put(key, t);
            mTagToValue.put(t, raw);
        }
        return "[" + t + "]";
    }

    /** 同一值的不同写法（空格 / 短横 / +86 / 大小写）算同一个。 */
    static String normalize(Kind k, String raw) {
        String r = raw.trim();
        if (k == Kind.EMAIL) return r.toLowerCase(java.util.Locale.ROOT);
        String d = r.replaceAll("[ \\-]", "");
        if (k == Kind.PHONE && d.startsWith("+86")) d = d.substring(3);
        else if (k == Kind.PHONE && d.length() == 13 && d.startsWith("86")) d = d.substring(2);
        return d.toUpperCase(java.util.Locale.ROOT);
    }

    private String replace(String s, Pattern p, int group, Kind k) {
        Matcher m = p.matcher(s);
        StringBuffer b = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            any = true;
            String whole = m.group(), val = m.group(group);
            if (inPlaceholder(s, m.start())) {
                m.appendReplacement(b, Matcher.quoteReplacement(whole));
                continue;
            }
            int vs = m.start(group) - m.start();
            String rep = whole.substring(0, vs) + tag(k, val) + whole.substring(vs + val.length());
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        if (!any) return s;
        m.appendTail(b);
        return b.toString();
    }

    private String replaceChecked(String s, Pattern p, int group, Kind k) {
        Matcher m = p.matcher(s);
        StringBuffer b = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            any = true;
            String val = m.group(group);
            boolean ok = k == Kind.ID_CARD ? idChecksumOk(val) : luhnOk(val.replaceAll("[ \\-]", ""));
            m.appendReplacement(b, Matcher.quoteReplacement(ok ? m.group().replace(val, tag(k, val)) : m.group()));
        }
        if (!any) return s;
        m.appendTail(b);
        return b.toString();
    }

    private static boolean inPlaceholder(String s, int at) {
        int open = s.lastIndexOf('[', at);
        int close = s.lastIndexOf(']', at);
        if (open < 0 || open < close) return false;
        int end = s.indexOf(']', at);
        return end > 0 && PLACEHOLDER.matcher(s.substring(open, end + 1)).matches();
    }

    /** 18 位身份证校验码（GB 11643）。 */
    static boolean idChecksumOk(String id) {
        if (id == null || id.length() != 18) return false;
        int[] w = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] c = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char ch = id.charAt(i);
            if (ch < '0' || ch > '9') return false;
            sum += (ch - '0') * w[i];
        }
        return Character.toUpperCase(id.charAt(17)) == c[sum % 11];
    }

    static boolean luhnOk(String digits) {
        if (digits == null || digits.length() < 16 || digits.length() > 19) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char ch = digits.charAt(i);
            if (ch < '0' || ch > '9') return false;
            int d = ch - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    /** 测试 / 报告用：本次换掉了哪几类各几个（不含值）。 */
    public synchronized List<String> kindsSummary() {
        List<String> out = new ArrayList<String>();
        for (Kind k : Kind.values()) if (mSeq[k.ordinal()] > 0) out.add(k.zh + "×" + mSeq[k.ordinal()]);
        return out;
    }
}
