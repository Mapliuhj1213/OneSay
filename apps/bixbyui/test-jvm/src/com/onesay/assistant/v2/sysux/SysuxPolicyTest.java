package com.onesay.assistant.v2.sysux;

import com.onesay.assistant.v2.ui.PanelSystemKeyPolicy;
import com.onesay.assistant.v2.wake.AssistAudioFocus;
import com.onesay.assistant.v2.wake.AssistAudioFocus.Action;
import com.onesay.assistant.v2.wake.BargeInDetector;
import com.onesay.assistant.v2.wake.ConversationTurnPolicy;
import com.onesay.assistant.v2.wake.WakePromptPolicy;
import com.onesay.assistant.v2.wake.WakePromptPolicy.Chime;
import com.onesay.assistant.v2.wake.WakePromptPolicy.Kind;
import com.onesay.assistant.v2.wake.WakeTuning;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ r2/sysux：系统级交互第一阶段的纯 Java 策略（按下即听 / 音频焦点 / 语音答 + 续听 + 打断 / 返回 · Home）。 */
public class SysuxPolicyTest {

    // ---------------- P1-1 按下即听 ----------------

    @Test public void kindOfSources() {
        assertEquals(Kind.KEY, WakePromptPolicy.kindOf("key-file"));
        assertEquals(Kind.KEY, WakePromptPolicy.kindOf("key-file-hold"));
        assertEquals(Kind.KEY, WakePromptPolicy.kindOf("key-sim"));
        assertEquals(Kind.VOICE, WakePromptPolicy.kindOf("voice"));
        assertEquals(Kind.CONTINUE, WakePromptPolicy.kindOf("voice-ui"));
        assertEquals(Kind.ASSIST, WakePromptPolicy.kindOf("assist-system"));
        assertEquals(Kind.FOLLOWUP, WakePromptPolicy.kindOf("followup-tts"));
        assertEquals(Kind.FOLLOWUP, WakePromptPolicy.kindOf("followup-bargein"));
    }

    @Test public void defaultIsInstantListen() {
        assertEquals("用户 10-29 拍板：默认按下即听", 1, WakeTuning.DEF_SHORT_CHIME);
        assertEquals("「我在」默认关", 0, WakeTuning.DEF_WAKE_ACK_VOICE);
    }

    @Test public void instantListenAllSourcesStartAtGuardWithPreRoll() {
        for (Kind k : new Kind[]{Kind.KEY, Kind.VOICE, Kind.ASSIST, Kind.CONTINUE}) {
            WakePromptPolicy.Plan p = WakePromptPolicy.plan(k, true, false, true, true, 300, 1100);
            assertEquals(k.name(), Chime.SHORT, p.chime);
            assertEquals(k.name(), 300L, p.captureFromMs);
            assertTrue(k + " 唤醒即收音（预录补 [唤醒, +300)）", p.preRoll);
            assertFalse(p.ackVoice);
        }
    }

    @Test public void preRollOffSwitchRespected() {
        assertFalse(WakePromptPolicy.plan(Kind.VOICE, true, false, false, true, 300, 1100).preRoll);
    }

    @Test public void ackVoiceOnlyScreenOffVoiceWake() {
        assertTrue(WakePromptPolicy.wantAckVoice(true, false, Kind.VOICE));
        assertFalse("亮屏不说", WakePromptPolicy.wantAckVoice(true, true, Kind.VOICE));
        assertFalse("按键不说", WakePromptPolicy.wantAckVoice(true, false, Kind.KEY));
        assertFalse("系统入口不说", WakePromptPolicy.wantAckVoice(true, false, Kind.ASSIST));
        assertFalse("开关关不说", WakePromptPolicy.wantAckVoice(false, false, Kind.VOICE));
        WakePromptPolicy.Plan p = WakePromptPolicy.plan(Kind.VOICE, true, true, true, false, 300, 1100);
        assertTrue(p.ackVoice);
        assertEquals("边说边听：起点不等「我在」播完", 300L, p.captureFromMs);
        assertFalse("「我在」窗不预录（回声门挡不住人声应答）", p.preRoll);
    }

    @Test public void followUpWindowSilentNoPreRoll() {
        WakePromptPolicy.Plan p = WakePromptPolicy.plan(Kind.FOLLOWUP, true, true, true, false, 300, 1100);
        assertEquals(Chime.NONE, p.chime);
        assertFalse(p.preRoll);
        assertFalse(p.ackVoice);
        assertTrue("隐私硬门限", p.captureFromMs >= 300);
        assertEquals(Chime.NONE, WakePromptPolicy.plan(Kind.FOLLOWUP, false, false, true, true, 300, 1100).chime);
    }

