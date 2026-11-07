package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * r2/sec-logic LOGIC-6 / LOGIC-7b：确认卡上必须让用户<b>看到真实参数</b>（纯 Java）。
 *
 * <p>渗透测试 logic §6：确认卡原来只显示 {@code cap.summary}；远程 MCP 工具的 summary 就是远端 server 写的 description
 * （可以写成「查天气」，实参却是把通讯录发给攻击者）。邮件卡（MailConfirmActivity）已经显示收件人 / 主题 / 正文，本类把其余所有确认卡补齐：
 * <ul>
 *   <li>收件人 / 号码（to / recipient / email / number / phone / cc / bcc …）——<b>完整显示，不截断</b>；</li>
 *   <li>网址只显示 host（并标出非 https）；</li>
 *   <li>金额（amount / price / total …）——完整显示；</li>
 *   <li>短信：将预填的正文；</li>
 *   <li>远程工具：真实 sign（不是 description）+ 参数摘要；description 标注「由外部服务器提供」。</li>
 * </ul>
 * 键名带 token / password / secret / key 的值一律不显示（只写「已隐藏」）。
 */
public final class ConfirmFacts {

    private ConfirmFacts() {}

    static final String[] RECIPIENT_KEYS = {"to", "recipient", "recipients", "email", "emails", "address", "addresses", "cc", "bcc",
            "number", "phone", "phone_number", "tel", "mobile", "contact", "contact_name", "channel", "user", "username", "chat_id",
            "target", "receiver", "destination", "webhook"};
    static final String[] URL_KEYS = {"url", "uri", "link", "href", "endpoint", "page", "site"};
    static final String[] AMOUNT_KEYS = {"amount", "price", "total", "money", "sum", "fee", "cost", "value_cny", "pay_amount"};
    static final String[] BODY_KEYS = {"body", "text", "message", "content", "msg", "sms_body"};
    static final String[] SECRET_HINTS = {"token", "password", "passwd", "secret", "apikey", "api_key", "credential", "otp", "pin", "cvv"};

    /** 远端说明的标注。 */
    public static final String REMOTE_DESC_LABEL = "说明（由外部服务器提供，仅供参考）：";

    public static boolean isRemote(Capability cap) {
        return cap != null && cap.sign != null && cap.sign.startsWith("conn.");
    }

    /** 有副作用（需要在卡上看实参）的能力。只读类不加明细。 */
    public static boolean hasSideEffect(Capability cap) {
        if (cap == null) return false;
        if (isRemote(cap)) return true;
        return cap.sideEffect != null && cap.sideEffect != SideEffect.NONE && cap.sideEffect != SideEffect.PRIVATE_DATA_READ;
    }

    /**
     * 卡片标题行：本机能力 = cap.summary；远程工具 = 「调用远程工具 conn.x.y」（不再用远端 description 当标题）。
     */
    public static String headline(Capability cap) {
        if (cap == null) return "这个操作";
        if (isRemote(cap)) return "调用远程工具「" + cap.sign + "」";
        return cap.summary == null || cap.summary.length() == 0 ? cap.sign : cap.summary;
    }

    /**
     * 卡片上的关键实参行（多行，换行分隔；没有可显示的返回 null）。收件人 / 金额 / 网址 host 不截断。
     */
    public static String lines(Capability cap, JSONObject args) {
        List<String> out = new ArrayList<String>();
        if (cap == null) return null;
        boolean remote = isRemote(cap);
        JSONObject a = args == null ? new JSONObject() : args;
        List<String> used = new ArrayList<String>();

        String rcpt = collect(a, RECIPIENT_KEYS, used);
        if (rcpt.length() > 0 && !(CommsRisk.isComms(cap.sign) && (used.contains("number") || used.contains("contact_name")))) {
            out.add("收件人 / 对象：" + rcpt);
        } else if (rcpt.length() > 0 && CommsRisk.isComms(cap.sign)) {
            // 号码行已由 CommsRisk.cardNote 显示；这里只补号码以外的对象
            String other = collectExcept(a, RECIPIENT_KEYS, new String[]{"number", "contact_name"});
            if (other.length() > 0) out.add("对象：" + other);
        }
        for (String k : URL_KEYS) {
            String v = str(a, k);
            if (v.length() == 0) continue;
            used.add(k);
            String host = host(v);
            boolean https = v.toLowerCase(Locale.ROOT).startsWith("https://");
            out.add("网址：" + (host.length() > 0 ? host : clip(v, 60)) + (https || host.length() == 0 ? "" : "（非 https）"));
        }
        String amt = collect(a, AMOUNT_KEYS, used);
        if (amt.length() > 0) out.add("金额：" + amt);
        if ("sms.send".equals(cap.sign) || cap.sign.startsWith("sms.")) {
            String body = firstOf(a, BODY_KEYS, used);
            out.add(body.length() > 0 ? "短信内容（将预填）：" + clip(body, 200) : "短信内容：（空，进短信 App 后自己填）");
        }
        if (remote) {
            String rest = summarizeRest(a, used, 6, 80);
            out.add("参数：" + (rest.length() > 0 ? rest : "（无）"));
            if (cap.summary != null && cap.summary.length() > 0) out.add(REMOTE_DESC_LABEL + clip(cap.summary, 120));
        } else if (hasSideEffect(cap) && !CommsRisk.isComms(cap.sign) && !"sms.send".equals(cap.sign)) {
            String rest = summarizeRest(a, used, 4, 60);
            if (rest.length() > 0) out.add("参数：" + rest);
        }
        if (out.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String s : out) {
            if (b.length() > 0) b.append("\n");
            b.append(s);
        }
        return b.toString();
    }

