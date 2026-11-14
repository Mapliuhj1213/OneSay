package com.onesay.assistant.v2.exec;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** ★ borrow-1112 ①：控屏状态颜色（谁在控屏）+ 开关 + 接线。 */
public class ControlColorTest {

    @Test public void colorsPerController() {
        assertEquals(ControlColor.AGENT, ControlColor.rgb(ControlColor.Who.AGENT));
        assertEquals(ControlColor.WAIT_USER, ControlColor.rgb(ControlColor.Who.WAIT_USER));
        assertEquals(ControlColor.USER, ControlColor.rgb(ControlColor.Who.USER));
        assertEquals(ControlColor.STOPPED, ControlColor.rgb(ControlColor.Who.STOPPED));
        java.util.Set<Integer> s = new java.util.HashSet<Integer>();
        for (ControlColor.Who w : ControlColor.Who.values()) s.add(ControlColor.rgb(w));
        assertEquals("四种状态四种颜色", 4, s.size());
    }

    @Test public void switchOffRestoresLegacyColorsAndNoIslandRing() {
        assertEquals(ControlColor.LEGACY_GLOW, ControlColor.shieldRgb(ControlColor.Who.USER, false, false));
        assertEquals(ControlColor.LEGACY_THIN, ControlColor.shieldRgb(ControlColor.Who.USER, true, false));
        assertEquals(ControlColor.USER, ControlColor.shieldRgb(ControlColor.Who.USER, true, true));
        assertEquals(0, ControlColor.islandRing(ControlColor.Who.AGENT, false));
        assertEquals(0xFF000000 | ControlColor.AGENT, ControlColor.islandRing(ControlColor.Who.AGENT, true));
        assertEquals(0, ControlColor.islandRing(null, true));
        assertTrue(ControlColor.DEFAULT_ON);
    }

    @Test public void pausedByUserIsGreenPausedByAgentIsAmber() {
        assertEquals(ControlColor.Who.USER, ControlColor.pausedWho(true));
        assertEquals(ControlColor.Who.WAIT_USER, ControlColor.pausedWho(false));
    }

    static String src(String rel) throws Exception {
        File root = new File(System.getProperty("bixby.root", "."));
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void wiredIntoExecModeShieldAndIsland() throws Exception {
        String pkg = new File(new File(System.getProperty("bixby.root", ".")), "app/src/com/bixbyui").isDirectory() ? "com/onesay/assistant/v2" : "com/onesay/assistant/v2";
        String ex = src("app/src/" + pkg + "/exec/ExecModeController.java");
        assertTrue(ex.contains("setWho(ControlColor.Who.AGENT)"));
        assertTrue(ex.contains("setWho(ControlColor.Who.WAIT_USER)"));
        assertTrue(ex.contains("setWho(ControlColor.Who.STOPPED)"));
        assertTrue(ex.contains("applyPaused(\"你在操作，好了点继续\", true)"));
        assertTrue(ex.contains("applyPaused(why, false)"));
        assertTrue(src("app/src/" + pkg + "/exec/ShieldOverlay.java").contains("mTint"));
        assertTrue(src("app/src/" + pkg + "/island/IslandView.java").contains("sControlRing"));
        String pp = src("app/src/" + pkg + "/kernel/plugins/PhonePlugin.java");
        assertTrue(pp.contains("控屏状态颜色"));
        assertFalse(pp.contains("Bixby"));
    }
}
