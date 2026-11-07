package com.onesay.assistant.v2.sec;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ShellCommand;
import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.backend.ShellBackend;
import com.onesay.assistant.v2.executor.backend.ShellPrograms;

import org.junit.Assume;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * ★ device-1102 问题 1 / 5：导航被守护拦 + 导航前置自动开定位。
 * <ul>
 *   <li>根因复现：s2.nav.app 旧模板（if … | grep -q Error; then …; fi; true）在守护 v4 判定模式下 DENY METACHAR；</li>
 *   <li>新程序：定位关 → 自动开（location_mode=3，写不进退 providers 模板）→ geo -p 包名 → geo → 高德网页，逐级回退；</li>
 *   <li>新程序发出的每一条命令在守护 v4（含本分支 am -p 放宽）判定模式下都 ALLOW；broadcast -p / 非法包名仍 DENY；</li>
 *   <li>拒绝话术：说清哪类动作、为什么、怎么开。</li>
 * </ul>
 */
public class Device1102NavTest {

    static final String OLD_NAV_APP = "if am start -a android.intent.action.VIEW -d 'geo:0,0?q={address}' -p {pkg} 2>&1 | grep -q Error; then "
            + "am start -a android.intent.action.VIEW -d 'geo:0,0?q={address}' >/dev/null 2>&1; fi; true";

    static Map<String, String> slots() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("address", "北京南站");
        m.put("pkg", "com.autonavi.minimap");
        return m;
    }

    static String geoPkg() { return ShellCommand.fill(ShellPrograms.NAV_GEO_PKG, slots()); }
    static String geo() { return ShellCommand.fill(ShellPrograms.NAV_GEO, slots()); }
    static String web() { return ShellCommand.fill(ShellPrograms.NAV_WEB, slots()); }

    @Test public void locationOffIsTurnedOnThenNavigatesWithPackage() {
        SecIntExecProtocolTest.Fake f = new SecIntExecProtocolTest.Fake()
                .on(ShellPrograms.LOC_MODE_GET, 0, "0\n")
                .on(geoPkg(), 0, "Starting: Intent { act=android.intent.action.VIEW }");
        ShellExecutor.ShellResponse r = ShellPrograms.forSign("s2.nav.app").run(f, slots());
        assertEquals(java.util.Arrays.asList(ShellPrograms.LOC_MODE_GET, ShellPrograms.LOC_MODE_ON, geoPkg()), f.cmds);
        assertEquals(0, r.rc);
        assertFalse(r.isDenied);
        assertTrue("回执带「定位是顺手打开的」标记: " + r.output, r.output.contains(ShellPrograms.LOC_AUTO_ON_MARK));
    }

    @Test public void locationAlreadyOnIsLeftAlone() {
        SecIntExecProtocolTest.Fake f = new SecIntExecProtocolTest.Fake().on(ShellPrograms.LOC_MODE_GET, 0, "3");
        ShellPrograms.forSign("s2.nav.app").run(f, slots());
        assertFalse(f.cmds.contains(ShellPrograms.LOC_MODE_ON));
        assertEquals(geoPkg(), f.cmds.get(1));
    }

    @Test public void locationModeWriteRefusedFallsBackToProvidersTemplateAndStillNavigates() {
        SecIntExecProtocolTest.Fake f = new SecIntExecProtocolTest.Fake()
                .on(ShellPrograms.LOC_MODE_GET, 0, "0").deny(ShellPrograms.LOC_MODE_ON).deny(ShellPrograms.LOC_PROVIDERS_ON);
        ShellExecutor.ShellResponse r = ShellPrograms.forSign("screen.nav.to").run(f, slots());
        assertTrue(f.cmds.contains(ShellPrograms.LOC_PROVIDERS_ON));
        assertTrue("开定位失败不阻断导航", f.cmds.contains(geo()));
        assertFalse(r.isDenied);
        assertFalse(r.output.contains(ShellPrograms.LOC_AUTO_ON_MARK));
    }

    @Test public void oldDaemonRefusingDashPFallsBackToPlainGeo() {
        SecIntExecProtocolTest.Fake f = new SecIntExecProtocolTest.Fake().deny(geoPkg());
        ShellExecutor.ShellResponse r = ShellPrograms.forSign("s2.nav.app").run(f, slots());
        assertEquals(geo(), f.cmds.get(2));
        assertFalse(r.isDenied);
        assertEquals(0, r.rc);
    }

    @Test public void everyStepDeniedSurfacesDenyAndDownStops() {
        SecIntExecProtocolTest.Fake f = new SecIntExecProtocolTest.Fake().deny(geoPkg()).deny(geo()).deny(web());
        ShellExecutor.ShellResponse r = ShellPrograms.forSign("s2.nav.app").run(f, slots());
        assertTrue("全被拒 → 按拒绝上抛（ShellBackend 给说明）", r.isDenied);
        SecIntExecProtocolTest.Fake d = new SecIntExecProtocolTest.Fake();
        d.ans.put(ShellPrograms.LOC_MODE_GET, new ShellExecutor.ShellResponse(-1, "", false, true));
        assertTrue(ShellPrograms.forSign("s2.nav.app").run(d, slots()).isDown);
        assertEquals("守护离线不再往下试", 1, d.cmds.size());
    }

    @Test public void invalidPackageSkipsDashPStep() {
        Map<String, String> s = slots();
        s.put("pkg", "x; rm -rf /");
        SecIntExecProtocolTest.Fake f = new SecIntExecProtocolTest.Fake();
        ShellPrograms.forSign("s2.nav.app").run(f, s);
        for (String c : f.cmds) assertFalse(c, c.contains(" -p "));
    }

    // ---- 守护 v4 判定模式（实证）----

    @Test public void daemonCheck_oldTemplateDeniedNewCommandsAllowed() throws Exception {
        Assume.assumeTrue(SecIntExecWhitelistCiTest.haveSh());
        String old = SecIntExecWhitelistCiTest.check(ShellCommand.fill(OLD_NAV_APP, slots()));
        assertTrue("根因：旧 s2.nav.app 复合脚本被守护拒: " + old, old.startsWith("DENY") && old.contains("METACHAR"));
        for (String c : new String[]{ShellPrograms.LOC_MODE_GET, ShellPrograms.LOC_MODE_ON, ShellPrograms.LOC_PROVIDERS_ON,
                geoPkg(), geo(), web()}) {
            String v = SecIntExecWhitelistCiTest.check(c);
            assertTrue(c + " → " + v, v.startsWith("ALLOW"));
        }
        assertTrue(SecIntExecWhitelistCiTest.check("am broadcast -a x.y -p com.autonavi.minimap").startsWith("DENY"));
        assertTrue(SecIntExecWhitelistCiTest.check("am start -a android.intent.action.VIEW -d 'geo:0,0?q=a' -p '$(id)'").startsWith("DENY"));
    }

    // ---- 拒绝话术 ----

    @Test public void denyMessageSaysWhatWhyHow() {
        Capability nav = new Capability.Builder("s2.nav.app", "导航").build();
        String m = ShellBackend.userDenyMessage(nav, "METACHAR(;)");
        assertTrue(m, m.startsWith("打开地图导航被手机上的系统特权守护拦下了"));
        assertTrue(m, m.contains("旧写法") && m.contains("更新"));
        String w = ShellBackend.userDenyMessage(nav, "PROG_NOT_WHITELISTED(foo)");
        assertTrue(w, w.contains("白名单") && w.contains("PROG_NOT_WHITELISTED"));
        assertTrue(ShellBackend.userDenyMessage(null, "SIG_MISMATCH").contains("身份"));
    }
}
