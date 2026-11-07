package com.onesay.assistant.v2.tts;

import com.onesay.assistant.v2.net.WebSocketClient;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * ★ r2/cloud-tts：百炼 CosyVoice WebSocket 会话 + 云端播报编排（超时回退 / 打断 / 断流）+ WebSocketClient 二进制帧。
 * 全部用本地桩服务（ws://127.0.0.1），不连外网、不用 key。真连百炼的结果见 fix-20261025/CLOUD-TTS.md。
 */
public class CloudTtsTest {

    // ------------------------------------------------------------ 纯函数

    @Test
    public void runTaskJson_carriesModelVoicePcm() throws Exception {
        JSONObject o = new JSONObject(DashScopeTtsSession.runTaskJson("t1", "cosyvoice-v3.5-flash", "v-c", 24000));
        JSONObject h = o.getJSONObject("header");
        assertEquals("run-task", h.getString("action"));
        assertEquals("t1", h.getString("task_id"));
        assertEquals("duplex", h.getString("streaming"));
        JSONObject p = o.getJSONObject("payload");
        assertEquals("audio", p.getString("task_group"));
        assertEquals("tts", p.getString("task"));
        assertEquals("SpeechSynthesizer", p.getString("function"));
        assertEquals("cosyvoice-v3.5-flash", p.getString("model"));
        JSONObject params = p.getJSONObject("parameters");
        assertEquals("v-c", params.getString("voice"));
        assertEquals("pcm", params.getString("format"));
        assertEquals(24000, params.getInt("sample_rate"));
        assertNotNull(p.getJSONObject("input"));
    }

    @Test
    public void continueAndFinishJson() throws Exception {
        JSONObject c = new JSONObject(DashScopeTtsSession.continueTaskJson("t2", "你好，\"世界\"\n"));
        assertEquals("continue-task", c.getJSONObject("header").getString("action"));
        assertEquals("t2", c.getJSONObject("header").getString("task_id"));
        assertEquals("你好，\"世界\"\n", c.getJSONObject("payload").getJSONObject("input").getString("text"));
        JSONObject f = new JSONObject(DashScopeTtsSession.finishTaskJson("t2"));
        assertEquals("finish-task", f.getJSONObject("header").getString("action"));
        assertEquals(0, f.getJSONObject("payload").getJSONObject("input").length());
    }

    @Test
    public void parseEvent_finishedUsageAndFailed() {
        DashScopeTtsSession.Event e = DashScopeTtsSession.parseEvent(finished("t", 22));
        assertEquals("task-finished", e.event);
        assertEquals(22, e.characters);
        DashScopeTtsSession.Event f = DashScopeTtsSession.parseEvent(
                "{\"header\":{\"event\":\"task-failed\",\"task_id\":\"t\",\"error_code\":\"InvalidParameter\",\"error_message\":\"bad voice\"},\"payload\":{}}");
        assertEquals("task-failed", f.event);
        assertEquals("InvalidParameter", f.errorCode);
        assertEquals("bad voice", f.errorMessage);
        assertNull(DashScopeTtsSession.parseEvent("not json"));
        assertNull(DashScopeTtsSession.parseEvent("{\"type\":\"x\"}"));
    }

    @Test
    public void parseHttpCode() {
        assertEquals(401, DashScopeTtsSession.parseHttpCode("WebSocket 升级失败: HTTP 401 (HTTP/1.1 401 Unauthorized)"));
        assertEquals(-1, DashScopeTtsSession.parseHttpCode("connect timed out"));
        assertEquals(-1, DashScopeTtsSession.parseHttpCode(null));
    }