    /**
     * 岛上（无人值守）确认卡的明细：关键字段（收件人 / 金额 / 网址 host）<b>不截断、排最前</b>，其余参数再按 60 字收尾（LOGIC-7b）。
     */
    public static String islandDetail(Capability cap, JSONObject args) {
        if (args == null) return null;
        List<String> used = new ArrayList<String>();
        StringBuilder key = new StringBuilder();
        String r = collect(args, RECIPIENT_KEYS, used);
        if (r.length() > 0) key.append("对象 ").append(r);
        String amt = collect(args, AMOUNT_KEYS, used);
        if (amt.length() > 0) append(key, "金额 " + amt);
        for (String k : URL_KEYS) {
            String v = str(args, k);
            if (v.length() == 0) continue;
            used.add(k);
            String h = host(v);
            append(key, "网址 " + (h.length() > 0 ? h : clip(v, 40)));
        }
        if (cap != null && isRemote(cap)) append(key, "远程工具 " + cap.sign);
        String rest = summarizeRest(args, used, 6, 60);
        if (rest.length() > 60) rest = rest.substring(0, 59) + "…";
        if (rest.length() > 0) append(key, rest);
        return key.length() == 0 ? null : key.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static void append(StringBuilder b, String s) {
        if (s == null || s.length() == 0) return;
        if (b.length() > 0) b.append(" · ");
        b.append(s);
    }

    static boolean isSecretKey(String k) {
        String l = k == null ? "" : k.toLowerCase(Locale.ROOT);
        for (String h : SECRET_HINTS) if (l.contains(h)) return true;
        return false;
    }

    static String str(JSONObject a, String k) {
        if (a == null || !a.has(k) || isSecretKey(k)) return "";
        Object v = a.opt(k);
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                String s = String.valueOf(arr.opt(i)).trim();
                if (s.length() == 0) continue;
                if (b.length() > 0) b.append("、");
                b.append(s);
            }
            return b.toString();
        }
        if (v instanceof JSONObject) return "";
        return String.valueOf(v).trim();
    }

    private static String collect(JSONObject a, String[] keys, List<String> used) {
        StringBuilder b = new StringBuilder();
        for (String k : keys) {
            String v = str(a, k);
            if (v.length() == 0) continue;
            used.add(k);
            if (b.length() > 0) b.append("；");
            if ("cc".equals(k) || "bcc".equals(k)) b.append(k).append(" ");
            b.append(v);
        }
        return b.toString();
    }

    private static String collectExcept(JSONObject a, String[] keys, String[] except) {
        StringBuilder b = new StringBuilder();
        outer:
        for (String k : keys) {
            for (String e : except) if (e.equals(k)) continue outer;
            String v = str(a, k);
            if (v.length() == 0) continue;
            if (b.length() > 0) b.append("；");
            b.append(v);
        }
        return b.toString();
    }

    private static String firstOf(JSONObject a, String[] keys, List<String> used) {
        for (String k : keys) {
            String v = str(a, k);
            if (v.length() > 0) {
                used.add(k);
                return v;
            }
        }
        return "";
    }

    /** 其余参数「k=v」摘要（跳过已显示的、私有 _ 前缀、密钥类）。 */
    static String summarizeRest(JSONObject a, List<String> used, int maxItems, int maxValue) {
        if (a == null) return "";
        StringBuilder b = new StringBuilder();
        int n = 0, hidden = 0, more = 0;
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k == null || k.startsWith("_") || used.contains(k)) continue;
            if (isSecretKey(k)) {
                hidden++;
                continue;
            }
            Object raw = a.opt(k);
            String v = raw instanceof JSONObject || raw instanceof JSONArray ? String.valueOf(raw) : str(a, k);
            if (v.length() == 0) continue;
            if (n >= maxItems) {
                more++;
                continue;
            }
            if (b.length() > 0) b.append("，");
            b.append(k).append("=").append(clip(v, maxValue));
            n++;
        }
        if (more > 0) b.append("，…另 ").append(more).append(" 项");
        if (hidden > 0) b.append(b.length() > 0 ? "，" : "").append(hidden).append(" 项凭据已隐藏");
        return b.toString();
    }

    /** 取 host（不带端口 / userinfo：「https://x@evil.com/」→ evil.com）。不是网址返回 ""。 */
    public static String host(String url) {
        if (url == null) return "";
        String u = url.trim();
        int p = u.indexOf("://");
        if (p < 0) return "";
        String rest = u.substring(p + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                end = i;
                break;
            }
        }
        String auth = rest.substring(0, end);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        int colon = auth.indexOf(':');
        if (colon >= 0 && !auth.startsWith("[")) auth = auth.substring(0, colon);
        return auth.toLowerCase(Locale.ROOT);
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
