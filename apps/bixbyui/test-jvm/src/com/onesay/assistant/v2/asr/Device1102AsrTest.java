package com.onesay.assistant.v2.asr;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.wake.MicReentry;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * ★ device-1102 问题 2「开麦 / 点录音即吐一大段」：
 * 热词上下文改纯词表（无前缀句、空格分隔、≤300 字）；词表回吐判据（流式 / 非流式同一个）；
 * TTS 回采守卫 AnswerEchoGuard；🎤 续说按钮 400 ms 去抖 + 开麦先停 TTS（接线）。开麦闸门见 RealtimeAsrTest.openMicGate_*。
 */
public class Device1102AsrTest {

    @After public void tearDown() {
        AnswerEchoGuard.clear();
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    // ---- 热词上下文 ----

    @Test public void hotwordContextIsBareSpaceSeparatedListUnderCap() {
        String[] w = AsrHotwords.staticWords();
        String t = AsrHotwords.composeText(Arrays.asList(w));
        assertFalse("不再带说明前缀句", t.startsWith(AsrHotwords.HOTWORD_PROMPT_PREFIX));
        assertFalse("不再用「、」连写", t.contains("、"));
        assertTrue(t.length() <= AsrHotwords.MAX_CHARS_V2);
        assertTrue(t.contains(" "));
        StringBuilder big = new StringBuilder();
        java.util.List<String> many = new java.util.ArrayList<String>();
        for (int i = 0; i < 200; i++) many.add("词条" + i);
        assertTrue(AsrHotwords.composeText(many).length() <= AsrHotwords.MAX_CHARS_V2);
        assertEquals("", AsrHotwords.composeText(null));
    }

    @Test public void corpusStripsLegacyPrefixAndCaps() {
        String c = RealtimeAsrSession.corpusFromHotwords(AsrHotwords.HOTWORD_PROMPT_PREFIX + "必应、QQ邮箱、支付宝");
        assertEquals("必应 QQ邮箱 支付宝", c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("热词").append(i).append(' ');
        String big = RealtimeAsrSession.corpusFromHotwords(sb.toString());
        assertTrue(big.length() <= RealtimeAsrSession.CORPUS_MAX);
        assertFalse("在词边界截断", big.endsWith(" "));
    }

    @Test public void corpusEchoCaughtBothSeparators() {
        String corpus = "必应 bing 百度 谷歌 QQ邮箱 支付宝 微信 抖音";
        assertTrue(RealtimeAsrSession.isCorpusEcho("必应、bing、百度、谷歌", corpus));
        assertTrue(RealtimeAsrSession.isCorpusEcho("必应 bing 百度 谷歌 QQ邮箱", corpus));
        assertFalse("正常一句话", RealtimeAsrSession.isCorpusEcho("打开微信给妈妈发消息", corpus));
        assertFalse(RealtimeAsrSession.isCorpusEcho("用百度搜一下天气", corpus));
    }

    // ---- TTS 回采 ----

    @Test public void answerEchoIsDroppedWhileFresh() {
        String said = "明天北京晴，最高气温十八度，最低五度，空气质量良，适合出门。";
        AnswerEchoGuard.noteSpoken(said, 10_000L);
        assertTrue("整段回采", AnswerEchoGuard.isEcho("明天北京晴最高气温十八度最低五度", 12_000L));
        assertTrue("识别有错字也算（最长公共子串 ≥70%）", AnswerEchoGuard.isEcho("明天北京晴，最高气温十八度，最低五读", 12_000L));
        assertFalse("太短不判（「明天」这种可能是用户自己说的）", AnswerEchoGuard.isEcho("明天北京", 12_000L));
        assertFalse("无关的话", AnswerEchoGuard.isEcho("帮我定一个明天七点的闹钟", 12_000L));
        assertFalse("过期（60 秒后）不判", AnswerEchoGuard.isEcho("明天北京晴最高气温十八度最低五度", 10_000L + AnswerEchoGuard.TTL_MS + 1));
        AnswerEchoGuard.clear();
        assertFalse(AnswerEchoGuard.isEcho("明天北京晴最高气温十八度最低五度", 12_000L));
    }

    // ---- 🎤 去抖 ----

    @Test public void micButtonDebounce400ms() {
        assertFalse("第一次", MicReentry.debounced(0L, 5_000L));
        assertTrue(MicReentry.debounced(5_000L, 5_000L + 399));
        assertFalse(MicReentry.debounced(5_000L, 5_000L + 400));
        assertFalse("时钟回绕不误杀", MicReentry.debounced(5_000L, 4_000L));
    }

    @Test public void wiring() throws Exception {
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        int cont = ws.indexOf("private synchronized void requestContinueInternal()");
        assertTrue(cont > 0);
        String body = ws.substring(cont, ws.indexOf("mLastWakeReqAt = now;", cont));
        assertTrue("🎤 续说先去抖", body.contains("MicReentry.debounced(mLastWakeReqAt, now)"));
        assertTrue("🎤 续说开麦先停 TTS", body.contains("TtsSpeaker.stopAll()"));
        assertTrue("中间结果过回采守卫", ws.contains("AnswerEchoGuard.isEcho(partial, now)"));
        assertTrue("最终结果过回采守卫", ws.contains("AnswerEchoGuard.isEcho(command, SystemClock.elapsedRealtime())"));
        assertTrue("开麦闸门从绑窗算", ws.contains("markMicOpen()"));
        assertTrue(src("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java").contains("AnswerEchoGuard.noteSpoken(text"));
        assertTrue("非流式也过词表回吐", src("app/src/com/onesay/assistant/v2/asr/DashScopeAsrChannel.java")
                .contains("RealtimeAsrSession.isCorpusEcho(ansTrim, RealtimeAsrSession.corpusFromHotwords(hotwords))"));
    }
}
