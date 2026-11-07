package com.onesay.assistant.v2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assume;
import org.junit.Test;


/**
 * round5：Galaxy Note 8 真机第 5 轮（PASS 87 / FAIL 11 / MANUAL 39）11 条假 FAIL 的验收脚本守卫。
 * <ul>
 *   <li>（IL 部分 2026-10-09b 随灵动岛 v3 重写，移到 AcceptanceScriptIslandTest：v3 的 IL 正则用真 IslandModel 同口径日志逐条实测）；</li>
 *   <li>S1-CP-4/5/6、S1-CL-5、S1-CI-1/3/4（及依赖 CI-1 的 CI-2）非交互记 MANUAL —— 用 bash 实跑 cmd_s1；</li>
 *   <li>SU-4/SU-6：采集到的前台是 android / 本 App → MANUAL + 「请把真实文章页摆到前台再跑」—— 用 bash 实跑 cmd_summary。</li>
 * </ul>
 */
public class AcceptanceScriptRound5Test {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    // ================================================================== S1：非交互记 MANUAL

    @Test public void s1HumanTapCasesAreManualWhenNonInteractive() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        for (String id : new String[]{"S1-CP-4", "S1-CP-5", "S1-CP-6", "S1-CL-5"}) {
            assertTrue(id, s.contains("if manual_tap " + id + " "));
        }
        Map<String, String> r = runModule(s, "cmd_s1", "VARIANT=debug", "", "");
        for (String id : new String[]{"S1-CP-4", "S1-CP-5", "S1-CP-6", "S1-CL-5", "S1-CI-1", "S1-CI-2", "S1-CI-3", "S1-CI-4"}) {
            assertEquals(id + " 非交互应记 MANUAL；全部结果：" + r, "MANUAL", r.get(id));
        }
        // 非交互不发「圈一下」（全屏遮罩会挡后面的模块）
        String calls = r.get("__calls__");
        assertFalse(calls, calls.contains("panel_query 圈一下"));
        assertFalse(calls, calls.contains("panel_query 框选"));
    }

    @Test public void s1InteractiveStillJudgesAutomatically() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        // 交互模式：manual_tap 只是 pause 后返回 0，判定照旧（日志里有 → PASS）
        String log = "[ISLAND] tapMain → [RENDER] mode=ISLAND\n[ISLAND] 点面板外区域 → 任务转后台，面板收成岛\n"
                + "[ISLAND] stop → [RENDER, CANCEL_TASK] mode=ISLAND\n[CLIP] 芯片点击 → screen.nav.to（经 ExecutionGate）\n"
                + "[CIRCLE] 区域上下文 texts=2\n消解结果: RESOLVED/NAVIGATE\n圈选裁剪 120x80\n用户取消圈选\n";
        Map<String, String> r = runModule(s, "cmd_s1", "VARIANT=debug", log, "interactive");
        for (String id : new String[]{"S1-CP-4", "S1-CP-5", "S1-CP-6", "S1-CL-5", "S1-CI-1", "S1-CI-2", "S1-CI-3", "S1-CI-4"}) {
            assertEquals(id + "；全部结果：" + r, "PASS", r.get(id));
        }
    }

    // ================================================================== SU：前台被面板/系统抢了

    @Test public void summaryForegroundStolenIsManualWithHint() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        assertTrue(s.contains("SU_HINT=\"请把真实文章页摆到前台再跑\""));
        assertTrue("先收起面板并等前台", s.contains("su_fg_ready || c_yel"));
        for (String app : new String[]{"android", "com.onesay.assistant"}) {
            String log = "[SUMMARY] 开始 | enabled=true\n[SUMMARY] 采集 812ms app=" + app + " source=uiautomator 节点=12 文字行=3 redacted=false\n"
                    + "[SUMMARY] 输入 chars=20 truncated=false\n[SUMMARY] 屏幕文字太少 → 截图 + 视觉模型\n";
            Map<String, String> r = runModule(s, "cmd_summary", "VARIANT=debug", log, "");
            assertEquals(app + " SU-4：" + r, "MANUAL", r.get("SU-4"));
            assertEquals(app + " SU-6：" + r, "MANUAL", r.get("SU-6"));
            assertTrue(r.get("SU-4#note"), r.get("SU-4#note").contains("请把真实文章页摆到前台再跑"));
            assertTrue(r.get("SU-6#note"), r.get("SU-6#note").contains("请把真实文章页摆到前台再跑"));
        }
        // 真文章页：照旧判定（PASS / FAIL 不受影响）
        String ok = "[SUMMARY] 采集 812ms app=com.sec.android.app.sbrowser source=uiautomator 节点=40 文字行=9 redacted=false 敏感=验证码\n"
                + "[SUMMARY] 完成 via=text OK gist=30字 bullets=4\n";
        Map<String, String> r = runModule(s, "cmd_summary", "VARIANT=debug", ok, "");
        assertEquals(r.toString(), "PASS", r.get("SU-4"));
        assertEquals(r.toString(), "PASS", r.get("SU-6"));
        String bad = "[SUMMARY] 采集 812ms app=com.sec.android.app.sbrowser source=uiautomator 节点=40 文字行=9 redacted=false\n"
                + "[SUMMARY] 文字路径 900ms 输出 80 字\n[SUMMARY] 完成 via=text OK gist=30字 bullets=7\n";
        r = runModule(s, "cmd_summary", "VARIANT=debug", bad, "");
        assertEquals("真页面上要点数不对仍是 FAIL：" + r, "FAIL", r.get("SU-4"));
        assertEquals("真页面上验证码没拦仍是 FAIL：" + r, "FAIL", r.get("SU-6"));
    }

    @Test public void summaryForegroundHelpersParseDumpsys() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        int a = s.indexOf("su_fg_pkg() {");
        int b = s.indexOf("su_fg_ready() {");
        assertTrue(a > 0 && b > a);
        String h = "PKG=com.onesay.assistant\n" + s.substring(a, b);
        String[][] cases = {
                {"  mCurrentFocus=Window{4c1d2e u0 com.sec.android.app.sbrowser/com.sec.android.app.sbrowser.SBrowserMainActivity}", "com.sec.android.app.sbrowser ok"},
                {"  mCurrentFocus=Window{4c1d2e u0 com.onesay.assistant}", "com.onesay.assistant bad"},
                {"  mCurrentFocus=Window{1 u0 android/com.android.internal.app.ResolverActivity}", "android bad"},
                {"  mCurrentFocus=null", " bad"},
        };
        for (String[] c : cases) {
            String out = runBash(h + "\nash() { printf '%s\\n' '" + c[0] + "'; }\n",
                    "p=\"$(su_fg_pkg)\"; su_bad_fg \"$p\" && echo \"$p bad\" || echo \"$p ok\"");
            assertEquals(c[0], c[1].trim(), out.trim());
        }
    }

    // ================================================================== bash 实跑模块（全部 adb 相关函数打桩）

    /**
     * 实跑 cmd_xxx：adb/截图/sleep 全部打桩，lc_save 写入给定的日志文本。返回 用例→结果（同一用例取最后一次），
     * 另有「用例#note」= 备注，「__calls__」= 调用过的 panel_query。
     */
    static Map<String, String> runModule(String script, String fn, String env, String log, String mode) throws Exception {
        int a = script.indexOf("\nusage() {");
        String body = script.substring(0, a);
        // 去掉 set -u 之外的顶层执行：脚本顶层只有定义和变量，最后的 case 在 usage 之后，已截掉
        String stubs = "\n"
                + "pick_device() { :; }\n detect_variant() { " + env + "; }\n"
                + "adb_run() { :; }\n ash() { :; }\n rsh() { :; }\n recv() { :; }\n"
                + "s1cmd() { :; }\n sucmd() { :; }\n ilcmd() { :; }\n"
                + "panel_query() { echo \"panel_query $1\" >> \"$CALLS\"; }\n panel_hide() { :; }\n panel_listen() { :; }\n"
                + "ready_probe() { :; }\n go_home() { :; }\n restart_app() { :; }\n shot() { :; }\n"
                + "lc_clear() { :; }\n lc_save() { cp \"$LOGSRC\" \"$RUN_DIR/$1.log\"; echo \"$RUN_DIR/$1.log\"; }\n"
                + "sleep() { :; }\n su_browser() { echo com.sec.android.app.sbrowser; }\n"
                + "c_yel() { :; }\n c_green() { :; }\n c_red() { :; }\n hdr() { :; }\n";
        if ("interactive".equals(mode)) {
            stubs += "NONINTERACTIVE=0\n pause() { :; }\n ask() { record \"$1\" SKIP stub; }\n confirm() { return 1; }\n";
        } else {
            stubs += "NONINTERACTIVE=1\n";
        }
        String out = runBash(body + stubs,
                "RUN_DIR=\"$PWD/run\"; mkdir -p \"$RUN_DIR\"; RESULTS=\"$PWD/results.tsv\"; : > \"$RESULTS\"; CALLS=\"$PWD/calls\"; : > \"$CALLS\";"
                        + " LOGSRC=\"$PWD/L\"; printf '%s' \"$LOG_TEXT\" > \"$LOGSRC\"; " + fn + " >/dev/null 2>&1;"
                        + " cat \"$RESULTS\"; echo '@@CALLS@@'; cat \"$CALLS\"",
                log);
        Map<String, String> r = new LinkedHashMap<String, String>();
        String[] parts = out.split("@@CALLS@@", 2);
        for (String line : parts[0].split("\n")) {
            String[] c = line.split("\t");
            if (c.length >= 3) {
                r.put(c[1], c[2]);
                r.put(c[1] + "#note", c.length > 3 ? c[3] : "");
            }
        }
        r.put("__calls__", parts.length > 1 ? parts[1] : "");
        return r;
    }

    private static String runBash(String helpers, String expr) throws Exception {
        return runBash(helpers, expr, "");
    }

    private static String runBash(String helpers, String expr, String logText) throws Exception {
        File bash = new File("/bin/bash");
        Assume.assumeTrue("没有 bash 时跳过实跑部分", bash.canExecute());
        File dir = Files.createTempDirectory("acc5").toFile();
        File h = new File(dir, "h.sh");
        Files.write(h.toPath(), helpers.getBytes(StandardCharsets.UTF_8));
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "source h.sh; " + expr);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("LOG_TEXT", logText);
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
