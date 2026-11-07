package com.onesay.assistant.v2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import org.junit.Assume;
import org.junit.Test;

/**
 * [round7] 第 4 项：10-10 真机复验暴露的 3 处验收脚本问题。
 * <ul>
 *   <li>RO-5：AUTOTAP 拿占位文案「未安装 …，点此安装」去找按钮，且真实节点 bounds=[0,0][0,0] → 改按正则找真实节点 + 屏幕下沿兜底；</li>
 *   <li>IL-F2：全屏前台用桌面（本机 policy_control 排除了桌面，回桌面即退出全屏）→ 改用设置页；</li>
 *   <li>IL-19：排在 IL-19b 真横滑之后被 60 s 暂避挡住 → 挪到前面 + il_untuck。</li>
 * </ul>
 */
public class AcceptanceScriptRound7Test {

    static String root() {
        return System.getProperty("bixby.root", ".");
    }

    static String script() throws Exception {
        return new String(Files.readAllBytes(new File(root(), "acceptance/device_acceptance.sh").toPath()), StandardCharsets.UTF_8);
    }

    static String fn(String s, String name) {
        int a = s.indexOf("\n" + name + "() {");
        int b = s.indexOf("\n}\n", a);
        return s.substring(a + 1, b + 3);
    }

    /** 跑脚本里的 pick_install_hint_xy（真 bash + python3），stdin = dump。 */
    static String pick(String dump, String screenH) throws Exception {
        File bash = new File("/bin/bash");
        Assume.assumeTrue("没有 bash 时跳过", bash.canExecute());
        File dir = Files.createTempDirectory("acc7").toFile();
        File h = new File(dir, "h.sh");
        Files.write(h.toPath(), fn(script(), "pick_install_hint_xy").getBytes(StandardCharsets.UTF_8));
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "source h.sh; pick_install_hint_xy " + screenH);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        pb.environment().put("LC_ALL", "C.UTF-8");
        Process p = pb.start();
        OutputStream os = p.getOutputStream();
        os.write(dump.getBytes(StandardCharsets.UTF_8));
        os.close();
        InputStream in = p.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        p.waitFor();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    static String fixture() throws Exception {
        return new String(Files.readAllBytes(new File(root(), "test-jvm/fixtures/acceptance/ro5_install_hint_dump_20261010.xml").toPath()),
                StandardCharsets.UTF_8);
    }

    @Test public void ro5PicksRealHintAndFallsBackBelowChips() throws Exception {
        String d = fixture();
        assertTrue("夹具就是真机 dump：真实文案 + 空 bounds", d.contains("text=\"未安装 曹操出行，点此安装\"") && d.contains("bounds=\"[0,0][0,0]\""));
        // 真机：芯片行下沿 2090、屏高 2220 → 点 (540, 2155)，落在截图里的提示条 2130–2215 之间
        assertEquals("540 2155 fallback", pick(d, "2220"));
        // 节点 bounds 有效 → 点中心
        assertEquals("540 2172 node", pick(d.replace("bounds=\"[0,0][0,0]\"", "bounds=\"[0,2130][1080,2215]\""), "2220"));
        // 不知道屏高、窗口下沿又紧贴芯片行 → 宁可不点（不能点到「打开相机」等芯片）
        assertEquals("", pick(d, ""));
        // 没有安装提示 → 不点
        assertEquals("", pick(d.replace("未安装 曹操出行，点此安装", "别的文字").replace("安装提示：未安装", "别的"), "2220"));
    }

    @Test public void ro5NoLongerSearchesPlaceholderAndChecksPanelYield() throws Exception {
        String s = script();
        int a = s.indexOf("# ---------------- 未安装 → 安装提示 + 选中的链接");
        String ro5 = s.substring(a, s.indexOf("record RO-5 MANUAL", a));
        assertFalse("不再把占位文案当按钮文字", ro5.contains("pause \"点「未安装 …，点此安装」\""));
        assertTrue(ro5.contains("autotap_install_hint"));
        assertTrue("第 3 项：商店拉起后面板让位", ro5.contains("RO-5g") && ro5.contains("已拉起外部界面.*面板让位"));
        assertTrue(s.contains("pick_install_hint_xy \"$sh\""));
    }

