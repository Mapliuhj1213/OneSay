package com.onesay.assistant.v2.exec;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ fast-screen-v2：执行模式状态机 + 护罩规则。 */
public class ExecStateTest {

    @Test public void startRunsWithFullShield() {
        ExecState s = new ExecState();
        assertEquals(ExecState.S.IDLE, s.state());
        assertEquals(ExecState.Shield.NONE, s.shield());
        assertTrue(s.start());
        assertEquals(ExecState.Shield.FULL, s.shield());
        assertFalse(s.start());   // 已在跑
    }

    @Test public void takeoverThinShieldThenResume() {
        ExecState s = new ExecState();
        s.start();
        assertTrue(s.takeover("你点了接管"));
        assertEquals(ExecState.S.TAKEOVER, s.state());
        assertEquals(ExecState.Shield.THIN, s.shield());
        assertTrue(s.paused());
        assertEquals("你点了接管", s.takePendingTakeover());
        assertNull(s.takePendingTakeover());   // 取一次就清
        assertFalse(s.takeover("再点"));         // 已在接手，不叠加
        assertTrue(s.resume());
        assertEquals(ExecState.Shield.FULL, s.shield());
        assertFalse(s.resume());
    }

    @Test public void navKeyIsEmergencyTakeoverNotStop() {
        ExecState s = new ExecState();
        s.start();
        assertTrue(s.navKey("按了返回键"));
        assertEquals(1, s.navTakeovers);
        assertFalse(s.stopped());
        assertEquals(ExecState.S.TAKEOVER, s.state());
        assertFalse(s.navKey("按了主页键"));
        assertEquals(1, s.navTakeovers);
    }

    @Test public void confirmBlocksTakeoverUntilResolved() {
        ExecState s = new ExecState();
        s.start();
        assertTrue(s.confirmBegin());
        assertEquals(ExecState.Shield.FULL, s.shield());   // 确认卡开着时护罩保留
        assertFalse(s.takeover("x"));
        assertFalse(s.confirmBegin());
        s.confirmEnd();
        assertEquals(ExecState.S.RUNNING, s.state());
    }

    @Test public void supplementCarriesTextOnce() {
        ExecState s = new ExecState();
        s.start();
        assertTrue(s.supplement());
        assertEquals(ExecState.S.SUPPLEMENT, s.state());
        assertEquals(ExecState.Shield.THIN, s.shield());
        assertEquals("补充", s.takePendingTakeover());
        s.supplementText("用北京的");
        assertTrue(s.resume());
        assertEquals("用北京的", s.takeSupplement());
        assertNull(s.takeSupplement());
        // 接手中也能补充
        s.takeover("t");
        assertTrue(s.supplement());
    }

    @Test public void stopFromAnyStateClearsShield() {
        for (int k = 0; k < 4; k++) {
            ExecState s = new ExecState();
            s.start();
            if (k == 1) s.takeover("t");
            if (k == 2) s.supplement();
            if (k == 3) s.confirmBegin();
            s.stop();
            assertTrue(s.stopped());
            assertEquals(ExecState.S.ENDED, s.state());
            assertEquals(ExecState.Shield.NONE, s.shield());
            assertFalse(s.resume());
        }
    }

    @Test public void endIsNotStopAndCanRestart() {
        ExecState s = new ExecState();
        s.start();
        s.end();
        assertFalse(s.stopped());
        assertEquals(ExecState.Shield.NONE, s.shield());
        assertTrue(s.start());
    }

    @Test public void strayTouchHintThrottled() {
        ExecState s = new ExecState();
        assertTrue(s.onStrayTouch(10_000));
        assertFalse(s.onStrayTouch(11_000));
        assertFalse(s.onStrayTouch(12_999));
        assertTrue(s.onStrayTouch(13_000));
        assertEquals(4, s.strayTouches);
    }

    @Test public void resultTemplateMapping() throws Exception {
        // ExecVoice.forResult 依赖 android 类所在的文件，这里只用源码守卫：完成 / 停止 / 出错各用一句模板
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("bixby.root"),
                "app/src/com/onesay/assistant/v2/exec/ExecVoice.java")), "UTF-8");
        assertTrue(src.contains("if (ok) return VoiceTemplate.DONE;"));
        assertTrue(src.contains("stopped ? VoiceTemplate.CANCELLED : VoiceTemplate.ERROR"));
        assertFalse("不应再用反射找模板", src.contains("Class.forName"));
        String ctl = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("bixby.root"),
                "app/src/com/onesay/assistant/v2/exec/ExecModeController.java")), "UTF-8");
        assertTrue(ctl.contains("ExecVoice.play(mApp, ExecVoice.forResult(ok, state.stopped()))"));
        assertTrue(ctl.contains("VoiceTemplate.CONFIRM"));
    }

    // ★ fast-screen-v3 真机修正：补充在代理取走之前就提交了 → 代理取到时不再暂停
    @Test public void supplementSubmittedBeforeAgentPollsIsResumedEarly() {
        ExecState s = new ExecState();
        s.start();
        assertTrue(s.supplement());
        s.supplementText("顺便看看蓝牙");
        assertTrue(s.resume());
        assertEquals(ExecState.Shield.FULL, s.shield());
        assertEquals("补充", s.takePendingTakeover());
        assertTrue(s.consumeResumedEarly());
        assertFalse("只用一次", s.consumeResumedEarly());
        assertEquals("顺便看看蓝牙", s.takeSupplement());
    }

    @Test public void normalTakeoverConsumedBeforeResumeIsNotEarly() {
        ExecState s = new ExecState();
        s.start();
        assertTrue(s.takeover("你点了接管"));
        assertEquals("你点了接管", s.takePendingTakeover());   // 代理先取走，再等继续
        assertTrue(s.resume());
        assertFalse(s.consumeResumedEarly());
        // 新一次接手清掉旧标记
        assertTrue(s.supplement());
        assertTrue(s.resume());
        assertTrue(s.takeover("返回键"));
        assertFalse(s.consumeResumedEarly());
    }
}
