package com.onesay.assistant.plug.phongent.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** phongent 插件 core：编解码 / 鉴权 / 任务映射 / 快照差分 / 视频换算（纯 Java）。 */
public class PgCoreTest {

    static PgHttp.Request req(String raw) throws IOException {
        return PgHttp.read(new ByteArrayInputStream(raw.getBytes("UTF-8")), PgHttp.MAX_BODY);
    }

    // ================================================================== HTTP

    @Test public void httpParsesGetWithQueryAndHeaders() throws Exception {
        PgHttp.Request r = req("GET /events?heartbeat=45&x=a%20b HTTP/1.1\r\nHost: h\r\nAuthorization: Bearer abc\r\n\r\n");
        assertEquals("GET", r.method);
        assertEquals("/events", r.path);
        assertEquals("45", r.query.get("heartbeat"));
        assertEquals("a b", r.query.get("x"));
        assertEquals("Bearer abc", r.header("Authorization"));
    }

    @Test public void httpParsesPostBodyUtf8() throws Exception {
        byte[] b = "{\"text\":\"打开蓝牙\"}".getBytes("UTF-8");
        PgHttp.Request r = req("POST /tasks HTTP/1.1\r\nContent-Length: " + b.length + "\r\n\r\n" + new String(b, "UTF-8"));
        assertEquals("打开蓝牙", r.json().getString("text"));
    }

    @Test public void httpRejectsBadRequests() {
        String[] bad = {
                "GET /x HTTP/1.1\r\n",                                              // eof
                "PUT /x HTTP/1.1\r\n\r\n",                                          // method
                "GET x HTTP/1.1\r\n\r\n",                                           // target
                "POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",          // chunked
                "POST /x HTTP/1.1\r\nContent-Length: 999999\r\n\r\n",              // too large
                "POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\n{}",                // short
                "GET /x HTTP/1.1\r\nnocolon\r\n\r\n"};
        for (String s : bad) {
            try {
                req(s);
                fail("should reject: " + s.replace("\r\n", "|"));
            } catch (IOException expected) {
            }
        }
        StringBuilder many = new StringBuilder("GET /x HTTP/1.1\r\n");
        for (int i = 0; i < 50; i++) many.append("H").append(i).append(": v\r\n");
        try {
            req(many.append("\r\n").toString());
            fail();
        } catch (IOException expected) {
        }
    }

