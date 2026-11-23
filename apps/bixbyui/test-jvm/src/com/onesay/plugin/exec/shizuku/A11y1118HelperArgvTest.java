package com.onesay.plugin.exec.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.fastscreen.FsProtocol;
import com.onesay.plugin.api.exec.ExecResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ★ a11y-1118：只有 Shizuku 时读屏助手改发一条精确 argv（app_process + 本包真实 base.apk + onesay_fs:&lt;包&gt; + HelperMain &lt;包&gt; &lt;uid&gt;），
 * 白名单逐项比较；字符串入口照旧不认守护模板名，也不因此放宽别的命令。
 */
public class A11y1118HelperArgvTest {

    static final String PKG = "com.onesay.assistant.debug";
    static final String APK = "/data/app/~~Zx9_Qw-1AbC==/com.onesay.assistant.debug-Kp3_Vb0Q==/base.apk";
    static final int UID = 10234;

    static ShizukuWhitelist wl(final String apk, final int uid) {
        ShizukuWhitelist w = new ShizukuWhitelist(PKG);
        w.setSelfInfo(new ShizukuWhitelist.SelfInfo() {
            @Override public String baseApk() { return apk; }
            @Override public int uid() { return uid; }
            @Override public String helperMain() { return FsProtocol.MAIN_CLASS; }
        });
        return w;
    }

    static List<String> good() {
        return Arrays.asList("/system/bin/app_process", "-Djava.class.path=" + APK, "/system/bin",
                "--nice-name=onesay_fs:" + PKG, "com.onesay.assistant.v2.fastscreen.HelperMain", PKG, String.valueOf(UID));
    }

    @Test public void exactArgvAllowed() {
        assertEquals(good(), ShizukuWhitelist.helperArgv(PKG, APK, UID, FsProtocol.MAIN_CLASS));
        ShizukuWhitelist.Verdict v = wl(APK, UID).checkHelperArgv(good());
        assertTrue(v.toString(), v.allowed);
        assertEquals(ShizukuWhitelist.CAT_FASTSCREEN, v.category);
        assertEquals(good(), v.argv);
        // 进程名与助手端判断 Shizuku 启动用的是同一个
        assertEquals("--nice-name=" + FsProtocol.shizukuNiceName(PKG), good().get(3));
        assertEquals("com.onesay.assistant.v2.fastscreen.HelperMain", FsProtocol.MAIN_CLASS);
        // 老式安装路径也认
        String old = "/data/app/com.onesay.assistant.debug-1/base.apk";
        assertTrue(wl(old, UID).checkHelperArgv(ShizukuWhitelist.helperArgv(PKG, old, UID, FsProtocol.MAIN_CLASS)).allowed);
    }

    @Test public void everyItemComparedOneByOne() {
        ShizukuWhitelist w = wl(APK, UID);
        String[] bad = {"/system/bin/sh", "-Djava.class.path=/data/app/evil-1/base.apk", "/data/local/tmp",
                "--nice-name=bixbyui_fs:" + PKG, "com.evil.Main", "com.onesay.assistant", "10235"};
        for (int i = 0; i < good().size(); i++) {
            List<String> a = new ArrayList<String>(good());
            a.set(i, bad[i]);
            ShizukuWhitelist.Verdict v = w.checkHelperArgv(a);
            assertFalse(i + " " + a, v.allowed);
            assertEquals("HELPER_ARGV(" + i + ")", v.reason);
        }
        List<String> more = new ArrayList<String>(good());
        more.add("--extra");
        assertEquals("HELPER_ARGV_LEN", w.checkHelperArgv(more).reason);
        assertEquals("HELPER_ARGV_LEN", w.checkHelperArgv(good().subList(0, 6)).reason);
        assertEquals("HELPER_ARGV_LEN", w.checkHelperArgv(null).reason);
        List<String> withNull = new ArrayList<String>(good());
        withNull.set(6, null);
        assertFalse(w.checkHelperArgv(withNull).allowed);
        List<String> spaced = new ArrayList<String>(good());
        spaced.set(5, PKG + " ");
        assertFalse(w.checkHelperArgv(spaced).allowed);
    }

