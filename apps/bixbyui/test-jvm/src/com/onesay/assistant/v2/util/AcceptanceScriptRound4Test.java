package com.onesay.assistant.v2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assume;
import org.junit.Test;

/**
 * round4：真机第 4 轮 4 条假 FAIL 的验收脚本修正守卫（R3-1d / R3-1e / S2-M8c 非交互记 MANUAL；
 * S2-H2（报告里写作 S2-0b）环境感知；CA-0 清正缓存）。脚本文本断言 + 用 bash 实跑候选解析函数。
 */
public class AcceptanceScriptRound4Test {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void manualTapItemsAreManualWhenNonInteractive() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        assertTrue(s.contains("manual_tap() {"));
        assertTrue(s.contains("record \"$id\" MANUAL \"人工点卡项"));
        assertTrue("R3-1d", s.contains("if manual_tap R3-1d "));
        assertTrue("R3-1e", s.contains("if manual_tap R3-1e "));
        assertTrue("S2-M8c", s.contains("if manual_tap S2-M8c "));
        assertFalse("旧写法：pause 之后无条件判定 S2-M8c", s.contains("  pause \"这次点【确认/允许】\"\n"));
    }

    @Test public void cacheZeroClearsPositive() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        assertTrue(s.contains("recv \"--es cache_unpos '$s'\""));
        String r = src("app/src/com/onesay/assistant/v2/test/TestRunReceiver.java");
        assertTrue(r.contains("getStringExtra(\"cache_unpos\")"));
        assertTrue(r.contains(".clearPositive(unpos)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/router/L1SkillRouter.java").contains("mCache.clearPositive(query)"));
    }

    @Test public void rideRoleIsEnvironmentAware() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        assertTrue(s.contains("role_installed_count \"$rc\" taxi"));
        assertTrue(s.contains("SKIP(env)"));
        // 用 bash 实跑解析函数（候选日志格式 = RoleCandidate.toString）
        int a = s.indexOf("role_cands_load() {");
        int b = s.indexOf("cmd_s2() {");
        assertTrue(a > 0 && b > a);
        String helpers = s.substring(a, b);
        String log = "10-08 18:00:00.000 I/RolesTestDriver( 1234):   taxi probe=null → NONE_INSTALLED [didi]\n"
                + "10-08 18:00:00.000 I/RolesTestDriver( 1234):     [didi[com.sdu.didi.psnger 未安装 [seed]], "
                + "amap[com.autonavi.minimap [seed, installed] mined=3], meituan[com.sankuai.meituan 未安装 [seed]]]\n"
                + "10-08 18:00:00.000 I/RolesTestDriver( 1234):   nav probe=geo → SINGLE amap\n"
                + "10-08 18:00:00.000 I/RolesTestDriver( 1234):     [baidu[com.baidu.BaiduMap 未安装 [seed]]]\n";
        assertEquals("1|no|yes|0", runBash(helpers, log,
                "echo \"$(role_installed_count L taxi)|$(role_app_installed L taxi didi && echo yes || echo no)|"
                        + "$(role_app_installed L taxi amap && echo yes || echo no)|$(role_installed_count L nav)\""));
    }

    private static String runBash(String helpers, String log, String expr) throws Exception {
        File bash = new File("/bin/bash");
        Assume.assumeTrue("没有 bash 时跳过实跑部分", bash.canExecute());
        File dir = Files.createTempDirectory("acc4").toFile();
        File h = new File(dir, "h.sh");
        File l = new File(dir, "L");
        Files.write(h.toPath(), helpers.getBytes(StandardCharsets.UTF_8));
        Files.write(l.toPath(), log.getBytes(StandardCharsets.UTF_8));
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
