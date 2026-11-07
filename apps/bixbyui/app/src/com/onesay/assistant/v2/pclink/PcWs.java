package com.onesay.assistant.v2.pclink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * [pclink] 最小 WebSocket 客户端（RFC 6455）：HTTP 升级、写掩码帧、读服务端帧（拼分片、应答 ping）。
 * 电脑端 {@code LanServer} 要求客户端帧必须带掩码、控制帧 ≤ 125 字节；服务端帧不带掩码。
 * Android 8 没有 java.net.http，所以自己写；纯 Java，JVM 可测。
 */
public final class PcWs {

    static final int OP_CONT = 0, OP_TEXT = 1, OP_BIN = 2, OP_CLOSE = 8, OP_PING = 9, OP_PONG = 10;
    /** 单条消息上限（截图 1600 px JPEG 的 base64 约 0.3–1 MB，留足余量）。 */
    static final int MAX_MESSAGE = 16 * 1024 * 1024;
    private static final SecureRandom RNG = new SecureRandom();

    private final Socket s;
    private final InputStream in;
    private final OutputStream out;
    /** 收到对方 close 帧时的状态码（-1 = 没收到）。 */
    public volatile int closeCode = -1;

    private PcWs(Socket s, InputStream in, OutputStream out) {
        this.s = s;
        this.in = in;
        this.out = out;
    }

    /** 连上并完成升级握手。connectMs = TCP 连接超时，readMs = 握手 / 之后每次读的超时。 */
    public static PcWs connect(InetSocketAddress a, String device, int connectMs, int readMs) throws IOException {
        Socket s = new Socket();
        boolean ok = false;
        try {
            s.setTcpNoDelay(true);
            s.connect(a, connectMs);
            s.setSoTimeout(readMs);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            byte[] nonce = new byte[16];
            RNG.nextBytes(nonce);
            String key = PcB64.encode(nonce);
            String host = a.getAddress().getHostAddress();
            String req = "GET /v1/ws?device=" + URLEncoder.encode(device, "UTF-8") + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + a.getPort() + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(PcProtocol.ASCII));
            out.flush();
            String head = readHead(in);
            String first = head.split("\r\n", 2)[0];
            if (!first.startsWith("HTTP/1.1 101") && !first.startsWith("HTTP/1.0 101")) throw new IOException("upgrade refused");
            String accept = null;
            for (String line : head.split("\r\n")) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("sec-websocket-accept")) accept = line.substring(c + 1).trim();
            }
            if (accept == null || !accept.equals(acceptKey(key))) throw new IOException("bad accept");
            ok = true;
            return new PcWs(s, in, out);
        } finally {
            if (!ok) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            return PcB64.encode(sha.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(PcProtocol.ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) throw new EOFException();
            b.write(c);
            if (b.size() > 8192) throw new IOException("header too large");
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        return new String(b.toByteArray(), "ISO-8859-1");
    }

    public void setReadTimeout(int ms) throws IOException {
        s.setSoTimeout(ms);
    }

    public synchronized void sendText(String t) throws IOException {
        write(OP_TEXT, t.getBytes(PcProtocol.UTF8));
    }

    synchronized void write(int op, byte[] p) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream(14);
        h.write(0x80 | op);
        if (p.length < 126) h.write(0x80 | p.length);
        else if (p.length < 65536) {
            h.write(0x80 | 126);
            h.write(p.length >>> 8);
            h.write(p.length & 0xFF);
        } else {
            h.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) h.write(i >= 4 ? 0 : (p.length >>> (8 * i)) & 0xFF);
        }
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        h.write(mask, 0, 4);
        byte[] body = p.clone();
        for (int i = 0; i < body.length; i++) body[i] ^= mask[i & 3];
        out.write(h.toByteArray());
        out.write(body);
        out.flush();
    }

    /** 读一条完整文本消息；对方关闭返回 null。超时抛 SocketTimeoutException。 */
    public String readText() throws IOException {
        ByteArrayOutputStream acc = null;
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;
            boolean fin = (b0 & 0x80) != 0, masked = (b1 & 0x80) != 0;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) {
                byte[] l = readN(2);
                len = (l[0] & 0xFFL) << 8 | (l[1] & 0xFFL);
            } else if (len == 127) {
                byte[] l = readN(8);
                len = 0;
                for (byte x : l) len = len << 8 | (x & 0xFF);
            }
            if (len < 0 || len > MAX_MESSAGE) throw new IOException("frame too large");
            byte[] mask = masked ? readN(4) : null;
            byte[] p = readN((int) len);
            if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
            switch (op) {
                case OP_PING:
                    write(OP_PONG, p);
                    continue;
                case OP_PONG:
                    continue;
                case OP_CLOSE:
                    closeCode = p.length >= 2 ? ((p[0] & 0xFF) << 8 | (p[1] & 0xFF)) : 1005;
                    return null;
                case OP_TEXT:
                case OP_BIN:
                    if (fin) return new String(p, PcProtocol.UTF8);
                    acc = new ByteArrayOutputStream();
                    acc.write(p);
                    continue;
                case OP_CONT:
                    if (acc == null) throw new IOException("stray continuation");
                    acc.write(p);
                    if (acc.size() > MAX_MESSAGE) throw new IOException("message too large");
                    if (fin) return new String(acc.toByteArray(), PcProtocol.UTF8);
                    continue;
                default:
                    throw new IOException("bad opcode");
            }
        }
    }

    private byte[] readN(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }

    public void close() {
        try {
            write(OP_CLOSE, new byte[]{0x03, (byte) 0xE8});
        } catch (IOException ignored) {
            // 已断
        }
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