    @Test
    public void config_urlAndWorkspaceValidation() {
        assertEquals("wss://ws-0123456789abcdef.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                CloudTtsConfig.wsUrlFor(CloudTtsConfig.HOST_WORKSPACE, "ws-0123456789abcdef"));
        // refactor/oss：Workspace ID 改为构建时注入（LocalDefaults），空 = 走 dashscope 域名
        if (CloudTtsConfig.DEFAULT_WORKSPACE_ID.length() == 0) {
            assertEquals(CloudTtsConfig.DASHSCOPE_WS_URL, CloudTtsConfig.wsUrlFor(CloudTtsConfig.HOST_WORKSPACE, CloudTtsConfig.DEFAULT_WORKSPACE_ID));
        }
        assertEquals(CloudTtsConfig.DASHSCOPE_WS_URL, CloudTtsConfig.wsUrlFor(CloudTtsConfig.HOST_DASHSCOPE, "ws-abc"));
        // 非法 workspace id 不拼进主机名
        assertEquals(CloudTtsConfig.DASHSCOPE_WS_URL, CloudTtsConfig.wsUrlFor(CloudTtsConfig.HOST_WORKSPACE, "evil.com/x"));
        assertEquals(CloudTtsConfig.DASHSCOPE_WS_URL, CloudTtsConfig.wsUrlFor(CloudTtsConfig.HOST_WORKSPACE, ""));
        assertFalse(CloudTtsConfig.isValidWorkspaceId("a@b"));
        assertTrue(CloudTtsConfig.shouldDowngradeHost(401));
        assertTrue(CloudTtsConfig.shouldDowngradeHost(403));
        assertFalse(CloudTtsConfig.shouldDowngradeHost(500));
        assertFalse(CloudTtsConfig.shouldDowngradeHost(-1));
        // 默认音色必须配它复刻时的模型
        assertTrue(CloudTtsConfig.DEFAULT_VOICE.length() == 0
                || CloudTtsConfig.DEFAULT_VOICE.startsWith(CloudTtsConfig.DEFAULT_MODEL + "-"));
        assertEquals(300, CloudTtsConfig.clampTimeout(1));
        assertEquals(10000, CloudTtsConfig.clampTimeout(99999));
    }

    // ------------------------------------------------------------ WebSocketClient 二进制帧

    @Test
    public void webSocket_binaryGoesToBinaryListenerOnly() throws Exception {
        Stub st = new Stub();
        st.audioFrames = 2;
        st.start();
        final List<byte[]> bins = Collections.synchronizedList(new ArrayList<byte[]>());
        final List<String> texts = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        class L implements WebSocketClient.Listener, WebSocketClient.BinaryListener {
            @Override public void onText(WebSocketClient ws, String t) {
                texts.add(t);
                if (t.contains("task-started")) {
                    ws.sendText(DashScopeTtsSession.continueTaskJson("x", "hi"));
                    ws.sendText(DashScopeTtsSession.finishTaskJson("x"));
                }
                if (t.contains("task-finished")) done.countDown();
            }
            @Override public void onBinary(WebSocketClient ws, byte[] d) { bins.add(d); }
            @Override public void onClosed(WebSocketClient ws, int c, String r) { done.countDown(); }
            @Override public void onFailure(WebSocketClient ws, Throwable t) { done.countDown(); }
        }
        WebSocketClient ws = new WebSocketClient(new L());
        ws.connect(st.url(), null, 2000, 5000);
        ws.sendText(DashScopeTtsSession.runTaskJson("x", "m", "v", 24000));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, bins.size());
        assertEquals(Stub.FRAME_BYTES, bins.get(0).length);
        ws.close(1000, "bye");
    }

    @Test
    public void webSocket_plainListenerStillIgnoresBinary() throws Exception {
        Stub st = new Stub();
        st.audioFrames = 3;
        st.start();
        final List<String> texts = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        WebSocketClient ws = new WebSocketClient(new WebSocketClient.Listener() {
            @Override public void onText(WebSocketClient w, String t) {
                texts.add(t);
                if (t.contains("task-started")) {
                    w.sendText(DashScopeTtsSession.continueTaskJson("x", "hi"));
                    w.sendText(DashScopeTtsSession.finishTaskJson("x"));
                }
                if (t.contains("task-finished")) done.countDown();
            }
            @Override public void onClosed(WebSocketClient w, int c, String r) { done.countDown(); }
            @Override public void onFailure(WebSocketClient w, Throwable t) { done.countDown(); }
        });
        ws.connect(st.url(), null, 2000, 5000);
        ws.sendText(DashScopeTtsSession.runTaskJson("x", "m", "v", 24000));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        // 只收到文本事件（task-started、3 条 result-generated、task-finished），二进制被丢掉且没把读线程搞坏
        assertEquals(5, texts.size());
        for (String t : texts) assertTrue(t.startsWith("{"));
        ws.close(1000, "bye");
    }

    // ------------------------------------------------------------ DashScopeTtsSession 端到端

