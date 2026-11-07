package com.onesay.assistant.v2.wake;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ r2/screenoff-wake：息屏 KWS 能量前置门 / 唤醒锁 / 暂停 / 亮屏 / 锁屏解锁要求 —— 纯判定。 */
public class ScreenOffPolicyTest {

    private static final float QUIET = 40f;
    private static final float VOICE = 900f;

    private static ScreenOffPolicy offPolicy() {
        ScreenOffPolicy p = new ScreenOffPolicy(ScreenOffPolicy.Config.defaults());
        p.setScreenOn(false);
        return p;
    }

    @After public void clearProbe() {
        ScreenOffPolicy.setKeyguardProbe(null);
    }

    // ---------- 亮屏：行为完全不变 ----------

    @Test public void screenOnAlwaysFeedsEveryFrame() {
        ScreenOffPolicy p = new ScreenOffPolicy(ScreenOffPolicy.Config.defaults());
        long t = 0;
        for (int i = 0; i < 600; i++, t += 100) {
            float rms = (i % 7 == 0) ? VOICE : (i % 3 == 0 ? 0f : QUIET);
            assertEquals("亮屏每一帧都要 FEED（与改动前逐帧一致），i=" + i, ScreenOffPolicy.FEED, p.decide(t, rms));
        }
        assertEquals(0, p.skippedFrames());
        assertEquals(0, p.activations());
    }

    @Test public void disabledGateFeedsEvenWhenScreenOff() {
        ScreenOffPolicy p = new ScreenOffPolicy(new ScreenOffPolicy.Config(false, 120, 180, 2000, 1500, 6000, true, 60000));
        p.setScreenOn(false);
        for (int i = 0; i < 100; i++) assertEquals(ScreenOffPolicy.FEED, p.decide(i * 100L, QUIET));
    }

    // ---------- 息屏：安静不跑 KWS ----------

    @Test public void screenOffQuietSkipsKws() {
        ScreenOffPolicy p = offPolicy();
        long t = 0;
        int skipped = 0;
        for (int i = 0; i < 3000; i++, t += 100) {   // 5 分钟安静
            if (p.decide(t, QUIET + (i % 5)) == ScreenOffPolicy.SKIP) skipped++;
        }
        assertEquals("5 分钟安静：全部 SKIP（第一帧从「在喂」切到不喂也是 SKIP）", 3000, skipped);
        assertEquals(0, p.activations());
    }

    @Test public void voiceActivatesOnceThenFeedsThroughHangThenSkips() {
        ScreenOffPolicy p = offPolicy();
        long t = 0;
        for (int i = 0; i < 50; i++, t += 100) p.decide(t, QUIET);
        // 唤醒词 ~800 ms
        assertEquals("安静转人声第一帧：ACTIVATE（调用方 reset 流 + 补喂预滚）", ScreenOffPolicy.ACTIVATE, p.decide(t, VOICE));
        t += 100;
        for (int i = 0; i < 7; i++, t += 100) assertEquals(ScreenOffPolicy.FEED, p.decide(t, VOICE));
        long lastVoice = t - 100;
        // 说完后的 hang（默认 2000 ms）内继续喂（KWS 要尾静音帧才出结果）
        while (t - lastVoice <= ScreenOffPolicy.Config.DEF_HANG_MS) {
            assertEquals("hang 内继续 FEED, dt=" + (t - lastVoice), ScreenOffPolicy.FEED, p.decide(t, QUIET));
            t += 100;
        }
        assertEquals("hang 过后回到 SKIP", ScreenOffPolicy.SKIP, p.decide(t, QUIET));
        assertEquals(1, p.activations());
        // 再说一次 → 再 ACTIVATE 一次
        t += 5000;
        assertEquals(ScreenOffPolicy.ACTIVATE, p.decide(t, VOICE));
        assertEquals(2, p.activations());
    }

    @Test public void softFarFieldVoiceStillOpensGate() {
        // 宁宽勿严：比 EnergyGate 软门（110）略高的 abs 120，安静底噪 40 时远场 150 也要开门
        ScreenOffPolicy p = offPolicy();
        long t = 0;
        for (int i = 0; i < 100; i++, t += 100) p.decide(t, QUIET);
        assertEquals(ScreenOffPolicy.ACTIVATE, p.decide(t, 150f));
    }

    @Test public void steadyNoiseFloorRaisesThreshold() {
        ScreenOffPolicy p = offPolicy();
        long t = 0;
        // 风扇 / 空调稳态 300：前几帧可能开门，底噪跟上后应回到 SKIP
        for (int i = 0; i < 1500; i++, t += 100) p.decide(t, 300f + (i % 3));   // 2.5 分钟
        assertTrue("底噪跟上后门限 > 稳态噪声: thr=" + p.voiceThreshold(), p.voiceThreshold() > 310f);
        int skip = 0;
        for (int i = 0; i < 100; i++, t += 100) if (p.decide(t, 300f + (i % 3)) == ScreenOffPolicy.SKIP) skip++;
        assertEquals("稳态噪声不再开门", 100, skip);
        // 噪声上的人声（×1.8 以上）照样开门
        assertEquals(ScreenOffPolicy.ACTIVATE, p.decide(t, 900f));
    }

