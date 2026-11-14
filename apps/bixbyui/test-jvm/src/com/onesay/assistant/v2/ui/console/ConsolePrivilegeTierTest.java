package com.onesay.assistant.v2.ui.console;

import org.junit.Test;

import static org.junit.Assert.*;

/** priv-llm：开源版没有 root 只是提示；「Shizuku 特权」可用时是正常的中间档。 */
public class ConsolePrivilegeTierTest {

    static ConsoleStatus.Snapshot healthy() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.wakeRunning = true;
        s.micReady = true;
        s.llmConfigured = true;
        s.asrConfigured = true;
        s.micPermission = true;
        s.overlayOk = true;
        s.batteryWhitelisted = true;
        s.voiceprint = true;
        s.done = true;
        return s;
    }

    @Test public void ossRootIsOptional() {
        assertTrue(ConsoleStatus.ROOT_OPTIONAL);
    }

    @Test public void noRootNoShizukuIsOnlyAHint() {
        ConsoleStatus.Snapshot s = healthy();
        s.rootOk = false;
        ConsoleStatus.Pill p = ConsoleStatus.root(s);
        assertEquals("无 Root", p.label);
        assertEquals(ConsoleStatus.Level.OFF, p.level);
        assertTrue("不出 Root 提醒条", ConsoleStatus.allProblems(s).isEmpty());
        ConsoleStatus.Hero h = ConsoleStatus.hero(s);
        assertEquals(ConsoleStatus.Level.OK, h.level);
        assertEquals("运行中", h.word);
        assertTrue(ConsoleStatus.privilegeText(s).contains("可选"));
    }

    @Test public void shizukuReadyIsMiddleTierOk() {
        ConsoleStatus.Snapshot s = healthy();
        s.rootOk = false;
        s.shizukuOk = true;
        ConsoleStatus.Pill p = ConsoleStatus.root(s);
        assertEquals("Shizuku", p.label);
        assertEquals(ConsoleStatus.Level.OK, p.level);
        assertEquals(ConsoleStatus.Level.OK, ConsoleStatus.hero(s).level);
        assertTrue(ConsoleStatus.privilegeText(s).contains("Shizuku"));
        s.rootOk = null;   // root 探测还没回来，Shizuku 已经可用：不显示「检测中」
        assertEquals("Shizuku", ConsoleStatus.root(s).label);
        assertEquals(ConsoleStatus.Level.OK, ConsoleStatus.hero(s).level);
    }

    @Test public void shizukuOnButNotReadyWarnsNotCritical() {
        ConsoleStatus.Snapshot s = healthy();
        s.rootOk = false;
        s.shizukuOk = false;
        ConsoleStatus.Pill p = ConsoleStatus.root(s);
        assertEquals("Shizuku 未就绪", p.label);
        assertEquals(ConsoleStatus.Level.WARN, p.level);
        assertNotEquals("需要处理", ConsoleStatus.hero(s).word);
    }

    @Test public void rootWinsWhenPresent() {
        ConsoleStatus.Snapshot s = healthy();
        s.rootOk = true;
        s.shizukuOk = true;
        assertEquals("Root", ConsoleStatus.root(s).label);
        assertEquals(ConsoleStatus.Level.OK, ConsoleStatus.root(s).level);
    }

    @Test public void pendingAndUnknownUnchanged() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        assertEquals(ConsoleStatus.Level.UNKNOWN, ConsoleStatus.root(s).level);
        s.done = true;
        assertEquals("Root 未知", ConsoleStatus.root(s).label);
    }
}
