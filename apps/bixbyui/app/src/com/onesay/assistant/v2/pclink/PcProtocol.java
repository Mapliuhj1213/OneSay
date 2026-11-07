package com.onesay.assistant.v2.pclink;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * [pclink] OneSay 端到端协议 v1 的手机端实现 —— 与电脑端 {@code com.onesay.desktop.Protocol}（0.4.1）逐字节兼容：
 *
 * <ul>
 *   <li>签名 = base64url(HMAC-SHA256(配对密钥, "onesay.v1\n" + 外层 id + "\n" + from + "\n" + to + "\n" + body 去掉 sig 的规范 JSON))，无填充；</li>
 *   <li>局域网发现 / 握手的短标签 = base64url(HMAC-SHA256(key, "onesay.lan\n" + text))；</li>
 *   <li>body 由 {@link #seal} 补 v=1、ts（毫秒）、nonce（12 字节随机 base64url）、sig；</li>
 *   <li>校验：签名 → 版本 → 时间窗 ±120 s → nonce 一次性（{@link ReplayGuard}）。</li>
 * </ul>
 * 纯 Java，JVM 可测；密钥 / 签名 / 配对串一律不进日志。
 */
public final class PcProtocol {

    public static final int VERSION = 1;
    public static final long MAX_SKEW_MS = 120000L;
    private static final SecureRandom RNG = new SecureRandom();
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final Charset ASCII = Charset.forName("US-ASCII");

    private PcProtocol() {}

    public static String nonce() {
        byte[] b = new byte[12];
        RNG.nextBytes(b);
        return PcB64.encodeUrl(b);
    }

    public static String newId() {
        return "m" + Long.toString(System.currentTimeMillis(), 36) + nonce().substring(0, 6);
    }

    static byte[] hmac(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text.getBytes(UTF8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 局域网发现、握手用的短标签。 */
    public static String tag(byte[] key, String text) {
        return PcB64.encodeUrl(hmac(key, "onesay.lan\n" + text));
    }

    public static boolean tagOk(byte[] key, String text, Object got) {
        return got instanceof String && MessageDigest.isEqual(tag(key, text).getBytes(ASCII), ((String) got).getBytes(ASCII));
    }

    /** 规范串（测试用：与电脑端逐字节比对）。 */
    static String canon(String id, String from, String to, Map<String, Object> bodyWithoutSig) {
        return "onesay.v1\n" + id + "\n" + from + "\n" + to + "\n" + PcJson.stringify(bodyWithoutSig);
    }

    static String sign(byte[] key, String id, String from, String to, Map<String, Object> bodyWithoutSig) {
        return PcB64.encodeUrl(hmac(key, canon(id, from, to, bodyWithoutSig)));
    }

    /** 给 body 加 v / ts / nonce / sig，返回新 Map。 */
    public static Map<String, Object> seal(byte[] key, String id, String from, String to, Map<String, Object> body, long nowMs) {
        TreeMap<String, Object> b = new TreeMap<String, Object>(body);
        b.remove("sig");
        b.put("v", Long.valueOf(VERSION));
        b.put("ts", Long.valueOf(nowMs));
        b.put("nonce", nonce());
        b.put("sig", sign(key, id, from, to, b));
        return b;
    }

    public static Map<String, Object> command(byte[] key, String id, String from, String to, String cmd, Map<String, Object> args, long nowMs) {
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("kind", "cmd");
        b.put("cmd", cmd);
        b.put("args", args == null ? new TreeMap<String, Object>() : args);
        return seal(key, id, from, to, b, nowMs);
    }

    public static Map<String, Object> hello(byte[] key, String id, String from, String to, long nowMs) {
        Map<String, Object> b = new TreeMap<String, Object>();
        b.put("kind", "hello");
        return seal(key, id, from, to, b, nowMs);
    }

    public enum Check { OK, NO_SIG, BAD_SIG, BAD_VERSION, STALE, REPLAY }

    /** 校验签名、版本、时间窗与一次性编号。replay 可为 null（只验签）。 */
    public static Check verify(byte[] key, String id, String from, String to, Map<String, Object> body, long nowMs, ReplayGuard replay) {
        Object sig = body.get("sig");
        if (!(sig instanceof String)) return Check.NO_SIG;
        TreeMap<String, Object> b = new TreeMap<String, Object>(body);
        b.remove("sig");
        String want = sign(key, id, from, to, b);
        if (!MessageDigest.isEqual(want.getBytes(ASCII), ((String) sig).getBytes(ASCII))) return Check.BAD_SIG;
        Object v = body.get("v");
        if (!(v instanceof Number) || ((Number) v).intValue() != VERSION) return Check.BAD_VERSION;
        Object ts = body.get("ts");
        if (!(ts instanceof Number) || Math.abs(nowMs - ((Number) ts).longValue()) > MAX_SKEW_MS) return Check.STALE;
        Object n = body.get("nonce");
        if (replay != null && (!(n instanceof String) || !replay.firstSeen((String) n, nowMs))) return Check.REPLAY;
        return Check.OK;
    }

    /** 记住时间窗内见过的 nonce。 */
    public static final class ReplayGuard {
        private final LinkedHashMap<String, Long> seen = new LinkedHashMap<String, Long>();

        public synchronized boolean firstSeen(String nonce, long nowMs) {
            Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
            while (it.hasNext()) {
                if (nowMs - it.next().getValue() > 2 * MAX_SKEW_MS) it.remove();
            }
            if (seen.size() > 10000) return false;
            if (seen.containsKey(nonce)) return false;
            seen.put(nonce, nowMs);
            return true;
        }
    }
}