    @Test public void voiceFramesBarelyRaiseFloor() {
        ScreenOffPolicy p = offPolicy();
        long t = 0;
        for (int i = 0; i < 100; i++, t += 100) p.decide(t, QUIET);
        assertEquals(120f, p.voiceThreshold(), 0.01f);
        for (int i = 0; i < 20; i++, t += 100) p.decide(t, 2000f);   // 2 s 大声说话
        assertTrue("一句话几乎不抬底噪: thr=" + p.voiceThreshold(), p.voiceThreshold() < 200f);
        // 说完回到安静，底噪快速回落
        for (int i = 0; i < 30; i++, t += 100) p.decide(t, QUIET);
        assertEquals(120f, p.voiceThreshold(), 0.01f);
        assertEquals("回落后远场 150 仍能开门（hang 已过）", ScreenOffPolicy.ACTIVATE, p.decide(t + 5000, 150f));
    }

    @Test public void loudFirstFrameDoesNotPoisonFloor() {
        ScreenOffPolicy p = offPolicy();
        p.decide(0, 3000f);   // 息屏第一帧正好是人声
        long t = 100;
        for (int i = 0; i < 30; i++, t += 100) p.decide(t, QUIET);
        assertEquals("底噪起点不高于 abs 门", 120f, p.voiceThreshold(), 0.01f);
    }

    @Test public void screenOnAfterSkippingActivatesThenFeeds() {
        ScreenOffPolicy p = offPolicy();
        long t = 0;
        for (int i = 0; i < 30; i++, t += 100) assertEquals(ScreenOffPolicy.SKIP, p.decide(t, QUIET));
        p.setScreenOn(true);
        assertEquals("亮屏第一帧：由不喂转喂 → ACTIVATE（reset，避免把两段拼成假关键词）", ScreenOffPolicy.ACTIVATE, p.decide(t, QUIET));
        t += 100;
        for (int i = 0; i < 30; i++, t += 100) assertEquals(ScreenOffPolicy.FEED, p.decide(t, QUIET));
    }

    @Test public void screenOffWhileFeedingKeepsFeedingOnlyDuringHang() {
        ScreenOffPolicy p = new ScreenOffPolicy(ScreenOffPolicy.Config.defaults());
        long t = 0;
        assertEquals(ScreenOffPolicy.FEED, p.decide(t, VOICE));
        p.setScreenOn(false);
        t += 100;
        assertEquals("息屏瞬间刚说过话：hang 内继续喂", ScreenOffPolicy.FEED, p.decide(t, QUIET));
        t += 3000;
        assertEquals(ScreenOffPolicy.SKIP, p.decide(t, QUIET));
    }

    @Test public void rmsAndRealFrames() {
        short[] buf = new short[1600];
        assertEquals(0f, ScreenOffPolicy.rms(buf, buf.length), 0.001f);
        for (int i = 0; i < buf.length; i++) buf[i] = (short) ((i % 2 == 0) ? 1000 : -1000);
        assertEquals(1000f, ScreenOffPolicy.rms(buf, buf.length), 0.5f);
        assertEquals(0f, ScreenOffPolicy.rms(null, 10), 0f);
        assertEquals(1000f, ScreenOffPolicy.rms(buf, 99999), 0.5f);   // n 超长按数组长度截
        ScreenOffPolicy p = offPolicy();
        short[] quiet = new short[1600];
        for (int i = 0; i < 50; i++) assertEquals(ScreenOffPolicy.SKIP, p.onFrame(i * 100L, quiet, quiet.length));
        assertEquals(ScreenOffPolicy.ACTIVATE, p.onFrame(5000, buf, buf.length));
    }

    @Test public void prerollClampedToBufferCapacity() {
        ScreenOffPolicy p = offPolicy();
        assertEquals(24000, p.prerollSamples(16000, 56000));   // 1.5 s
        assertEquals(20000, p.prerollSamples(16000, 20000));   // 缓冲只有 1.25 s
        ScreenOffPolicy z = new ScreenOffPolicy(new ScreenOffPolicy.Config(true, 120, 180, 2000, 0, 6000, true, 60000));
        assertEquals(0, z.prerollSamples(16000, 56000));
    }

