package com.onesay.assistant.v2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Test;

/**
 * 2026-10-10：SU-4 在「文字路径只读到地址栏 → 改走看图（98d430b）→ 看图成功」时判 PASS；真失败仍 FAIL。
 * 用 bash 实跑 cmd_summary（打桩同 AcceptanceScriptRound5Test.runModule）。
 */
public class AcceptanceScriptSu4VisionTest {

    static final String CAP = "[SUMMARY] 采集 812ms app=com.sec.android.app.sbrowser source=uiautomator 节点=14 文字行=2 redacted=false\n"
            + "[SUMMARY] 输入 chars=0 lines=0 truncated=false\n";
    static final String EMPTY = "[SUMMARY] 文字路径 1200ms 输出 2 字 → EMPTY gist=0字 bullets=0\n[SUMMARY] 文字路径 EMPTY → 改走截图 + 视觉模型\n"
            + "[SUMMARY] 屏幕文字太少 → 截图 + 视觉模型\n";
    static final String FEW = "[SUMMARY] 屏幕文字太少 → 截图 + 视觉模型\n";

    static String script() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, "acceptance/device_acceptance.sh").toPath()), StandardCharsets.UTF_8);
    }

    static Map<String, String> run(String log) throws Exception {
        return AcceptanceScriptRound5Test.runModule(script(), "cmd_summary", "VARIANT=debug", log, "");
    }

    @Test public void visionAfterAddressBarOnlyPasses() throws Exception {
        Map<String, String> r = run(CAP + EMPTY + "[SUMMARY] 视觉路径 5300ms 输出 180 字 → OK gist=28字 bullets=4\n[SUMMARY] 完成 via=vl OK gist=28字 bullets=4\n");
        assertEquals(r.toString(), "PASS", r.get("SU-4"));
        assertTrue(r.get("SU-4#note"), r.get("SU-4#note").contains("改走看图"));
        r = run(CAP + FEW + "[SUMMARY] 完成 via=vl OK gist=20字 bullets=3\n");
        assertEquals("文字太少直接看图也算：" + r, "PASS", r.get("SU-4"));
    }

    @Test public void genuineFailuresStillFail() throws Exception {
        assertEquals("看图要点数不对", "FAIL", run(CAP + EMPTY + "[SUMMARY] 完成 via=vl OK gist=28字 bullets=7\n").get("SU-4"));
        assertEquals("看图失败", "FAIL", run(CAP + EMPTY + "[SUMMARY] 视觉模型失败: timeout\n").get("SU-4"));
        assertEquals("看图判敏感（文章页不该）", "FAIL",
                run(CAP + EMPTY + "[SUMMARY] 视觉输出含敏感信息（scrubbed=1）→ 按敏感页拒绝\n").get("SU-4"));
        assertEquals("没有改走看图的原因却出 via=vl", "FAIL", run(CAP + "[SUMMARY] 完成 via=vl OK gist=28字 bullets=4\n").get("SU-4"));
        assertEquals("什么都没完成", "FAIL", run(CAP + EMPTY).get("SU-4"));
    }

    @Test public void textPathUnchanged() throws Exception {
        assertEquals("PASS", run(CAP + "[SUMMARY] 完成 via=text OK gist=30字 bullets=4\n").get("SU-4"));
        assertEquals("FAIL", run(CAP + "[SUMMARY] 完成 via=text OK gist=30字 bullets=6\n").get("SU-4"));
    }
}