    @Test
    public void session_happyPath_audioThenDone() throws Exception {
        Stub st = new Stub();
        st.audioFrames = 3;
        st.start();
        SRec r = new SRec();
        DashScopeTtsSession s = new DashScopeTtsSession(st.url(), "sk-test", "cosyvoice-v3.5-flash", "v-c", 24000, 2000, r);
        s.start("你好，我是你的语音助手。");
        assertTrue(r.end.await(5, TimeUnit.SECONDS));
        assertTrue(r.connected);
        assertTrue(r.done);
        assertNull(r.err);
        assertEquals(3 * Stub.FRAME_BYTES, r.audio.size());
        assertEquals(3 * Stub.FRAME_BYTES, s.audioBytes());
        assertEquals(22, s.usageCharacters);
        assertEquals(DashScopeTtsSession.State.DONE, s.state());
        assertEquals("Bearer sk-test", st.auth);
        // 客户端发了 run-task → continue-task（带原文）→ finish-task，且 task_id 一致
        assertTrue(st.received.size() >= 3);
        JSONObject run = new JSONObject(st.received.get(0));
        JSONObject cont = new JSONObject(st.received.get(1));
        JSONObject fin = new JSONObject(st.received.get(2));
        assertEquals("run-task", run.getJSONObject("header").getString("action"));
        assertEquals("continue-task", cont.getJSONObject("header").getString("action"));
        assertEquals("你好，我是你的语音助手。", cont.getJSONObject("payload").getJSONObject("input").getString("text"));
        assertEquals("finish-task", fin.getJSONObject("header").getString("action"));
        String tid = run.getJSONObject("header").getString("task_id");
        assertEquals(tid, cont.getJSONObject("header").getString("task_id"));
        assertEquals(tid, fin.getJSONObject("header").getString("task_id"));
    }

    @Test
    public void session_taskFailed_reportsCode() throws Exception {
        Stub st = new Stub();
        st.endWith = "failed";
        st.start();
        SRec r = new SRec();
        DashScopeTtsSession s = new DashScopeTtsSession(st.url(), "k", "m", "v", 24000, 2000, r);
        s.start("x");
        assertTrue(r.end.await(5, TimeUnit.SECONDS));
        assertNotNull(r.err);
        assertFalse(r.done);
        assertEquals("InvalidParameter", s.errorCode);
        assertEquals(DashScopeTtsSession.State.FAILED, s.state());
    }

    @Test
    public void session_handshake401_reportsHttpCode() throws Exception {
        Stub st = new Stub();
        st.httpCode = 401;
        st.start();
        SRec r = new SRec();
        DashScopeTtsSession s = new DashScopeTtsSession(st.url(), "k", "m", "v", 24000, 2000, r);
        s.start("x");
        assertTrue(r.end.await(5, TimeUnit.SECONDS));
        assertNotNull(r.err);
        assertFalse(r.connected);
        assertEquals(401, s.handshakeHttpCode);
        assertEquals("HTTP 401", s.errorCode);
    }

    // ------------------------------------------------------------ CloudUtterance：回退 / 打断 / 断流

    @Test
    public void utterance_cloudOk_noFallback() throws Exception {
        Stub st = new Stub();
        st.audioFrames = 4;
        st.start();
        URec u = run(st, 1500, 2500);
        assertTrue(u.finished.await(5, TimeUnit.SECONDS));
        assertEquals(CloudUtterance.Outcome.CLOUD_OK, u.utt.outcome());
        assertNull(u.fallbackReason);
        assertTrue(u.sink.began);
        assertTrue(u.sink.ended);
        assertFalse(u.sink.stopped);
        assertEquals(24000, u.sink.rate);
        assertEquals(4 * Stub.FRAME_BYTES, u.sink.bytes.size());
        assertTrue(u.utt.connectMs >= 0);
        assertTrue(u.utt.firstAudioMs >= u.utt.connectMs);
    }

    @Test
    public void utterance_connectTimeout_fallsBackToSystem() throws Exception {
        Stub st = new Stub();
        st.silentHandshake = true;   // 接了 TCP 但不回升级响应
        st.start();
        long t0 = System.currentTimeMillis();
        URec u = run(st, 300, 2000);
        assertTrue(u.finished.await(5, TimeUnit.SECONDS));
        long dt = System.currentTimeMillis() - t0;
        assertEquals(CloudUtterance.Outcome.FALLBACK, u.utt.outcome());
        assertNotNull(u.fallbackReason);
        assertTrue(u.fallbackReason, u.fallbackReason.contains("建连超时"));
        assertEquals("测试句子。", u.fallbackText);
        assertTrue("应在建连预算附近回退，实际 " + dt + "ms", dt < 1500);
        assertFalse(u.sink.began);
    }

