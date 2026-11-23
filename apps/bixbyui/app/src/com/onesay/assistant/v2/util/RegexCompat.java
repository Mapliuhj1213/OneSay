package com.onesay.assistant.v2.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Android 老版本（API 24–27，ICU 56–60）正则兼容层。纯 Java，无 android 依赖，可在 JVM 单测中使用。
 *
 * <p>背景（a8fix-1118，Note 8 / Android 8.0 真机）：Android 的 {@code java.util.regex} 是 ICU 正则，
 * 不认 Java 专有的脚本 / 区块写法 {@code \p{IsHan}}、{@code \p{IsHiragana}}、{@code \p{InXxx}}、{@code \p{script=…}}；
 * 写在 static 字段里会让整个类初始化失败（PatternSyntaxException → ExceptionInInitializerError），
 * 一发指令就崩。JVM 单测和新系统都过，所以只在老机器上暴露。另外 {@link Matcher#group(String)} 是 API 26 才有的，
 * Android 7.x 上调用会 NoSuchMethodError。
 *
 * <p>规则：
 * <ul>
 *   <li>汉字字符类一律用 {@link #HAN}（显式码位区间，Java 与 ICU 都认 {@code \x{…}}，含扩展 B–I 等增补平面，
 *       与 Unicode 15.1 的 Script=Han 一致）；代码里判断单个码位用 {@link #isHan(int)}（Character.UnicodeScript，API 24 起可用）；</li>
 *   <li>具名分组取值用 {@link #group(Matcher, String)}，不要直接调 {@code matcher.group("name")}；</li>
 *   <li>static 正则若含新写法，用 {@link #compile(String, int, String)} 带一个保底写法，类初始化永不因正则失败。</li>
 * </ul>
 * 回归：test-jvm …/util/RegexCompatTest 扫全部生产源码，禁止 {@code \p{Is…}} / {@code \p{In…}} / {@code \p{script…}} 等写法。
 */
public final class RegexCompat {

    private RegexCompat() {}

    /**
     * 汉字（Script=Han）字符类的**内容**（不带方括号），用法：{@code "[A-Za-z" + RegexCompat.HAN + "]"}。
     * 部首补充 / 康熙部首 / 々〇〡–〩〸–〻 / 扩展 A / 基本区 / 兼容区 / 增补平面（扩展 B–I、兼容补充、扩展 G–H）。
     * 不含假名（平假名 3040–309F、片假名 30A0–30FF）、注音、谚文、拉丁。
     */
    public static final String HAN =
            "\\x{2E80}-\\x{2E99}\\x{2E9B}-\\x{2EF3}\\x{2F00}-\\x{2FD5}"
            + "\\x{3005}\\x{3007}\\x{3021}-\\x{3029}\\x{3038}-\\x{303B}"
            + "\\x{3400}-\\x{4DBF}\\x{4E00}-\\x{9FFF}\\x{F900}-\\x{FA6D}\\x{FA70}-\\x{FAD9}"
            + "\\x{16FE2}-\\x{16FE3}\\x{16FF0}-\\x{16FF1}"
            + "\\x{20000}-\\x{2A6DF}\\x{2A700}-\\x{2EBE0}\\x{2EBF0}-\\x{2EE5D}"
            + "\\x{2F800}-\\x{2FA1D}\\x{30000}-\\x{3134A}\\x{31350}-\\x{323AF}";

    /** 只要常用汉字时的窄区间（扩展 A + 基本区 + 兼容区，全在 BMP）。 */
    public static final String HAN_CORE = "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF";

    /** 代码里判断单个码位是不是汉字（API 24+ 可用，与系统 Unicode 版本同步）。 */
    public static boolean isHan(int codePoint) {
        try {
            return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 字符串里有没有汉字（按码位走，增补平面安全）。 */
    public static boolean containsHan(CharSequence s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            if (isHan(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * 编译正则；主写法在本机正则引擎上编不过时（老 Android ICU）记日志并改用保底写法，
     * 保底也编不过 → 返回永不匹配的正则。**永不抛异常**，适合 static 字段。
     */
    public static Pattern compile(String regex, int flags, String fallback) {
        try {
            return Pattern.compile(regex, flags);
        } catch (IllegalArgumentException e) {   // PatternSyntaxException 是它的子类；flags 不认也是它
            SafeLog.e("RegexCompat", "正则在本机不兼容，改用保底写法: "
                    + (e instanceof PatternSyntaxException ? ((PatternSyntaxException) e).getDescription() : String.valueOf(e)));
        }
        if (fallback != null) {
            try {
                return Pattern.compile(fallback, flags);
            } catch (IllegalArgumentException e2) {
                SafeLog.e("RegexCompat", "保底正则也不可用: " + e2);
            }
        }
        return NEVER;
    }

    /** 永不匹配。 */
    public static final Pattern NEVER = Pattern.compile("(?!x)x");

    /**
     * 具名分组取值，不依赖 API 26 的 {@link Matcher#group(String)}：按正则源码里 {@code (?<name>} 出现前的捕获组个数算组号。
     * 名字不存在 → {@link IllegalArgumentException}（与 JDK 行为一致）。
     */
    public static String group(Matcher m, String name) {
        int idx = groupIndex(m.pattern().pattern(), name);
        if (idx < 0) throw new IllegalArgumentException("No group with name <" + name + ">");
        return m.group(idx);
    }

    /** 正则源码里具名分组 name 的组号（1 起）；没有 → -1。转义、字符类、{@code \Q…\E} 里的括号不算。 */
    public static int groupIndex(String regex, String name) {
        if (regex == null || name == null) return -1;
        int count = 0;
        boolean inClass = false;
        int depthClass = 0;
        int n = regex.length();
        for (int i = 0; i < n; i++) {
            char c = regex.charAt(i);
            if (c == '\\') {
                if (i + 1 < n && regex.charAt(i + 1) == 'Q') {
                    int e = regex.indexOf("\\E", i + 2);
                    if (e < 0) return -1;
                    i = e + 1;
                } else {
                    i++;
                }
                continue;
            }
            if (inClass) {
                if (c == '[') depthClass++;
                else if (c == ']') { if (--depthClass == 0) inClass = false; }
                continue;
            }
            if (c == '[') {
                inClass = true;
                depthClass = 1;
                // 紧跟的 ] 或 ^] 是字面量
                if (i + 1 < n && regex.charAt(i + 1) == '^') i++;
                if (i + 1 < n && regex.charAt(i + 1) == ']') i++;
                continue;
            }
            if (c != '(') continue;
            if (i + 1 < n && regex.charAt(i + 1) == '?') {
                if (i + 2 < n && regex.charAt(i + 2) == '<' && i + 3 < n
                        && regex.charAt(i + 3) != '=' && regex.charAt(i + 3) != '!') {
                    count++;
                    int close = regex.indexOf('>', i + 3);
                    if (close > 0 && regex.substring(i + 3, close).equals(name)) return count;
                }
                continue;   // 其他 (?…) 不捕获
            }
            count++;
        }
        return -1;
    }

    /**
     * 这个异常是不是「正则在本机不兼容」一类（老 Android ICU 不认写法 / 缺 API）：
     * 因果链里有 PatternSyntaxException，或类初始化失败 / 缺方法。顶层指令入口据此给友好提示而不是崩溃。
     */
    public static boolean isRegexCompatFailure(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof PatternSyntaxException) return true;
            if (t instanceof ExceptionInInitializerError || t instanceof NoClassDefFoundError
                    || t instanceof NoSuchMethodError) return true;
        }
        return false;
    }
}
