package com.onesay.assistant.v2.tts;

import com.onesay.assistant.v2.asr.AsrFailure;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.ui.wake.WakeCopy;
import com.onesay.assistant.v2.wake.ConversationTurnPolicy;
import com.onesay.assistant.v2.wake.WakeWordStripper;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** ★ r2/voice-templates：模板短句 + 打断后归还焦点 + 卡片分段 + 提示词改「屏幕阅读」。 */
public class VoiceTemplateTest {

    private static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    private static byte[] asset(String name) throws Exception {
        // 开源版：预合成模板音频不随仓库分发（缺音频时运行期只显示文字、不出声），缺素材则跳过音频校验。
        org.junit.Assume.assumeTrue("模板音频不在开源仓库里：" + name, new File(root(), "app/assets/" + name).isFile());
        return Files.readAllBytes(new File(root(), "app/assets/" + name).toPath());
    }

    private static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(root(), rel).toPath()), "UTF-8");
    }

    // ------------------------------------------------------------------ 清单与音频

    /** ★ arch/voice-sync：v3 41 句全部进 assets（同一状态多变体也在），每句文字 = REWRITE-v3.md 原文，音频可解析、24 kHz、≤4 s。 */
    @Test public void templatesAreV3AndAssetsPlayable() throws Exception {
        long total = 0;
        java.util.Set<String> assets = new java.util.HashSet<String>();
        for (VoiceTemplate t : VoiceTemplate.values()) {
            byte[] b = asset(t.asset);
            VoiceTemplate.Pcm p = VoiceTemplate.parseWav(b);
            assertNotNull(t + " 解析失败 " + t.asset, p);
            assertEquals(t + " 采样率", 24000, p.sampleRate);
            assertTrue(t + " 时长 " + p.durationMs(), p.durationMs() >= 300 && p.durationMs() <= 4000);
            if (assets.add(t.asset)) total += b.length;
            assertTrue(t + " 44 字节干净头（或原 confirm_prompt）", b.length > 44);
        }
        // 41 句 v3（wake_ack1/2、ack_done 复用原文件名）+ v3b 补合成 8 句 + 保留的 confirm_prompt
        assertEquals("v3 41 句 + v3b 8 句 + confirm_prompt", 50, assets.size());
        assertTrue("模板音频总量 " + total, total < 3 * 1024 * 1024);
        for (String gone : new String[]{"tpl_answer.wav", "tpl_error.wav", "tpl_offline.wav", "tpl_cancelled.wav", "tpl_miss.wav"}) {
            assertFalse("v1 模板音频已退役：" + gone, new File(root(), "app/assets/" + gone).exists());
        }
    }

    /** ★ v3b：补合成 8 句的文字 = REWRITE-v3.md「v3b 补充 8 句」原文、asset 命名 vo_<状态>_01、响度与 v3 同口径（RMS≈4000）。 */
    @Test public void v3bTemplatesPresentAndLeveled() throws Exception {
        Object[][] v3b = {
                {VoiceTemplate.ASK_PICK, "你选一下", "vo_ASK_USER_PROMPT_01.wav"},
                {VoiceTemplate.NEED_INFO, "还缺点信息，你补一句？", "vo_NEED_INFO_01.wav"},
                {VoiceTemplate.NEED_LOGIN, "得你先登录一下", "vo_NEED_LOGIN_01.wav"},
                {VoiceTemplate.NEED_VERIFY, "验证得你自己来", "vo_NEED_VERIFY_01.wav"},
                {VoiceTemplate.NEED_PAY, "付款得你自己点", "vo_NEED_PAY_01.wav"},
                {VoiceTemplate.NO_KEY, "这个还没配好，我用不了", "vo_BLOCKED_NO_KEY_01.wav"},
                {VoiceTemplate.DEVICE_BUSY, "手机这会儿腾不开手，等会儿再试", "vo_BLOCKED_DEVICE_BUSY_01.wav"},
                {VoiceTemplate.PLAN_PARTIAL, "弄好了一部分，剩下的没弄成", "vo_PLAN_PARTIAL_01.wav"},
        };
        String rewrite = null;
        File rw = new File(new File(root(), "app").getCanonicalFile().getParentFile(), "../fix-20261025/phrasebook/REWRITE-v3.md");   // 话术源在仓库外（工作区）；不在时跳过这条
        if (rw.exists()) rewrite = new String(Files.readAllBytes(rw.toPath()), "UTF-8");
        for (Object[] c : v3b) {
            VoiceTemplate t = (VoiceTemplate) c[0];
            assertEquals(t + " 文字", c[1], t.text);
            assertEquals(t + " asset", c[2], t.asset);
            byte[] b = asset(t.asset);
            assertEquals(t + " 干净 44 字节头", 44, b.length - VoiceTemplate.parseWav(b).data.length);
            VoiceTemplate.Pcm p = VoiceTemplate.parseWav(b);
            double sum = 0;
            int peak = 0;
            for (int i = 0; i + 1 < p.data.length; i += 2) {
                int v = (short) ((p.data[i] & 0xFF) | (p.data[i + 1] << 8));
                sum += (double) v * v;
                peak = Math.max(peak, Math.abs(v));
            }
            double rms = Math.sqrt(sum / (p.data.length / 2));
            assertTrue(t + " RMS " + rms, rms > 3800 && rms < 4200);
            assertTrue(t + " 峰值 " + peak, peak <= 29000);
            if (rewrite != null) assertTrue(t + " 文字出自 REWRITE-v3.md", rewrite.contains("| " + c[1] + " |"));
        }
        assertTrue("ask_user 提问是提示类", !VoiceTemplate.ASK_PICK.isReply());
        for (VoiceTemplate t : new VoiceTemplate[]{VoiceTemplate.NEED_INFO, VoiceTemplate.NEED_LOGIN, VoiceTemplate.NEED_VERIFY,
                VoiceTemplate.NEED_PAY, VoiceTemplate.NO_KEY, VoiceTemplate.DEVICE_BUSY, VoiceTemplate.PLAN_PARTIAL}) {
            assertTrue(t + " 是结局句（回答类）", t.isReply());
        }
    }

    @Test public void replyAndCueSplit() {
        assertTrue(VoiceTemplate.ANSWER.isReply());
        assertTrue(VoiceTemplate.DONE.isReply());
        assertTrue(VoiceTemplate.ERROR.isReply());
        assertTrue(VoiceTemplate.PLAN_ALL.isReply());
        assertTrue(VoiceTemplate.UNVERIFIED.isReply());
        assertFalse("确认卡是一次性提示", VoiceTemplate.CONFIRM.isReply());
        assertFalse(VoiceTemplate.MISS.isReply());
        assertFalse(VoiceTemplate.WAKE.isReply());
        assertFalse("应答不进回答状态机", VoiceTemplate.ACK_ON_IT.isReply());
        assertFalse("催一次不进回答状态机", VoiceTemplate.STILL_WORKING.isReply());
        assertEquals("好了", VoiceTemplate.ANSWER.text);
        assertEquals("好了", VoiceTemplate.DONE.text);
        assertEquals("收到，我来弄", VoiceTemplate.ACK_ON_IT.text);
        assertEquals("这个有点多，我一步步来", VoiceTemplate.ACK_MANY.text);
        assertEquals("还在弄，快好了", VoiceTemplate.STILL_WORKING.text);
        assertEquals("我点了，不过没看到结果，你看一眼？", VoiceTemplate.UNVERIFIED.text);
        assertEquals("confirm_prompt.wav", VoiceTemplate.CONFIRM.asset);
        assertEquals("ack_done.wav", VoiceTemplate.DONE.asset);
        assertEquals("wake_ack1.wav", VoiceTemplate.WAKE.asset);
        assertEquals("wake_ack2.wav", VoiceTemplate.WAKE_2.asset);
    }

    /** v1 文案全部退役。 */
    @Test public void noV1Copy() {
        for (VoiceTemplate t : VoiceTemplate.values()) {
            assertFalse(t + " 还是 v1 文案：" + t.text, t.text.contains("看屏幕") || t.text.equals("已完成") || t.text.equals("已取消")
                    || t.text.equals("没连上网络") || t.text.equals("没听清，再说一遍"));
        }
    }

    @Test public void failureMapping() {
        assertEquals(VoiceTemplate.ERROR, VoiceTemplate.forFailure(null));
        assertEquals(VoiceTemplate.CANCELLED, VoiceTemplate.forFailure(ErrorCode.CANCELLED));
        assertEquals(VoiceTemplate.USER_REJECTED, VoiceTemplate.forFailure(ErrorCode.USER_REJECTED));
        assertEquals(VoiceTemplate.USER_REJECTED, VoiceTemplate.forFailure(ErrorCode.RISK_REJECTED));
        assertEquals(VoiceTemplate.OFFLINE, VoiceTemplate.forFailure(ErrorCode.NETWORK_ERROR));
        assertEquals(VoiceTemplate.OFFLINE, VoiceTemplate.forFailure(ErrorCode.TLS_ERROR));
        assertEquals(VoiceTemplate.ERROR, VoiceTemplate.forFailure(ErrorCode.EXEC_FAILED));
        assertEquals(VoiceTemplate.TIMEOUT, VoiceTemplate.forFailure(ErrorCode.TIMEOUT));
        assertEquals(VoiceTemplate.TIMEOUT, VoiceTemplate.forFailure(ErrorCode.CHANNEL_TIMEOUT));
        assertEquals(VoiceTemplate.GATE, VoiceTemplate.forFailure(ErrorCode.GATE_DENIED));
        assertEquals(VoiceTemplate.UNSUPPORTED, VoiceTemplate.forFailure(ErrorCode.UNKNOWN_CAPABILITY));
        assertEquals(VoiceTemplate.MISS, VoiceTemplate.forAsrFailure(AsrFailure.Kind.NO_SPEECH));
        assertEquals(VoiceTemplate.ASR_OFFLINE, VoiceTemplate.forAsrFailure(AsrFailure.Kind.NETWORK));
        assertEquals(VoiceTemplate.ERROR, VoiceTemplate.forAsrFailure(AsrFailure.Kind.AUTH));
        assertEquals(VoiceTemplate.ERROR, VoiceTemplate.forAsrFailure(AsrFailure.Kind.NOT_CONFIGURED));
        assertEquals(VoiceTemplate.ERROR, VoiceTemplate.forAsrFailure(null));
        assertEquals(VoiceTemplate.MISS, VoiceTemplate.forMiss(1));
        assertEquals(VoiceTemplate.MISS_2, VoiceTemplate.forMiss(2));
        assertEquals(VoiceTemplate.MISS_MAX, VoiceTemplate.forMiss(3));
        assertEquals(VoiceTemplate.MISS_MAX, VoiceTemplate.forMiss(9));
    }

    @Test public void templateVsFullRead() {
        assertTrue("默认（朗读完整回答关）→ 模板", VoiceTemplate.useTemplate(false, false));
        assertFalse("开了 → 整句", VoiceTemplate.useTemplate(false, true));
        assertFalse("主动播报照旧整句", VoiceTemplate.useTemplate(true, false));
        assertTrue("会播模板 → ack_done 不单独响", VoiceTemplate.ackDoneSuppressed(true, false));
        assertFalse("不出声的回合照旧响 ack_done", VoiceTemplate.ackDoneSuppressed(false, false));
        assertFalse("整句朗读时保持原行为", VoiceTemplate.ackDoneSuppressed(true, true));
    }

    @Test public void templateEchoIsSpeakerOwnVoice() {
        assertTrue(VoiceTemplate.isTemplateEcho("哎呀，没弄成。"));
        assertTrue(VoiceTemplate.isTemplateEcho("收到我来弄"));
        assertTrue(WakeWordStripper.isAckSoundUtterance("还在弄，快好了"));
        assertTrue(WakeWordStripper.isAckSoundUtterance("没听清，再说一遍？"));
        assertTrue(VoiceTemplate.isTemplateEcho("还是没听懂，换个说法试试？比如「打开蓝牙」"));
        assertFalse(VoiceTemplate.isTemplateEcho("哎呀，没弄成蓝牙"));
        assertFalse(WakeWordStripper.isAckSoundUtterance("打开微信"));
        assertFalse("「好了」仍是结束语，不是回读", VoiceTemplate.isTemplateEcho("好了"));
        assertFalse("「好的」是用户应答词，不能当回读吞掉", VoiceTemplate.isTemplateEcho("好的"));
        assertFalse("「马上」≤2 字不进黑名单", VoiceTemplate.isTemplateEcho("马上"));
        assertTrue(ConversationTurnPolicy.isDismissPhrase("好了"));
    }

    // ------------------------------------------------------------------ WAV 解析

    private static byte[] wav(int sr, int ch, int bits, int fmt, byte[] data, int declaredLen) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        try {
            o.write("RIFF".getBytes("US-ASCII"));
            le32(o, 36 + data.length);
            o.write("WAVE".getBytes("US-ASCII"));
            o.write("LIST".getBytes("US-ASCII"));
            le32(o, 4);
            o.write("INFO".getBytes("US-ASCII"));
            o.write("fmt ".getBytes("US-ASCII"));
            le32(o, 16);
            le16(o, fmt);
            le16(o, ch);
            le32(o, sr);
            le32(o, sr * ch * bits / 8);
            le16(o, ch * bits / 8);
            le16(o, bits);
            o.write("data".getBytes("US-ASCII"));
            le32(o, declaredLen);
            o.write(data);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return o.toByteArray();
    }

    private static void le16(java.io.ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(java.io.ByteArrayOutputStream o, int v) {
        le16(o, v & 0xFFFF);
        le16(o, (v >>> 16) & 0xFFFF);
    }

    @Test public void parseWavHandlesChunksAndRejectsWrongFormat() {
        byte[] d = new byte[480];
        for (int i = 0; i < d.length; i++) d[i] = (byte) i;
        VoiceTemplate.Pcm p = VoiceTemplate.parseWav(wav(24000, 1, 16, 1, d, d.length));
        assertNotNull("跳过 LIST 块", p);
        assertEquals(24000, p.sampleRate);
        assertArrayEquals(d, p.data);
        assertEquals(10, p.durationMs());
        VoiceTemplate.Pcm over = VoiceTemplate.parseWav(wav(24000, 1, 16, 1, d, 0x7FFFFFF0));
        assertNotNull("data 长度写多了按剩余算", over);
        assertEquals(480, over.data.length);
        assertNull("立体声不认", VoiceTemplate.parseWav(wav(24000, 2, 16, 1, d, d.length)));
        assertNull("8 bit 不认", VoiceTemplate.parseWav(wav(24000, 1, 8, 1, d, d.length)));
        assertNull("非 PCM 不认", VoiceTemplate.parseWav(wav(24000, 1, 16, 3, d, d.length)));
        assertNull(VoiceTemplate.parseWav(new byte[10]));
        assertNull(VoiceTemplate.parseWav(null));
        byte[] junk = wav(24000, 1, 16, 1, d, d.length);
        junk[0] = 'X';
        assertNull(VoiceTemplate.parseWav(junk));
    }

    // ------------------------------------------------------------------ 打断后归还焦点

    @Test public void interruptReleasesFocusOnlyWhenNobodyTookOver() {
        assertTrue("ttsstop / 关开关：还停在 SPEAKING、没开窗 → 立刻归还",
                ConversationTurnPolicy.releaseFocusAfterInterrupt(true, true, false));
        assertFalse("说话打断 / 新唤醒：已开收音窗", ConversationTurnPolicy.releaseFocusAfterInterrupt(true, true, true));
        assertFalse("状态已变（LISTENING / 新一句在播）", ConversationTurnPolicy.releaseFocusAfterInterrupt(false, true, false));
        assertFalse("没持有焦点", ConversationTurnPolicy.releaseFocusAfterInterrupt(true, false, false));
    }

    @Test public void wiring() throws Exception {
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        int i = ws.indexOf("public static void onReplySpeechDone(final boolean interrupted)");
        assertTrue(i >= 0);
        String done = ws.substring(i, ws.indexOf("public static void onReplyNotSpoken", i));
        assertTrue("打断分支要判归还", done.contains("releaseFocusAfterInterrupt"));
        assertTrue(done.contains("focusTurnEnd(\"播报被打断、没有新一轮接管\")"));
        String tts = src("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java");
        assertTrue("speakAnswer 走 ANSWER 模板", tts.contains("SpeechText.ANSWER_MAX, com.onesay.assistant.v2.tts.VoiceTemplate.ANSWER)"));
        assertTrue("speakConfirmation 走 DONE 模板", tts.contains("com.onesay.assistant.v2.tts.VoiceTemplate.DONE)"));
        assertTrue("主动播报不用模板", tts.contains("speak(text, true, max, null)"));
        assertTrue("stopAll 也停模板", tts.contains("s.cancelTemplateLocked();"));
        assertTrue("模板走进度闸门", tts.contains("@Override public void onAudible() { gate.audible(); }"));
        assertTrue(tts.contains("K_TTS_FULL_ANSWER = \"tts_full_answer\""));
        assertTrue("默认关", tts.contains("getBoolean(K_TTS_FULL_ANSWER, false)"));
        String fs = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(fs.contains("if (TtsSpeaker.shouldSkipAckDone(this))"));
        String fv = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(fv.contains("speakFailure(mV2Body, result.code)"));
        String vp = src("app/src/com/onesay/assistant/v2/ui/console/VoicePage.java");
        assertTrue("设置里有开关", vp.contains("\"朗读完整回答\""));
    }

    // ------------------------------------------------------------------ 卡片分段

    @Test public void paragraphsOnlyTouchWhitespace() {
        assertEquals("", WakeCopy.readableParagraphs(null));
        String shortA = "明天台中多云，最高二十八度。";
        assertEquals(shortA, WakeCopy.readableParagraphs(shortA));
        String longA = "台中明天多云转晴，最高二十八度，最低二十一度。早晚温差大，出门带件薄外套。"
                + "下午三点后紫外线偏强，户外活动注意防晒。后天开始转雨，周末两天都有阵雨，记得带伞。"
                + "空气质量良好。";
        String out = WakeCopy.readableParagraphs(longA);
        assertTrue("长答案拆段：" + out, out.indexOf('\n') > 0);
        assertEquals("不改一个字", longA, out.replace("\n", ""));
        for (String para : out.split("\n")) assertTrue("段落不碎：" + para, para.length() >= 16);
        assertFalse("短尾句并回上一段", out.endsWith("\n空气质量良好。"));
        String quoted = "他说：“今天不去了。”我们就改到下周。" + longA;
        String q = WakeCopy.readableParagraphs(quoted);
        assertFalse("右引号不落到下一段开头", q.contains("\n”"));
        assertEquals("第一行\n第二行", WakeCopy.readableParagraphs("  第一行  \n\n\n 第二行 "));
    }

    // ------------------------------------------------------------------ 提示词

    @Test public void promptsAskForScreenReadingNotSpeech() throws Exception {
        String l2 = src("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java");
        assertFalse(l2.contains("适合语音朗读"));
        assertFalse(l2.contains("最终答复用自然口语中文"));
        assertTrue(l2.contains("适合屏幕阅读：先给结论"));
        String ask = com.onesay.assistant.v2.router.PromptLibrary.Defaults.ASK_OUTPUT;
        assertTrue(ask.contains("第一句就是结论"));
        assertTrue(ask.contains("短段落"));
        assertTrue("增量受控", ask.length() < 520);
    }
}
