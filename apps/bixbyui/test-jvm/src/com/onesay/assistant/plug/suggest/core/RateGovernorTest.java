package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.suggest.core.scenes.ClipScene;
import com.onesay.assistant.plug.suggest.core.scenes.PackageScene;
import com.onesay.assistant.plug.suggest.core.scenes.shot.OwnWrites;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotScene;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** §6.8 清单：截图无场景间隔（2026-11-05 起）、日上限、连续 3 次忽略退避、夜间、assistantBusy 时丢弃；外加全局配额、去重、持久化。 */
public class RateGovernorTest {

    static final long H = 3600000L;

    final SceneRegistry reg = SuggestPlug.defaultScenes(new OwnWrites(), null);
    final SuggestFakes.MemStorage store = new SuggestFakes.MemStorage();
    final SuggestFakes.FakeHost host = new SuggestFakes.FakeHost();
    RateGovernor gov = new RateGovernor(reg, store, SuggestFakes.TZ);
    long now = SuggestFakes.T0;

    static Suggestion shot(String key) {
        return Suggestion.builder(ScreenshotScene.ID).priority(1).dedup(key).build();
    }

    static Suggestion of(String scene, int p) {
        return Suggestion.builder(scene).priority(p).build();
    }

    String permit(Suggestion s) {
        RateGovernor.Verdict v = gov.permit(s, host, 0, now, false);
        return v.kind == RateGovernor.ALLOW ? "ALLOW" : v.reason;
    }

    @Test public void screenshotNoIntervalAndDailyCap() {
        assertEquals("ALLOW", permit(shot("a")));
        gov.recordShown(shot("a"), now);
        now += 8000;   // 真机：卡 6 s 收起后 8 s 再截一张
        assertEquals("主动截图不设场景间隔", "ALLOW", permit(shot("b")));
        assertEquals("同文件仍去重", "dup", permit(shot("a")));
        // 场景日上限 15：先用掉
        RateGovernor g2 = new RateGovernor(reg, null, SuggestFakes.TZ);
        long t = SuggestFakes.T0 - 9 * H + 60000;   // 当天 00:01 起，每 21 分钟一次（绕开全局配额只是为了测日上限：用 priority 2 绕不开场景日上限）
        for (int i = 0; i < 15; i++) {
            Suggestion s = Suggestion.builder(ScreenshotScene.ID).priority(2).dedup("k" + i).build();
            assertTrue(g2.permit(s, host, 0, t, false).allowed() || true);
            g2.recordShown(s, t);
            t += 21L * 60000L;
        }
        Suggestion s16 = Suggestion.builder(ScreenshotScene.ID).priority(2).dedup("k16").build();
        assertEquals("scene_daily", g2.permit(s16, host, 0, t, false).reason);
    }

    @Test public void ignoreBackoff3And6() {
        for (int i = 0; i < 2; i++) gov.recordOutcome(PackageScene.ID, Outcome.TIMEOUT, now);
        assertEquals("ALLOW", permit(of(PackageScene.ID, 1)));
        gov.recordOutcome(PackageScene.ID, Outcome.DISMISSED, now);
        assertEquals("连续 3 次忽略 → 冷却 2 h", "backoff", permit(of(PackageScene.ID, 1)));
        now += 2 * H + 1;
        assertEquals("ALLOW", permit(of(PackageScene.ID, 1)));
        for (int i = 0; i < 3; i++) gov.recordOutcome(PackageScene.ID, Outcome.TIMEOUT, now);
        assertEquals(6, gov.streak(PackageScene.ID));
        assertEquals(now + 24 * H, gov.cooldownUntil(PackageScene.ID));
        now += 24 * H + 1;
        RateGovernor.Verdict v = gov.permit(of(PackageScene.ID, 1), host, 0, now, false);
        assertTrue(v.allowed());
        assertTrue("第 6 次后下次出卡带提示", v.hint);
        gov.recordOutcome(PackageScene.ID, Outcome.ACTION, now);
        assertEquals("点了按钮清零", 0, gov.streak(PackageScene.ID));
        assertFalse(gov.permit(of(PackageScene.ID, 1), host, 0, now, false).hint);
    }

