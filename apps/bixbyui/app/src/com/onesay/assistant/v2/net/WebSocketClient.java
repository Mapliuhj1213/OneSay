package com.onesay.assistant.v2.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebSocket 客户端（RFC 6455，★ R14 流式 ASR 用）。纯 java.net / javax.net.ssl，无第三方依赖、无 android 依赖，JVM 可测。
 *
 * <h3>为什么自己写</h3>
 * 工程没有 Gradle、没有 OkHttp 依赖，Android 8 自带的 com.android.okhttp 不公开 WebSocket API。
 * 流式识别只需要：TLS 握手 + 升级握手、发文本帧（客户端必须加掩码）、收文本帧（含分片）、回 pong、收发 close。
 * 这些合起来不到 400 行，引一个库反而要改构建、改 Manifest、多 1 MB。
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>{@link #connect} 阻塞（TCP + TLS + HTTP 升级），必须在后台线程调；成功后起一个读线程回调 {@link Listener}。</li>
 *   <li>{@link #sendText} 线程安全（输出流上加锁），可以从任何线程调；写失败只回调一次 onFailure。</li>
 *   <li>TLS：系统默认 SSLSocketFactory（系统信任库）+ 显式主机名校验（{@link HttpsURLConnection#getDefaultHostnameVerifier()}）；
 *       createSocket(socket, host, …) 带主机名，所以 SNI 照常发送。</li>
 *   <li>回调都在读线程上；{@link Listener#onClosed} / {@link Listener#onFailure} 二者只会回调其一，且只一次。</li>
 * </ul>
 */
public final class WebSocketClient {

    /** 事件回调（读线程上调用）。 */
    public interface Listener {
        void onText(WebSocketClient ws, String text);

        /** 对端或本端正常关闭。 */
        void onClosed(WebSocketClient ws, int code, String reason);

        /** 连接 / 读 / 写失败。 */
        void onFailure(WebSocketClient ws, Throwable t);
    }

    /**
     * 可选：要收二进制消息（云端 TTS 的音频走 binary 通道）的 Listener 再实现这个接口。
     * 没实现的（如 RealtimeAsrSession）行为不变：二进制消息照旧丢弃。回调在读线程上，不要阻塞。
     */
    public interface BinaryListener {
        void onBinary(WebSocketClient ws, byte[] data);
    }

    public static final int OP_CONT = 0x0, OP_TEXT = 0x1, OP_BINARY = 0x2, OP_CLOSE = 0x8, OP_PING = 0x9, OP_PONG = 0xA;

    /** 单帧上限（防异常服务端把内存撑爆）：识别事件都是几百字节的 JSON。 */
    static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom RNG = new SecureRandom();

    private final Listener mListener;
    private volatile Socket mSocket;
    private volatile OutputStream mOut;
    private volatile boolean mDone;
    private final Object mWriteLock = new Object();

    /** 握手各阶段耗时（日志 / 时延分解用）：tcp、tls、upgrade，毫秒。 */
    public volatile long tcpMs = -1, tlsMs = -1, upgradeMs = -1;

    public WebSocketClient(Listener listener) {
        mListener = listener;
    }

    /**
     * 连接并完成升级握手。失败抛 IOException（不回调 Listener）；成功后读线程开始回调。
     *
     * @param url     ws:// 或 wss://
     * @param headers 额外请求头（如 Authorization），可为 null
     */
    public void connect(String url, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        URI u = URI.create(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        boolean tls = "wss".equals(scheme);
        if (!tls && !"ws".equals(scheme)) throw new IOException("不支持的协议: " + scheme);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = (u.getRawPath() == null || u.getRawPath().length() == 0) ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();

        long t0 = System.nanoTime();
        Socket raw = new Socket();
        Socket s = raw;
        try {
            raw.setTcpNoDelay(true);
            raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            tcpMs = (System.nanoTime() - t0) / 1000000L;
            raw.setSoTimeout(Math.max(connectTimeoutMs, 1000));
            if (tls) {
                long t1 = System.nanoTime();
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
                try {
                    // 握手时就做主机名校验（JDK 与 Conscrypt 都认 "HTTPS"）
                    javax.net.ssl.SSLParameters sp = ssl.getSSLParameters();
                    sp.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(sp);
                } catch (Throwable ignore) {
                }
                ssl.startHandshake();
                // 再用系统 HostnameVerifier 复核一遍（Android = OkHostnameVerifier）。桌面 JDK 的默认实现恒返回 false
                // （JDK 指望上面的 endpoint identification），那种情况下跳过，不误杀。
                HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
                boolean jdkDummy = hv != null && hv.getClass().getName().startsWith("javax.net.ssl.HttpsURLConnection");
                if (hv != null && !jdkDummy && !hv.verify(host, ssl.getSession())) {
                    throw new javax.net.ssl.SSLPeerUnverifiedException("主机名校验失败: " + host);
                }
                tlsMs = (System.nanoTime() - t1) / 1000000L;
                s = ssl;
            } else {
                tlsMs = 0;
            }
            long t2 = System.nanoTime();
            byte[] keyBytes = new byte[16];
            RNG.nextBytes(keyBytes);
            String key = base64(keyBytes);
            StringBuilder req = new StringBuilder(256);
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(port == (tls ? 443 : 80) ? "" : ":" + port).append("\r\n");
            req.append("Upgrade: websocket\r\nConnection: Upgrade\r\n");
            req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            req.append("Sec-WebSocket-Version: 13\r\n");
            req.append("User-Agent: OneSay\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes("UTF-8"));
            out.flush();
            InputStream in = s.getInputStream();
            String status = readHttpHead(in);
            int code = statusCode(status);
            if (code != 101) {
                throw new IOException("WebSocket 升级失败: HTTP " + code + firstLine(status));
            }
            String accept = headerValue(status, "sec-websocket-accept");
            if (accept == null || !accept.trim().equals(acceptFor(key))) {
                throw new IOException("WebSocket 升级失败: Sec-WebSocket-Accept 不匹配");
            }
            upgradeMs = (System.nanoTime() - t2) / 1000000L;
            s.setSoTimeout(readTimeoutMs);
            mSocket = s;
            mOut = out;
            final InputStream fin = in;
            com.onesay.assistant.v2.util.Threads.start("ws-read", new Runnable() {
                @Override
                public void run() {
                    readLoop(fin);
                }
            });
        } catch (IOException e) {
            closeQuietly(s);
            if (s != raw) closeQuietly(raw);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(s);
            if (s != raw) closeQuietly(raw);
            throw new IOException(e.toString(), e);
        }
    }

    /** 发一条文本帧。连接已断返回 false（并已/将回调 onFailure）。 */
    public boolean sendText(String text) {
        try {
            return sendFrame(OP_TEXT, text.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            return false;
        }
    }

    /** 正常关闭（发 close 帧后关 socket）。幂等。 */
    public void close(int code, String reason) {
        if (mDone) return;
        try {
            byte[] r = reason == null ? new byte[0] : reason.getBytes("UTF-8");
            byte[] p = new byte[2 + Math.min(r.length, 120)];
            p[0] = (byte) (code >> 8);
            p[1] = (byte) code;
            System.arraycopy(r, 0, p, 2, p.length - 2);
            sendFrame(OP_CLOSE, p);
        } catch (Throwable ignore) {
        }
        finish(true, code, reason, null);
    }

    /** 立即断开（不发 close 帧，不回调）。 */
    public void cancel() {
        mDone = true;
        closeQuietly(mSocket);
    }

    public boolean isOpen() {
        return !mDone && mSocket != null;
    }

    // ------------------------------------------------------------------ 内部

    private boolean sendFrame(int opcode, byte[] payload) {
        OutputStream out = mOut;
        if (out == null || mDone) return false;
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        byte[] frame = encodeFrame(opcode, payload, mask);
        try {
            synchronized (mWriteLock) {
                out.write(frame);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            finish(false, 0, null, e);
            return false;
        }
    }

    private void readLoop(InputStream in) {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int msgOp = -1;
        try {
            while (!mDone) {
                Frame f = readFrame(in);
                switch (f.opcode) {
                    case OP_PING:
                        sendFrame(OP_PONG, f.payload);
                        break;
                    case OP_PONG:
                        break;
                    case OP_CLOSE: {
                        int code = f.payload.length >= 2 ? ((f.payload[0] & 0xFF) << 8) | (f.payload[1] & 0xFF) : 1005;
                        String reason = f.payload.length > 2 ? new String(f.payload, 2, f.payload.length - 2, "UTF-8") : "";
                        if (!mDone) {
                            try {
                                sendFrame(OP_CLOSE, f.payload.length >= 2 ? new byte[] {f.payload[0], f.payload[1]} : new byte[0]);
                            } catch (Throwable ignore) {
                            }
                        }
                        finish(true, code, reason, null);
                        return;
                    }
                    case OP_TEXT:
                    case OP_BINARY:
                        msg.reset();
                        msgOp = f.opcode;
                        msg.write(f.payload);
                        if (f.fin) {
                            deliver(msgOp, msg);
                            msgOp = -1;
                        }
                        break;
                    case OP_CONT:
                        if (msgOp < 0) throw new IOException("协议错误：孤立的续帧");
                        if (msg.size() + f.payload.length > MAX_FRAME_BYTES) throw new IOException("消息过大");
                        msg.write(f.payload);
                        if (f.fin) {
                            deliver(msgOp, msg);
                            msgOp = -1;
                        }
                        break;
                    default:
                        throw new IOException("协议错误：未知 opcode " + f.opcode);
                }
            }
        } catch (Throwable t) {
            finish(false, 0, null, t);
        }
    }

    private void deliver(int op, ByteArrayOutputStream msg) throws IOException {
        if (op == OP_BINARY) {
            // ★ r2/cloud-tts：二进制消息只交给实现了 BinaryListener 的监听者（云端 TTS 音频）；其余照旧丢弃
            if (mListener instanceof BinaryListener) {
                try {
                    ((BinaryListener) mListener).onBinary(this, msg.toByteArray());
                } catch (Throwable t) {
                    // 回调自己的异常不能把读线程搞死
                }
            }
            return;
        }
        if (op != OP_TEXT) return;
        String text = new String(msg.toByteArray(), "UTF-8");
        try {
            mListener.onText(this, text);
        } catch (Throwable t) {
            // 回调自己的异常不能把读线程搞死
        }
    }

    private void finish(boolean clean, int code, String reason, Throwable t) {
        synchronized (this) {
            if (mDone) {
                closeQuietly(mSocket);
                return;
            }
            mDone = true;
        }
        closeQuietly(mSocket);
        try {
            if (clean) mListener.onClosed(this, code, reason == null ? "" : reason);
            else mListener.onFailure(this, t);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ 纯函数（JVM 单测）

    /** 一帧（读出来的）。 */
    public static final class Frame {
        public final boolean fin;
        public final int opcode;
        public final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** 编码一帧（FIN=1）。mask 非 null = 客户端帧（必须带掩码）；null = 服务端帧（单测桩服务用）。 */
    public static byte[] encodeFrame(int opcode, byte[] payload, byte[] mask) {
        int n = payload == null ? 0 : payload.length;
        int hdr = 2 + (n < 126 ? 0 : (n <= 0xFFFF ? 2 : 8)) + (mask != null ? 4 : 0);
        byte[] f = new byte[hdr + n];
        f[0] = (byte) (0x80 | (opcode & 0x0F));
        int i = 1;
        int maskBit = mask != null ? 0x80 : 0;
        if (n < 126) {
            f[i++] = (byte) (maskBit | n);
        } else if (n <= 0xFFFF) {
            f[i++] = (byte) (maskBit | 126);
            f[i++] = (byte) (n >> 8);
            f[i++] = (byte) n;
        } else {
            f[i++] = (byte) (maskBit | 127);
            long ln = n;
            for (int k = 7; k >= 0; k--) f[i++] = (byte) (ln >> (8 * k));
        }
        if (mask != null) {
            System.arraycopy(mask, 0, f, i, 4);
            i += 4;
            for (int k = 0; k < n; k++) f[i + k] = (byte) (payload[k] ^ mask[k & 3]);
        } else if (n > 0) {
            System.arraycopy(payload, 0, f, i, n);
        }
        return f;
    }

    /** 读一帧（自动去掩码）。 */
    public static Frame readFrame(InputStream in) throws IOException {
        int b0 = readByte(in);
        int b1 = readByte(in);
        boolean fin = (b0 & 0x80) != 0;
        int op = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int k = 0; k < 8; k++) len = (len << 8) | readByte(in);
        }
        if (len < 0 || len > MAX_FRAME_BYTES) throw new IOException("帧过大: " + len);
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] p = new byte[(int) len];
        readFully(in, p);
        if (mask != null) for (int k = 0; k < p.length; k++) p[k] ^= mask[k & 3];
        return new Frame(fin, op, p);
    }

    /** Sec-WebSocket-Accept 期望值。 */
    public static String acceptFor(String key) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            return base64(md.digest((key + GUID).getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 标准 Base64（带 = 补齐、不换行）。纯 Java，JVM / Android 通用（音频帧 append 也用它）。 */
    public static String base64(byte[] b) {
        return base64(b, 0, b.length);
    }

    public static String base64(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder((len + 2) / 3 * 4);
        int end = off + len;
        int i = off;
        while (i + 2 < end) {
            int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8) | (b[i + 2] & 0xFF);
            sb.append(B64[v >>> 18]).append(B64[(v >>> 12) & 63]).append(B64[(v >>> 6) & 63]).append(B64[v & 63]);
            i += 3;
        }
        int rem = end - i;
        if (rem == 1) {
            int v = (b[i] & 0xFF) << 16;
            sb.append(B64[v >>> 18]).append(B64[(v >>> 12) & 63]).append("==");
        } else if (rem == 2) {
            int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8);
            sb.append(B64[v >>> 18]).append(B64[(v >>> 12) & 63]).append(B64[(v >>> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    /** 读 HTTP 响应头（到空行为止，最多 16 KB）。 */
    static String readHttpHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(512);
        int state = 0;
        while (b.size() < 16384) {
            int c = in.read();
            if (c < 0) throw new EOFException("握手时连接被关闭");
            b.write(c);
            if (c == '\r' && (state == 0 || state == 2)) state++;
            else if (c == '\n' && (state == 1 || state == 3)) {
                state++;
                if (state == 4) return new String(b.toByteArray(), "ISO-8859-1");
            } else state = 0;
        }
        throw new IOException("握手响应头过长");
    }

    static int statusCode(String head) {
        try {
            String l = firstLineRaw(head);
            String[] p = l.split(" ");
            return Integer.parseInt(p[1].trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    static String headerValue(String head, String name) {
        for (String l : head.split("\r\n")) {
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase(name)) return l.substring(c + 1).trim();
        }
        return null;
    }

    private static String firstLineRaw(String head) {
        int i = head.indexOf("\r\n");
        return i < 0 ? head : head.substring(0, i);
    }

    private static String firstLine(String head) {
        String l = firstLineRaw(head);
        return l.length() > 80 ? " (" + l.substring(0, 80) + ")" : " (" + l + ")";
    }

    private static int readByte(InputStream in) throws IOException {
        int c = in.read();
        if (c < 0) throw new EOFException("连接已关闭");
        return c;
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException("连接已关闭");
            off += n;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Throwable ignore) {
        }
    }
}