    @Test public void legacyModeUnchanged() {
        WakePromptPolicy.Plan v = WakePromptPolicy.plan(Kind.VOICE, false, true, true, false, 300, 1100);
        assertEquals(Chime.LEGACY, v.chime);
        assertEquals(1100L, v.captureFromMs);
        assertFalse("旧版唤醒词不预录", v.preRoll);
        WakePromptPolicy.Plan k = WakePromptPolicy.plan(Kind.KEY, false, false, true, true, 300, 1100);
        assertEquals(300L, k.captureFromMs);
        assertTrue(k.preRoll);
    }

    @Test public void preRollPrivacyCheck() {
        assertTrue(WakePromptPolicy.preRollPrivacyOk(1000, 1000, 1000, true));
        assertFalse("起点早于唤醒", WakePromptPolicy.preRollPrivacyOk(999, 1000, 1000, true));
        assertFalse("属于上一次唤醒", WakePromptPolicy.preRollPrivacyOk(1000, 1000, 900, true));
        assertFalse("本窗计划不预录", WakePromptPolicy.preRollPrivacyOk(1000, 1000, 1000, false));
    }

    // ---------------- P1-2 音频焦点 ----------------

    @Test public void focusPauseListenKeepThinkDuckSpeakReturnAtEnd() {
        AssistAudioFocus f = new AssistAudioFocus(true);
        assertEquals(Action.REQUEST_TRANSIENT, f.onListen());
        assertEquals("拿到指令不归还（旧代码在这里 abandon）", Action.NONE, f.onCommand());
        assertTrue(f.held());
        assertEquals(Action.REQUEST_DUCK, f.onSpeakStart());
        assertEquals("续听重新暂停", Action.REQUEST_TRANSIENT, f.onListen());
        assertEquals(Action.ABANDON, f.onTurnEnd());
        assertFalse(f.held());
        assertEquals("幂等", Action.NONE, f.onTurnEnd());
    }

    @Test public void focusNoDuckOption() {
        AssistAudioFocus f = new AssistAudioFocus(false);
        f.onListen();
        assertEquals("已持有 TRANSIENT：不重复申请", Action.NONE, f.onSpeakStart());
    }

    @Test public void focusTextTurnNeverRequests() {
        AssistAudioFocus f = new AssistAudioFocus(true);
        assertEquals(Action.NONE, f.onCommand());
        assertEquals(Action.NONE, f.onTurnEnd());
    }

    @Test public void focusLossThenEndStillAbandonsOnce() {
        AssistAudioFocus f = new AssistAudioFocus(true);
        f.onListen();
        f.onLoss();
        assertFalse(f.held());
        assertEquals(Action.ABANDON, f.onTurnEnd());
        assertEquals(Action.NONE, f.onTurnEnd());
    }

    @Test public void repeatedListenDoesNotReRequest() {
        AssistAudioFocus f = new AssistAudioFocus(true);
        assertEquals(Action.REQUEST_TRANSIENT, f.onListen());
        assertEquals(Action.NONE, f.onListen());
    }

    // ---------------- P1-3 语音答 / 续听 / 打断 ----------------

    @Test public void speakVoiceTurnByDefault() {
        assertTrue(ConversationTurnPolicy.shouldSpeak(false, true, true, false));
        assertFalse("文字回合 + 语音回复开关关", ConversationTurnPolicy.shouldSpeak(false, false, true, false));
        assertTrue("文字回合 + 开关开", ConversationTurnPolicy.shouldSpeak(false, false, true, true));
        assertFalse("语音回合但「语音问就语音答」关", ConversationTurnPolicy.shouldSpeak(false, true, false, false));
        assertTrue("主动播报不看开关", ConversationTurnPolicy.shouldSpeak(true, false, false, false));
    }

    @Test public void followUpConditions() {
        assertTrue(ConversationTurnPolicy.shouldFollowUp(true, true, false, true, false, false));
        assertFalse("被打断", ConversationTurnPolicy.shouldFollowUp(true, true, true, true, false, false));
        assertFalse("文字回合", ConversationTurnPolicy.shouldFollowUp(true, false, false, true, false, false));
        assertFalse("面板已收", ConversationTurnPolicy.shouldFollowUp(true, true, false, false, false, false));
        assertFalse("进岛", ConversationTurnPolicy.shouldFollowUp(true, true, false, true, true, false));
        assertFalse("暂停", ConversationTurnPolicy.shouldFollowUp(true, true, false, true, false, true));
        assertFalse("开关关", ConversationTurnPolicy.shouldFollowUp(false, true, false, true, false, false));
    }

