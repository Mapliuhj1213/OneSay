package com.onesay.assistant.v2.errand.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 跑腿接手页（ErrandBrowserActivity，用户本人在操作的真浏览器）里非 http(s) 跳转的放行策略（纯函数，JVM 可测）。
 *
 * <p>安全修复 IPC-4（pentest-20261024/ipc.md §4）：以前对任何 scheme 都 {@code Intent.parseUri} 后
 * {@code startActivity}，网页（可能是攻击者控制的页面）能借 {@code intent:} URI 拉起任意 BROWSABLE Activity 并带 extras。
 * 现在：
 * <ul>
 *   <li>只放行已知支付 / 登录 / 购物 App 的 scheme（{@link #ALLOWED_SCHEMES}）；其余（tel/sms/market/file/content/javascript/未知）一律拦；</li>
 *   <li>{@code intent:} URI 不再交给 {@code Intent.parseUri}：自己解析 {@code #Intent;…;end}，scheme 必须在白名单里；
 *       带 {@code component=} 或 selector（{@code SEL;}）直接拒绝；{@code package=} 只认 {@link #ALLOWED_PACKAGES}；
 *       {@code action=} 只认 VIEW；extras（S./i./B./… 含 browser_fallback_url）一律丢弃；</li>
 *   <li>放行时调用方重建一个干净的 {@code ACTION_VIEW + CATEGORY_BROWSABLE} Intent（data = 还原后的 scheme URI），并提示用户将离开本 App。</li>
 * </ul>
 */
public final class ExternalSchemePolicy {

    private ExternalSchemePolicy() {}

    /** 允许从接手页拉起的外部 scheme（小写）。 */
    public static final Set<String> ALLOWED_SCHEMES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "alipays", "alipay",                 // 支付宝
            "weixin",                            // 微信（支付 / 登录）
            "taobao", "tbopen", "tmall",         // 淘宝 / 天猫
            "openapp.jdmobile",                  // 京东
            "pinduoduo",                         // 拼多多
            "imeituan", "meituanwaimai",         // 美团
            "eleme",                             // 饿了么
            "upwrapper"                          // 云闪付
    )));

    /** intent: URI 里允许出现的 package=（对应上面的 App）。 */
    public static final Set<String> ALLOWED_PACKAGES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "com.eg.android.AlipayGphone",
            "com.tencent.mm",
            "com.taobao.taobao", "com.tmall.wireless",
            "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo",
            "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew",
            "me.ele",
            "com.unionpay"
    )));

    public static final class Decision {
        public final boolean allowed;
        /** 放行时：重建 Intent 用的 data URI（scheme://…）。 */
        public final String viewUri;
        /** 放行时：intent: URI 里声明且在白名单里的 package；没声明为 null。 */
        public final String pkg;
        /** 拒绝原因（日志用）。 */
        public final String reason;

        Decision(boolean allowed, String viewUri, String pkg, String reason) {
            this.allowed = allowed;
            this.viewUri = viewUri;
            this.pkg = pkg;
            this.reason = reason;
        }

        static Decision deny(String why) {
            return new Decision(false, null, null, why);
        }
    }

    /** 取 URI 的 scheme（小写）；没有返回 null。 */
    static String schemeOf(String u) {
        int c = u.indexOf(':');
        if (c <= 0) return null;
        String s = u.substring(0, c);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '+' || ch == '-';
            if (!ok) return null;
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** 判定一个非 http(s) 跳转能不能放行。 */
    public static Decision decide(String url) {
        if (url == null) return Decision.deny("空 URL");
        String u = url.trim();
        if (u.length() == 0 || u.length() > 4096) return Decision.deny("URL 长度异常");
        for (int i = 0; i < u.length(); i++) {
            if (u.charAt(i) < 0x20) return Decision.deny("含控制字符");
        }
        String scheme = schemeOf(u);
        if (scheme == null) return Decision.deny("没有 scheme");
        if ("intent".equals(scheme)) return decideIntent(u);
        if (!ALLOWED_SCHEMES.contains(scheme)) return Decision.deny("scheme 不在白名单: " + scheme);
        return new Decision(true, u, null, null);
    }

    private static Decision decideIntent(String u) {
        int hash = u.indexOf("#Intent;");
        if (hash < 0) return Decision.deny("intent: 缺少 #Intent; 段");
        String head = u.substring("intent:".length(), hash);          // //host/path?q  或  host/path
        String frag = u.substring(hash + "#Intent;".length());
        if (!frag.endsWith("end")) return Decision.deny("intent: 段没有以 end 结尾");
        if (frag.contains("SEL;")) return Decision.deny("intent: 带 selector");
        String scheme = null;
        String pkg = null;
        for (String part : frag.substring(0, frag.length() - 3).split(";")) {
            if (part.length() == 0) continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            if ("component".equals(k)) return Decision.deny("intent: 带 component");
            if ("scheme".equals(k)) scheme = v.toLowerCase(Locale.ROOT);
            else if ("package".equals(k)) pkg = v;
            else if ("action".equals(k)) {
                if (!"android.intent.action.VIEW".equals(v)) return Decision.deny("intent: action 不是 VIEW");
            }
            // category / launchFlags / S. i. B. l. f. d. s. c. b. 等 extras：一律不带过去（重建的 Intent 里没有）
        }
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) return Decision.deny("intent: scheme 不在白名单: " + scheme);
        if (pkg != null && !ALLOWED_PACKAGES.contains(pkg)) return Decision.deny("intent: package 不在白名单: " + pkg);
        if (head.indexOf('#') >= 0) return Decision.deny("intent: data 段异常");
        return new Decision(true, scheme + ":" + head, pkg, null);
    }
}
