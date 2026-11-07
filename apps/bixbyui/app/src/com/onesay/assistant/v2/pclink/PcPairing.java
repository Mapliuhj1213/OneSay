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
        String s = raw == null ? "" : raw.trim();
        if (!s.startsWith(PREFIX)) throw new IllegalArgumentException("不是电脑端的配对码");
        Map<String, String> q = new LinkedHashMap<String, String>();
        for (String kv : s.substring(PREFIX.length()).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) q.put(kv.substring(0, eq), dec(kv.substring(eq + 1)));
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