    @Test public void followUpWindowClamped4to6s() {
        assertEquals(5000, ConversationTurnPolicy.followUpMs(0));
        assertEquals(4000, ConversationTurnPolicy.followUpMs(1000));
        assertEquals(6000, ConversationTurnPolicy.followUpMs(20000));
        assertEquals(4500, ConversationTurnPolicy.followUpMs(4500));
    }

    @Test public void dismissPhrases() {
        assertTrue(ConversationTurnPolicy.isDismissPhrase("好了"));
        assertTrue(ConversationTurnPolicy.isDismissPhrase("谢谢。"));
        assertTrue(ConversationTurnPolicy.isDismissPhrase("没事了吧"));
        assertFalse(ConversationTurnPolicy.isDismissPhrase("好了打开微信"));
        assertFalse(ConversationTurnPolicy.isDismissPhrase("那明天呢"));
        assertFalse(ConversationTurnPolicy.isDismissPhrase(""));
        assertFalse(ConversationTurnPolicy.isDismissPhrase(null));
    }

    @Test public void bargeInIgnoresEchoAndFiresOnLouderSustainedSpeech() {
        BargeInDetector d = new BargeInDetector(1200f, 4f, 2.5f, 3, 500);
        d.start(0, 200f);
        // 标定期：TTS 回声 800
        for (long t = 0; t < 500; t += 100) assertFalse("标定期不触发", d.offer(t, 800f));
        assertEquals(2000f, d.threshold(), 1f);
        // 回声照旧：不触发
        for (long t = 500; t < 2000; t += 100) assertFalse(d.offer(t, 900f));
        // 零星大声：不够连续
        assertFalse(d.offer(2000, 3000f));
        assertFalse(d.offer(2100, 3000f));
        assertFalse(d.offer(2200, 500f));
        // 连续 3 块真人声
        assertFalse(d.offer(2300, 3200f));
        assertFalse(d.offer(2400, 3300f));
        assertTrue(d.offer(2500, 3100f));
        assertFalse("每次播报只触发一次", d.offer(2600, 4000f));
        assertFalse(d.active());
    }

    @Test public void bargeInRespectsAbsMinAndFloor() {
        BargeInDetector d = new BargeInDetector(1200f, 4f, 2.5f, 3, 500);
        d.start(0, 600f);   // 嘈杂：底噪 ×4 = 2400
        for (long t = 0; t < 500; t += 100) d.offer(t, 100f);
        assertEquals(2400f, d.threshold(), 1f);
        for (long t = 500; t < 1000; t += 100) assertFalse(d.offer(t, 2000f));
        d.stop();
        assertFalse(d.offer(1000, 9000f));
    }

    // ---------------- P1-4 返回 / Home ----------------

    @Test public void closeSystemDialogsReasons() {
        assertTrue(PanelSystemKeyPolicy.collapseOnCloseSystemDialogs("homekey"));
        assertTrue(PanelSystemKeyPolicy.collapseOnCloseSystemDialogs("recentapps"));
        assertFalse("长按 Home 召唤本助理时系统发 assist —— 不能自己关自己", PanelSystemKeyPolicy.collapseOnCloseSystemDialogs("assist"));
        assertFalse(PanelSystemKeyPolicy.collapseOnCloseSystemDialogs("voiceinteraction"));
        assertFalse(PanelSystemKeyPolicy.collapseOnCloseSystemDialogs("globalactions"));
        assertFalse(PanelSystemKeyPolicy.collapseOnCloseSystemDialogs("lock"));
        assertFalse(PanelSystemKeyPolicy.collapseOnCloseSystemDialogs(null));
    }

    @Test public void focusableOnlyAfterCaptureAndOnResult() {
        PanelSystemKeyPolicy.Phase L = PanelSystemKeyPolicy.Phase.LISTENING, A = PanelSystemKeyPolicy.Phase.ANSWER,
                F = PanelSystemKeyPolicy.Phase.FOLLOWUP, T = PanelSystemKeyPolicy.Phase.THINKING;
        assertTrue(PanelSystemKeyPolicy.wantFocusable(true, A, false, false));
        assertTrue(PanelSystemKeyPolicy.wantFocusable(true, F, false, false));
        assertFalse("识屏还在 dump：绝不抢焦点", PanelSystemKeyPolicy.wantFocusable(true, A, true, false));
        assertFalse("听写中保持不可获焦", PanelSystemKeyPolicy.wantFocusable(true, L, false, false));
        assertFalse("思考中工具可能还要读屏", PanelSystemKeyPolicy.wantFocusable(true, T, false, false));
        assertTrue("文字模式（原逻辑）", PanelSystemKeyPolicy.wantFocusable(true, L, true, true));
        assertTrue("旧界面一直可获焦", PanelSystemKeyPolicy.wantFocusable(false, L, true, false));
    }
}
