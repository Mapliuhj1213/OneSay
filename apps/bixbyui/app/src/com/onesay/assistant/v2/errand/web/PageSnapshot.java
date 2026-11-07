package com.onesay.assistant.v2.errand.web;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ★ lane C3-2：页面快照（{@link PageScript#EXTRACT} 在 WebView 里跑出来的 JSON → 本类；纯 Java）。
 *
 * <p>可点击元素在页面里打了 {@code data-bxid} 编号，模型只能按编号点 / 输入，不能自己拼选择器。
 */
public final class PageSnapshot {

    /** 一个可交互元素。 */
    public static final class El {
        public final int id;
        public final String tag;
        /** a / button / input:text / input:password / input:submit / select / textarea / role:button … */
        public final String kind;
        public final String text;
        public final String href;
        public final String value;
        /**
         * aria-label / title / alt（含内部 img 的 alt）+ 去空白后的完整可见文字（截 160 字），
         * 只给安全守卫匹配用（不进提示词）。innerText 非空时旧版 txt() 根本不取这些属性，「图标按钮 + aria-label=立即支付」会漏。
         */
        public final String label;

        public El(int id, String tag, String kind, String text, String href, String value) {
            this(id, tag, kind, text, href, value, "");
        }

        public El(int id, String tag, String kind, String text, String href, String value, String label) {
            this.id = id;
            this.label = label == null ? "" : label;
            this.tag = tag == null ? "" : tag;
            this.kind = kind == null ? "" : kind;
            this.text = text == null ? "" : text.replaceAll("\\s+", " ").trim();
            this.href = href == null ? "" : href;
            this.value = value == null ? "" : value;
        }

        public boolean isInput() {
            return kind.startsWith("input:") || kind.equals("textarea") || kind.equals("select");
        }

        public boolean isPassword() {
            return kind.equals("input:password");
        }

        public boolean isLink() {
            return href.length() > 0;
        }

        @Override
        public String toString() {
            return "[" + id + "] " + kind + " \"" + text + "\"" + (href.length() > 0 ? " → " + href : "");
        }
    }

    public final String url;
    public final String title;
    public final String text;
    public final List<El> els;
    public final boolean hasPassword;
    public final boolean hasCaptcha;
    public final int scrollY;
    public final int scrollMax;
    /** 内容签名（url + 正文长度 + 前后若干字的哈希），读回校验用。 */
    public final String sig;

    public PageSnapshot(String url, String title, String text, List<El> els, boolean hasPassword, boolean hasCaptcha,
                        int scrollY, int scrollMax, String sig) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.els = els == null ? Collections.<El>emptyList() : Collections.unmodifiableList(new ArrayList<El>(els));
        this.hasPassword = hasPassword;
        this.hasCaptcha = hasCaptcha;
        this.scrollY = scrollY;
        this.scrollMax = scrollMax;
        this.sig = sig == null ? (this.url + "#" + this.text.length()) : sig;
    }

    public El find(int id) {
        for (El e : els) if (e.id == id) return e;
        return null;
    }

    public boolean canScrollDown() {
        return scrollY < scrollMax - 10;
    }

    /** evaluateJavascript 的返回值是 JSON 字符串字面量（外层再包一次引号），两种都认。 */
    public static PageSnapshot parse(String raw) {
        if (raw == null) return null;
        try {
            String s = raw.trim();
            if (s.startsWith("\"")) s = new JSONArray("[" + s + "]").getString(0);
            if (s.equals("null") || s.length() == 0) return null;
            JSONObject o = new JSONObject(s);
            List<El> els = new ArrayList<El>();
            JSONArray arr = o.optJSONArray("els");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    els.add(new El(e.optInt("i", -1), e.optString("tag"), e.optString("kind"), e.optString("text"),
                            e.optString("href"), e.optString("value"), e.optString("lab")));
                }
            }
            return new PageSnapshot(o.optString("url"), o.optString("title"), o.optString("text"), els,
                    o.optBoolean("pwd"), o.optBoolean("captcha"), o.optInt("sy"), o.optInt("smax"), o.optString("sig", null));
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 人工接管（登录 / 验证码）

    private static final Pattern LOGIN_URL = Pattern.compile(
            "passport\\.|//login\\.|/login|/signin|/sign_in|/sign-in|/passport|account\\.\\w+\\.com/|/auth/|/oauth|/verify|captcha|/sec/|"
                    + "login\\.taobao|login\\.tmall|passport\\.jd|plogin\\.m\\.jd|/ap/signin", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOGIN_TEXT = Pattern.compile(
            "扫码登录|密码登录|短信登录|验证码登录|账号登录|手机号登录|请登录|登录后|立即登录|登录/注册|登录 / 注册|Sign in|Log in");
    private static final Pattern CAPTCHA_TEXT = Pattern.compile(
            "验证码|安全验证|人机验证|滑动验证|拖动滑块|请完成验证|向右滑动|点击按钮进行验证|访问验证|异常访问|访问过于频繁|"
                    + "verify you are human|I'm not a robot|captcha|Just a moment|Checking your browser", Pattern.CASE_INSENSITIVE);

    /**
     * 需要人来接手吗？返回原因（给岛上提示用），不需要返回 null。
     * 规则：页面上有密码框 / 验证码，或 URL 是登录 / 验证页，或正文短且以登录 / 验证提示为主。
     */
    public String takeoverReason() {
        if (hasCaptcha) return "需要你完成验证";
        String head = text.length() > 1200 ? text.substring(0, 1200) : text;
        if (text.length() < 3000 && CAPTCHA_TEXT.matcher(head).find()) return "需要你完成验证";
        if (hasPassword) return "需要你登录";
        for (El e : els) if (e.isPassword()) return "需要你登录";
        if (LOGIN_URL.matcher(url).find()) return "需要你登录";
        if (text.length() < 800 && LOGIN_TEXT.matcher(head).find()) return "需要你登录";
        return null;
    }

    // ------------------------------------------------------------------ 给模型看的摘要

    public static final int TEXT_FOR_LLM = 2500;
    public static final int ELS_FOR_LLM = 60;

    public String forLlm() {
        StringBuilder sb = new StringBuilder();
        sb.append("网址：").append(url).append('\n');
        if (title.length() > 0) sb.append("标题：").append(title).append('\n');
        sb.append("滚动：").append(scrollY).append('/').append(scrollMax).append('\n');
        sb.append("正文（节选）：\n");
        String t = text.length() > TEXT_FOR_LLM ? text.substring(0, TEXT_FOR_LLM) + "…（后面还有 " + (text.length() - TEXT_FOR_LLM) + " 字）" : text;
        sb.append(t).append('\n');
        sb.append("可操作元素（[编号] 类型 \"文字\" → 链接）：\n");
        int n = 0;
        for (El e : els) {
            if (n++ >= ELS_FOR_LLM) {
                sb.append("…（还有 ").append(els.size() - ELS_FOR_LLM).append(" 个）\n");
                break;
            }
            sb.append('[').append(e.id).append("] ").append(e.kind).append(" \"").append(clip(e.text, 40)).append('"');
            if (e.isInput() && e.value.length() > 0 && !e.isPassword()) sb.append(" 值=\"").append(clip(e.value, 20)).append('"');
            if (e.href.length() > 0) sb.append(" → ").append(clip(e.href, 80));
            sb.append('\n');
        }
        return sb.toString();
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    @Override
    public String toString() {
        return "Page{" + url + " els=" + els.size() + " text=" + text.length() + (hasPassword ? " pwd" : "") + (hasCaptcha ? " captcha" : "") + "}";
    }
}