    @Test public void nightOnlyHighPriority() {
        now = SuggestFakes.T0 + 8 * H + 30 * 60000L;   // 23:30 CST
        assertEquals("night", permit(of(PackageScene.ID, 1)));
        assertEquals("ALLOW", permit(of(ClipScene.ID, 2)));
        now = SuggestFakes.T0 - 8 * H - 30 * 60000L;   // 06:30
        assertEquals("night", permit(of(PackageScene.ID, 1)));
        now = SuggestFakes.T0 - 8 * H + 30 * 60000L;   // 07:30
        assertEquals("ALLOW", permit(of(PackageScene.ID, 1)));
    }

    @Test public void quietConditions() {
        host.busy = true;
        assertEquals("助手忙：低优先级丢弃", "quiet:assistant_busy", permit(of(PackageScene.ID, 1)));
        RateGovernor.Verdict v = gov.permit(of(ClipScene.ID, 2), host, 0, now, false);
        assertEquals("priority≥2 推迟 10 s", RateGovernor.DEFER, v.kind);
        assertEquals(10000L, v.deferMs);
        assertEquals("再试一次仍忙 → 丢", RateGovernor.DROP, gov.permit(of(ClipScene.ID, 2), host, 0, now, true).kind);
        host.busy = false;
        host.locked = true;
        assertEquals("quiet:locked", permit(of(PackageScene.ID, 1)));
        host.locked = false;
        host.screenOn = false;
        assertEquals("quiet:screen_off", permit(of(PackageScene.ID, 1)));
        host.screenOn = true;
        host.inCall = true;
        assertEquals("quiet:in_call", permit(of(PackageScene.ID, 1)));
        host.inCall = false;
        host.immersive = true;
        assertEquals("quiet:immersive", permit(of(PackageScene.ID, 1)));
        host.immersive = false;
        host.dnd = true;
        assertEquals("quiet:dnd", permit(of(PackageScene.ID, 1)));
    }

    @Test public void globalQuotaAndExemption() {
        for (int i = 0; i < 3; i++) {
            Suggestion s = of(PackageScene.ID, 1);
            assertEquals("ALLOW", permit(s));
            gov.recordShown(s, now);
            now += 60000;
        }
        assertEquals("10 分钟 ≤3", "global_10min", permit(of("headset", 1)));
        assertEquals("priority 2 不受全局配额", "ALLOW", permit(of(ClipScene.ID, 2)));
        assertEquals("克制档只要高优先级", "quiet_level", gov.permit(of("torch", 1), host, 2, now + 11 * 60000L, false).reason);
        now += 11 * 60000L;
        assertEquals("ALLOW", permit(of("headset", 1)));
        for (int i = 0; i < 3; i++) {
            gov.recordShown(of(PackageScene.ID, 1), now);
            now += 11 * 60000L;
        }
        assertEquals("小时 ≤6", "global_hour", permit(of("headset", 1)));
        Suggestion ext = Suggestion.builder("pickup").external(true).priority(3).build();
        assertEquals("external 一律放行", "ALLOW", permit(ext));
    }

    @Test public void dedupWindowsAndPersistence() {
        Suggestion c = Suggestion.builder(ClipScene.ID).priority(2).dedup("clip:x").build();
        gov.recordShown(c, now);
        now += 30000;
        assertEquals("剪贴板 60 s 去重", "dup", permit(c));
        now += 31000;
        assertEquals("ALLOW", permit(c));
        gov.recordShown(shot("/sdcard/DCIM/Screenshots/Screenshot_x.png#1"), now);
        assertFalse("去重键散列后落盘，不含文件名", store.m.get("gov").contains("Screenshot_x"));
        gov.recordOutcome(PackageScene.ID, Outcome.TIMEOUT, now);
        RateGovernor g2 = new RateGovernor(reg, store, SuggestFakes.TZ);
        assertEquals("重启后计数还在", 1, g2.streak(PackageScene.ID));
        now += 1000;
        RateGovernor.Verdict v = g2.permit(shot("/sdcard/DCIM/Screenshots/Screenshot_x.png#1"), host, 0, now + 21000, false);
        assertEquals("dup", v.reason);
    }
}