    @Test public void httpWritesJsonResponse() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        PgHttp.writeJson(o, 401, PgHttp.err("unauthorized"));
        String s = o.toString("UTF-8");
        assertTrue(s.startsWith("HTTP/1.1 401 Unauthorized\r\n"));
        assertTrue(s.contains("Connection: close\r\n"));
        assertTrue(s.contains("Content-Length: 24\r\n"));
        assertTrue(s.endsWith("{\"error\":\"unauthorized\"}"));
    }

    // ================================================================== 鉴权

    static JSONObject pairBody(String id) throws Exception {
        return new JSONObject().put("requestId", id).put("name", "iPhone\u0007 15");
    }

    @Test public void pairApproveTokenOnceAndHashOnlyStored() throws Exception {
        PgFakes f = new PgFakes();
        PgAuth a = new PgAuth(f, f, f, null);
        String id = PgFakes.rid('a');
        PgAuth.Result r = a.request(pairBody(id));
        assertEquals(202, r.code);
        assertEquals("pending", r.body.getString("status"));
        assertEquals(Arrays.asList(id + "|iPhone 15"), f.asked);   // 控制字符被去掉
        assertEquals(200, a.request(pairBody(id)).code);              // 同 id 重复：幂等
        assertEquals("pending", a.poll(id).getString("status"));
        assertTrue(a.decide(id, true));
        JSONObject p = a.poll(id);
        assertEquals("approved", p.getString("status"));
        String token = p.getString("token");
        assertTrue(token.matches("[0-9a-f]{64}"));
        assertFalse("token 只交付一次", a.poll(id).has("token"));
        assertEquals("claimed", a.poll(id).getString("status"));
        for (String v : f.store.values()) assertFalse("不存明文 token", v.contains(token));
        for (String k : f.store.keySet()) assertFalse(k.contains(token));
        assertNotNull(a.authorize("Bearer " + token));
        assertNull(a.authorize("Bearer " + token.replace(token.charAt(0), token.charAt(0) == 'a' ? 'b' : 'a')));
        assertNull(a.authorize(token));
        assertNull(a.authorize("Bearer short"));
        assertEquals(1, a.peerCount());
        assertEquals("iPhone 15", a.peerName(a.authorize("Bearer " + token)));
    }

    @Test public void pairRateLimitDenyExpireAndNotifications() throws Exception {
        PgFakes f = new PgFakes();
        PgAuth a = new PgAuth(f, f, f, null);
        assertEquals(400, a.request(new JSONObject().put("requestId", "short")).code);
        String id1 = PgFakes.rid('a'), id2 = PgFakes.rid('b'), id3 = PgFakes.rid('c');
        assertEquals(202, a.request(pairBody(id1)).code);
        assertEquals(429, a.request(pairBody(id2)).code);          // 已有待批
        assertTrue(a.decide(id1, false));
        assertEquals("denied", a.poll(id1).getString("status"));
        assertEquals(429, a.request(pairBody(id2)).code);          // 15 秒内只受理一次
        f.elapsed += PgAuth.REQUEST_GAP_MS + 1;
        f.canPrompt = false;
        assertEquals(409, a.request(pairBody(id2)).code);          // 通知被关：不能本机确认 → 拒
        f.canPrompt = true;
        assertEquals(202, a.request(pairBody(id2)).code);
        f.elapsed += PgAuth.OFFER_TTL_MS + 1;
        assertEquals("expired", a.poll(id2).getString("status"));
        assertFalse(a.decide(id2, true));                          // 过期后点允许无效
        assertTrue(f.dismissed.contains(id2));
        assertEquals(0, a.peerCount());
        f.elapsed += PgAuth.REQUEST_GAP_MS + 1;
        f.failPut = true;
        assertEquals(202, a.request(pairBody(id3)).code);
        a.decide(id3, true);
        assertEquals("落盘失败按拒绝", "denied", a.poll(id3).getString("status"));
    }

    @Test public void peersCapRevokeAndManualIssue() throws Exception {
        PgFakes f = new PgFakes();
        PgAuth a = new PgAuth(f, f, f, null);
        List<String> tokens = new ArrayList<String>();
        for (int i = 0; i < PgAuth.MAX_PEERS; i++) tokens.add(a.issueLocal("m" + i));
        assertNull(a.issueLocal("over"));
        f.elapsed += 1;
        assertEquals(409, a.request(pairBody(PgFakes.rid('z'))).code);
        String key = a.authorize("Bearer " + tokens.get(0));
        assertTrue(a.revoke(key));
        assertNull(a.authorize("Bearer " + tokens.get(0)));
        assertNotNull(a.authorize("Bearer " + tokens.get(1)));
        assertFalse(a.revoke("tasks"));                             // 只能撤 peer: 键
        assertEquals(PgAuth.MAX_PEERS - 1, a.revokeAll());
        assertEquals(0, a.peers().length());
    }

    // ================================================================== 来源地址

    static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test public void netAllowsOnlyLanAndTailscale() throws Exception {
        String[] lan = {"192.168.1.5", "10.0.0.2", "172.20.1.1", "169.254.3.3", "fe80::1", "fd12:3456::1"};
        String[] ts = {"100.64.0.1", "100.101.102.103", "100.127.255.254", "fd7a:115c:a1e0::5"};
        String[] wan = {"8.8.8.8", "100.128.0.1", "100.63.255.255", "172.32.0.1", "2001:4860::8888", "::ffff:8.8.8.8"};
        for (String s : lan) assertTrue(s, PgNet.allowed(ip(s), false, false));
        for (String s : lan) assertFalse(s + " tsOnly", PgNet.allowed(ip(s), true, false));
        for (String s : ts) assertTrue(s, PgNet.allowed(ip(s), true, false));
        for (String s : wan) assertFalse(s, PgNet.allowed(ip(s), false, true));
        assertTrue(PgNet.allowed(ip("::ffff:192.168.0.9"), false, false));
        assertFalse("生产不放回环", PgNet.allowed(ip("127.0.0.1"), false, false));
        assertTrue(PgNet.allowed(ip("127.0.0.1"), false, true));
    }

    // ================================================================== 任务映射

    static JSONObject task(String id, String text) throws Exception {
        return new JSONObject().put("id", id).put("action", "task").put("text", text).put("conversationId", "");
    }

    @Test public void tasksEnqueueIdempotentAndMapRunStates() throws Exception {
        PgFakes f = new PgFakes();
        PgTasks t = new PgTasks(f, f);
        JSONObject r = t.enqueue(task("task-0001", "打开蓝牙"), 1);
        assertEquals("queued", r.getString("status"));
        assertEquals(PgTasks.CONVERSATION, r.getString("conversationId"));
        t.enqueue(task("task-0001", "换个说法"), 2);
        assertEquals("同 id 只执行一次", Arrays.asList("打开蓝牙"), f.submitted);
        long run = 101;
        f.set(run, "RUNNING", "打开设置", "", "");
        assertTrue(t.sync());
        assertEquals("running", t.current().state);
        assertEquals("打开设置", t.current().step);
        f.set(run, "WAITING_CONFIRM", "", "", "");
        t.sync();
        assertTrue(t.current().confirming);
        f.set(run, "DONE", "", "蓝牙已打开", "");
        t.sync();
        JSONObject item = t.list().getJSONArray("items").getJSONObject(0);
        assertEquals("completed", item.getString("state"));
        assertEquals("蓝牙已打开", item.getString("result"));
        for (String k : new String[]{"id", "text", "conversation", "state", "error", "result"}) assertTrue(k, item.has(k));
        assertFalse(t.anyActive());
        // 快照里的 queuedTasks 不带正文
        assertEquals("", t.status().getJSONObject(0).getString("text"));
    }

    @Test public void tasksFailCancelRetryStopAllAndValidation() throws Exception {
        PgFakes f = new PgFakes();
        PgTasks t = new PgTasks(f, f);
        t.enqueue(task("task-0001", "a"), 1);
        t.enqueue(task("task-0002", "b"), 2);
        f.set(101, "FAILED", "", "", "没有网络");
        t.sync();
        assertEquals("没有网络", t.list().getJSONArray("items").getJSONObject(0).getString("error"));
        t.retry("task-0001", 3);
        assertEquals(3, f.submitted.size());
        try {
            t.retry("task-0002", 4);
            fail();
        } catch (IllegalStateException e) {
            assertEquals("task_cannot_retry", e.getMessage());
        }
        t.cancel("task-0002");
        assertTrue(f.cancelled.contains(102L));
        f.set(103, "RUNNING", "", "", "");
        t.sync();
        try {
            t.cancel(t.list().getJSONArray("items").getJSONObject(2).getString("id"));
            fail("执行中的不能 cancel（要 stop）");
        } catch (IllegalStateException e) {
            assertEquals("task_not_queued", e.getMessage());
        }
        t.stopAll();
        assertTrue(f.cancelled.contains(103L));
        assertFalse(t.anyActive());
        String[][] bad = {{"x", "a"}, {"task-0009", ""}, {"task-0009", new String(new char[8001]).replace('\0', 'x')}};
        for (String[] b : bad) {
            try {
                t.enqueue(task(b[0], b[1]), 5);
                fail();
            } catch (IllegalArgumentException expected) {
            }
        }
        try {
            t.enqueue(task("task-0010", "x").put("conversationId", "other-chat-1"), 6);
            fail();
        } catch (IllegalStateException e) {
            assertEquals("conversation_changed", e.getMessage());
        }
    }

    @Test public void tasksStayQueuedWhenExecutorFullAndRestartNeverReplays() throws Exception {
        PgFakes f = new PgFakes();
        f.full = true;
        PgTasks t = new PgTasks(f, f);
        t.enqueue(task("task-0001", "a"), 1);
        assertEquals("queued", t.list().getJSONArray("items").getJSONObject(0).getString("state"));
        assertTrue(f.submitted.isEmpty());
        f.full = false;
        t.sync();
        assertEquals(1, f.submitted.size());
        // 模拟「已记 dispatching 还没拿到 runId 时进程被杀」
        JSONArray a = new JSONArray(f.store.get(PgTasks.KEY));
        a.put(new JSONObject().put("id", "task-0002").put("text", "b").put("state", "dispatching").put("runId", 0));
        f.store.put(PgTasks.KEY, a.toString());
        PgTasks t2 = new PgTasks(f, f);
        t2.sync();
        JSONObject x = t2.list().getJSONArray("items").getJSONObject(1);
        assertEquals("uncertain", x.getString("state"));
        assertEquals("不重放", 1, f.submitted.size());
        t2.acknowledge("task-0002");
        assertEquals("completed", t2.list().getJSONArray("items").getJSONObject(1).getString("state"));
        // 执行记录没了 → 待核实
        f.runs.clear();
        t2.sync();
        assertEquals("uncertain", t2.list().getJSONArray("items").getJSONObject(0).getString("state"));
    }

    @Test public void notificationReplyTasksAreTainted() throws Exception {
        PgFakes f = new PgFakes();
        PgTasks t = new PgTasks(f, f);
        String ios = "用户要求发送下面 JSON 的 reply 原文。…数据：\n{\"app\":\"微信\",\"notificationTitle\":\"张三\",\"notificationPreview\":\"在吗\",\"reply\":\"在\"}";
        t.enqueue(task("task-0001", ios), 1);
        t.enqueue(task("task-0002", "把亮度调到一半"), 2);
        assertEquals(Arrays.asList(true, false), f.taints);
        assertTrue(PgTasks.taintedText("用户要求回复通知对应会话。数据：{\"notification\":\"x\"}"));
    }

    // ================================================================== 快照 / 事件流

    @Test public void snapshotPhasesFollowTasks() throws Exception {
        PgFakes f = new PgFakes();
        PgTasks t = new PgTasks(f, f);
        PgMessages m = new PgMessages();
        JSONObject s = PgState.snapshot(f, t, m, 5000);
        assertEquals("idle", s.getString("phase"));
        assertEquals(1, s.getInt("taskQueueVersion"));
        assertTrue(s.getBoolean("eventStream"));
        assertEquals("Note8", s.getString("device"));
        for (String k : new String[]{"phase", "detail", "runId", "busy", "device", "updatedAt", "messages"}) assertTrue("iOS 必填 " + k, s.has(k));
        t.enqueue(task("task-0001", "清理通知"), 1);
        f.set(101, "WAITING_CONFIRM", "", "", "");
        t.sync();
        s = PgState.snapshot(f, t, m, 5000);
        assertEquals("confirmation", s.getString("phase"));
        assertEquals("请在安卓端确认操作", s.getString("detail"));
        assertEquals("pg-101", s.getString("runId"));
        assertEquals("task-0001", s.getString("commandId"));
        assertTrue(s.getBoolean("busy"));
        f.set(101, "CANCELLED", "", "", "");
        t.sync();
        assertEquals("cancelled", PgState.snapshot(f, t, m, 5000).getString("phase"));
        f.busy = true;
        assertTrue("本机前台忙也算 busy", PgState.snapshot(f, t, m, 5000).getBoolean("busy"));
    }

    @Test public void eventDiffSequenceStateAndMessages() throws Exception {
        PgFakes f = new PgFakes();
        PgTasks t = new PgTasks(f, f);
        PgMessages m = new PgMessages();
        PgState.EventDiff d = new PgState.EventDiff();
        JSONObject e0 = d.first(PgState.snapshot(f, t, m, 1000));
        assertEquals("snapshot", e0.getString("kind"));
        assertEquals(1, e0.getLong("sequence"));
        assertEquals(0, d.next(PgState.snapshot(f, t, m, 2000)).length);   // 只有 updatedAt 变：不发
        m.post("k", "com.tencent.mm", "微信", "张三", null, entries("在吗", 10L), null, 3000);
        JSONObject[] ev = d.next(PgState.snapshot(f, t, m, 3000));
        assertEquals(1, ev.length);
        assertEquals("messages", ev[0].getString("kind"));
        assertEquals(2, ev[0].getLong("sequence"));
        assertEquals(1, ev[0].getJSONArray("upserts").length());
        t.enqueue(task("task-0001", "x"), 1);
        m.clear();
        ev = d.next(PgState.snapshot(f, t, m, 4000));
        assertEquals(2, ev.length);
        assertEquals("state", ev[0].getString("kind"));
        assertEquals("state 帧不带消息", 0, ev[0].getJSONObject("snapshot").getJSONArray("messages").length());
        assertEquals(1, ev[1].getJSONArray("removed").length());
        assertEquals(4, ev[1].getLong("sequence"));
        assertEquals(5, d.heartbeat().getLong("sequence"));
    }

    static List<Object[]> entries(String text, long time) {
        List<Object[]> l = new ArrayList<Object[]>();
        l.add(new Object[]{text, time});
        return l;
    }

    // ================================================================== 通知预览

    @Test public void messagesDedupLinkCapAndLongPoll() throws Exception {
        PgMessages m = new PgMessages();
        final int[] changes = {0};
        m.setOnChange(new Runnable() {
            @Override public void run() {
                changes[0]++;
            }
        });
        m.post("k1", "com.tencent.mm", "微信", "群", "sc1", entries("看 https://example.com/a?b=1 这个", 1000L), null, 5000);
        m.post("k1", "com.tencent.mm", "微信", "群", "sc1", entries("看 https://example.com/a?b=1 这个", 1000L), null, 5000);
        assertEquals(1, m.size());
        assertEquals(1, changes[0]);
        JSONObject o = m.snapshot().getJSONObject(0);
        assertEquals("https://example.com/a?b=1", o.getString("link"));
        assertEquals("com.tencent.mm:sc1", o.getString("conversationId"));
        assertEquals(64, o.getString("id").length());
        assertFalse(o.getBoolean("canOpen"));
        for (int i = 0; i < 1005; i++) m.post("k" + i, "p", "a", "t", null, entries("m" + i, i), null, 1);
        assertEquals(PgMessages.MAX, m.size());
        long rev = m.revision();
        long t0 = System.currentTimeMillis();
        assertEquals(rev, m.changes(rev, 0).getLong("revision"));
        assertTrue(System.currentTimeMillis() - t0 < 500);
        try {
            m.open("nope");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("notification_expired", e.getMessage());
        }
    }

    // ================================================================== 视频

    @Test public void yuvConversionAndCoords() {
        int[] px = {0xffffffff, 0xff000000, 0xffffffff, 0xff000000};
        byte[] i420 = new byte[2 * 2 * 3 / 2];
        PgVideo.argbToYuv420(px, 2, 2, i420, true);
        assertEquals(235, i420[0] & 0xff);   // 白 = Y 235
        assertEquals(16, i420[1] & 0xff);    // 黑 = Y 16
        assertEquals(128, i420[4] & 0xff);   // 中性色度
        assertEquals(128, i420[5] & 0xff);
        int[] red = {0xffff0000, 0xffff0000, 0xffff0000, 0xffff0000};
        byte[] nv12 = new byte[6];
        PgVideo.argbToYuv420(red, 2, 2, nv12, false);
        assertEquals(82, nv12[0] & 0xff);
        assertEquals(90, nv12[4] & 0xff);
        assertEquals(240, nv12[5] & 0xff);
        assertArrayEquals(new int[]{350, 720}, PgVideo.scaled(1440, 2960, 720));
        assertEquals(1439, PgVideo.coord(1.0, 1440));
        assertEquals(0, PgVideo.coord(0.0, 1440));
        try {
            PgVideo.coord(1.5, 10);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test public void videoGateConsentIdleAndViewers() throws Exception {
        PgVideo.Gate g = new PgVideo.Gate();
        long now = 0;
        assertFalse(g.join(now));
        g.request(now);
        assertEquals("请在安卓上允许查看屏幕", g.status(now).getString("error"));
        g.decide(true, now);
        g.size(352, 720);
        assertTrue(g.status(now).getBoolean("active"));
        assertTrue(g.join(now));
        assertTrue(g.join(now));
        assertFalse("最多 2 个观看者", g.join(now));
        g.leave(now);
        g.leave(now);
        assertFalse("没人看 30 秒自动停", g.active(now + PgVideo.IDLE_STOP_MS + 1));
        g.request(now);
        g.decide(false, now);
        assertEquals("屏幕共享未获允许", g.status(now).getString("error"));
        g.request(now);
        g.decide(true, now);
        assertTrue(g.join(now));
        assertFalse("同意最长 10 分钟", g.active(now + PgVideo.CONSENT_MAX_MS + 1));
    }

    @Test public void screenInputMapsToHostPixels() throws Exception {
        PgFakes f = new PgFakes();
        PgScreen s = new PgScreen(f, f, f, f);
        try {
            s.input(new JSONObject().put("action", "back"));
            fail("没同意不能接管");
        } catch (IllegalStateException e) {
            assertEquals("screen_stale", e.getMessage());
        }
        s.start("iPhone");
        assertEquals(1, f.screenAsks);
        s.decide(true);
        s.input(new JSONObject().put("action", "gesture").put("x", 0.5).put("y", 0.5).put("endX", 0.5).put("endY", 0.5));
        s.input(new JSONObject().put("action", "gesture").put("x", 0.5).put("y", 0.8).put("endX", 0.5).put("endY", 0.2).put("duration", 9999));
        s.input(new JSONObject().put("action", "home"));
        assertEquals(Arrays.asList("tap 720,1480", "swipe 720,2367->720,592 1500", "key 3"), f.inputs);
        try {
            s.input(new JSONObject().put("action", "text").put("text", "x"));
            fail();
        } catch (IllegalStateException e) {
            assertEquals("text_input_unsupported", e.getMessage());
        }
    }
}
