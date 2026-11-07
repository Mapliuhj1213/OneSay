package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.suggest.core.scenes.PackageScene;
import com.onesay.assistant.plug.suggest.core.scenes.shot.OwnWrites;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotScene;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 真机 2026-11-05 00:07：授权后 B 源 CAND、引擎 SKIP(night) → 截图场景是用户主动行为，夜间 / 克制档 / 全局配额不拦。
 */
public class ShotNightTest {

    static final long H = 3600000L;
    /** T0 = 15:00 CST → +9 h 7 min = 00:07 CST（次日）。 */
    static final long NIGHT_0007 = SuggestFakes.T0 + 9 * H + 7 * 60000L;

    @Test public void screenshotShowsAtNightThroughEngine() {
        SuggestFakes.Rig r = new SuggestFakes.Rig();
        r.clock.wall = NIGHT_0007;
        r.start();
        r.engine.emit(r.shot("/storage/emulated/0/DCIM/Screenshots/Screenshot_20261105-000733_One UI Home.jpg", 'B'));
        assertEquals("00:07 截图照样出卡：" + r.logs, 1, r.presenter.shown.size());
        assertEquals(ScreenshotScene.TITLE_ONE, r.presenter.last().title);
        assertTrue(!r.logs.toString().contains("SKIP(night)"));
    }

    @Test public void governorExemptsUserInitiatedOnly() {
        SceneRegistry reg = SuggestPlug.defaultScenes(new OwnWrites(), null);
        SuggestFakes.FakeHost host = new SuggestFakes.FakeHost();
        RateGovernor gov = new RateGovernor(reg, null, SuggestFakes.TZ);
        assertTrue(ScreenshotScene.POLICY.userInitiated);
        assertTrue(!PackageScene.POLICY.userInitiated);
        Suggestion shot = Suggestion.builder(ScreenshotScene.ID).priority(1).dedup("k1").build();
        assertTrue("夜间放行", gov.permit(shot, host, 0, NIGHT_0007, false).allowed());
        assertTrue("克制档放行", gov.permit(shot, host, 2, NIGHT_0007, false).allowed());
        assertEquals("其他低优先级场景夜里照旧丢", "night",
                gov.permit(Suggestion.builder(PackageScene.ID).priority(1).build(), host, 0, NIGHT_0007, false).reason);
        // 全局配额：先用满 10 分钟 3 次（别的场景），截图仍放行
        long t = SuggestFakes.T0;
        for (int i = 0; i < 3; i++) gov.recordShown(Suggestion.builder("x" + i).priority(1).build(), t + i);
        assertEquals("global_10min", gov.permit(Suggestion.builder("x9").priority(1).build(), host, 0, t + 10, false).reason);
        assertTrue("截图不受全局配额", gov.permit(Suggestion.builder(ScreenshotScene.ID).priority(1).dedup("k2").build(), host, 0, t + 10, false).allowed());
        // 静默条件照旧：锁屏丢
        host.locked = true;
        assertEquals("quiet:locked", gov.permit(Suggestion.builder(ScreenshotScene.ID).priority(1).dedup("k3").build(), host, 0, t + 20, false).reason);
    }

    /** 真机序列：00:07:34 截图出卡 → 6 s 到时收起 → 00:07:42 再截一张，仍出卡；到时收起 ×3 不触发 2 h 退避，点「忽略」×3 照旧退避。 */
    @Test public void secondShotAfterTimeoutShowsAndTimeoutsDoNotBackoff() {
        SuggestFakes.Rig r = new SuggestFakes.Rig();
        r.clock.wall = NIGHT_0007;
        r.start();
        r.engine.emit(r.shot("/storage/emulated/0/DCIM/Screenshots/Screenshot_20261105-000733_A.jpg", 'B'));
        assertEquals(1, r.presenter.shown.size());
        r.clock.advance(6500);
        r.sched.advance(6500);
        r.engine.onTimeout(r.presenter.last().id);
        r.clock.advance(2000);
        r.engine.emit(r.shot("/storage/emulated/0/DCIM/Screenshots/Screenshot_20261105-000742_A.jpg", 'B'));
        assertEquals("8 s 后第二张照样出卡：" + r.logs, 2, r.presenter.shown.size());

        SceneRegistry reg = SuggestPlug.defaultScenes(new OwnWrites(), null);
        RateGovernor gov = new RateGovernor(reg, null, SuggestFakes.TZ);
        long t = SuggestFakes.T0;
        for (int i = 0; i < 6; i++) gov.recordOutcome(ScreenshotScene.ID, Outcome.TIMEOUT, t);
        assertEquals("到时收起不攒退避", 0, gov.streak(ScreenshotScene.ID));
        assertEquals(0L, gov.cooldownUntil(ScreenshotScene.ID));
        for (int i = 0; i < 3; i++) gov.recordOutcome(ScreenshotScene.ID, Outcome.DISMISSED, t);
        assertEquals("点忽略 3 次照旧冷却 2 h", t + 2 * H, gov.cooldownUntil(ScreenshotScene.ID));
    }

    /** 每一层丢弃都留一行原因：总开关关、场景关（不含文件名）。 */
    @Test public void masterAndSceneOffAreLogged() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.cfg.master = false;
        r.engine.emit(r.shot("/storage/emulated/0/DCIM/Screenshots/Screenshot_9.jpg", 'B'));
        assertTrue(r.logs.toString(), r.logs.toString().contains("SKIP(master_off)"));
        r.cfg.master = true;
        r.cfg.scenes.put(ScreenshotScene.ID, Boolean.FALSE);
        r.clock.advance(5000);
        r.engine.emit(r.shot("/storage/emulated/0/DCIM/Screenshots/Screenshot_10.jpg", 'B'));
        assertTrue(r.logs.toString(), r.logs.toString().contains("screenshot: SKIP(scene_off)"));
        assertEquals(0, r.presenter.shown.size());
        assertTrue(!r.logs.toString().contains("Screenshot_10"));
    }
}
