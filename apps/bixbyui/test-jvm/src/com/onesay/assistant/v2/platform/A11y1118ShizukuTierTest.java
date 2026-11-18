package com.onesay.assistant.v2.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.onesay.plugin.exec.shizuku.ShizukuExecBackend;
import com.onesay.plugin.exec.shizuku.ShizukuWhitelist;

import org.junit.Test;

/** ★ a11y-1118：「此刻是不是 Shizuku 档」与 ExecTierRouter.route 同一套判定（读屏助手改发 argv、识屏截图换顺序都看它）。 */
public class A11y1118ShizukuTierTest {

    @Test public void tierMatchesRoute() {
        ShizukuTierTest.FakeBridge fb = new ShizukuTierTest.FakeBridge();
        ShizukuExecBackend shz = new ShizukuExecBackend(fb, new ShizukuWhitelist(ShizukuTierTest.PKG));
        assertTrue(ExecTierRouter.isShizukuTier(null, shz, true));
        assertTrue(ExecTierRouter.isShizukuTier(shz, shz, false));
        assertFalse(ExecTierRouter.isShizukuTier(null, shz, false));
        assertFalse("su 优先", ExecTierRouter.isShizukuTier(new ShizukuTierTest.Su(), shz, true));
        assertFalse("root 守护优先", ExecTierRouter.isShizukuTier(new ShizukuTierTest.Daemon(), shz, true));
        assertFalse(ExecTierRouter.isShizukuTier(null, null, true));
        fb.granted = false;
        assertFalse(ExecTierRouter.isShizukuTier(null, shz, true));
        fb.granted = true;
        // 与 route 对同一条命令的判定一致：Shizuku 档 → 白名单外的命令被拒（DENY SHIZUKU_…）
        assertEquals(ExecTierRouter.Kind.DENY, ExecTierRouter.route("bixby-fastscreen-start", null, shz, true).kind);
        assertEquals(ExecTierRouter.Kind.DAEMON, ExecTierRouter.route("bixby-fastscreen-start", new ShizukuTierTest.Daemon(), shz, true).kind);
    }
}
