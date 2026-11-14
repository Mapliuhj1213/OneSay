package com.onesay.desktop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * OneSay 端到端协议 v1。中转只看外层 {type,to,id}；body 由两端用配对密钥签名。
 *
 * 指令 body：{"v":1,"kind":"cmd","cmd":"screenshot","args":{...},"ts":<ms>,"nonce":"..","sig":".."}
 * 结果 body：{"v":1,"kind":"result","re":"<指令 id>","ok":true,"text":"..","data":{...},"ts":..,"nonce":"..","sig":".."}
 *
 * 签名 = base64url(HMAC-SHA256(配对密钥, "onesay.v1\n" + 外层 id + "\n" + from + "\n" + to + "\n" + body 去掉 sig 后的规范 JSON))
 * 规范 JSON：键字典序、无空白（见 Json.stringify）。把 from/to 绑进签名，防止中转改来源或把消息转给别人。
 */
public final class Protocol {
    public static final int VERSION = 1;
    public static final long MAX_SKEW_MS = 120_000;
    private static final SecureRandom RNG = new SecureRandom();

    private Protocol() {}

    public static byte[] newKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    public static String nonce() {
        byte[] b = new byte[12];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String newId() {
        return "m" + Long.toString(System.currentTimeMillis(), 36) + nonce().substring(0, 6);
    }

    /** 局域网发现、握手用的短标签：base64url(HMAC-SHA256(key, "onesay.lan\n" + text))。 */
    public static String tag(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("onesay.lan\n" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean tagOk(byte[] key, String text, Object got) {
        return got instanceof String && MessageDigest.isEqual(tag(key, text).getBytes(StandardCharsets.US_ASCII), ((String) got).getBytes(StandardCharsets.US_ASCII));
    }

    static String sign(byte[] key, String id, String from, String to, Map<String, Object> bodyWithoutSig) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String canon = "onesay.v1\n" + id + "\n" + from + "\n" + to + "\n" + Json.stringify(bodyWithoutSig);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canon.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 给 body 加 v/ts/nonce/sig，返回新 Map。 */
    public static Map<String, Object> seal(byte[] key, String id, String from, String to, Map<String, Object> body, long nowMs) {
        TreeMap<String, Object> b = new TreeMap<>(body);
        b.remove("sig");
        b.put("v", (long) VERSION);
        b.put("ts", nowMs);
        b.put("nonce", nonce());
        b.put("sig", sign(key, id, from, to, b));
        return b;
    }

    public static Map<String, Object> command(byte[] key, String id, String from, String to, String cmd, Map<String, Object> args, long nowMs) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", "cmd");
        b.put("cmd", cmd);
        b.put("args", args == null ? new TreeMap<String, Object>() : args);
        return seal(key, id, from, to, b, nowMs);
    }

    public static Map<String, Object> result(byte[] key, String id, String from, String to, String re, boolean ok, String text, Map<String, Object> data, long nowMs) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", "result");
        b.put("re", re);
        b.put("ok", ok);
        b.put("text", text == null ? "" : text);
        b.put("data", data == null ? new TreeMap<String, Object>() : data);
        return seal(key, id, from, to, b, nowMs);
    }

    /** 外层信封（交给中转）。 */
    public static String envelope(String to, String id, Map<String, Object> body) {
        Map<String, Object> m = new TreeMap<>();
        m.put("type", "msg");
        m.put("to", to);
        m.put("id", id);
        m.put("body", body);
        return Json.stringify(m);
    }

    public enum Check { OK, NO_SIG, BAD_SIG, BAD_VERSION, STALE, REPLAY }

    /** 校验签名、版本、时间窗与一次性编号。replay 可为 null（只验签）。 */
    public static Check verify(byte[] key, String id, String from, String to, Map<String, Object> body, long nowMs, ReplayGuard replay) {
        Object sig = body.get("sig");
        if (!(sig instanceof String)) return Check.NO_SIG;
        TreeMap<String, Object> b = new TreeMap<>(body);
        b.remove("sig");
        String want;
        try {
            want = sign(key, id, from, to, b);
        } catch (RuntimeException e) {
            return Check.BAD_SIG; // [sync-1114 ← 手机 / 开源] 签不出来（Infinity / NaN 这种 JSON 写不出的值）= 不是合法报文，不抛给收帧循环
        }
        if (!MessageDigest.isEqual(want.getBytes(StandardCharsets.US_ASCII), ((String) sig).getBytes(StandardCharsets.US_ASCII))) return Check.BAD_SIG;
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
        private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

        public synchronized boolean firstSeen(String nonce, long nowMs) {
            seen.values().removeIf(t -> nowMs - t > 2 * MAX_SKEW_MS);
            if (seen.size() > 10_000) return false; // 异常洪泛，拒绝
            return seen.putIfAbsent(nonce, nowMs) == null;
        }
    }
}
