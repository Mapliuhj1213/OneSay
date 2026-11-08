package com.onesay.assistant.plug.vyrse.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.Assert.*;

/** compat-vyrse：裸 Socket 明文 HTTP 客户端（不放开 App 全局 cleartext）。 */
public class VyrseLanSocketTest {

    static VyrseHttp.Response parse(String raw) throws IOException {
        return VyrseHttp.LanSocket.readResponse(new ByteArrayInputStream(raw.getBytes("UTF-8")));
    }

    @Test public void parsesLengthChunkedAndEof() throws Exception {
        VyrseHttp.Response a = parse("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 13\r\n\r\n{\"commands\":[]}");
        assertEquals(200, a.code);
        assertEquals("{\"commands\":", a.body.substring(0, 12));
        VyrseHttp.Response b = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n{\"a\":\r\n3;x=1\r\n12}\r\n0\r\n\r\n");
        assertEquals("{\"a\":12}", b.body);
        VyrseHttp.Response c = parse("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n{\"error\":\"x\"}");
        assertEquals(401, c.code);
        assertEquals("{\"error\":\"x\"}", c.body);
    }

    @Test public void rejectsJunk() {
        for (String raw : new String[]{"", "garbage\r\n\r\n", "HTTP/1.1 abc\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc"}) {
            try {
                parse(raw);
                fail(raw);
            } catch (IOException expected) {
            }
        }
    }

    @Test public void refusesPublicHostsAndHeaderInjectionBeforeConnecting() {
        VyrseHttp h = new VyrseHttp.LanSocket();
        String[][] bad = {{"http://8.8.8.8:47630/phone/commands", "t"}, {"https://192.168.1.2/x", "t"},
                {"http://192.168.1.2:47630/phone/commands", "t\r\nX-Evil: 1"}};
        for (String[] b : bad) {
            try {
                h.send("GET", b[0], b[1], null, 1000);
                fail(b[0]);
            } catch (IOException expected) {
            }
        }
    }

    /** 本机起一个一次性 HTTP 服务，验证真实往返：请求行、key 头、Content-Length、body。 */
    @Test public void roundTripOverLoopback() throws Exception {
        final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final String[] got = new String[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Socket s = ss.accept();
                    InputStream in = s.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    int c;
                    while ((c = in.read()) >= 0) {
                        sb.append((char) c);
                        if (sb.indexOf("\r\n\r\n") >= 0 && sb.toString().endsWith("}")) break;
                    }
                    got[0] = sb.toString();
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n b\r\n{\"ok\":true}\r\n0\r\n\r\n".getBytes("UTF-8"));
                    out.flush();
                    s.close();
                } catch (IOException ignored) {
                }
            }
        });
        t.start();
        VyrseHttp.Response r = new VyrseHttp.LanSocket().send("POST", "http://127.0.0.1:" + ss.getLocalPort() + "/phone/command-result",
                "KEY123", "{\"id\":\"1\",\"result\":{}}", 5000);
        t.join(5000);
        ss.close();
        assertEquals(200, r.code);
        assertEquals("{\"ok\":true}", r.body);
        assertTrue(got[0], got[0].startsWith("POST /phone/command-result HTTP/1.1\r\n"));
        assertTrue(got[0].contains("X-Hermes-Session-Token: KEY123\r\n"));
        assertTrue(got[0].contains("Content-Length: 22\r\n"));
        assertTrue(got[0].contains("Content-Type: application/json\r\n"));
    }
}