    @Test
    public void utterance_firstAudioTimeout_fallsBack_andLateAudioIgnored() throws Exception {
        Stub st = new Stub();
        st.audioFrames = 2;
        st.delayBeforeAudioMs = 900;
        st.start();
        URec u = run(st, 500, 400);
        assertTrue(u.finished.await(5, TimeUnit.SECONDS));
        assertEquals(CloudUtterance.Outcome.FALLBACK, u.utt.outcome());
        assertTrue(u.fallbackReason, u.fallbackReason.contains("首包超时"));
        Thread.sleep(800);   // 迟到的音频不能再出声
        assertFalse(u.sink.began);
        assertEquals(0, u.sink.bytes.size());
        assertEquals(1, u.fallbacks);
    }

    @Test
    public void utterance_handshakeRejected_fallsBackImmediately() throws Exception {
        Stub st = new Stub();
        st.httpCode = 403;
        st.start();
        long t0 = System.currentTimeMillis();
        URec u = run(st, 1500, 2500);
        assertTrue(u.finished.await(5, TimeUnit.SECONDS));
        assertEquals(CloudUtterance.Outcome.FALLBACK, u.utt.outcome());
        assertTrue(System.currentTimeMillis() - t0 < 1400);
        assertEquals(403, u.utt.session().handshakeHttpCode);
    }

    @Test
    public void utterance_noAudioThenFinished_fallsBack() throws Exception {
        Stub st = new Stub();
        st.audioFrames = 0;
        st.start();
        URec u = run(st, 1500, 2500);
        assertTrue(u.finished.await(5, TimeUnit.SECONDS));
        assertEquals(CloudUtterance.Outcome.FALLBACK, u.utt.outcome());
        assertEquals(1, u.fallbacks);
    }

    @Test
    public void utterance_dropAfterAudio_playsPartial_noSystemTts() throws Exception {
        Stub st = new Stub();
        st.audioFrames = 2;
        st.endWith = "drop";
        st.start();
        URec u = run(st, 1500, 2500);
        assertTrue(u.finished.await(5, TimeUnit.SECONDS));
        assertEquals(CloudUtterance.Outcome.CLOUD_PARTIAL, u.utt.outcome());
        assertNull(u.fallbackReason);
        assertTrue(u.sink.ended);
        assertEquals(2 * Stub.FRAME_BYTES, u.sink.bytes.size());
    }

    @Test
    public void utterance_cancel_stopsSinkAndNeverFallsBack() throws Exception {
        Stub st = new Stub();
        st.audioFrames = 2;
        st.delayBeforeAudioMs = 300;
        st.start();
        URec u = run(st, 1500, 2500);
        Thread.sleep(150);
        u.utt.cancel();
        assertTrue(u.finished.await(2, TimeUnit.SECONDS));
        Thread.sleep(3000);  // 越过首包预算：看门狗也不能再回退
        assertEquals(CloudUtterance.Outcome.CANCELLED, u.utt.outcome());
        assertTrue(u.sink.stopped);
        assertNull(u.fallbackReason);
        assertEquals(0, u.sink.bytes.size());
        assertFalse(u.utt.isActive());
        assertEquals(DashScopeTtsSession.State.CANCELLED, u.utt.session().state());
    }

    // ------------------------------------------------------------ 桩与记录器

    static String finished(String tid, int chars) {
        return "{\"header\":{\"event\":\"task-finished\",\"task_id\":\"" + tid + "\"},\"payload\":{\"output\":{},\"usage\":{\"characters\":" + chars + "}}}";
    }

    URec run(Stub st, int connectMs, int firstMs) {
        final URec u = new URec();
        final String url = st.url();
        u.utt = new CloudUtterance("测试句子。", new CloudUtterance.SessionFactory() {
            @Override
            public DashScopeTtsSession create(DashScopeTtsSession.Listener l) {
                return new DashScopeTtsSession(url, "k", "cosyvoice-v3.5-flash", "v", 24000, 2000, l);
            }
        }, u.sink, new CloudUtterance.Fallback() {
            @Override
            public void speakSystem(String text, String reason) {
                u.fallbackText = text;
                u.fallbackReason = reason;
                u.fallbacks++;
            }
        }, new CloudUtterance.Observer() {
            @Override
            public void onFinished(CloudUtterance c) {
                u.finished.countDown();
            }
        }, connectMs, firstMs);
        u.utt.start();
        return u;
    }

    static final class URec {
        CloudUtterance utt;
        final FakeSink sink = new FakeSink();
        volatile String fallbackReason, fallbackText;
        volatile int fallbacks;
        final CountDownLatch finished = new CountDownLatch(1);
    }

