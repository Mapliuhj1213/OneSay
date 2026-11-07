package com.onesay.assistant.v2.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * ★ r2/input：断句时间线模拟（100 ms 一帧，和 WakeService 读循环同节拍）。
 *
 * <p>模型：用户按脚本说话 / 停顿；本地能量门在说话帧记人声；服务端 VAD 静音 {@code serverSilenceMs} 后切一段、
 * 再过 {@link #FINAL_LATENCY_MS} 送来这一段的 completed（文字 = 脚本里这段话）。对比：
 * <ul>
 *   <li><b>R14</b>：服务端 VAD 450 ms，第一条 completed 到达即执行；</li>
 *   <li><b>r2</b>：服务端 VAD 700 ms 只分段，{@link UtteranceEndpointer#decide} 每帧裁决（与 WakeService.semanticEndpointTick 同一逻辑）。</li>
 * </ul>
 */
public class EndpointSimulationTest {

    static final long FRAME = 100L;
    static final long FINAL_LATENCY_MS = 300L;
    static final long ARM_AT = 1000L;
    static final long WINDOW_MS = 12000L;     // 按键唤醒起步窗
    static final long MAX_MS = WakeService.CAPTURE_MAX_MS;

    /** 一段话（说 durMs，之后停 pauseMs）。 */
    static final class Seg {
        final String text;
        final long durMs, pauseMs;
        Seg(String text, long durMs, long pauseMs) { this.text = text; this.durMs = durMs; this.pauseMs = pauseMs; }
    }

    static Seg say(String t, long dur, long pause) { return new Seg(t, dur, pause); }

    /** 模拟结果：什么时候、提交了什么（null = 没提交 / 静音超时）。 */
    static final class Result {
        Long at;
        String text;
        String why;
        @Override public String toString() { return at == null ? "未提交" : ("@" + (at - ARM_AT) + "ms「" + text + "」(" + why + ")"); }
    }

    /** 服务端分段：返回 [段文字, 段结束时刻(最后一帧人声), completed 到达时刻] 列表。 */
    static List<long[]> serverSegments(long startAt, List<Seg> script, long silenceMs, List<String> texts) {
        List<long[]> out = new ArrayList<long[]>();
        long t = startAt;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < script.size(); i++) {
            Seg s = script.get(i);
            cur.append(s.text);
            t += s.durMs;
            long speechEnd = t;
            boolean last = i == script.size() - 1;
            if (s.pauseMs >= silenceMs || last) {
                out.add(new long[] {speechEnd, speechEnd + silenceMs + FINAL_LATENCY_MS});
                texts.add(cur.toString());
                cur.setLength(0);
            }
            t += s.pauseMs;
        }
        return out;
    }

    static boolean speakingAt(long now, long startAt, List<Seg> script) {
        long t = startAt;
        for (Seg s : script) {
            if (now >= t && now < t + s.durMs) return true;
            t += s.durMs + s.pauseMs;
        }
        return false;
    }

    /** R14：服务端 450 ms，第一条 completed 就执行。 */
    static Result simulateR14(long startAt, List<Seg> script) {
        List<String> texts = new ArrayList<String>();
        List<long[]> segs = serverSegments(startAt, script, 450, texts);
        Result r = new Result();
        if (!segs.isEmpty()) {
            r.at = segs.get(0)[1];
            r.text = texts.get(0);
            r.why = "R14 第一段 completed";
        }
        return r;
    }

    /** r2：服务端 700 ms 分段 + UtteranceEndpointer。 */
    static Result simulateR2(long startAt, List<Seg> script) {
        UtteranceEndpointer ep = UtteranceEndpointer.defaults();
        List<String> texts = new ArrayList<String>();
        List<long[]> segs = serverSegments(startAt, script, 700, texts);
        List<String> got = new ArrayList<String>();
        long first = 0, last = 0, activity = 0, localEpAt = 0;
        int frames = 0;
        long until = ARM_AT + WINDOW_MS;
        Result r = new Result();
        for (long now = ARM_AT; now <= ARM_AT + MAX_MS + 2000; now += FRAME) {
            boolean sp = speakingAt(now, startAt, script);
            if (sp) {
                if (first == 0) first = now;
                last = now;
                frames++;
            }
            // completed 到达
            for (int i = 0; i < segs.size(); i++) {
                if (segs.get(i)[1] > now - FRAME && segs.get(i)[1] <= now && got.size() == i) {
                    got.add(texts.get(i));   // 段结果在静音之后才到：不算「动静」（与 WakeService 一致）
                    if (localEpAt > 0) {
                        r.at = now; r.text = UtteranceEndpointer.joinSegments(got); r.why = "最后一段到齐";
                        return r;
                    }
                }
            }
            // 服务端「在说」= 说话中，或停顿还没到 700 ms（VAD 还没切）
            boolean serverSpeaking = sp;
            String partial = "";
            // 在途半段：已经开口、但那一段的 completed 还没到
            if (got.size() < segs.size()) {
                long segStartGuess = got.isEmpty() ? startAt : segs.get(got.size() - 1)[0];
                if (now > segStartGuess && first > 0 && last > (got.isEmpty() ? 0 : segs.get(got.size() - 1)[0])) {
                    partial = texts.get(got.size());
                }
            }
            UtteranceEndpointer.Input in = new UtteranceEndpointer.Input();
            in.now = now; in.firstSpeechAt = first; in.lastSpeechAt = last; in.speechFrames = frames; in.minFrames = 4;
            in.rtOwns = true; in.rtSpeaking = serverSpeaking; in.finals = UtteranceEndpointer.joinSegments(got);
            in.partial = partial; in.rtActivityAt = activity;
            UtteranceEndpointer.Verdict v = ep.decide(in);
            if (v.action == UtteranceEndpointer.Action.COMMIT_TEXT) {
                r.at = now; r.text = in.finals; r.why = "说完 " + v; return r;
            }
            if (v.action == UtteranceEndpointer.Action.WAIT_FOR_FINAL && localEpAt == 0) localEpAt = now;
            if (v.action == UtteranceEndpointer.Action.WAIT && localEpAt > 0 && v.silentMs < v.requiredMs) localEpAt = 0;
            if (localEpAt > 0 && now - localEpAt >= com.onesay.assistant.v2.asr.RealtimeAsrPolicy.LOCAL_EP_GRACE_MS) {
                r.at = now; r.text = UtteranceEndpointer.joinSegments(got) + partial; r.why = "grace"; return r;
            }
            // 窗到点 / 续期（WakeService.renewCaptureIfStillSpeaking 同判据）
            if (now >= until) {
                long lastAct = Math.max(last, activity);
                if (CaptureSessionPolicy.shouldRenew(now, ARM_AT, ARM_AT, until, lastAct,
                        CaptureSessionPolicy.renewWithinMs(WakeService.RENEW_IF_SPEECH_WITHIN_MS, v.requiredMs), MAX_MS)
                        && frames >= 4) {
                    until = now + Math.min(WakeService.CAPTURE_EXTEND_STEP_MS, Math.max(0, ARM_AT + MAX_MS - now));
                    if (until <= now) until = now;
                } else {
                    if (!got.isEmpty() || partial.length() > 0) {
                        r.at = now; r.text = UtteranceEndpointer.joinSegments(got) + partial; r.why = "窗到点";
                    }
                    return r;
                }
            }
        }
        return r;
    }

    static List<Seg> script(Seg... s) {
        List<Seg> l = new ArrayList<Seg>();
        for (Seg x : s) l.add(x);
        return l;
    }

    // ------------------------------------------------------------------ 场景

    @Test public void breathPauseMidSentenceNotCut() {
        // 「帮我查一下」换气 1.0 s「明天北京的天气」
        List<Seg> sc = script(say("帮我查一下", 900, 1000), say("明天北京的天气", 1200, 0));
        Result old = simulateR14(ARM_AT + 500, sc);
        Result neu = simulateR2(ARM_AT + 500, sc);
        assertEquals("R14 被切成半句", "帮我查一下", old.text);
        assertEquals("r2 完整", "帮我查一下，明天北京的天气", neu.text.replace("。", ""));
    }

    @Test public void pauseOf1point8sInNeutralSentenceNotCut() {
        List<Seg> sc = script(say("明天早上八点", 1100, 1800), say("叫我起床", 800, 0));
        Result neu = simulateR2(ARM_AT + 300, sc);
        assertEquals("明天早上八点，叫我起床", neu.text);
    }

    @Test public void incompleteEndingWaitsThroughLongThinkingPause() {
        // 「给张三发消息说」停 2.6 s 想措辞 →「我晚点到」
        List<Seg> sc = script(say("给张三发消息然后", 1300, 2600), say("说我晚点到", 1000, 0));
        Result old = simulateR14(ARM_AT + 300, sc);
        Result neu = simulateR2(ARM_AT + 300, sc);
        assertEquals("给张三发消息然后", old.text);
        assertEquals("给张三发消息然后，说我晚点到", neu.text);
    }

    @Test public void shortCompleteCommandEndsQuickly() {
        List<Seg> sc = script(say("打开蓝牙？", 800, 0));   // 带问号 = COMPLETE
        long speechEnd = ARM_AT + 300 + 800;
        Result neu = simulateR2(ARM_AT + 300, sc);
        assertNotNull(neu.at);
        long latency = neu.at - speechEnd;
        assertTrue("完整短指令 1.3–1.6 s 内提交，实际 " + latency, latency >= 1300 && latency <= 1600);
    }

    @Test public void neutralSentenceEndsAfterAbout2s() {
        List<Seg> sc = script(say("讲个笑话", 900, 0));
        long speechEnd = ARM_AT + 300 + 900;
        Result neu = simulateR2(ARM_AT + 300, sc);
        long latency = neu.at - speechEnd;
        assertTrue("中性 2.0–2.3 s，实际 " + latency, latency >= 2000 && latency <= 2300);
    }

    @Test public void preparationTimeBeforeFirstWord() {
        // 唤醒后想了 3.8 s 才开口，不应该提前结束
        List<Seg> sc = script(say("打开手电筒", 1000, 0));
        Result neu = simulateR2(ARM_AT + 3800, sc);
        assertEquals("打开手电筒", neu.text);
    }

    @Test public void longDictationUpTo25sKeepsAll() {
        List<Seg> parts = new ArrayList<Seg>();
        String[] p = {"帮我记一下", "今天开会说了三件事", "第一是预算", "第二是排期", "第三是招人", "然后", "下周一之前要给方案"};
        for (int i = 0; i < p.length; i++) parts.add(say(p[i], 2400, i == p.length - 1 ? 0 : 1100));
        Result neu = simulateR2(ARM_AT + 300, parts);
        assertNotNull(neu.text);
        assertTrue(neu.toString(), neu.text.startsWith("帮我记一下") && neu.text.endsWith("下周一之前要给方案"));
        assertTrue("续期一路续到说完，不触 30 s 顶", neu.at - ARM_AT < MAX_MS);
    }

    @Test public void hardCapAt30s() {
        // 一直说不停（40 s）：30 s 顶一到就收，用已有文字
        List<Seg> sc = new ArrayList<Seg>();
        for (int i = 0; i < 40; i++) sc.add(say("啦", 900, 600));
        Result neu = simulateR2(ARM_AT + 300, sc);
        assertNotNull(neu.at);
        assertTrue(neu.toString(), neu.at - ARM_AT <= MAX_MS + 200);
    }

    @Test public void silenceOnlyNeverCommits() {
        Result neu = simulateR2(ARM_AT + 300, new ArrayList<Seg>());
        assertNull(neu.at);
    }
}
