package com.onesay.assistant.v2.contacts;

import com.onesay.assistant.v2.executor.CommsRisk;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「打电话给妈妈」：phone.call / sms.send 的目标不是号码时，在<b>本机通讯录</b>里找人（纯 Java，JVM 全测）。
 *
 * <ul>
 *   <li>只在本机匹配：通讯录不上传、不进提示词、不写日志（日志只写匹配条数与打码号码）；</li>
 *   <li>称呼别名：妈妈 / 老妈 / 妈 / 母亲 / mom …（{@link #ALIASES}），爸爸、老婆、老公、爷爷奶奶外公外婆、兄弟姐妹同理；</li>
 *   <li>匹配分两档：<b>精确</b>（联系人名 = 目标或其别名，忽略空格 / 大小写 / 表情）优先；没有精确的才用<b>包含</b>
 *       （名字里含目标或 ≥2 字的别名，例如「妈妈❤」「老妈（家）」）。包含档可能误中「小明妈妈」——所以卡片上一定写出联系人名和号码，由用户确认；</li>
 *   <li>结果：一个号码 → 直接进红卡（打电话）/ 黄卡（短信）；多个联系人或一人多号 → 芯片让用户选；没有 → 「通讯录里没找到 X」。</li>
 * </ul>
 * 号码分级（控制字符 / MMI 码拒绝）仍由 {@link CommsRisk} 在过闸时做，查到的号码同样要过这一关。
 */
public final class ContactMatcher {

    private ContactMatcher() {}

    /** 一条「联系人名 + 号码」（同一人多号 = 多条）。 */
    public static final class Contact {
        public final String name;
        public final String number;

        public Contact(String name, String number) {
            this.name = name == null ? "" : name.trim();
            this.number = number == null ? "" : number.trim();
        }

        /** 芯片 / 卡片上显示的完整文字（屏幕上给用户看，不进日志）。 */
        public String label() {
            return name + " " + number;
        }

        @Override public String toString() {
            return "Contact(" + name.length() + "字, " + mask(number) + ")";
        }
    }

    public enum Kind { NONE, ONE, MANY }

    public static final class Resolution {
        public final Kind kind;
        public final String target;
        public final List<Contact> matches;

        Resolution(Kind kind, String target, List<Contact> matches) {
            this.kind = kind;
            this.target = target;
            this.matches = matches;
        }

        public Contact single() {
            return kind == Kind.ONE ? matches.get(0) : null;
        }
    }

    /** 称呼别名组：组内任一个说法都当同一个人（第一个是规范名）。 */
    static final String[][] ALIASES = {
            {"妈妈", "老妈", "妈", "母亲", "老娘", "娘", "妈咪", "mom", "mum", "mama", "mother"},
            {"爸爸", "老爸", "爸", "父亲", "老爹", "爹", "爹地", "dad", "papa", "father"},
            {"老婆", "媳妇", "媳妇儿", "妻子", "爱人", "太太", "wife"},
            {"老公", "丈夫", "先生", "husband"},
            {"爷爷", "祖父"},
            {"奶奶", "祖母"},
            {"外公", "姥爷", "外祖父"},
            {"外婆", "姥姥", "外祖母"},
            {"哥哥", "哥", "老哥"},
            {"姐姐", "姐", "老姐"},
            {"弟弟", "弟", "老弟"},
            {"妹妹", "妹", "老妹"},
    };

    /** 目标前后的口语壳：「给我妈打个电话」里 L2 偶尔把「我妈」「我的老婆」整个塞进 number。 */
    private static final String[] PREFIX = {"给我的", "给我", "给", "跟我的", "跟我", "跟", "我的", "我", "咱", "俺"};
    private static final String[] SUFFIX = {"的手机号码", "的手机号", "的电话号码", "的电话", "的手机", "手机号", "电话", "手机", "的号码", "号码", "吧", "呀", "啊", "一下"};

    /**
     * 目标需要查通讯录吗：去掉分隔符后<b>不含数字</b>、且有汉字或字母。
     * 含数字的一律不查（「妈妈138」「*#06#」「10086,1」照旧交给 {@link CommsRisk} 判拒绝 / 放行）。
     */
    public static boolean needsLookup(String target) {
        String n = CommsRisk.normalize(target);
        if (n.length() == 0 || n.length() > 30) return false;
        boolean word = false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= '0' && c <= '9') return false;
            if (c == '*' || c == '#' || c == ',' || c == ';' || c == '+') return false;
            if (Character.isLetter(c)) word = true;
        }
        return word;
    }

    /** sms.send / phone.call 的参数需要查通讯录吗。 */
    public static boolean needsLookup(String sign, JSONObject args) {
        return CommsRisk.isComms(sign) && args != null && needsLookup(args.optString("number", ""));
    }

    /** 口语壳剥掉后的目标（「给我妈」→「妈」，「老婆的手机」→「老婆」）。 */
    public static String cleanTarget(String raw) {
        String s = key(raw);
        boolean changed = true;
        while (changed && s.length() > 1) {
            changed = false;
            for (String p : PREFIX) {
                if (s.startsWith(p) && s.length() > p.length()) { s = s.substring(p.length()); changed = true; break; }
            }
            for (String x : SUFFIX) {
                if (s.endsWith(x) && s.length() > x.length()) { s = s.substring(0, s.length() - x.length()); changed = true; break; }
            }
        }
        return s;
    }

    /** 比较用的键：小写，去掉空白、标点、表情等非字母数字字符。 */
    static String key(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        String t = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 目标对应的全部说法（自己 + 所在别名组），规范化后去重。 */
    public static List<String> terms(String target) {
        String t = cleanTarget(target);
        Set<String> out = new LinkedHashSet<String>();
        if (t.length() > 0) out.add(t);
        for (String[] g : ALIASES) {
            if (Arrays.asList(g).contains(t)) {
                for (String a : g) out.add(key(a));
            }
        }
        return new ArrayList<String>(out);
    }

    /** 在给定通讯录（已按 LIKE 预筛或全量）里解析目标。 */
    public static Resolution resolve(String target, List<Contact> book) {
        String t = cleanTarget(target);
        List<String> terms = terms(target);
        List<Contact> exact = new ArrayList<Contact>();
        List<Contact> loose = new ArrayList<Contact>();
        if (book != null && t.length() > 0) {
            for (Contact c : book) {
                if (c == null || c.number.length() == 0) continue;
                String nk = key(c.name);
                if (nk.length() == 0) continue;
                if (terms.contains(nk)) {
                    addUnique(exact, c);
                } else {
                    for (String term : terms) {
                        // 单字别名（妈 / 爸 / 哥）只做精确匹配：包含匹配会把「姑妈」「舅妈」「表哥」都带出来
                        if (term.length() >= 2 && nk.contains(term)) { addUnique(loose, c); break; }
                    }
                }
            }
        }
        List<Contact> use = !exact.isEmpty() ? exact : loose;
        Kind k = use.isEmpty() ? Kind.NONE : (use.size() == 1 ? Kind.ONE : Kind.MANY);
        return new Resolution(k, t.length() > 0 ? t : (target == null ? "" : target.trim()), use);
    }

    private static void addUnique(List<Contact> l, Contact c) {
        String nn = digits(c.number);
        for (Contact o : l) {
            if (key(o.name).equals(key(c.name)) && digits(o.number).equals(nn)) return;
        }
        l.add(c);
    }

    /** 号码里的数字（比较同号用；+86 前缀归一成国内号）。 */
    static String digits(String number) {
        StringBuilder sb = new StringBuilder();
        if (number != null) for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        String d = sb.toString();
        if (d.startsWith("86") && d.length() == 13) d = d.substring(2);
        return d;
    }

    /** 日志用打码：只留后 4 位。 */
    public static String mask(String number) {
        String d = digits(number);
        if (d.length() <= 4) return "****";
        return "***" + d.substring(d.length() - 4);
    }

    /**
     * 解析 {@code content query --uri content://com.android.contacts/data/phones --projection display_name:data1} 的输出：
     * 每行 {@code Row: 0 display_name=张三, data1=138 0000 0000}。名字里有「, data1=」的极端情况按最后一个切。
     */
    public static List<Contact> parseContentQuery(String out) {
        List<Contact> l = new ArrayList<Contact>();
        if (out == null) return l;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("Row:")) continue;
            int a = s.indexOf("display_name=");
            int b = s.lastIndexOf(", data1=");
            if (a < 0 || b < 0 || b < a) continue;
            String name = s.substring(a + "display_name=".length(), b).trim();
            String num = s.substring(b + ", data1=".length()).trim();
            if ("NULL".equals(name)) name = "";
            if ("NULL".equals(num) || num.length() == 0) continue;
            l.add(new Contact(name, num));
        }
        return l;
    }

    /**
     * root 兜底查询的 where 子句（只允许字母 / 汉字，防注入）；没有可用的词返回 null。
     * 单字词也放进 LIKE（预筛宽一点没关系，最终由 {@link #resolve} 判定）。
     */
    public static String likeWhere(List<String> terms) {
        StringBuilder sb = new StringBuilder();
        for (String t : terms) {
            if (!safeTerm(t)) continue;
            if (sb.length() > 0) sb.append(" OR ");
            sb.append("display_name LIKE '%").append(t).append("%'");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static boolean safeTerm(String t) {
        if (t == null || t.length() == 0 || t.length() > 20) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '\u4e00' && c <= '\u9fff');
            if (!ok) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ AgentLoop 侧（宿主装上本机通讯录后才可用）

    /** 本机通讯录查询口（App 启动时由 {@link ContactSource} 装上；JVM 测试装假实现）。 */
    public interface Directory {
        /** 按词预筛（名字包含任一词）；查不了返回 null。 */
        List<Contact> lookup(List<String> terms);
    }

    private static volatile Directory sDirectory;

    public static void install(Directory d) {
        sDirectory = d;
    }

    public static Directory directory() {
        return sDirectory;
    }

    /**
     * AgentLoop 工具调用过闸前：目标是人名 → 本机解析。唯一号码 → 改写 args（number + contact_name），返回 null 继续过闸（红 / 黄卡照旧）；
     * 否则返回给模型的观测（<b>不带</b>任何联系人名或号码，通讯录不出本机）。
     */
    public static String agentPrecheck(String sign, JSONObject args) {
        if (!needsLookup(sign, args)) return null;
        String target = args.optString("number", "");
        Directory d = sDirectory;
        List<Contact> book = d == null ? null : d.lookup(terms(target));
        if (book == null) {
            return "{\"error\":\"「" + safeEcho(target) + "」不是电话号码，本机通讯录暂时查不了。请让用户直接说号码，或在面板上说「打电话给" + safeEcho(target) + "」。\"}";
        }
        Resolution r = resolve(target, book);
        if (r.kind == Kind.NONE) {
            return "{\"error\":\"通讯录里没找到「" + safeEcho(r.target) + "」。请如实告诉用户，不要猜号码。\"}";
        }
        if (r.kind == Kind.MANY) {
            return "{\"error\":\"通讯录里「" + safeEcho(r.target) + "」有 " + r.matches.size()
                    + " 个号码，需要用户自己选。请结束任务并告诉用户在面板上直接说「打电话给" + safeEcho(r.target) + "」，会弹出选项。\"}";
        }
        apply(args, r.single());
        return null;
    }

    /** 把选定的联系人写进参数（号码 + 卡片显示名）。 */
    public static void apply(JSONObject args, Contact c) {
        if (args == null || c == null) return;
        try {
            args.put("number", c.number);
            args.put("contact_name", c.name);
        } catch (Exception ignored) {
        }
    }

    private static String safeEcho(String s) {
        String k = s == null ? "" : s.replace("\"", "").replace("\\", "");
        return k.length() > 12 ? k.substring(0, 12) : k;
    }
}
