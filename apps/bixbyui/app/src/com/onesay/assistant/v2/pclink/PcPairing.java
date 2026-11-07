package com.onesay.assistant.v2.pclink;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [pclink] 电脑端设置页二维码 / 「复制配对串」的内容：
 * {@code onesay://pair?key=<base64url>&peer=<电脑 id>&lan=ip:port,...[&relay=..&token=..]}。
 *
 * <p>解析规则照抄电脑端 {@code Config.importPairing}（0.4.1）：key / peer 必须有、peer 只许 {@code [A-Za-z0-9._-]{1,64}}、
 * 密钥 ≥ 32 字节、有中转就要令牌 ≥ 24 位、局域网与中转至少一个；lan 里每段 {@code ip:port}，端口取最后一个能解析的，
 * {@code 0.0.0.0:port} 只给端口不当地址。
 *
 * <p>手机端 v1 只走局域网：配对串只有中转、没有局域网 → 拒绝（说清原因）。中转字段不保存。
 * <b>整串等于钥匙</b>：{@link #toString()} 不带密钥，日志只许打 {@link #redacted()}。
 */
public final class PcPairing {

    public static final String PREFIX = "onesay://pair?";
    public static final int DEFAULT_PORT = 47820;

    public final String peer;
    public final byte[] key;
    /** 提示地址 ip:port,...（不含 0.0.0.0）。 */
    public final String lanHosts;
    /** 电脑端局域网端口（UDP 发现用 +1）。 */
    public final int lanPort;
    public final boolean hasRelay;

    PcPairing(String peer, byte[] key, String lanHosts, int lanPort, boolean hasRelay) {
        this.peer = peer;
        this.key = key;
        this.lanHosts = lanHosts;
        this.lanPort = lanPort;
        this.hasRelay = hasRelay;
    }

    /** 不合法抛 IllegalArgumentException（消息给人看，不含串内容）。 */
    public static PcPairing parse(String raw) {
        String s = normalize(raw);
        if (!s.startsWith(PREFIX)) throw new IllegalArgumentException("不是电脑端的配对码");
        Map<String, String> q = new LinkedHashMap<String, String>();
        for (String kv : s.substring(PREFIX.length()).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) q.put(kv.substring(0, eq), dec(kv.substring(eq + 1))); // 参数名已在 normalize 里统一小写
        }
        String relay = get(q, "relay"), token = get(q, "token"), key = get(q, "key"), peer = get(q, "peer"), lan = get(q, "lan");
        if (key.length() == 0 || peer.length() == 0 || !validId(peer)) throw new IllegalArgumentException("配对码不完整");
        if (relay.length() > 0 && token.length() < 24) throw new IllegalArgumentException("配对码里的中转令牌不完整");
        if (relay.length() == 0 && lan.length() == 0) throw new IllegalArgumentException("配对码既没有局域网也没有中转");
        byte[] k;
        try {
            k = PcB64.decode(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("配对密钥格式不对");
        }
        if (k.length < 32) throw new IllegalArgumentException("配对密钥太短");
        StringBuilder hosts = new StringBuilder();
        int port = 0;
        for (String part : lan.split(",")) {
            part = part.trim();
            int c = part.lastIndexOf(':');
            if (c <= 0) continue;
            try {
                port = Integer.parseInt(part.substring(c + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!part.startsWith("0.0.0.0:")) hosts.append(hosts.length() == 0 ? "" : ",").append(part);
        }
        int lanPort = lan.length() == 0 ? 0 : (port > 0 ? port : DEFAULT_PORT);
        if (lanPort <= 0 || lanPort >= 65535) {
            throw new IllegalArgumentException("这个配对码只有中转、没有局域网；手机端暂时只支持同一个 Wi-Fi 下直连，请在电脑端打开局域网");
        }
        return new PcPairing(peer, k, hosts.toString(), lanPort, relay.length() > 0);
    }

    /**
     * 输入容错，返回从 {@code onesay://pair?} 开始的规范串；找不到前缀返回去掉空白后的原文（让 parse 报「不是电脑端的配对码」）。
     *
     * <p>真机（oss-device-1108）：配对码经讯飞等中文输入法「打」进输入框时，输入法把按键改写了——
     * 中文模式下 {@code : ? & = / , .} 变成全角 {@code ：？＆＝／，．} 或 {@code 。、}，{@code _} 变成 {@code ——}，
     * 英文模式下首字母可能被自动大写，还会夹进空格 / 零宽字符；从聊天里复制时前后还常带别的字（「配对串：onesay://…」）。
     * 电脑端（0.4.2 {@code Config.pairingString}）本身只输出 ASCII：scheme 固定小写、值经 URLEncoder 编码，
     * 所以把这些改写还原回 ASCII 不会把一个合法串变成另一个合法串。
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '\uFF01' && ch <= '\uFF5E') ch = (char) (ch - 0xFEE0); // 全角 ASCII → 半角（：？＆＝／，．＿－％ 及全角字母数字）
            else if (ch == '\u3002' || ch == '\uFF61') ch = '.'; // 。｡
            else if (ch == '\u3001') ch = '/'; // 、（中文输入法里 / 键）
            else if (ch == '\u2014') { // ——（中文输入法里 shift+- 即 _）
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\u2014') i++;
                ch = '_';
            }
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\u2060' || ch == '\uFEFF') continue;
            b.append(ch);
        }
        String s = b.toString();
        int at = s.toLowerCase(java.util.Locale.ROOT).indexOf(PREFIX);
        if (at < 0) return s;
        int end = PREFIX.length() + at;
        while (end < s.length() && urlChar(s.charAt(end))) end++; // 串后面跟着的中文 / 括号等不要
        while (end > at + PREFIX.length() && ".,:".indexOf(s.charAt(end - 1)) >= 0) end--; // 句末标点（电脑端的串不会以这些结尾）
        // 参数名不分大小写（输入法自动大写 Key= / Peer=），统一成小写；值（base64 钥匙等）区分大小写，不动
        StringBuilder q = new StringBuilder(PREFIX);
        for (String kv : s.substring(at + PREFIX.length(), end).split("&", -1)) {
            if (q.length() > PREFIX.length()) q.append('&');
            int eq = kv.indexOf('=');
            q.append(eq > 0 ? kv.substring(0, eq).toLowerCase(java.util.Locale.ROOT) + kv.substring(eq) : kv);
        }
        return q.toString();
    }

    /** 配对串前缀后面可能出现的字符：电脑端 URLEncoder 只留 {@code A-Za-z0-9 . - * _}（空格成 +、其余成 %XX），再加分隔符和手打时的 {@code : , / ~}。 */
    private static boolean urlChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || ".-*_+%=&:,/~".indexOf(c) >= 0;
    }

    /** 剪贴板 / 扫码结果里有没有电脑端配对码（只看前缀，内容对不对由 {@link #parse} 判断）。 */
    public static boolean looksLike(String raw) {
        return normalize(raw).startsWith(PREFIX);
    }

    /** 剪贴板里的配对码，规范化后返回；不像配对码返回 null。 */
    public static String extract(String raw) {
        String s = normalize(raw);
        return s.startsWith(PREFIX) ? s : null;
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,64}");
    }

    private static String get(Map<String, String> q, String k) {
        String v = q.get(k);
        return v == null ? "" : v;
    }

    private static String dec(String v) {
        try {
            return URLDecoder.decode(v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return v;
        } catch (IllegalArgumentException e) {
            return v;
        }
    }

    /** 日志 / 状态用：不含密钥。 */
    public String redacted() {
        return "peer=" + peer + " keyLen=" + key.length + " lanPort=" + lanPort + " hosts=" + (lanHosts.length() == 0 ? 0 : lanHosts.split(",").length)
                + (hasRelay ? " relay=ignored" : "");
    }

    @Override public String toString() {
        return redacted();
    }
}
