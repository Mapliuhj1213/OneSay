package com.onesay.assistant.v2.r14;

import com.onesay.assistant.v2.asr.RealtimeAsrPolicy;
import com.onesay.assistant.v2.asr.RealtimeAsrSession;
import com.onesay.assistant.v2.net.WebSocketClient;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * R14 #2：流式识别（WebSocket 客户端 + qwen3-asr-flash-realtime 协议 + 回退裁决）。
 * 本地桩服务（ServerSocket，ws://127.0.0.1）跑完整握手 + 事件序列；真连百炼的验证见 verify-20261015-r14/probe/RtProbe.java。
 */
public class RealtimeAsrTest {

    // ------------------------------------------------------------ WebSocket 编解码

    @Test
    public void acceptKey_rfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketClient.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void base64_matchesJdk() {
        Random r = new Random(7);
        for (int n = 0; n < 70; n++) {
            byte[] b = new byte[n * 13 + (n % 3)];
            r.nextBytes(b);
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), WebSocketClient.base64(b));
        }
    }

    @Test
    public void frameRoundTrip_allLengthForms_maskedAndUnmasked() throws Exception {
        for (int n : new int[] {0, 1, 125, 126, 300, 65535, 65536, 200000}) {
            byte[] p = new byte[n];
            new Random(n).nextBytes(p);
            byte[] mask = {1, 2, 3, 4};
            WebSocketClient.Frame f = WebSocketClient.readFrame(new ByteArrayInputStream(WebSocketClient.encodeFrame(WebSocketClient.OP_TEXT, p, mask)));
            assertTrue(f.fin);
            assertEquals(WebSocketClient.OP_TEXT, f.opcode);
            assertArrayEquals("n=" + n, p, f.payload);
            WebSocketClient.Frame g = WebSocketClient.readFrame(new ByteArrayInputStream(WebSocketClient.encodeFrame(WebSocketClient.OP_BINARY, p, null)));
            assertArrayEquals(p, g.payload);
        }
        byte[] masked = WebSocketClient.encodeFrame(WebSocketClient.OP_TEXT, "hi".getBytes("UTF-8"), new byte[] {9, 9, 9, 9});
        assertEquals("客户端帧必须带掩码位", 0x80, masked[1] & 0x80);
    }

    // ------------------------------------------------------------ 协议

    @Test
    public void parseEvents() {
        RealtimeAsrSession.Event p = RealtimeAsrSession.parseEvent(
                "{\"type\":\"conversation.item.input_audio_transcription.text\",\"text\":\"查看\",\"stash\":\"最新的\"}");
        assertTrue(p.isPartial());
        assertEquals("查看最新的", p.text);
        RealtimeAsrSession.Event c = RealtimeAsrSession.parseEvent(
                "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"查看最新的邮件。\"}");
        assertTrue(c.isFinal());
        assertEquals("查看最新的邮件。", c.text);
        RealtimeAsrSession.Event e = RealtimeAsrSession.parseEvent(
                "{\"type\":\"error\",\"error\":{\"code\":\"invalid_api_key\",\"message\":\"bad key\"}}");
        assertTrue(e.isError());
        assertEquals("invalid_api_key", e.code);
        assertTrue(RealtimeAsrSession.parseEvent("{\"type\":\"input_audio_buffer.speech_stopped\",\"audio_end_ms\":1216}").isSpeechStopped());
        assertNull(RealtimeAsrSession.parseEvent("not json"));
        assertNull(RealtimeAsrSession.parseEvent("{}"));
    }

    @Test
    public void sessionUpdate_serverVad450_corpusWithoutPromptPrefix() throws Exception {
        String corpus = RealtimeAsrSession.corpusFromHotwords("语音识别热词表，以下专有名词请优先按原词识别：必应、QQ邮箱");
        assertEquals("★ device-1102：词表改空格分隔", "必应 QQ邮箱", corpus);
        JSONObject o = new JSONObject(RealtimeAsrSession.sessionUpdateJson(450, corpus));
        assertEquals("session.update", o.getString("type"));
        JSONObject s = o.getJSONObject("session");
        assertEquals("pcm", s.getString("input_audio_format"));
        assertEquals(16000, s.getInt("sample_rate"));
        assertEquals("server_vad", s.getJSONObject("turn_detection").getString("type"));
        assertEquals(450, s.getJSONObject("turn_detection").getInt("silence_duration_ms"));
        assertEquals("必应 QQ邮箱", s.getJSONObject("input_audio_transcription").getJSONObject("corpus").getString("text"));
        assertEquals("zh", s.getJSONObject("input_audio_transcription").getString("language"));
    }

    @Test
    public void appendJson_isValidAndCarriesBase64() throws Exception {
        byte[] pcm = new byte[3200];
        new Random(1).nextBytes(pcm);
        JSONObject o = new JSONObject(RealtimeAsrSession.appendJson(pcm, 5));
        assertEquals("input_audio_buffer.append", o.getString("type"));
        assertArrayEquals(pcm, java.util.Base64.getDecoder().decode(o.getString("audio")));
    }

    // ------------------------------------------------------------ 端到端（本地桩服务）

    /** 极简 ws 桩服务：握手 → 收 session.update → 回 session.updated → 收到 N 帧音频后按脚本发事件。 */
    static final class StubServer implements Runnable {
        final ServerSocket ss;
        final List<String> script;
        final int afterChunks;
        final boolean closeInsteadOfFinal;
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        volatile String authHeader;

        StubServer(List<String> script, int afterChunks, boolean closeInsteadOfFinal) throws Exception {
            ss = new ServerSocket(0);
            this.script = script;
            this.afterChunks = afterChunks;
            this.closeInsteadOfFinal = closeInsteadOfFinal;
            Thread t = new Thread(this, "ws-stub");
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "ws://127.0.0.1:" + ss.getLocalPort() + "/api-ws/v1/realtime?model=x";
        }

        @Override
        public void run() {
            try {
                Socket s = ss.accept();
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                StringBuilder head = new StringBuilder();
                int c, st = 0;
                while ((c = in.read()) >= 0) {
                    head.append((char) c);
                    if (head.toString().endsWith("\r\n\r\n")) break;
                }
                String key = null;
                for (String l : head.toString().split("\r\n")) {
                    if (l.toLowerCase().startsWith("sec-websocket-key:")) key = l.substring(18).trim();
                    if (l.toLowerCase().startsWith("authorization:")) authHeader = l.substring(14).trim();
                }
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                        + WebSocketClient.acceptFor(key) + "\r\n\r\n").getBytes("UTF-8"));
                out.flush();
                int audio = 0;
                boolean scripted = false;
                while (true) {
                    WebSocketClient.Frame f = WebSocketClient.readFrame(in);
                    if (f.opcode == WebSocketClient.OP_CLOSE) break;
                    String text = new String(f.payload, "UTF-8");
                    received.add(text);
                    if (text.contains("session.update")) send(out, "{\"type\":\"session.updated\"}");
                    if (text.contains("input_audio_buffer.append")) audio++;
                    if (!scripted && audio >= afterChunks) {
                        scripted = true;
                        for (String ev : script) send(out, ev);
                        if (closeInsteadOfFinal) {
                            out.write(WebSocketClient.encodeFrame(WebSocketClient.OP_CLOSE, new byte[] {0x03, (byte) 0xF3}, null));
                            out.flush();
                            s.close();
                            return;
                        }
                    }
                }
                s.close();
            } catch (Throwable ignore) {
            }
        }

        static void send(OutputStream out, String json) throws Exception {
            out.write(WebSocketClient.encodeFrame(WebSocketClient.OP_TEXT, json.getBytes("UTF-8"), null));
            out.flush();
        }
    }

    static final class Rec implements RealtimeAsrSession.Listener {
        final List<String> partials = Collections.synchronizedList(new ArrayList<String>());
        volatile String fin, err;
        volatile boolean ready, started, stopped;
        final CountDownLatch done = new CountDownLatch(1);

        @Override public void onReady(RealtimeAsrSession s) { ready = true; }
        @Override public void onSpeechStarted(RealtimeAsrSession s) { started = true; }
        @Override public void onSpeechStopped(RealtimeAsrSession s) { stopped = true; }
        @Override public void onPartial(RealtimeAsrSession s, String t) { partials.add(t); }
        @Override public void onFinal(RealtimeAsrSession s, String t) { fin = t; done.countDown(); }
        @Override public void onError(RealtimeAsrSession s, String r) { err = r; done.countDown(); }
    }

    static final String SPEECH_STARTED = "{\"type\":\"input_audio_buffer.speech_started\",\"audio_start_ms\":216}";
    static final String PARTIAL_1 = "{\"type\":\"conversation.item.input_audio_transcription.text\",\"text\":\"\",\"stash\":\"讲个\"}";
    static final String PARTIAL_2 = "{\"type\":\"conversation.item.input_audio_transcription.text\",\"text\":\"\",\"stash\":\"讲个笑话\"}";
    static final String STOPPED = "{\"type\":\"input_audio_buffer.speech_stopped\",\"audio_end_ms\":1216}";
    static final String EMPTY_FINAL = "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"\"}";
    static final String FINAL = "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"讲个笑话。\"}";

    @Test
    public void endToEnd_audioQueuedDuringConnect_partialsThenFinal() throws Exception {
        StubServer srv = new StubServer(java.util.Arrays.asList(SPEECH_STARTED, PARTIAL_1, PARTIAL_2, STOPPED, EMPTY_FINAL, FINAL), 5, false);
        Rec rec = new Rec();
        RealtimeAsrSession s = new RealtimeAsrSession(srv.url(), "sk-test", 450, "语音识别热词表，以下专有名词请优先按原词识别：笑话", 3000, 1, rec);
        // 建连前就开始喂音频（按下键 → 开窗前后），应排队、连上后按序补发
        short[] frame = voicedFrame();   // ★ device-1102：开麦闸门要求真有人声能量（全 0 帧 = 近空音频，结果会被丢）
        s.start();
        s.tMicOpen = System.nanoTime() / 1000000L - 1000;   // 开麦已过 OPEN_GATE_MS（真机由绑窗 markMicOpen 打点）
        for (int i = 0; i < 8; i++) s.appendShorts(frame, frame.length);
        assertTrue("最终结果按时到达", rec.done.await(5, TimeUnit.SECONDS));
        assertNull(rec.err);
        assertEquals("讲个笑话。", rec.fin);
        assertEquals(java.util.Arrays.asList("讲个", "讲个笑话"), rec.partials);
        assertTrue(rec.ready && rec.started && rec.stopped);
        assertTrue(s.finalDelivered());
        assertEquals("Bearer sk-test", srv.authHeader);
        assertTrue("第一条是 session.update", srv.received.get(0).contains("\"session.update\""));
        assertTrue(srv.received.get(0).contains("\"silence_duration_ms\":450"));
        assertFalse(s.isHealthy());
        s.cancel();
    }

    static short[] voicedFrame() {
        short[] f = new short[1600];
        for (int i = 0; i < f.length; i++) f[i] = (short) Math.round(3000 * Math.sin(2 * Math.PI * 220 * i / 16000.0));
        return f;
    }

    /** ★ device-1102 问题 2：开麦即送近空音频（全 0）→ 服务端幻觉出整句也不交付（中间 / 最终都挡），会话不关。 */
    @Test
    public void openMicGate_silentAudioHallucinationIsDropped() throws Exception {
        StubServer srv = new StubServer(java.util.Arrays.asList(SPEECH_STARTED, PARTIAL_1, PARTIAL_2, STOPPED, FINAL), 5, false);
        Rec rec = new Rec();
        RealtimeAsrSession s = new RealtimeAsrSession(srv.url(), "sk-test", 450, null, 3000, 1, rec);
        short[] frame = new short[1600];
        s.start();
        s.tMicOpen = System.nanoTime() / 1000000L - 1000;
        for (int i = 0; i < 8; i++) s.appendShorts(frame, frame.length);
        assertFalse("近空音频的幻觉结果不交付", rec.done.await(1500, TimeUnit.MILLISECONDS));
        assertNull(rec.fin);
        assertTrue("中间结果也没上屏: " + rec.partials, rec.partials.isEmpty());
        assertTrue(s.gateDropped() >= 1);
        s.cancel();
    }

    /** ★ device-1102：纯判据 —— 开麦 500 ms 内 / 无人声 / 没 speech_started 的中间结果 / corpus 回吐 一律不交付。 */
    @Test
    public void openMicGate_rules() {
        assertFalse(RealtimeAsrSession.gateAllows(false, 100, true, true, false));
        assertFalse(RealtimeAsrSession.gateAllows(true, RealtimeAsrSession.OPEN_GATE_MS - 1, true, true, false));
        assertFalse("没有有声音频", RealtimeAsrSession.gateAllows(true, 2000, true, false, false));
        assertFalse("speech_started 前的中间结果", RealtimeAsrSession.gateAllows(false, 2000, false, true, false));
        assertTrue("最终结果不要求 speech_started", RealtimeAsrSession.gateAllows(true, 2000, false, true, false));
        assertFalse("corpus 回吐", RealtimeAsrSession.gateAllows(true, 2000, true, true, true));
        assertTrue(RealtimeAsrSession.gateAllows(false, 600, true, true, false));
    }

    @Test
    public void serverClosesBeforeFinal_reportsErrorOnce() throws Exception {
        StubServer srv = new StubServer(java.util.Arrays.asList(SPEECH_STARTED, PARTIAL_1), 3, true);
        Rec rec = new Rec();
        RealtimeAsrSession s = new RealtimeAsrSession(srv.url(), "k", 450, null, 3000, 1, rec);
        s.start();
        short[] frame = new short[1600];
        for (int i = 0; i < 5; i++) s.appendShorts(frame, frame.length);
        assertTrue(rec.done.await(5, TimeUnit.SECONDS));
        assertNull(rec.fin);
        assertNotNull(rec.err);
        assertEquals(RealtimeAsrSession.State.FAILED, s.state());
        assertFalse(s.isHealthy());
    }

    @Test
    public void connectRefused_reportsError() throws Exception {
        ServerSocket tmp = new ServerSocket(0);
        int port = tmp.getLocalPort();
        tmp.close();
        Rec rec = new Rec();
        RealtimeAsrSession s = new RealtimeAsrSession("ws://127.0.0.1:" + port + "/x", "k", 450, null, 1500, 1, rec);
        s.start();
        assertTrue(rec.done.await(5, TimeUnit.SECONDS));
        assertNotNull(rec.err);
        assertTrue(rec.err, rec.err.startsWith("建连失败"));
    }

    @Test
    public void cancelBeforeConnect_noCallbacks() throws Exception {
        StubServer srv = new StubServer(java.util.Arrays.asList(FINAL), 1, false);
        Rec rec = new Rec();
        RealtimeAsrSession s = new RealtimeAsrSession(srv.url(), "k", 450, null, 3000, 1, rec);
        s.start();
        s.cancel();
        s.appendShorts(new short[1600], 1600);
        assertFalse("取消后不回调", rec.done.await(800, TimeUnit.MILLISECONDS));
        assertEquals(RealtimeAsrSession.State.CLOSED, s.state());
    }

    // ------------------------------------------------------------ 回退裁决

    @Test
    public void policy_fallbackDecisions() {
        assertTrue(RealtimeAsrPolicy.shouldUseRealtime(true, true, true, false));
        assertFalse("开关关", RealtimeAsrPolicy.shouldUseRealtime(false, true, true, false));
        assertFalse("没网", RealtimeAsrPolicy.shouldUseRealtime(true, true, false, false));
        assertFalse("熔断", RealtimeAsrPolicy.shouldUseRealtime(true, true, true, true));
        assertEquals(RealtimeAsrPolicy.OnLocalEndpoint.WAIT_FOR_REALTIME, RealtimeAsrPolicy.onLocalEndpoint(true));
        assertEquals(RealtimeAsrPolicy.OnLocalEndpoint.FIRE_NONSTREAM, RealtimeAsrPolicy.onLocalEndpoint(false));
        assertEquals(RealtimeAsrPolicy.OnExpiry.DEFAULT, RealtimeAsrPolicy.onWindowExpired(false, true, 9));
        assertEquals("静音超时不发请求", RealtimeAsrPolicy.OnExpiry.MISS_NO_SPEECH, RealtimeAsrPolicy.onWindowExpired(true, false, 0));
        // 10-15 上机：提示音尾巴让服务端 VAD 报说话、本地只 1 帧 → 仍算静音超时，不发请求
        assertEquals("提示音尾巴不算人声", RealtimeAsrPolicy.OnExpiry.MISS_NO_SPEECH, RealtimeAsrPolicy.onWindowExpired(true, true, 1));
        assertEquals("流式关闭时静音也不发", RealtimeAsrPolicy.OnExpiry.MISS_NO_SPEECH, RealtimeAsrPolicy.onWindowExpired(false, true, 2));
        assertEquals(RealtimeAsrPolicy.OnExpiry.FALLBACK_NONSTREAM, RealtimeAsrPolicy.onWindowExpired(true, true, 3));
        assertEquals(RealtimeAsrPolicy.OnExpiry.FALLBACK_NONSTREAM, RealtimeAsrPolicy.onWindowExpired(true, false, 7));
        assertEquals(RealtimeAsrPolicy.OnExpiry.FALLBACK_NONSTREAM, RealtimeAsrPolicy.onWindowExpired(true, false, 3));
        assertTrue(RealtimeAsrPolicy.renewAllowed(0, 4, true));
        assertFalse(RealtimeAsrPolicy.renewAllowed(1, 4, false));
        assertTrue(RealtimeAsrPolicy.renewAllowed(4, 4, false));
    }

    @Test
    public void breaker_opensAfter3_halfOpensAfterCooldown() {
        RealtimeAsrPolicy.Breaker b = new RealtimeAsrPolicy.Breaker();
        long t = 1000;
        assertFalse(b.isOpen(t));
        b.onFailure(t);
        b.onFailure(t);
        assertFalse(b.isOpen(t));
        b.onFailure(t);
        assertTrue(b.isOpen(t + 1));
        assertFalse("冷却后半开放一次", b.isOpen(t + RealtimeAsrPolicy.BREAKER_COOLDOWN_MS));
        b.onFailure(t + RealtimeAsrPolicy.BREAKER_COOLDOWN_MS + 5);
        assertTrue("试探失败立刻再熔断", b.isOpen(t + RealtimeAsrPolicy.BREAKER_COOLDOWN_MS + 6));
        b.onSuccess();
        assertFalse(b.isOpen(t + RealtimeAsrPolicy.BREAKER_COOLDOWN_MS + 7));
    }

    @Test
    public void fillerTurn_chimeTailIsNotACommand() {
        // 10-15 上机：提示音尾巴被服务端转写成「嗯。」—— 当空 turn，会话继续
        assertTrue(com.onesay.assistant.v2.asr.RealtimeAsrSession.isFillerTurn("嗯。"));
        assertTrue(com.onesay.assistant.v2.asr.RealtimeAsrSession.isFillerTurn("诶"));
        assertFalse(com.onesay.assistant.v2.asr.RealtimeAsrSession.isFillerTurn("讲个笑话。"));
        assertFalse(com.onesay.assistant.v2.asr.RealtimeAsrSession.isFillerTurn("嗯，讲个笑话"));
    }

    @Test
    public void corpusEcho_silenceHallucinationIsRejected() {
        String corpus = RealtimeAsrSession.corpusFromHotwords(
                "必应、bing、百度、谷歌、google、搜狗、夸克、360搜索、微信、支付宝、淘宝、京东、拼多多、抖音、快手、小红书、闹钟、备忘录、设置、蓝牙、飞行模式、定位、GPS");
        assertTrue(RealtimeAsrSession.isCorpusEcho("必应、bing、百度、谷歌、google、搜狗、夸克", corpus));
        assertTrue(RealtimeAsrSession.isCorpusEcho("闹钟、备忘录、设置、蓝牙、飞行模式、定位、GPS。", corpus));
        assertFalse(RealtimeAsrSession.isCorpusEcho("打开微信", corpus));
        assertFalse(RealtimeAsrSession.isCorpusEcho("用百度搜一下今天的新闻", corpus));
        assertFalse(RealtimeAsrSession.isCorpusEcho("打开蓝牙", corpus));
        assertFalse(RealtimeAsrSession.isCorpusEcho("讲个笑话。", corpus));
    }
}