    @Test public void configClampsOutOfRangeValues() {
        ScreenOffPolicy.Config c = new ScreenOffPolicy.Config(true, -5, 50, 1, 99999, 1, false, 1);
        assertEquals(20f, c.absMin, 0f);
        assertEquals(1.1f, c.floorRatio, 0.001f);
        assertEquals(300, c.hangMs);
        assertEquals(2500, c.prerollMs);
        assertEquals(1000, c.screenWakeMs);
        assertEquals(5000, c.hostMaxMs);
        assertFalse(c.keyguardHost);
        ScreenOffPolicy.Config d = ScreenOffPolicy.Config.defaults();
        assertTrue(d.enabled);
        assertEquals(120f, d.absMin, 0f);
        assertEquals(1.8f, d.floorRatio, 0.001f);
        assertEquals(2000, d.hangMs);
        assertEquals(1500, d.prerollMs);
        assertEquals(6000, d.screenWakeMs);
        assertTrue(d.keyguardHost);
    }

    // ---------- 唤醒锁 / 暂停 ----------

    @Test public void partialWakeLockOnlyWhenScreenOffAndNotPaused() {
        assertTrue(ScreenOffPolicy.wantPartialWakeLock(false, false));
        assertFalse("亮屏不需要", ScreenOffPolicy.wantPartialWakeLock(false, true));
        assertFalse("暂停绝不持有", ScreenOffPolicy.wantPartialWakeLock(true, false));
        assertFalse(ScreenOffPolicy.wantPartialWakeLock(true, true));
    }

    @Test public void micPausesOnlyWhenPausedAndIdle() {
        assertTrue(ScreenOffPolicy.micShouldPause(true, false, false, false));
        assertFalse("没暂停不停", ScreenOffPolicy.micShouldPause(false, false, false, false));
        assertFalse("声纹录制要麦克风", ScreenOffPolicy.micShouldPause(true, true, false, false));
        assertFalse("声纹注册会话要麦克风", ScreenOffPolicy.micShouldPause(true, false, true, false));
        assertFalse("暂停前已开的捕获窗收完再停", ScreenOffPolicy.micShouldPause(true, false, false, true));
    }

    // ---------- 亮屏 / 锁屏 ----------

    @Test public void screenWakeAndKeyguardHostDecisions() {
        assertTrue(ScreenOffPolicy.needScreenWake(false));
        assertFalse(ScreenOffPolicy.needScreenWake(true));
        assertTrue(ScreenOffPolicy.needKeyguardHost(true, ScreenOffPolicy.Config.defaults()));
        assertFalse(ScreenOffPolicy.needKeyguardHost(false, ScreenOffPolicy.Config.defaults()));
        assertFalse("keyguard_host=0 不起托底",
                ScreenOffPolicy.needKeyguardHost(true, new ScreenOffPolicy.Config(true, 120, 180, 2000, 1500, 6000, false, 60000)));
    }

    @Test public void lockedRequiresUnlockForEverySideEffectButNone() {
        for (SideEffect se : SideEffect.values()) {
            assertEquals(se.name(), se != SideEffect.NONE, ScreenOffPolicy.requiresUnlock(se));
        }
        assertFalse(ScreenOffPolicy.requiresUnlock(null));
    }

    @Test public void unlockDenyReasonFollowsProbe() {
        Capability send = new Capability.Builder("t.sms", "发短信").risk(2, true).sideEffect(SideEffect.PUBLIC_COMMS).cmd("x").build();
        Capability read = new Capability.Builder("t.ask", "问答").risk(0, false).cmd("x").build();
        assertNull("没有探针（JVM / 服务没起）= 视为未锁", ScreenOffPolicy.unlockDenyReason(send));
        final boolean[] locked = {true};
        ScreenOffPolicy.setKeyguardProbe(new ScreenOffPolicy.KeyguardProbe() {
            @Override public boolean locked() { return locked[0]; }
        });
        assertEquals(ScreenOffPolicy.UNLOCK_REASON, ScreenOffPolicy.unlockDenyReason(send));
        assertNull("无副作用的问答锁屏照样回答", ScreenOffPolicy.unlockDenyReason(read));
        assertNull(ScreenOffPolicy.unlockDenyReason(null));
        locked[0] = false;
        assertNull(ScreenOffPolicy.unlockDenyReason(send));
        ScreenOffPolicy.setKeyguardProbe(new ScreenOffPolicy.KeyguardProbe() {
            @Override public boolean locked() { throw new RuntimeException("boom"); }
        });
        assertFalse("探针异常按未锁处理（不因探针故障误拦）", ScreenOffPolicy.deviceLocked());
    }

    @Test public void stateSettersAndStats() {
        ScreenOffPolicy p = new ScreenOffPolicy(null);
        assertTrue(p.screenOn());
        p.setScreenOn(false);
        p.setKeyguardLocked(true);
        assertFalse(p.screenOn());
        assertTrue(p.keyguardLocked());
        p.decide(0, QUIET);
        assertTrue(p.stats(), p.stats().contains("skipped=1"));
        p.setConfig(new ScreenOffPolicy.Config(false, 120, 180, 2000, 1500, 6000, true, 60000));
        assertFalse(p.config().enabled);
        p.setConfig(null);
        assertFalse("null 不覆盖", p.config().enabled);
    }
}
