package com.onesay.assistant.v2.pclink;

import java.io.ByteArrayOutputStream;

/**
 * [pclink] Base64 / Base64url（RFC 4648）。minSdk 24 没有 java.util.Base64（API 26 才有），JVM 单测也不能用 android.util.Base64，
 * 所以自带一份。url 变体编码不带填充（与电脑端 {@code Base64.getUrlEncoder().withoutPadding()} 一致）；解码两种字母表、有无填充都收。
 */
public final class PcB64 {

    private PcB64() {}

    private static final char[] STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    public static String encode(byte[] b) {
        return enc(b, STD, true);
    }

    public static String encodeUrl(byte[] b) {
        return enc(b, URL, false);
    }

    private static String enc(byte[] b, char[] a, boolean pad) {
        StringBuilder sb = new StringBuilder((b.length + 2) / 3 * 4);
        int i = 0;
        for (; i + 2 < b.length; i += 3) {
            int n = (b[i] & 0xFF) << 16 | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF);
            sb.append(a[n >>> 18 & 63]).append(a[n >>> 12 & 63]).append(a[n >>> 6 & 63]).append(a[n & 63]);
        }
        int rest = b.length - i;
        if (rest == 1) {
            int n = (b[i] & 0xFF) << 16;
            sb.append(a[n >>> 18 & 63]).append(a[n >>> 12 & 63]);
            if (pad) sb.append("==");
        } else if (rest == 2) {
            int n = (b[i] & 0xFF) << 16 | (b[i + 1] & 0xFF) << 8;
            sb.append(a[n >>> 18 & 63]).append(a[n >>> 12 & 63]).append(a[n >>> 6 & 63]);
            if (pad) sb.append('=');
        }
        return sb.toString();
    }

    private static int val(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+' || c == '-') return 62;
        if (c == '/' || c == '_') return 63;
        return -1;
    }

    /** 解码（标准 / url 字母表、带不带 = 都行；忽略空白）。非法字符抛 IllegalArgumentException。 */
    public static byte[] decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 3 / 4 + 3);
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            int v = val(c);
            if (v < 0) throw new IllegalArgumentException("bad base64");
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write(acc >>> bits & 0xFF);
            }
        }
        if (bits >= 6) throw new IllegalArgumentException("bad base64 length");
        return out.toByteArray();
    }
}
