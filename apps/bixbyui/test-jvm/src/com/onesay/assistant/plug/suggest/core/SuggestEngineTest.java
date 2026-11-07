package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.suggest.core.scenes.ClipScene;
import com.onesay.assistant.plug.suggest.core.scenes.PackageScene;
import com.onesay.assistant.plug.suggest.core.scenes.TorchScene;
import com.onesay.assistant.plug.suggest.core.scenes.TranslateScene;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotScene;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** §6.8 清单：A、B 同文件只出一次；文件被删 → withdraw；「不再提示」→ sceneOn=false 且可撤销；外加合并、替换、外部计数、骨架、总开关。 */
public class SuggestEngineTest {

    static final String P1 = "/storage/emulated/0/DCIM/Screenshots/Screenshot_1.jpg";
    static final String P1B = "/data/media/0/DCIM/Screenshots/Screenshot_1.jpg";
    static final String P2 = "/storage/emulated/0/DCIM/Screenshots/Screenshot_2.jpg";

    @Test public void screenshotShowsOnceForAAndB() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        r.files.files.put(P1B, 123456L);
        r.clock.advance(80);
        r.engine.emit(r.shot(P1B, 'B'));
        assertEquals(1, r.presenter.shown.size());
        Suggestion s = r.presenter.last();
        assertEquals(ScreenshotScene.TITLE_ONE, s.title);
        assertEquals("分析", s.actions.get(0).label);
        assertEquals("忽略", s.actions.get(1).label);
        assertEquals("不再提示", s.actions.get(2).label);
        assertEquals("D1 6 s", 6000L, s.ttlMs);
        assertEquals(P1, s.thumbRef);
        assertTrue("B 同文件被判重复：" + r.logs, r.logs.toString().contains("SKIP(dup) src=B"));
        assertTrue("日志不含文件名", !r.logs.toString().contains("Screenshot_1"));
    }

    @Test public void burstMergesIntoShownCard() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        r.clock.advance(1200);
        r.engine.emit(r.shot(P2, 'A'));
        r.clock.advance(1200);
        String p3 = "/sdcard/DCIM/Screenshots/Screenshot_3.jpg";
        r.engine.emit(r.shot(p3, 'A'));
        assertEquals("连按 3 次只有一张卡", 1, r.presenter.shown.size());
        assertEquals(2, r.presenter.updates.size());
        assertTrue(r.presenter.updates.get(1).endsWith("|3 张截图"));
        assertEquals(p3, r.engine.current().get("path"));
        assertEquals(r.presenter.last().id, r.engine.current().id);
    }

    @Test public void deletedFileWithdrawsCard() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        String id = r.presenter.last().id;
        r.sched.advance(1000);
        assertTrue(r.presenter.withdrawn.isEmpty());
        r.files.files.remove(P1);
        r.sched.advance(1000);
        assertEquals(1, r.presenter.withdrawn.size());
        assertEquals(id, r.presenter.withdrawn.get(0));
        assertTrue(r.outcomes.contains("screenshot:WITHDRAWN:file_gone"));
        assertNull(r.engine.current());
    }

    @Test public void burstDeleteOneKeepsOthers() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        r.clock.advance(500);
        r.engine.emit(r.shot(P2, 'A'));
        r.files.files.remove(P2);
        r.sched.advance(1000);
        assertTrue("还剩一张：不撤卡", r.presenter.withdrawn.isEmpty());
        assertEquals(P1, r.engine.current().get("path"));
        r.files.files.remove(P1);
        r.sched.advance(1000);
        assertEquals(1, r.presenter.withdrawn.size());
    }

    @Test public void muteThenUndo() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        Suggestion s = r.presenter.last();
        r.engine.onAction(s.id, "mute");
        assertEquals(Boolean.FALSE, r.cfg.scenes.get(ScreenshotScene.ID));
        Suggestion undo = r.presenter.last();
        assertEquals(SuggestEngine.UNDO_SCENE, undo.sceneId);
        assertEquals("D2 3 s 撤销", 3000L, undo.ttlMs);
        assertEquals("撤销", undo.actions.get(0).label);
        assertTrue(r.outcomes.contains("screenshot:MUTED:button"));
        // 关掉以后再截图不弹
        r.clock.advance(30000);
        r.engine.emit(r.shot(P2, 'A'));
        assertEquals(2, r.presenter.shown.size());
        r.engine.onAction(undo.id, SuggestEngine.UNDO_ACTION);
        assertEquals(Boolean.TRUE, r.cfg.scenes.get(ScreenshotScene.ID));
    }

    @Test public void analyzeRunsActionOnlyWhenTapped() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        assertTrue("没点就不执行（不上传）", r.runner.runs.isEmpty());
        r.engine.onAction(r.presenter.last().id, ScreenshotScene.ACT_ANALYZE);
        assertEquals("screenshot:analyze", r.runner.runs.get(0));
        r.engine.onAction(r.presenter.last().id, ScreenshotScene.ACT_ANALYZE);
        assertEquals("过期点击不重复执行", 1, r.runner.runs.size());
    }

    @Test public void timeoutAndSwipeRecordIgnore() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        r.engine.onTimeout(r.presenter.last().id);
        assertEquals("主动截图卡到时收起不计忽略（2026-11-05）", 0, r.engine.governor().streak(ScreenshotScene.ID));
        assertTrue(r.outcomes.toString(), r.outcomes.toString().contains("screenshot:TIMEOUT"));
        r.clock.advance(21000);
        r.engine.emit(r.shot(P2, 'A'));
        r.engine.onSwipedAway(r.presenter.last().id);
        assertEquals("划掉仍算忽略", 1, r.engine.governor().streak(ScreenshotScene.ID));
        assertTrue(r.outcomes.contains("screenshot:DISMISSED:swipe"));
    }

    @Test public void masterOffZeroEffect() {
        SuggestFakes.Rig r = new SuggestFakes.Rig();
        r.cfg.master = false;
        r.start();
        r.engine.emit(r.shot(P1, 'A'));
        assertTrue(r.presenter.shown.isEmpty());
        assertTrue(r.runner.runs.isEmpty());
        assertTrue(r.store.m.isEmpty());
    }

    @Test public void presenterRefusalIsSuppressed() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.presenter.accept = false;
        r.engine.emit(r.shot(P1, 'A'));
        assertTrue(r.outcomes.contains("screenshot:SUPPRESSED:host:presenter"));
        assertNull(r.engine.current());
    }

    @Test public void newReplacesOld() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        String first = r.presenter.last().id;
        r.engine.emit(Signal.of(Signal.PKG_ADDED, r.clock.el, r.clock.wall, "pkg", "com.example.app", "label", "示例", "launchable", "1"));
        assertEquals(2, r.presenter.shown.size());
        assertTrue(r.presenter.withdrawn.contains(first));
        assertTrue(r.outcomes.contains("screenshot:WITHDRAWN:replaced"));
        assertEquals(PackageScene.ID, r.engine.current().sceneId);
    }

    @Test public void externalPickupCountsTowardQuota() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        for (int i = 0; i < 3; i++) {
            r.engine.emit(Signal.of(Signal.PICKUP, r.clock.el, r.clock.wall, "code_hash", "h" + i, "kind", "取件码"));
            r.clock.advance(1000);
        }
        assertTrue("取件码不经岛 suggest 卡", r.presenter.shown.isEmpty());
        r.engine.emit(Signal.of(Signal.PKG_ADDED, r.clock.el, r.clock.wall, "pkg", "com.example.app", "launchable", "1"));
        assertTrue("取件码占了全局配额：" + r.outcomes, r.outcomes.contains("newapp:SUPPRESSED:global_10min"));
    }

    @Test public void skeletonNeverShownEvenIfOn() {
        SuggestFakes.Rig r = new SuggestFakes.Rig();
        r.cfg.scenes.put(TranslateScene.ID, true);
        r.cfg.scenes.put("link", false);
        r.start();
        r.engine.emit(Signal.of(Signal.CLIP, r.clock.el, r.clock.wall, "text", "The quick brown fox jumps over the lazy dog again", "len", "48"));
        assertTrue(r.presenter.shown.isEmpty());
        assertTrue(r.outcomes.contains("translate:SUPPRESSED:skeleton"));
    }

    @Test public void clipDeferredWhenBusyThenRetried() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.host.busy = true;
        r.engine.emit(Signal.of(Signal.CLIP, r.clock.el, r.clock.wall, "labels", "导航\n打车", "title", "复制了地址：x", "token", "t1", "hash", "h1"));
        assertTrue(r.presenter.shown.isEmpty());
        r.host.busy = false;
        r.sched.advance(10000);
        assertEquals(1, r.presenter.shown.size());
        Suggestion s = r.presenter.last();
        assertEquals(ClipScene.ID, s.sceneId);
        assertEquals(3, s.actions.size());
        assertEquals("不再提示", s.actions.get(2).label);
        r.engine.onAction(s.id, "chip1");
        assertEquals("clip:chip1", r.runner.runs.get(0));
    }

    @Test public void linkBeatsClipOnSameSignal() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(Signal.of(Signal.CLIP, r.clock.el, r.clock.wall, "text", "【淘宝】好物 https://m.tb.cn/h.abc123 点击链接", "installed",
                "com.taobao.taobao", "labels", "打开", "title", "复制了链接", "token", "t2", "hash", "h2"));
        assertEquals("link", r.presenter.last().sceneId);
        assertEquals("在淘宝打开", r.presenter.last().actions.get(0).label);
    }

    @Test public void stopWithdrawsAndIgnoresNewSignals() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        r.engine.emit(r.shot(P1, 'A'));
        String id = r.presenter.last().id;
        r.plug.stop();
        assertTrue(r.presenter.withdrawn.contains(id));
        assertFalse(r.plug.running());
        r.engine.emit(r.shot(P2, 'A'));
        assertEquals(1, r.presenter.shown.size());
        assertNotNull(r.engine.status());
    }

    static Signal torch(SuggestFakes.Rig r, long since, String ev) {
        return Signal.of(Signal.TORCH, r.clock.el, r.clock.wall, "on", "off".equals(ev) ? "0" : "1", "on_since", String.valueOf(since),
                "event", ev, Signal.K_WITHDRAW, "off".equals(ev) ? TorchScene.ID : null);
    }

    @Test public void torchOffWithdrawsCard() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        long since = r.clock.el;
        r.clock.advance(31000L);
        r.engine.emit(torch(r, since, "tick"));
        assertEquals("亮屏 30 s 出卡", 1, r.presenter.shown.size());
        String id = r.presenter.last().id;
        r.clock.advance(1000L);
        r.engine.emit(torch(r, since, "off"));
        assertEquals("关了手电就撤卡", id, r.presenter.withdrawn.get(0));
        assertTrue(r.outcomes.toString(), r.outcomes.contains("torch:WITHDRAWN:source_off"));
        assertNull(r.engine.current());
    }

    @Test public void torchLockedThenScreenOnShowsAndOffCancelsDeferred() {
        SuggestFakes.Rig r = new SuggestFakes.Rig().start();
        long since = r.clock.el;
        r.host.screenOn = false;
        r.clock.advance(31000L);
        r.engine.emit(torch(r, since, "tick"));
        assertEquals("息屏期间不弹", 0, r.presenter.shown.size());
        r.clock.advance(60000L);
        r.host.screenOn = true;
        r.engine.emit(torch(r, since, "screen_on"));
        assertEquals("亮屏立刻提醒", 1, r.presenter.shown.size());
        assertEquals("已经开了 1 分钟", r.presenter.last().subtitle);

        // 场景放行但宿主忙（面板在屏）→ 推迟 10 s；推迟期间关了手电 → 不再弹
        SuggestFakes.Rig r2 = new SuggestFakes.Rig().start();
        long s2 = r2.clock.el;
        r2.clock.advance(31000L);
        r2.host.busy = true;
        r2.engine.emit(torch(r2, s2, "tick"));
        assertEquals(0, r2.presenter.shown.size());
        r2.clock.advance(2000L);
        r2.engine.emit(torch(r2, s2, "off"));
        r2.host.busy = false;
        r2.sched.advance(20000L);
        assertEquals("推迟的候选被撤回", 0, r2.presenter.shown.size());
        assertTrue(r2.outcomes.toString(), r2.outcomes.contains("torch:SUPPRESSED:withdrawn"));
    }
}