    @Test public void expectedValuesComeFromInjectedPackageInfoOnly() {
        assertEquals("HELPER_NO_SELFINFO", new ShizukuWhitelist(PKG).checkHelperArgv(good()).reason);
        ShizukuWhitelist badMain = new ShizukuWhitelist(PKG);
        badMain.setSelfInfo(new ShizukuWhitelist.SelfInfo() {
            @Override public String baseApk() { return APK; }
            @Override public int uid() { return UID; }
            @Override public String helperMain() { return "Main; id"; }
        });
        assertEquals("HELPER_MAIN", badMain.checkHelperArgv(good()).reason);
        // 期望路径不像本包的安装路径 → 拒（哪怕请求和它一致）
        for (String apk : new String[]{"/sdcard/Download/base.apk", "/data/local/tmp/base.apk", "/data/app/com.other.app-1/base.apk",
                "/data/app/com.onesay.assistant.debug-1/../evil/base.apk", "/data/app/com.onesay.assistant.debug-1/x.apk",
                "/data/app/com.onesay.assistant.debug-1/base.apk;id", "/data/app//com.onesay.assistant.debug-1/base.apk", null,
                "/data/app/com.onesay.assistant.debug/base.apk"}) {
            ShizukuWhitelist.Verdict v = wl(apk, UID).checkHelperArgv(ShizukuWhitelist.helperArgv(PKG, apk, UID, FsProtocol.MAIN_CLASS));
            assertFalse(String.valueOf(apk), v.allowed);
            assertEquals("HELPER_APK_PATH", v.reason);
        }
        for (int uid : new int[]{0, 1000, 2000, 9999, 1001000}) {
            ShizukuWhitelist.Verdict v = wl(APK, uid).checkHelperArgv(ShizukuWhitelist.helperArgv(PKG, APK, uid, FsProtocol.MAIN_CLASS));
            assertEquals(String.valueOf(uid), "HELPER_UID", v.reason);
        }
        assertTrue(wl(APK, 1010234).checkHelperArgv(ShizukuWhitelist.helperArgv(PKG, APK, 1010234, FsProtocol.MAIN_CLASS)).allowed);   // 第二个用户
        // 请求的 apk 与 PackageManager 给的不一样 → 拒
        String other = "/data/app/~~Other==/com.onesay.assistant.debug-Other==/base.apk";
        assertEquals("HELPER_ARGV(1)", wl(APK, UID).checkHelperArgv(ShizukuWhitelist.helperArgv(PKG, other, UID, FsProtocol.MAIN_CLASS)).reason);
    }

    @Test public void stringEntryNotLoosened() {
        ShizukuWhitelist w = wl(APK, UID);
        assertEquals("PROG_NOT_WHITELISTED(bixby-fastscreen-start)", w.check("bixby-fastscreen-start").reason);
        assertFalse(w.check("app_process /system/bin com.onesay.assistant.v2.fastscreen.HelperMain " + PKG + " " + UID).allowed);
        assertFalse(w.check(String.join(" ", good())).allowed);
        assertFalse(w.check("/system/bin/app_process -Djava.class.path=/data/app/com.onesay.assistant.debug-1/base.apk /system/bin "
                + "--nice-name=onesay_fs:" + PKG + " com.onesay.assistant.v2.fastscreen.HelperMain " + PKG + " " + UID).allowed);
        assertFalse(w.check("sh -c id").allowed);
    }

    static final class Bridge implements ShizukuExecBackend.Bridge, ShizukuExecBackend.Spawner {
        List<String> spawned;
        int execs;

        @Override public boolean running() { return true; }
        @Override public boolean granted() { return true; }
        @Override public int uid() { return 2000; }
        @Override public ExecResult exec(List<String> argv, long t) { execs++; return new ExecResult(0, "", false, false); }
        @Override public ExecResult spawn(List<String> argv, long settleMs) {
            spawned = new ArrayList<String>(argv);
            return new ExecResult(0, "spawned", false, false);
        }
    }

    static final class PlainBridge implements ShizukuExecBackend.Bridge {
        @Override public boolean running() { return true; }
        @Override public boolean granted() { return true; }
        @Override public int uid() { return 2000; }
        @Override public ExecResult exec(List<String> argv, long t) { return new ExecResult(0, "", false, false); }
    }

    @Test public void backendSpawnsOnlyTheExactArgv() {
        Bridge b = new Bridge();
        ShizukuExecBackend be = new ShizukuExecBackend(b, wl(APK, UID));
        ExecResult r = be.spawnHelper(good(), 600);
        assertTrue(r.ok());
        assertEquals(good(), b.spawned);
        b.spawned = null;
        List<String> a = new ArrayList<String>(good());
        a.set(4, "com.evil.Main");
        r = be.spawnHelper(a, 600);
        assertEquals(ShizukuExecBackend.RC_DENIED, r.rc);
        assertEquals("DENY SHIZUKU_HELPER_ARGV(4)", r.output);
        assertNull(b.spawned);
        assertEquals(0, b.execs);
        // 字符串入口照旧拒模板名
        assertEquals("DENY SHIZUKU_PROG_NOT_WHITELISTED(bixby-fastscreen-start)", be.run("bixby-fastscreen-start", 1000).output);
        // 桥不支持常驻进程 → 不可用，不退回普通执行
        assertTrue(new ShizukuExecBackend(new PlainBridge(), wl(APK, UID)).spawnHelper(good(), 600).backendDown);
        // 没有桥 / 不可用
        assertTrue(new ShizukuExecBackend().spawnHelper(good(), 600).backendDown);
    }
}
