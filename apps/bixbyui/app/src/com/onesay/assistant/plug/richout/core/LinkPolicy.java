/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.*。
 */
package com.onesay.assistant.plug.richout.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 回答里的链接被点时怎么处理（纯函数）。
 *
 * <ul>
 *   <li>{@link Action#READER}：http / https → 应用内阅读器打开（不跳外部浏览器）；</li>
 *   <li>{@link Action#CONFIRM}：第三方 App 深链（weixin:// 之类）、mailto: / tel: / sms: / geo: / market: → 先弹确认，
 *       用户点「打开」才交给系统；宿主还要再过一遍跑腿接手页那套 scheme 白名单；</li>
 *   <li>{@link Action#BLOCK}：javascript: / vbscript: / data: / file: / content: / intent: / about: / blob: / chrome: / 
 *       ftp: / ws:… 一律拦；带控制字符、超长、没有 scheme、http 却没有主机的也拦。</li>
 * </ul>
 * 另外标出「看着像 A 实际去 B」的可疑 http 链接（userinfo@host、punycode 主机、数字 IP、局域网），阅读器顶部显示真实主机；
 * 本机地址（localhost / 127.x / ::1）一律拦。
 */
public final class LinkPolicy {

    private LinkPolicy() {}

    public enum Action { READER, CONFIRM, BLOCK }

    public static final int MAX_LEN = 4096;

    /** 永远拦下的 scheme（小写）。 */
    public static final Set<String> BLOCKED = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "javascript", "vbscript", "livescript", "data", "file", "content", "intent", "about", "blob", "chrome",
            "chrome-extension", "android-app", "android.resource", "jar", "ftp", "ws", "wss", "view-source", "filesystem",
            "res", "asset", "resource", "ms-settings", "settings", "package", "shell", "adb")));

    /** 不是第三方 App，但同样会离开本 App（拨号、发短信、写邮件、地图、应用商店）：确认后放行。 */
    public static final Set<String> SYSTEM_CONFIRM = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "mailto", "tel", "sms", "smsto", "geo", "market")));

    public static final class Decision {
        public final Action action;
        /** 规范化后的地址（READER 时可直接加载；CONFIRM 时交给系统的 data URI）。BLOCK 时为原文截断。 */
        public final String url;
        public final String scheme;
        /** http(s) 的主机（小写，去掉 userinfo 和端口）；其它为空串。 */
        public final String host;
        /** 可疑（userinfo、punycode、数字 IP）：阅读器顶部要醒目显示真实主机。 */
        public final boolean suspicious;
        public final String reason;

        Decision(Action action, String url, String scheme, String host, boolean suspicious, String reason) {
            this.action = action;
            this.url = url;
            this.scheme = scheme == null ? "" : scheme;
            this.host = host == null ? "" : host;
            this.suspicious = suspicious;
            this.reason = reason;
        }

        @Override public String toString() {
            return action + " " + scheme + (host.length() > 0 ? " host=" + host : "") + (suspicious ? " 可疑" : "")
                    + (reason != null ? " (" + reason + ")" : "");
        }
    }

    private static Decision block(String url, String scheme, String why) {
        String u = url == null ? "" : url;
        if (u.length() > 120) u = u.substring(0, 120) + "…";
        return new Decision(Action.BLOCK, u, scheme, null, false, why);
    }

    public static Decision decide(String raw) {
        if (raw == null) return block(null, null, "空链接");
        String u = raw.trim();
        if (u.length() == 0) return block(u, null, "空链接");
        if (u.length() > MAX_LEN) return block(u, null, "链接过长");
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x200b && c <= 0x200f) || c == 0x2028 || c == 0x2029 || c == 0xfeff) return block(u, null, "含控制 / 不可见字符");
        }
        String scheme = schemeOf(u);
        if (scheme == null) {
            // 相对地址 / 「www.example.com」：只有像域名的才补 https
            if (u.startsWith("//")) return block(u, null, "协议相对地址");
            if (u.matches("(?i)www\\.[a-z0-9-]+(\\.[a-z0-9-]+)+([/?#].*)?")) return decide("https://" + u);
            return block(u, null, "没有 scheme");
        }
        if (BLOCKED.contains(scheme)) return block(u, scheme, "禁止的 scheme: " + scheme);
        if (scheme.equals("http") || scheme.equals("https")) {
            String rest = u.substring(scheme.length() + 1);
            if (!rest.startsWith("//")) return block(u, scheme, "缺少 //");
            String auth = rest.substring(2);
            int end = auth.length();
            for (int i = 0; i < auth.length(); i++) {
                char c = auth.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            auth = auth.substring(0, end);
            if (auth.indexOf('\\') >= 0) return block(u, scheme, "主机里有反斜杠");
            boolean suspicious = false;
            int at = auth.lastIndexOf('@');
            if (at >= 0) {
                suspicious = true;
                auth = auth.substring(at + 1);
            }
            String host = auth;
            if (host.startsWith("[")) {
                int rb = host.indexOf(']');
                host = rb > 0 ? host.substring(0, rb + 1) : host;
                suspicious = true;
            } else {
                int colon = host.indexOf(':');
                if (colon >= 0) {
                    String port = host.substring(colon + 1);
                    if (!port.matches("\\d{1,5}")) return block(u, scheme, "端口异常");
                    host = host.substring(0, colon);
                }
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.length() == 0 || host.equals(".")) return block(u, scheme, "没有主机");
            if (!host.startsWith("[") && !host.matches("[a-z0-9\\u0080-\\uffff.-]+")) return block(u, scheme, "主机含非法字符");
            if (host.matches("[0-9.]+")) suspicious = true;
            if (host.startsWith("xn--") || host.contains(".xn--")) suspicious = true;
            // 本机地址一律拦（本机有守护进程 / 调试端口在 127.0.0.1 上听着）；局域网（路由器后台之类）放行但标可疑
            if (host.equals("localhost") || host.endsWith(".localhost") || host.startsWith("127.") || host.equals("0.0.0.0")
                    || host.equals("[::1]") || host.equals("[::]") || host.startsWith("[::ffff:127.")) {
                return block(u, scheme, "本机地址");
            }
            if (host.startsWith("10.") || host.startsWith("192.168.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")
                    || host.startsWith("169.254.")) suspicious = true;
            return new Decision(Action.READER, scheme + u.substring(scheme.length()), scheme, host, suspicious, null);
        }
        if (SYSTEM_CONFIRM.contains(scheme)) return new Decision(Action.CONFIRM, u, scheme, null, false, "离开本 App");
        if (!scheme.matches("[a-z][a-z0-9+.-]{1,40}")) return block(u, scheme, "scheme 异常");
        return new Decision(Action.CONFIRM, u, scheme, null, false, "第三方 App 深链");
    }

    /** 取 scheme（小写），没有返回 null。 */
    static String schemeOf(String u) {
        int c = u.indexOf(':');
        if (c <= 0) return null;
        String s = u.substring(0, c);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (i > 0 && ((ch >= '0' && ch <= '9') || ch == '.' || ch == '+' || ch == '-'));
            if (!ok) return null;
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** 阅读器里页面内部的跳转：只放行 http(s)（与点击同一裁决，但非 http(s) 一律不跳）。 */
    public static boolean allowInReader(String url) {
        return decide(url).action == Action.READER;
    }
}
