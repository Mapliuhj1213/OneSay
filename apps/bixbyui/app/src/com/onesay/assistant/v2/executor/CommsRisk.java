package com.onesay.assistant.v2.executor;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * sms.send / phone.call 风险分级（纯函数，JVM 全测）。
 *
 * <table>
 *   <tr><th>情况</th><th>分级</th><th>理由</th></tr>
 *   <tr><td>phone.call 号码含 * # , ; 或字母</td><td>拒绝</td><td>ACTION_CALL 会把 {@code *#06#}、{@code **21*…#}（呼叫转移）这类 MMI/USSD 码
 *       直接交给拨号器执行；{@code , ;} 是接通后自动按键（DTMF）——都不是「打电话」</td></tr>
 *   <tr><td>号码去掉空格/横线后不是 3–15 位数字（可带 +）</td><td>拒绝</td><td>不是电话号码（多半是模型把人名塞进了号码）</td></tr>
 *   <tr><td>phone.call 正常号码</td><td>红卡</td><td>ACTION_CALL 立即拨出、产生费用、不可撤回 → 不给「始终允许」</td></tr>
 *   <tr><td>phone.call 紧急号码（110/119/120/122/112/999/911）</td><td>红卡 + 卡片注明</td><td>误拨后果大</td></tr>
 *   <tr><td>sms.send</td><td>黄卡（不可信来源时由 ToolGate 升红卡）</td><td>只是用 SENDTO 打开短信编辑页、预填正文，真正发送还要你在短信 App 里点「发送」</td></tr>
 * </table>
 */
public final class CommsRisk {

    public enum Level { NOT_COMMS, DENY, RED, YELLOW }

    public static final class Assessment {
        public final Level level;
        public final String reason;
        public final String normalizedNumber;
        Assessment(Level level, String reason, String normalizedNumber) {
            this.level = level; this.reason = reason; this.normalizedNumber = normalizedNumber;
        }
    }

    static final Set<String> EMERGENCY = new HashSet<String>(Arrays.asList(
            "110", "119", "120", "122", "112", "999", "911"));

    private CommsRisk() {}

    public static boolean isComms(String sign) {
        return "phone.call".equals(sign) || "sms.send".equals(sign);
    }

    /** 去掉人类可读的分隔符（空格、横线、括号、点），其它字符原样保留以便判定。 */
    public static String normalize(String number) {
        if (number == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' || c == '\u3000' || c == '\u00a0') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 「呼叫：妈妈 138 0013 8000」/「短信发给：10086」。
     * 联系人名来自本机通讯录解析（contacts/ContactMatcher 写进 args.contact_name）。不是通信类返回 null。
     */
    public static String cardNote(String sign, JSONObject args) {
        if (!isComms(sign) || args == null) return null;
        String num = args.optString("number", "").trim();
        if (num.length() == 0) return null;
        String name = args.optString("contact_name", "").trim();
        String who = name.length() > 0 ? name + " " + num : num;
        return ("phone.call".equals(sign) ? "呼叫：" : "短信发给：") + who;
    }

    public static Assessment assess(String sign, JSONObject args) {
        if (!isComms(sign)) return new Assessment(Level.NOT_COMMS, "", "");
        String raw = args == null ? "" : args.optString("number", "");
        String n = normalize(raw);
        if (n.length() == 0) return new Assessment(Level.DENY, "没有电话号码", n);
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '*' || c == '#' || c == ',' || c == ';' || c == 'p' || c == 'P' || c == 'w' || c == 'W') {
                return new Assessment(Level.DENY, "号码里有 * # , ; 等控制字符（可能是拨号指令 / 呼叫转移码），不执行", n);
            }
        }
        String digits = n.startsWith("+") ? n.substring(1) : n;
        if (digits.length() < 3 || digits.length() > 15) {
            return new Assessment(Level.DENY, "「" + raw + "」不像电话号码", n);
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return new Assessment(Level.DENY, "「" + raw + "」不像电话号码", n);
            }
        }
        if ("sms.send".equals(sign)) {
            return new Assessment(Level.YELLOW, "打开短信编辑页（还需要你在短信 App 里点发送）", n);
        }
        if (EMERGENCY.contains(digits)) {
            return new Assessment(Level.RED, "紧急号码 " + digits + "：会立即拨出", n);
        }
        return new Assessment(Level.RED, "会立即拨出并产生通话费用", n);
    }
}