    static final class FakeSink implements CloudUtterance.Sink {
        volatile boolean began, ended, stopped;
        volatile int rate;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override public void begin(int sampleRate) { began = true; rate = sampleRate; }
        @Override public synchronized void write(byte[] pcm) { bytes.write(pcm, 0, pcm.length); }
        @Override public void end() { ended = true; }
        @Override public void stop() { stopped = true; }
    }

    static final class SRec implements DashScopeTtsSession.Listener {
        volatile boolean connected, done;
        volatile String err;
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch end = new CountDownLatch(1);

        @Override public void onConnected(DashScopeTtsSession s) { connected = true; }
        @Override public synchronized void onAudio(DashScopeTtsSession s, byte[] pcm) { audio.write(pcm, 0, pcm.length); }
        @Override public void onDone(DashScopeTtsSession s) { done = true; end.countDown(); }
        @Override public void onError(DashScopeTtsSession s, String r) { err = r; end.countDown(); }
    }

    /** CosyVoice duplex 桩：握手 → run-task → task-started → 收到 finish-task 后按配置发音频 + 结尾。 */
    static final class Stub implements Runnable {
        static final int FRAME_BYTES = 4800;   // 100ms @ 24kHz 16-bit
        final ServerSocket ss;
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        volatile String auth;
        int httpCode = 101;
        boolean silentHandshake;
        int audioFrames = 3;
        int delayBeforeAudioMs;
        String endWith = "finished";

        Stub() throws Exception {
            ss = new ServerSocket(0);
        }

        void start() {
            Thread t = new Thread(this, "tts-stub");
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "ws://127.0.0.1:" + ss.getLocalPort() + "/api-ws/v1/inference";
        }

        @Override
        public void run() {
            try {
                Socket s = ss.accept();
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                StringBuilder head = new StringBuilder();
                int c;
                while ((c = in.read()) >= 0) {
                    head.append((char) c);
                    if (head.toString().endsWith("\r\n\r\n")) break;
                }
                if (silentHandshake) {
                    Thread.sleep(5000);
                    s.close();
                    return;
                }
                String key = null;
                for (String l : head.toString().split("\r\n")) {
                    if (l.toLowerCase().startsWith("sec-websocket-key:")) key = l.substring(18).trim();
                    if (l.toLowerCase().startsWith("authorization:")) auth = l.substring(14).trim();
                }
                if (httpCode != 101) {
                    out.write(("HTTP/1.1 " + httpCode + " Nope\r\nContent-Length: 0\r\n\r\n").getBytes("UTF-8"));
                    out.flush();
                    s.close();
                    return;
                }
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                        + WebSocketClient.acceptFor(key) + "\r\n\r\n").getBytes("UTF-8"));
                out.flush();
                String tid = "x";
                while (true) {
                    WebSocketClient.Frame f = WebSocketClient.readFrame(in);
                    if (f.opcode == WebSocketClient.OP_CLOSE) break;
                    String text = new String(f.payload, "UTF-8");
                    received.add(text);
                    JSONObject h = new JSONObject(text).getJSONObject("header");
                    String action = h.getString("action");
                    tid = h.getString("task_id");
                    if ("run-task".equals(action)) {
                        sendText(out, "{\"header\":{\"event\":\"task-started\",\"task_id\":\"" + tid + "\"},\"payload\":{}}");
                    } else if ("finish-task".equals(action)) {
                        if (delayBeforeAudioMs > 0) Thread.sleep(delayBeforeAudioMs);
                        for (int i = 0; i < audioFrames; i++) {
                            byte[] pcm = new byte[FRAME_BYTES];
                            pcm[0] = (byte) i;
                            out.write(WebSocketClient.encodeFrame(WebSocketClient.OP_BINARY, pcm, null));
                            out.flush();
                            sendText(out, "{\"header\":{\"event\":\"result-generated\",\"task_id\":\"" + tid + "\"},\"payload\":{}}");
                        }
                        if ("finished".equals(endWith)) {
                            sendText(out, finished(tid, 22));
                        } else if ("failed".equals(endWith)) {
                            sendText(out, "{\"header\":{\"event\":\"task-failed\",\"task_id\":\"" + tid
                                    + "\",\"error_code\":\"InvalidParameter\",\"error_message\":\"bad\"},\"payload\":{}}");
                        } else {
                            s.close();   // drop
                            return;
                        }
                    }
                }
                s.close();
            } catch (Throwable ignore) {
            }
        }

        static void sendText(OutputStream out, String json) throws Exception {
            out.write(WebSocketClient.encodeFrame(WebSocketClient.OP_TEXT, json.getBytes("UTF-8"), null));
            out.flush();
        }
    }
}
