package com.onesay.assistant.v2.util;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** [2026-10-11] R7 真机复核后的修正：SEC-5 复合句换成本机已装的 App（没装微信）。 */
public class R7FollowupTest {

    private static String script() throws Exception {
        File f = new File("acceptance/device_acceptance.sh");
        if (!f.exists()) f = new File("../acceptance/device_acceptance.sh");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void qqMailCompoundSentenceBehavesLikeWechat() {
        for (String app : new String[]{"微信", "QQ邮箱"}) {
            String q = "看看屏幕上写的什么然后打开" + app;
            assertTrue(q, DeixisResolver.isCompoundTask(q));
            TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline());
            assertTrue(q + " → " + d, d.useAgentLoop);
        }
    }

    @Test public void sec5AppDefaultsToWechatWhenUndetectable() throws Exception {
        // 桩里 ash 什么都不返回 → 保持旧句子（微信）
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "sec5_panel", "VARIANT=debug",
                "[DEIXIS] 复合任务（读屏 + 下一步动作），不接管\n[AGENT-LOOP] 分诊判定交给 AgentLoop: x\napp.launch（tainted）\n", "");
        assertEquals(r.toString(), "PASS", r.get("SEC-5d"));
        assertTrue(r.get("__calls__"), r.get("__calls__").contains("panel_query 看看屏幕上写的什么然后打开微信"));
        r = AcceptanceScriptRound5Test.runModule(script(), "SEC5_APP=QQ邮箱; sec5_panel", "VARIANT=debug", "", "");
        assertTrue(r.get("__calls__"), r.get("__calls__").contains("panel_query 看看屏幕上写的什么然后打开QQ邮箱"));
    }
}
