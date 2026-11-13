package com.onesay.assistant.v2.platform;

import com.onesay.plugin.api.exec.ExecBackend;
import com.onesay.plugin.api.exec.ExecResult;
import com.onesay.plugin.exec.shizuku.ShizukuExecBackend;
import com.onesay.plugin.exec.shizuku.ShizukuWhitelist;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** priv-llm：「Shizuku 特权」插件 —— 白名单、后端状态、执行档位选择（假桥，不碰 Android）。 */
public class ShizukuTierTest {

    static final String PKG = "com.onesay.assistant";

    static final class FakeBridge implements ShizukuExecBackend.Bridge {
        boolean running = true, granted = true;
        int uid = 2000;
        List<String> lastArgv;
        int calls;

        @Override public boolean running() { return running; }
        @Override public boolean granted() { return granted; }
        @Override public int uid() { return uid; }
        @Override public ExecResult exec(List<String> argv, long timeoutMs) {
            calls++;
            lastArgv = new ArrayList<String>(argv);
            return new ExecResult(0, "ok", false, false);
        }
    }

    static final class Su implements ExecBackend {
        @Override public String id() { return "root_su"; }
        @Override public String title() { return "su"; }
        @Override public UidLevel level() { return UidLevel.ROOT; }
        @Override public Tri available() { return Tri.YES; }
        @Override public String reason() { return null; }
        @Override public ExecResult run(String c, long ms) { return new ExecResult(0, "su", false, false); }
    }

    static final class Daemon implements ExecBackend {
        @Override public String id() { return "root"; }
        @Override public String title() { return "daemon"; }
        @Override public UidLevel level() { return UidLevel.ROOT; }
        @Override public Tri available() { return Tri.YES; }
        @Override public String reason() { return null; }
        @Override public ExecResult run(String c, long ms) { return new ExecResult(0, "d", false, false); }
    }

    static ShizukuWhitelist wl() {
        return new ShizukuWhitelist(PKG);
    }

    @Test public void whitelistAllows() {
        ShizukuWhitelist w = wl();
        String[] ok = {
                "appops set " + PKG + " READ_CLIPBOARD allow",
                "appops set " + PKG + " READ_CLIPBOARD default",
                "appops get " + PKG + " READ_CLIPBOARD",
                "appops set " + PKG + " ACCESS_RESTRICTED_SETTINGS allow",
                "cmd notification allow_listener " + PKG + "/com.onesay.assistant.v2.events.EventNotificationListener",
                "cmd notification disallow_listener " + PKG + "/.events.EventNotificationListener",
                "settings get secure enabled_notification_listeners",
                "input keyevent KEYCODE_HOME",
                "input keyevent --longpress KEYCODE_VOLUME_UP",
                "input keyevent 4",
                "screencap -p /data/local/tmp/onesay_shot.png",
                "screencap -p /sdcard/Android/data/" + PKG + "/cache/s1.png",
                "  input   keyevent   KEYCODE_BACK  ",
        };
        for (String c : ok) assertTrue(c, w.check(c).allowed);
        ShizukuWhitelist.Verdict v = w.check("input keyevent KEYCODE_BACK");
        assertEquals(ShizukuWhitelist.CAT_KEYEVENT, v.category);
        assertEquals(java.util.Arrays.asList("input", "keyevent", "KEYCODE_BACK"), v.argv);
        assertEquals(ShizukuWhitelist.CAT_CLIPBOARD, w.check(ok[0]).category);
        assertEquals(ShizukuWhitelist.CAT_SCREENCAP, w.check(ok[10]).category);
    }

