package com.onesay.assistant.v2.conn.google;

import com.onesay.assistant.v2.conn.Codec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;

/**
 * [conn/google] OAuth loopback 回调接收器：只监听 127.0.0.1 的随机端口，等浏览器把 {@code ?code=…&state=…} 带回来。
 *
 * <ul>
 *   <li>只接受 GET；state 不符的请求回 400 并继续等（防止别的本机 App 抢先塞一个假 code）；</li>
 *   <li>收到 {@code error=…}（用户点了拒绝）→ 立即结束；</li>
 *   <li>超时（默认 5 分钟）→ 结束；任何情况下都关端口。</li>
 * </ul>
 * 纯 Java，JVM 单测里用真 socket 跑。
 */
public final class LoopbackReceiver {

    public static final class Result {
        public final String code;
        public final String error;

        Result(String code, String error) {
            this.code = code;
            this.error = error;
        }

        public boolean ok() {
            return code != null && code.length() > 0;
        }
    }

    private final ServerSocket mServer;

    private LoopbackReceiver(ServerSocket s) {
        mServer = s;
    }

    public static LoopbackReceiver open() throws java.io.IOException {
        ServerSocket s = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        return new LoopbackReceiver(s);
    }

    public int port() {
        return mServer.getLocalPort();
    }

    /** 注册到 Google 的 redirect_uri（桌面应用客户端允许任意端口的 http://127.0.0.1）。 */
    public String redirectUri() {
        return "http://127.0.0.1:" + port();
    }

    /** 阻塞等回调。 */
    public Result await(String expectState, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return new Result(null, "timeout");
                mServer.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
                Socket c;
                try {
                    c = mServer.accept();
                } catch (SocketTimeoutException e) {
                    return new Result(null, "timeout");
                }
                try {
                    c.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line = in.readLine();
                    Map<String, String> q = parseRequestLine(line);
                    if (q == null) {
                        respond(c, 400, "无效请求");
                        continue;
                    }
                    if (q.containsKey("error")) {
                        respond(c, 200, "已取消授权，可以回到 OneSay。");
                        return new Result(null, q.get("error"));
                    }
                    if (expectState == null || !expectState.equals(q.get("state"))) {
                        respond(c, 400, "state 不匹配，已忽略。");
                        continue;
                    }
                    String code = q.get("code");
                    if (code == null || code.length() == 0) {
                        respond(c, 400, "缺少 code");
                        continue;
                    }
                    respond(c, 200, "授权完成，可以关闭此页面回到 OneSay。");
                    return new Result(code, null);
                } finally {
                    try { c.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            return new Result(null, "io: " + e.getClass().getSimpleName());
        } finally {
            close();
        }
    }

    public void close() {
        try { mServer.close(); } catch (Exception ignored) { }
    }

    /** "GET /?code=x&state=y HTTP/1.1" → 查询参数；不是 GET 返回 null。 */
    static Map<String, String> parseRequestLine(String line) {
        if (line == null || !line.startsWith("GET ")) return null;
        int sp = line.indexOf(' ', 4);
        String target = sp < 0 ? line.substring(4) : line.substring(4, sp);
        int qm = target.indexOf('?');
        return Codec.query(qm < 0 ? "" : target.substring(qm + 1));
    }

    private static void respond(Socket c, int code, String msg) {
        try {
            String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                    + "<body style='font-family:sans-serif;padding:2em'><h3>OneSay</h3><p>" + msg + "</p></body>";
            byte[] b = Codec.utf8(html);
            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Bad Request") + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
            OutputStream os = c.getOutputStream();
            os.write(Codec.utf8(head));
            os.write(b);
            os.flush();
        } catch (Exception ignored) {
        }
    }
}
