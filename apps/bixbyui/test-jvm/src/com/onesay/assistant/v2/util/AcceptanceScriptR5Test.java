package com.onesay.assistant.v2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assume;
import org.junit.Test;

/** R5：roles 模块新增的补足 4 个 / 点未安装芯片用例的脚本守卫 + bash 实跑芯片计数函数。 */
public class AcceptanceScriptR5Test {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void rolesModuleHasFillToFourCases() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        for (String id : new String[]{"RO-1c-$trig", "RO-2c", "RO-10 ", "RO-10b", "RO-10c", "RO-10d"}) {
            assertTrue(id, s.contains("record " + id) || s.contains("record \"" + id.trim() + "\"") || s.contains("ask " + id.trim() + " "));
        }
        assertTrue(s.contains("未安装 → 只引导安装，不设默认"));
        String readme = src("acceptance/README.md");
        assertTrue(readme.contains("RO-10"));
        assertTrue(readme.contains("RO-2c"));
    }

    @Test public void chipCountParsesPanelLog() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        int a = s.indexOf("roles_chip_count() {");
        int b = s.indexOf("cmd_roles() {");
        assertTrue(a > 0 && b > a);
        String helpers = s.substring(a, b);
        String log = "10-08 21:00:00.000 I/Roles( 1): [ROLES] 选择芯片 role=taxi mode=apply [滴滴出行, 高德地图, 美团（未安装）, 花小猪打车（未安装）]\n";
        assertEquals("4|0", runBash(helpers, log, "echo \"$(roles_chip_count L)|$(roles_chip_count /dev/null)\""));
    }

    private static String runBash(String helpers, String log, String expr) throws Exception {
        File bash = new File("/bin/bash");
        Assume.assumeTrue("没有 bash 时跳过实跑部分", bash.canExecute());
        File dir = Files.createTempDirectory("accr5").toFile();
        Files.write(new File(dir, "h.sh").toPath(), helpers.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "L").toPath(), log.getBytes(StandardCharsets.UTF_8));
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "source h.sh; " + expr);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        pb.environment().put("LC_ALL", "C.UTF-8");
        Process p = pb.start();
        InputStream in = p.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        p.waitFor();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8).trim();
    }
}