    @Test public void fullscreenUsesAppWhereItSticks() throws Exception {
        String s = script();
        int a = s.indexOf("# ---------------- 3. 全屏（policy_control immersive.full=*，跑完还原）");
        int f2 = s.indexOf("record IL-F2 PASS", a);
        String fs = s.substring(a, f2);
        assertTrue("全屏前台用设置页", fs.contains("am start -a android.settings.SETTINGS"));
        assertFalse("IL-F1/F2 之前不能回桌面（桌面在 policy_control 里被排除，全屏不保持）", fs.contains("go_home"));
    }

    @Test public void il19RunsBeforeRealSwipeAndTuckIsReset() throws Exception {
        String s = script();
        String il = s.substring(s.indexOf("cmd_island() {"));
        int i19 = il.indexOf("il_fresh IL-19 ");
        int i18 = il.indexOf("il_fresh IL-18 ");
        int i19b = il.indexOf("record IL-19b PASS");
        assertTrue("IL-19 必须排在 IL-18 / IL-19b 真横滑之前", i19 > 0 && i19 < i18 && i18 < i19b);
        assertTrue("IL-19 前先清暂避", il.lastIndexOf("il_untuck", i19) > il.indexOf("il_fresh IL-17 "));
        assertTrue("IL-19b 之后清暂避", il.indexOf("il_untuck", i19b) > i19b && il.indexOf("il_untuck", i19b) < il.indexOf("全屏（policy_control", i19b));
        String un = fn(s, "il_untuck");
        assertTrue(un, un.contains("ilcmd key") && un.contains("il_reset_all"));

        // il_untuck 的语义在真 IslandModel 上成立：暂避中 → key 叫回 → 复位 → run 能到 COMPACT_TASK
        AcceptanceScriptIslandTest.Sim sim = new AcceptanceScriptIslandTest.Sim();
        sim.cmd("run", null, 1.2);
        sim.cmd("tuck", null, 1.2);
        sim.reset();
        sim.cmd("run", null, 1.2);
        assertFalse("不清暂避时 run 到不了紧凑（10-10 IL-19 SKIP 的原因）：" + sim.view(),
                Pattern.compile("^COMPACT_TASK\\(TASK\\) status=RUN").matcher(sim.view()).find());
        sim.cmd("key", "复位暂避", 0.5);
        sim.reset();
        sim.cmd("run", null, 1.2);
        assertTrue(sim.view(), Pattern.compile("^COMPACT_TASK\\(TASK\\) status=RUN").matcher(sim.view()).find());
    }

    @Test public void sec5PanelEntryJudgesDeixisRelease() throws Exception {
        String ok = "I/ScreenRef(1): [DEIXIS] 复合任务（读屏 + 下一步动作），不接管，交路由层 / AgentLoop: 看看屏幕上写的什么然后打开微信\n"
                + "I/FloatingSheetService(1): [AGENT-LOOP] 分诊判定交给 AgentLoop: 看看屏幕上写的什么然后打开微信\n"
                + "I/AgentLoop(1): [AGENT-LOOP] 步骤需确认，挂起等待用户决策: app.launch（tainted）\n";
        java.util.Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "sec5_panel", "VARIANT=debug", ok, "");
        assertEquals(r.toString(), "PASS", r.get("SEC-5d"));
        assertEquals("红卡是否出现要人看", "MANUAL", r.get("SEC-5e"));
        assertTrue(r.get("__calls__"), r.get("__calls__").contains("panel_query 看看屏幕上写的什么然后打开微信"));
        String old = "I/ScreenRef(1): [DEIXIS] 指代命中，接管: 看看屏幕上写的什么然后打开微信\n"
                + "I/ScreenRef(1): [DEIXIS] 消解结果: NO_MATCH/OPEN_URL ctx=null\n";
        assertEquals("10-10 真机日志必须判 FAIL", "FAIL",
                AcceptanceScriptRound5Test.runModule(script(), "sec5_panel", "VARIANT=debug", old, "").get("SEC-5d"));
        String noLaunch = ok.substring(0, ok.lastIndexOf("I/AgentLoop"));
        assertEquals("INFO", AcceptanceScriptRound5Test.runModule(script(), "sec5_panel", "VARIANT=debug", noLaunch, "").get("SEC-5d"));
    }
}
