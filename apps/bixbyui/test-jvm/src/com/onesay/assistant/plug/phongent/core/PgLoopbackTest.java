package com.onesay.assistant.plug.phongent.core;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.UUID;

import static org.junit.Assert.*;

/** 在回环上起真的 {@link PhongentPlug}，用 HTTP 客户端扮 phongent 主控走一遍：配对 → 鉴权 → 下发 → 进度 → 结果 → 事件流 → 画面。 */
public class PgLoopbackTest {

    PgFakes f;
    PhongentPlug plug;
    int port;

    @Before public void up() throws Exception {
        f = new PgFakes();
        f.elapsed = 0;
        plug = new PhongentPlug(f, f, Clock.SYSTEM, f, f, f, f, f, f, f);
        assertEquals("构造零副作用：不 attach 通知来源", 0, f.attaches);
        assertFalse(plug.running());
        port = plug.start(InetAddress.getByName("127.0.0.1"), 0);
        assertEquals(1, f.attaches);
    }

    @After public void down() {
        plug.stop();
    }

    static class Resp {
        int code;
        JSONObject body;
    }

    Resp call(String method, String path, String token, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(body.toString().getBytes("UTF-8"));
            o.close();
        } else {
            c.setRequestMethod(method);
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String l; (l = br.readLine()) != null; ) sb.append(l);
        r.body = new JSONObject(sb.toString());
        c.disconnect();
        return r;
    }

    String pair() throws Exception {
        String nonce = UUID.randomUUID().toString() + UUID.randomUUID().toString();
        Resp r = call("POST", "/pair", null, new JSONObject().put("requestId", nonce).put("name", "iPhone"));
        assertEquals(202, r.code);
        assertEquals("pending", call("GET", "/pair/" + nonce, null, null).body.getString("status"));
        assertTrue("本机点了允许", plug.decidePair(nonce, true));
        Resp p = call("GET", "/pair/" + nonce, null, null);
        assertEquals("approved", p.body.getString("status"));
        return p.body.getString("token");
    }

    @Test public void fullControllerFlow() throws Exception {
        assertEquals(401, call("GET", "/state", null, null).code);
        assertEquals(401, call("GET", "/state", "0000000000000000000000000000000000000000000000000000000000000000", null).code);
        String token = pair();
        Resp s = call("GET", "/state", token, null);
        assertEquals(200, s.code);
        assertEquals("idle", s.body.getString("phase"));
        assertEquals(1, s.body.getInt("taskQueueVersion"));
        assertEquals(501, call("GET", "/models/settings", token, null).code);
        assertEquals(501, call("POST", "/settings", token, new JSONObject().put("mcpLan", true)).code);
        assertEquals(404, call("GET", "/nope", token, null).code);

        String id = UUID.randomUUID().toString();
        Resp t = call("POST", "/tasks", token, new JSONObject().put("id", id).put("action", "task").put("text", "打开飞行模式").put("conversationId", ""));
        assertEquals(200, t.code);
        assertEquals("queued", t.body.getString("status"));
        assertEquals("打开飞行模式", f.submitted.get(0));
        f.set(101, "RUNNING", "打开设置", "", "");
        s = call("GET", "/state", token, null);
        assertEquals("running", s.body.getString("phase"));
        assertEquals("正在：打开设置", s.body.getString("detail"));
        assertEquals(id, s.body.getString("commandId"));
        assertEquals(409, call("POST", "/command", token, new JSONObject().put("id", UUID.randomUUID().toString()).put("action", "answer").put("text", "x").put("runId", "pg-101")).code);
        f.set(101, "DONE", "", "已打开飞行模式", "");
        Resp list = call("GET", "/tasks", token, null);
        JSONObject item = list.body.getJSONArray("items").getJSONObject(0);
        assertEquals("completed", item.getString("state"));
        assertEquals("已打开飞行模式", item.getString("result"));
        assertEquals("completed", call("GET", "/state", token, null).body.getString("phase"));
        // 重复下发同一 id：不重复执行
        call("POST", "/tasks", token, new JSONObject().put("id", id).put("action", "task").put("text", "打开飞行模式"));
        assertEquals(1, f.submitted.size());
        // 改名、会话
        assertEquals("主力机下的 Note8", call("POST", "/device/name", token, new JSONObject().put("name", "主力机下的 Note8")).body.getString("name"));
        assertEquals(PgTasks.CONVERSATION, call("GET", "/chats", token, null).body.getJSONArray("chats").getJSONObject(0).getString("id"));
        assertEquals(4, call("GET", "/chat/" + PgTasks.CONVERSATION, token, null).body.getJSONArray("messages").length() + 2);
        assertEquals(false, call("GET", "/virtual/status", token, null).body.getBoolean("active"));
    }

    @Test public void eventStreamSnapshotThenNotificationThenState() throws Exception {
        String token = pair();
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(8000);
        s.getOutputStream().write(("GET /events?heartbeat=15 HTTP/1.1\r\nAuthorization: Bearer " + token + "\r\n\r\n").getBytes("UTF-8"));
        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        assertEquals("HTTP/1.1 200 OK", br.readLine());
        JSONObject first = nextEvent(br);
        assertEquals("snapshot", first.getString("kind"));
        assertEquals(1, first.getLong("sequence"));
        f.sink.post(new NotificationSource.Notice("k", "com.tencent.mm", "微信", "张三", null, PgCoreTest.entries("晚上吃饭吗", 1L), null));
        JSONObject m = nextEvent(br);
        assertEquals("messages", m.getString("kind"));
        assertEquals(2, m.getLong("sequence"));
        assertEquals("晚上吃饭吗", m.getJSONArray("upserts").getJSONObject(0).getString("text"));
        call("POST", "/tasks", token, new JSONObject().put("id", "task-ev-0001").put("text", "回复张三：好"));
        JSONObject st = nextEvent(br);
        assertEquals("state", st.getString("kind"));
        assertEquals(3, st.getLong("sequence"));
        s.close();
    }

    static JSONObject nextEvent(BufferedReader br) throws Exception {
        for (String l; (l = br.readLine()) != null; ) if (l.startsWith("data: ")) return new JSONObject(l.substring(6));
        throw new AssertionError("stream ended");
    }

    @Test public void videoNeedsSwitchAndLocalConsent() throws Exception {
        String token = pair();
        f.screen = false;
        assertEquals(409, call("POST", "/video/start", token, new JSONObject()).code);
        f.screen = true;
        assertEquals(false, call("POST", "/video/start", token, new JSONObject()).body.getBoolean("active"));
        assertEquals(1, f.screenAsks);
        assertEquals(409, call("GET", "/video", token, null).code);
        plug.decideScreen(true);
        JSONObject vs = call("GET", "/video/status", token, null).body;
        assertTrue("同意后立刻有尺寸（iOS 要 width>0 才连）", vs.getBoolean("active") && vs.getInt("width") == 352 && vs.getInt("height") == 720);
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5000);
        s.getOutputStream().write(("GET /video HTTP/1.1\r\nAuthorization: Bearer " + token + "\r\n\r\n").getBytes("UTF-8"));
        DataInputStream in = new DataInputStream(s.getInputStream());
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
        assertTrue(head.toString().startsWith("HTTP/1.1 200 OK"));
        int n = in.readInt();
        byte[] a = new byte[n];
        in.readFully(a);
        assertEquals(0x67, a[4]);   // SPS
        n = in.readInt();
        a = new byte[n];
        in.readFully(a);
        assertEquals(0x65, a[4]);   // IDR
        s.close();
        f.busy = true;
        assertEquals("agent_busy", call("POST", "/screen/input", token, new JSONObject().put("action", "home")).body.getString("error"));
        f.busy = false;
        assertEquals(200, call("POST", "/screen/input", token, new JSONObject().put("action", "home")).code);
        assertEquals("key 3", f.inputs.get(0));
    }

    @Test public void stopIsCompleteAndRestartable() throws Exception {
        plug.stop();
        assertEquals(1, f.detaches);
        assertFalse(plug.running());
        // JDK 的 NioSocketImpl 在另一线程阻塞 accept 时会把 fd 的真正关闭推迟到 accept 返回：给 1 秒
        boolean refused = false;
        for (int i = 0; i < 20 && !refused; i++) {
            Thread.sleep(50);
            try {
                new Socket("127.0.0.1", port).close();
            } catch (java.io.IOException expected) {
                refused = true;
            }
        }
        assertTrue("stop 后端口应已关闭", refused);
        port = plug.start(InetAddress.getByName("127.0.0.1"), 0);
        assertTrue(plug.running());
    }

    @Test public void loopbackRejectedWhenPolicySaysSo() throws Exception {
        f.loopback = false;
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(3000);
        s.getOutputStream().write("GET /state HTTP/1.1\r\n\r\n".getBytes("UTF-8"));
        assertEquals("来源闸直接断开，不回包", -1, s.getInputStream().read());
        s.close();
    }
}
