package com.onesay.assistant.v2.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import org.junit.Test;

/** 2026-10-10 安装链分流：roles 模块的 RO-5f / RO-11a~h 用例守卫；日志正则对得上新日志格式；应用宝提法已去掉。 */
public class AcceptanceScriptInstallRegionTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    static String rolesModule() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        int a = s.indexOf("cmd_roles() {");
        int b = s.indexOf("\n}\n", a);
        assertTrue(a > 0 && b > a);
        return s.substring(a, b);
    }

    @Test public void casesPresent() throws Exception {
        String m = rolesModule();
        for (String id : new String[]{"RO-5f", "RO-11a", "RO-11b", "RO-11c", "RO-11d", "RO-11e", "RO-11f", "RO-11g", "RO-11h"}) {
            assertTrue(id, m.contains("record " + id + " "));
        }
        for (String id : new String[]{"RO-11d", "RO-11e", "RO-11f", "RO-11g", "RO-11h"}) {
            assertTrue(id + " 必须是 MANUAL（会断当前 VPN）", m.contains("record " + id + " MANUAL"));
        }
        assertTrue(m.contains("打开代理 App（会断开当前 VPN）"));
        assertFalse("roles 模块不再把应用宝当成预期结果", m.contains("应用宝详情") || m.contains("应用宝网页") || m.contains("应用宝/"));
        String readme = src("acceptance/README.md");
        assertTrue(readme.contains("RO-11d ~ 11h"));
    }

    /** 脚本里的 grep 正则与生产日志格式一致（RoleHooks / RolesTestDriver 的 Log 文本）。 */
    @Test public void regexMatchesProductionLogFormat() throws Exception {
        String clickLog = "I/Roles: [ROLES] 安装链 pkg=com.huaxiaozhu.rider region=cn → OPEN GALAXY samsungapps://ProductDetail/com.huaxiaozhu.rider via com.sec.android.app.samsungapps [cn→GALAXY]";
        assertTrue(Pattern.compile("安装链 pkg=[^ ]+ region=cn → OPEN GALAXY samsungapps://ProductDetail/").matcher(clickLog).find());
        String hint = "[ROLES] 显示安装提示 pkg=com.t3go.passenger region=cn 预选链接=GALAXY samsungapps://ProductDetail/com.t3go.passenger via x";
        assertTrue(Pattern.compile("\\[ROLES\\] 显示安装提示 pkg=[^ ]+ region=[a-z]+ 预选链接=[A-Z]+ [^ ]+").matcher(hint).find());
        String drv = "[ROLES-TEST] install pkg=com.ubercab installed=false region=intl 预选(不联网)=PLAY market://details?id=com.ubercab via com.android.vending";
        assertTrue(Pattern.compile("install pkg=com\\.ubercab .*region=intl").matcher(drv).find());
        String hooks = src("app/src/com/onesay/assistant/v2/roles/RoleHooks.java");
        assertTrue(hooks.contains("\"[ROLES] 安装链 pkg=\" + pkg + \" region=\" + region.id() + \" → \""));
        assertTrue(hooks.contains("\"[ROLES] 显示安装提示 pkg=\" + pkg + \" region=\""));
        String d = src("app/src/com/onesay/assistant/v2/test/RolesTestDriver.java");
        assertTrue(d.contains("\" region=\" + rg.id() + \" 预选(不联网)=\""));
    }
}
