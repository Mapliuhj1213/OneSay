package com.onesay.desktop;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 最小 WebSocket 服务端帧处理（RFC 6455）：握手、读掩码帧、写无掩码帧。 */
final class Ws {
    static final int MAX_MESSAGE = 8 * 1024 * 1024;
    static final int OP_CONT = 0, OP_TEXT = 1, OP_BIN = 2, OP_CLOSE = 8, OP_PING = 9, OP_PONG = 10;

    private Ws() {}

    static final class Frame {
        final boolean fin;
        final int op;
        final byte[] payload;
        Frame(boolean fin, int op, byte[] payload) { this.fin = fin; this.op = op; this.payload = payload; }
    }

    /** 读 HTTP 请求头（≤8 KB）。返回 {"_line": 请求行, 小写头名: 值}。 */
    static Map<String, String> readHttpHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) throw new EOFException();
            b.write(c);
            if (b.size() > 8192) throw new IOException("header too large");
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        String[] lines = b.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("_line", lines[0]);
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        return h;
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(sha.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Frame read(InputStream in, boolean requireMask) throws IOException {
        int b0 = in.read(), b1 = in.read();
        if (b0 < 0 || b1 < 0) throw new EOFException();
        if ((b0 & 0x70) != 0) throw new IOException("reserved bits");
        boolean fin = (b0 & 0x80) != 0, masked = (b1 & 0x80) != 0;
        int op = b0 & 0x0F;
        if (requireMask && !masked) throw new IOException("client frame not masked");
        long len = b1 & 0x7F;
        if (len == 126) { byte[] l = readN(in, 2); len = (l[0] & 0xFFL) << 8 | (l[1] & 0xFFL); }
        else if (len == 127) { byte[] l = readN(in, 8); len = 0; for (byte x : l) len = len << 8 | (x & 0xFF); }
        if (len < 0 || len > MAX_MESSAGE) throw new IOException("frame too large");
        if (op >= 8 && (len > 125 || !fin)) throw new IOException("bad control frame");
        byte[] mask = masked ? readN(in, 4) : null;
        byte[] p = readN(in, (int) len);
        if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
        return new Frame(fin, op, p);
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }

    static void write(OutputStream out, int op, byte[] p) throws IOException {
        write(out, op, p, null);
    }

    /** mask 非空时写掩码帧（客户端用，测试里也用）。 */
    static void write(OutputStream out, int op, byte[] p, byte[] mask) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream(14);
        h.write(0x80 | op);
        int m = mask != null ? 0x80 : 0;
        if (p.length < 126) h.write(m | p.length);
        else if (p.length < 65536) { h.write(m | 126); h.write(p.length >>> 8); h.write(p.length & 0xFF); }
        else { h.write(m | 127); for (int i = 7; i >= 0; i--) h.write(i >= 4 ? 0 : (p.length >>> (8 * i)) & 0xFF); }
        byte[] body = p;
        if (mask != null) {
            h.write(mask, 0, 4);
            body = p.clone();
            for (int i = 0; i < body.length; i++) body[i] ^= mask[i & 3];
        }
        out.write(h.toByteArray());
        out.write(body);
        out.flush();
    }
}