    @Test public void whitelistDenies() {
        ShizukuWhitelist w = wl();
        String[] bad = {
                null, "", "   ",
                "sh -c id", "id", "pm install /sdcard/x.apk", "am start -n x/y", "rm -rf /sdcard",
                "settings put secure enabled_notification_listeners x/y",
                "settings get global adb_enabled",
                "appops set com.other.app READ_CLIPBOARD allow",
                "appops set " + PKG + " SYSTEM_ALERT_WINDOW allow",
                "appops set " + PKG + " READ_CLIPBOARD allow extra",
                "cmd notification allow_listener com.other/.Listener",
                "cmd package list packages",
                "input tap 100 200",
                "input keyevent KEYCODE_POWER", "input keyevent 26", "input keyevent KEYCODE_SLEEP",
                "input keyevent lowercase",
                "input keyevent KEYCODE_HOME; reboot",
                "input keyevent KEYCODE_HOME && reboot",
                "input keyevent $(reboot)",
                "input keyevent `reboot`",
                "input keyevent KEYCODE_HOME\nreboot",
                "screencap -p /sdcard/DCIM/x.png",
                "screencap -p /data/local/tmp/other.png",
                "screencap -p /data/local/tmp/onesay/../../data/x.png",
                "screencap -p /sdcard/Android/data/" + PKG + "/cache/x.jpg",
                "screencap /data/local/tmp/onesay.png",
                "echo OK",
        };
        for (String c : bad) assertFalse(String.valueOf(c), w.check(c).allowed);
        assertTrue(w.check("cat /etc/hosts").reason.startsWith("PROG_NOT_WHITELISTED"));
        try { new ShizukuWhitelist("bad pkg"); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void backendStates() {
        FakeBridge b = new FakeBridge();
        ShizukuExecBackend s = new ShizukuExecBackend(b, wl());
        assertEquals("shizuku", s.id());
        assertEquals(ExecBackend.Tri.YES, s.available());
        assertNull(s.reason());
        assertEquals(ExecBackend.UidLevel.SHELL, s.level());
        b.uid = 0;
        assertEquals("root 启动的 Shizuku", ExecBackend.UidLevel.ROOT, s.level());
        b.granted = false;
        assertEquals(ExecBackend.Tri.NO, s.available());
        assertEquals(ShizukuExecBackend.REASON_NOT_GRANTED, s.reason());
        assertFalse(s.run("input keyevent 4", 1000).ok());
        assertEquals("没授权不执行", 0, b.calls);
        b.running = false;
        assertEquals(ShizukuExecBackend.REASON_NOT_RUNNING, s.reason());
        assertTrue(s.run("input keyevent 4", 1000).backendDown);
        ShizukuExecBackend none = new ShizukuExecBackend();
        assertEquals(ExecBackend.Tri.NO, none.available());
        assertEquals(ShizukuExecBackend.REASON_NO_BRIDGE, none.reason());
        assertFalse(none.permits("input keyevent 4"));
    }

    @Test public void backendRunsArgvAndDeniesOutsideWhitelist() {
        FakeBridge b = new FakeBridge();
        ShizukuExecBackend s = new ShizukuExecBackend(b, wl());
        ExecResult r = s.run("input keyevent KEYCODE_HOME", 2000);
        assertTrue(r.ok());
        assertEquals(java.util.Arrays.asList("input", "keyevent", "KEYCODE_HOME"), b.lastArgv);
        ExecResult d = s.run("pm uninstall com.x", 2000);
        assertEquals(ShizukuExecBackend.RC_DENIED, d.rc);
        assertTrue(d.output, d.output.startsWith("DENY SHIZUKU_PROG_NOT_WHITELISTED"));
        assertEquals("被拒的命令没送到 Shizuku", 1, b.calls);
    }

    @Test public void tierRouting() {
        FakeBridge b = new FakeBridge();
        ShizukuExecBackend shz = new ShizukuExecBackend(b, wl());
        String key = "input keyevent KEYCODE_HOME", other = "svc wifi enable";
        // 没开插件、没选后端 → 原守护通道（与之前一致）
        assertEquals(ExecTierRouter.Kind.DAEMON, ExecTierRouter.route(key, null, shz, false).kind);
        // 开了插件、Shizuku 可用：白名单内 → Shizuku；白名单外 → 直接拒绝
        ExecTierRouter.Route r = ExecTierRouter.route(key, null, shz, true);
        assertEquals(ExecTierRouter.Kind.BACKEND, r.kind);
        assertSame(shz, r.backend);
        ExecTierRouter.Route d = ExecTierRouter.route(other, null, shz, true);
        assertEquals(ExecTierRouter.Kind.DENY, d.kind);
        assertTrue(d.denyReason, d.denyReason.startsWith("SHIZUKU_PROG_NOT_WHITELISTED"));
        // root 优先：守护在线 / su 直连可用时不走 Shizuku
        assertEquals(ExecTierRouter.Kind.DAEMON, ExecTierRouter.route(key, new Daemon(), shz, true).kind);
        ExecTierRouter.Route su = ExecTierRouter.route(other, new Su(), shz, true);
        assertEquals(ExecTierRouter.Kind.BACKEND, su.kind);
        assertEquals("root_su", su.backend.id());
        // Shizuku 不可用 → 退回原通道（不拒绝，不报错）
        b.granted = false;
        assertEquals(ExecTierRouter.Kind.DAEMON, ExecTierRouter.route(key, null, shz, true).kind);
        b.granted = true;
        // 旧设置里把 shizuku 选成了后端 = 视同打开插件
        assertEquals(ExecTierRouter.Kind.BACKEND, ExecTierRouter.route(key, shz, shz, false).kind);
        assertEquals(ExecTierRouter.Kind.DAEMON, ExecTierRouter.route(key, null, null, true).kind);
    }

    @Test public void tierLevels() {
        assertEquals(RootOps.Level.ROOT, ExecTierRouter.tier(true, true));
        assertEquals(RootOps.Level.SHELL, ExecTierRouter.tier(false, true));
        assertEquals(RootOps.Level.SHELL, ExecTierRouter.tier(null, true));
        assertEquals(RootOps.Level.NONE, ExecTierRouter.tier(false, false));
        assertEquals(RootOps.Level.NONE, ExecTierRouter.tier(false, null));
    }

    @Test public void grantActionsPassWhitelistForReleaseAndDebugPackages() {
        for (String pkg : new String[]{"com.onesay.assistant", "com.onesay.assistant.debug"}) {
            ShizukuWhitelist w = new ShizukuWhitelist(pkg);
            for (String c : ShizukuActions.clipboardGrant(pkg)) assertTrue(c, w.check(c).allowed);
            for (String c : ShizukuActions.notificationAccess(pkg, 34)) assertTrue(c, w.check(c).allowed);
            assertEquals(2, ShizukuActions.notificationAccess(pkg, 34).size());
            assertEquals("Android 13 以下没有受限设置", 1, ShizukuActions.notificationAccess(pkg, 28).size());
        }
    }

    @Test public void pluginCoreStaysPureJava() throws Exception {
        java.io.File root = new java.io.File(System.getProperty("bixby.root", "."), "../../libs/plugins/exec-shizuku/src/com/onesay/plugin/exec/shizuku");
        for (String f : new String[]{"ShizukuExecBackend.java", "ShizukuWhitelist.java"}) {
            String s = new String(java.nio.file.Files.readAllBytes(new java.io.File(root, f).toPath()), "UTF-8");
            assertFalse(f, s.contains("import android."));
            assertFalse(f, s.contains("import rikka.") || s.contains("rikka.shizuku.Shizuku."));
            assertFalse(f, s.contains("com.onesay.assistant"));
        }
    }
}
