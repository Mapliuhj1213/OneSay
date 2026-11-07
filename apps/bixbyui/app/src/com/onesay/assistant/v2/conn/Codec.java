package com.onesay.assistant.v2.conn;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [conn] 纯 Java 编解码小工具（JVM 可测；不用 android.util.Base64 / java.util.Base64——后者 API 26 才有，minSdk 是 24）。
 * Base64 / Base64url、SHA-256、URL 编码、表单体、随机串。
 */
public final class Codec {

    private Codec() {}

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    public static String b64(byte[] in) {
        return encode(in, false, true);
    }

    /** RFC 4648 §5 base64url，无填充（PKCE / Gmail raw 用）。 */
    public static String b64url(byte[] in) {
        return encode(in, true, false);
    }

    private static String encode(byte[] in, boolean url, boolean pad) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : -1;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : -1;
            sb.append(ch(b0 >> 2, url));
            sb.append(ch(((b0 & 3) << 4) | (b1 < 0 ? 0 : b1 >> 4), url));
            if (b1 >= 0) sb.append(ch(((b1 & 15) << 2) | (b2 < 0 ? 0 : b2 >> 6), url));
            else if (pad) sb.append('=');
            if (b2 >= 0) sb.append(ch(b2 & 63, url));
            else if (pad) sb.append('=');
        }
        return sb.toString();
    }

    private static char ch(int v, boolean url) {
        char c = B64[v];
        if (url && c == '+') return '-';
        if (url && c == '/') return '_';
        return c;
    }

    /** 解码 base64 或 base64url（忽略空白与填充；非法字符返回 null）。 */
    public static byte[] unb64(String s) {
        if (s == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+' || c == '-') v = 62;
            else if (c == '/' || c == '_') v = 63;
            else if (c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            else return null;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    public static byte[] utf8(String s) {
        try {
            return s == null ? new byte[0] : s.getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static String str(byte[] b) {
        try {
            return b == null ? "" : new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String s) {
        byte[] d = sha256(utf8(s));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** n 字节随机数的 base64url（PKCE verifier / state / 草稿键）。 */
    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return b64url(b);
    }

    public static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    public static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** application/x-www-form-urlencoded 体（值为 null 的键跳过）。 */
    public static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(kv[i])).append('=').append(urlEncode(kv[i + 1]));
        }
        return sb.toString();
    }

    /** 解析查询串（a=1&b=2），重复键取第一个。 */
    public static Map<String, String> query(String q) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (q == null) return m;
        for (String part : q.split("&")) {
            if (part.length() == 0) continue;
            int eq = part.indexOf('=');
            String k = urlDecode(eq < 0 ? part : part.substring(0, eq));
            String v = eq < 0 ? "" : urlDecode(part.substring(eq + 1));
            if (!m.containsKey(k)) m.put(k, v);
        }
        return m;
    }
}
