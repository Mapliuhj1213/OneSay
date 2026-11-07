package com.onesay.assistant.v2.vision.screenref;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [screenref] 宽容的 uiautomator dump XML 解析器（纯 Java，JVM 可测）。
 *
 * <h3>为什么不用 XmlPullParser / SAX</h3>
 * <ul>
 *   <li>真机 dump 里偶尔会带控制字符、截断的尾巴（dump 被 timeout 杀掉时），严格解析器会整份失败；</li>
 *   <li>我们只关心 {@code <node .../>} 的几个属性，不需要层级；</li>
 *   <li>XmlPullParser 在 JVM 单测里是 android.jar 的 Stub，不可用。</li>
 * </ul>
 * 因此用正则逐个扫描 {@code <node ...>} 开标签、逐个取属性，坏掉的节点跳过，不影响其它节点。
 *
 * <h3>隐私</h3>
 * <ul>
 *   <li>{@code password="true"} 的节点、resource-id 像密码/PIN/CVV 的节点：整节点丢弃（计入 {@link Result#redacted}）；</li>
 *   <li>文字里像银行卡号（16~19 位且通过 Luhn 校验）或 18 位身份证号的数字串：打码成 {@code ****1234}。</li>
 *   <li>自家悬浮面板（{@code ownPackage}）的节点丢弃，避免把"我在听，请讲"当成屏幕内容。</li>
 * </ul>
 */
public final class UiDumpParser {

    private UiDumpParser() {}

    /** 解析结果。 */
    public static final class Result {
        public final List<UiNode> nodes;
        /** 第一个节点（根）的 package；空 dump 为 null。 */
        public final String rootPackage;
        /** 原始节点总数（含被丢弃的）。 */
        public final int totalNodes;
        /** 因隐私规则丢弃/打码的节点数。 */
        public final int redacted;
        /** 因属于自家面板而丢弃的节点数。 */
        public final int ownFiltered;

        Result(List<UiNode> nodes, String rootPackage, int totalNodes, int redacted, int ownFiltered) {
            this.nodes = nodes;
            this.rootPackage = rootPackage;
            this.totalNodes = totalNodes;
            this.redacted = redacted;
            this.ownFiltered = ownFiltered;
        }

        /** 有文字（text 或 content-desc）的节点数。 */
        public int textNodeCount() {
            int n = 0;
            for (UiNode u : nodes) if (u.label().trim().length() > 0) n++;
            return n;
        }
    }

    private static final Pattern NODE_TAG = Pattern.compile("<node\\b([^>]*?)/?>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_][\\w\\-:]*)=\"([^\"]*)\"");
    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    private static final Pattern SECRET_ID = Pattern.compile(
            "(?i)(password|passwd|pwd|passcode|pin_?code|pinpad|cvv|cvc|security_?code|paypwd|pay_pwd)");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![0-9A-Za-z])(\\d[\\d ]{14,22}[\\dXx])(?![0-9A-Za-z])");

    public static Result parse(String xml, String ownPackage) {
        if (xml == null) return new Result(Collections.<UiNode>emptyList(), null, 0, 0, 0);
        List<UiNode> out = new ArrayList<UiNode>();
        String rootPkg = null;
        int total = 0, redacted = 0, own = 0;
        Matcher m = NODE_TAG.matcher(xml);
        while (m.find()) {
            total++;
            String attrs = m.group(1);
            String text = null, desc = null, rid = null, cls = null, pkg = null, bounds = null;
            boolean clickable = false, password = false;
            Matcher a = ATTR.matcher(attrs);
            while (a.find()) {
                String k = a.group(1);
                String v = a.group(2);
                if ("text".equals(k)) text = unescape(v);
                else if ("content-desc".equals(k)) desc = unescape(v);
                else if ("resource-id".equals(k)) rid = v;
                else if ("class".equals(k)) cls = v;
                else if ("package".equals(k)) pkg = v;
                else if ("bounds".equals(k)) bounds = v;
                else if ("clickable".equals(k)) clickable = "true".equals(v);
                else if ("password".equals(k)) password = "true".equals(v);
            }
            if (rootPkg == null && pkg != null) rootPkg = pkg;
            if (ownPackage != null && ownPackage.equals(pkg)) {
                own++;
                continue;
            }
            if (password || (rid != null && SECRET_ID.matcher(rid).find())) {
                redacted++;
                continue;
            }
            String t2 = maskSecrets(text);
            String d2 = maskSecrets(desc);
            if ((t2 != null && !t2.equals(text)) || (d2 != null && !d2.equals(desc))) redacted++;
            int l = 0, t = 0, r = 0, b = 0;
            if (bounds != null) {
                Matcher bm = BOUNDS.matcher(bounds);
                if (bm.find()) {
                    try {
                        l = Integer.parseInt(bm.group(1));
                        t = Integer.parseInt(bm.group(2));
                        r = Integer.parseInt(bm.group(3));
                        b = Integer.parseInt(bm.group(4));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            out.add(new UiNode(t2, d2, rid, cls, pkg, clickable, false, l, t, r, b));
        }
        return new Result(out, rootPkg, total, redacted, own);
    }

    /** ★ r2/sysux P2：资源 id 像密码 / 支付码输入框（AssistStructure 来源同一规则）。 */
    public static boolean isSecretId(String rid) {
        return rid != null && SECRET_ID.matcher(rid).find();
    }

    /** 把银行卡号 / 身份证号打码为 ****末四位。其它文字原样返回。 */
    public static String maskSecrets(String s) {
        if (s == null || s.length() < 15) return s;
        Matcher m = LONG_DIGITS.matcher(s);
        StringBuffer sb = null;
        while (m.find()) {
            String g = m.group(1);
            String digits = g.replace(" ", "");
            boolean idCard = digits.length() == 18 && digits.substring(0, 17).matches("\\d{17}")
                    && digits.matches("\\d{17}[\\dXx]") && looksLikeIdCardDate(digits);
            boolean bankCard = digits.matches("\\d{16,19}") && luhn(digits);
            if (!idCard && !bankCard) continue;
            if (sb == null) sb = new StringBuffer();
            m.appendReplacement(sb, Matcher.quoteReplacement("****" + digits.substring(digits.length() - 4)));
        }
        if (sb == null) return s;
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean looksLikeIdCardDate(String d) {
        try {
            int y = Integer.parseInt(d.substring(6, 10));
            int mo = Integer.parseInt(d.substring(10, 12));
            int day = Integer.parseInt(d.substring(12, 14));
            return y >= 1900 && y <= 2100 && mo >= 1 && mo <= 12 && day >= 1 && day <= 31;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (n < 0 || n > 9) return false;
            if (alt) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    static String unescape(String v) {
        if (v == null || v.indexOf('&') < 0) return v;
        StringBuilder sb = new StringBuilder(v.length());
        int i = 0;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (c == '&') {
                int semi = v.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = v.substring(i + 1, semi);
                    String rep = null;
                    if ("amp".equals(ent)) rep = "&";
                    else if ("lt".equals(ent)) rep = "<";
                    else if ("gt".equals(ent)) rep = ">";
                    else if ("quot".equals(ent)) rep = "\"";
                    else if ("apos".equals(ent)) rep = "'";
                    else if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        try { rep = String.valueOf((char) Integer.parseInt(ent.substring(2), 16)); } catch (Exception ignored) {}
                    } else if (ent.startsWith("#")) {
                        try { rep = String.valueOf((char) Integer.parseInt(ent.substring(1))); } catch (Exception ignored) {}
                    }
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * 解析 {@code dumpsys activity activities} 里的 mResumedActivity / mFocusedActivity 行，
     * 返回 {pkg, activity}；解析不到返回 null。
     * 例：{@code mResumedActivity: ActivityRecord{3a1b2c u0 com.tencent.mm/.ui.LauncherUI t123}}
     */
    public static String[] parseResumedActivity(String dumpsysOut) {
        if (dumpsysOut == null) return null;
        Matcher m = Pattern.compile("(?:mResumedActivity|mFocusedActivity|ResumedActivity|mCurrentFocus)[^\\n]*?\\s([\\w.]+)/([\\w.$]+)")
                .matcher(dumpsysOut);
        if (!m.find()) return null;
        String pkg = m.group(1);
        String act = m.group(2);
        if (act.startsWith(".")) act = pkg + act;
        return new String[] {pkg, act};
    }
}
