package com.onesay.assistant.v2.platform;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.shortcut.ShortcutActions;
import com.onesay.plugin.api.exec.ExecBackend;
import com.onesay.plugin.api.exec.ExecBackends;
import com.onesay.plugin.api.exec.ExecResult;
import com.onesay.plugin.exec.root.RootExecBackend;
import com.onesay.plugin.exec.shizuku.ShizukuExecBackend;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** OneSay 开源版：特权动作看「选中的执行后端是否可用」，不再只看编译期开关。 */
public class ExecBackendsTest {

    static final class Fake implements ExecBackend {
        ExecBackend.Tri t = ExecBackend.Tri.YES;
        String last;
        @Override public String id() { return "fake"; }
        @Override public String title() { return "假后端"; }
        @Override public UidLevel level() { return UidLevel.SHELL; }
        @Override public ExecBackend.Tri available() { return t; }
        @Override public String reason() { return t == ExecBackend.Tri.YES ? null : "没连上"; }
        @Override public ExecResult run(String c, long ms) { last = c; return new ExecResult(0, "ok", false, false); }
    }

    @Before public void reset() { ExecBackends.unregisterAll(); }
    @After public void clean() { ExecBackends.unregisterAll(); }

    static Capability dnd() {
        return new Capability.Builder("dnd.on", "勿扰").build();   // executorType 缺省为 shell = 特权
    }

    @Test public void defaultNoneSelected_privilegedStaysOff() {
        ExecBackends.register(new ShizukuExecBackend());
        assertEquals("", ExecBackends.selected());
        assertNull(ExecBackends.ready());
        assertEquals(ShortcutActions.REASON_PRIVILEGED_OFF, ShortcutActions.unavailableReason("dnd.on", dnd(), null, false));
    }

    @Test public void selectedAvailableBackend_lightsUpPrivileged() {
        Fake f = new Fake();
        ExecBackends.register(f);
        ExecBackends.select("fake");
        assertSame(f, ExecBackends.ready());
        assertNull(ShortcutActions.unavailableReason("dnd.on", dnd(), null, false));
        f.t = ExecBackend.Tri.UNKNOWN;
        String why = ShortcutActions.unavailableReason("dnd.on", dnd(), null, false);
        assertNotNull(why);
        assertTrue(why, why.contains("假后端"));
    }

    @Test public void unknownIdMeansNone() {
        ExecBackends.register(new Fake());
        ExecBackends.select("nope");
        assertEquals("", ExecBackends.selected());
    }

    @Test public void shizukuStubNeverAvailable() {
        ShizukuExecBackend s = new ShizukuExecBackend();
        ExecBackends.register(s);
        ExecBackends.select("shizuku");
        assertEquals(ExecBackend.Tri.NO, s.available());
        assertEquals(ExecBackend.UidLevel.SHELL, s.level());
        assertNull(ExecBackends.ready());
        assertFalse(s.run("id", 100).ok());
        assertTrue(ExecBackends.unavailableReason().contains("Shizuku"));
    }

    @Test public void suBackendProbeNeedsUid0() {
        final String[] out = {"2000\n"};
        RootExecBackend su = RootExecBackend.su(new RootExecBackend.SuRunner() {
            @Override public ExecResult run(String c, long ms) { return new ExecResult(0, out[0], false, false); }
        });
        assertEquals("root_su", su.id());
        assertEquals(ExecBackend.Tri.UNKNOWN, su.available());
        assertEquals(ExecBackend.Tri.NO, su.probe());
        out[0] = "0\n";
        assertEquals(ExecBackend.Tri.YES, su.probe());
    }

    @Test public void daemonBackendFollowsChannel() {
        final ExecBackend.Tri[] on = {ExecBackend.Tri.NO};
        RootExecBackend d = RootExecBackend.daemon(new RootExecBackend.Channel() {
            @Override public ExecResult run(String c, long ms) { return new ExecResult(0, c, false, false); }
            @Override public ExecBackend.Tri online() { return on[0]; }
        });
        assertEquals("root", d.id());
        assertEquals(ExecBackend.Tri.NO, d.available());
        on[0] = ExecBackend.Tri.YES;
        assertEquals(ExecBackend.Tri.YES, d.available());
        assertEquals("settings get global x", d.run("settings get global x", 1000).output);
    }

    @Test public void pluginApiHasNoAndroidOrAppDependency() throws Exception {
        java.io.File root = new java.io.File(System.getProperty("bixby.root", "."), "../../libs");
        int n = 0;
        for (String rel : new String[]{"plugin-api/src/com/onesay/plugin/api/exec/ExecBackend.java",
                "plugin-api/src/com/onesay/plugin/api/exec/ExecBackends.java", "plugin-api/src/com/onesay/plugin/api/exec/ExecResult.java",
                "plugins/exec-root/src/com/onesay/plugin/exec/root/RootExecBackend.java",
                "plugins/exec-shizuku/src/com/onesay/plugin/exec/shizuku/ShizukuExecBackend.java"}) {
            String s = new String(java.nio.file.Files.readAllBytes(new java.io.File(root, rel).toPath()), "UTF-8");
            assertFalse(rel, s.contains("import android."));
            assertFalse(rel, s.contains("com.onesay.assistant"));
            n++;
        }
        assertEquals(5, n);
    }
}
