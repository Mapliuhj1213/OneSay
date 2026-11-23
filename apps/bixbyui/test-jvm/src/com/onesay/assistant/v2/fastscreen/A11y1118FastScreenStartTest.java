package com.onesay.assistant.v2.fastscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.vision.CapturePlan;

import org.junit.Test;

/** ★ a11y-1118：经 Shizuku 起读屏助手的结果判定、助手的启动时证书（只限 Shizuku 起的 shell 进程）、识屏截图顺序。 */
public class A11y1118FastScreenStartTest {

    static final String PKG = "com.onesay.assistant.debug";

    @Test public void startVerdict() {
        FastScreen.StartVerdict v = FastScreen.startVerdict(0, "spawned", false, false);
        assertTrue(v.tryConnect);
        v = FastScreen.startVerdict(77, "DENY SHIZUKU_HELPER_ARGV(1)", false, false);
        assertFalse(v.tryConnect);
        assertEquals(FastScreen.BACKOFF_DENIED_MS, v.backoffMs);
        assertEquals("start failed: DENY SHIZUKU_HELPER_ARGV(1)", v.error);
        v = FastScreen.startVerdict(2, "helper exited rc=2 bad args: NOT_PINNED(" + PKG + ")", false, false);
        assertFalse(v.tryConnect);
        assertTrue(v.error.contains("NOT_PINNED"));
        assertEquals(FastScreen.BACKOFF_DENIED_MS, v.backoffMs);
        assertTrue(FastScreen.startVerdict(3, "helper exited rc=3 already running: x", false, false).tryConnect);
        v = FastScreen.startVerdict(-1, "Shizuku 没在运行", true, false);
        assertFalse(v.tryConnect);
        assertEquals(FastScreen.BACKOFF_MS, v.backoffMs);
        assertTrue(v.error.contains("Shizuku 没在运行"));
        assertFalse(FastScreen.startVerdict(0, "", false, true).tryConnect);
    }

    @Test public void launchPinOnlyForShizukuShellHelper() {
        assertTrue(FsProtocol.launchPinAllowed("onesay_fs:" + PKG, FsProtocol.SHELL_UID, PKG));
        assertFalse("root 守护起的照旧要编译期固定值", FsProtocol.launchPinAllowed("bixbyui_fs:" + PKG, 0, PKG));
        assertFalse(FsProtocol.launchPinAllowed("onesay_fs:" + PKG, 0, PKG));
        assertFalse(FsProtocol.launchPinAllowed("bixbyui_fs:" + PKG, FsProtocol.SHELL_UID, PKG));
        assertFalse(FsProtocol.launchPinAllowed("onesay_fs:com.other", FsProtocol.SHELL_UID, PKG));
        assertFalse(FsProtocol.launchPinAllowed("app_process", FsProtocol.SHELL_UID, PKG));
        assertFalse(FsProtocol.launchPinAllowed("", FsProtocol.SHELL_UID, PKG));
    }

    @Test public void authorizeWithLaunchPin() {
        String sha = "ab";
        String[] pk = {PKG};
        assertNull(FsProtocol.authorizePeer(10234, 10234, PKG, pk, new String[]{"AB"}, sha));
        assertEquals("CERT_MISMATCH", FsProtocol.authorizePeer(10234, 10234, PKG, pk, new String[]{"cd"}, sha));
        assertEquals("UID(10235!=10234)", FsProtocol.authorizePeer(10235, 10234, PKG, pk, new String[]{"ab"}, sha));
        assertEquals("PKG", FsProtocol.authorizePeer(10234, 10234, PKG, new String[]{"com.x"}, new String[]{"ab"}, sha));
        assertEquals("CERT_COUNT", FsProtocol.authorizePeer(10234, 10234, PKG, pk, new String[]{"ab", "ab"}, sha));
        // 没有固定值、没有启动证书 → 照旧 fail closed
        assertEquals("NOT_PINNED(" + PKG + ")", FsProtocol.authorizePeer(10234, 10234, PKG, pk, new String[]{"ab"}, null));
        assertEquals("NOT_PINNED(" + PKG + ")", FsProtocol.authorizePeer(10234, 10234, PKG, pk, new String[]{"ab"}));
    }

    @Test public void captureOrderShizukuTriesExternalFirstForScreencap() {
        assertEquals("[private, external+shell]", CapturePlan.steps(true).toString());
        assertEquals("[private+shell, internal+shell, external+shell]", CapturePlan.steps(false).toString());
        assertEquals("无法截取屏幕内容：截屏命令被拒绝（SHIZUKU_SCREENCAP_PATH(/data/user/0/x/a.png)）",
                CapturePlan.userError(CapturePlan.describe(true, "SHIZUKU_SCREENCAP_PATH(/data/user/0/x/a.png)", false, false, 77, "", false, -1)));
        assertTrue(CapturePlan.describe(false, "", false, false, 0, "", true, 0).contains("0 字节"));
        assertTrue(CapturePlan.describe(false, "", false, false, 1, "Error opening", false, -1).contains("没有写出文件"));
        assertEquals("无法截取屏幕内容：画面文件为空或权限受阻", CapturePlan.userError(null));
    }
}
