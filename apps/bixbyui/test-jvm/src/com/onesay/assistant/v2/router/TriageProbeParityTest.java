package com.onesay.assistant.v2.router;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * 2026-10-09 D-2a（用户拍板 B）：删除旧分诊探针 TriageV2，{@code --es triage} 改打印现行 TriageDecider；
 * 设备自测 V2SelfTest 6c 段改查现行决策表。这里在 JVM 上守住同一张表（设备自测与 JVM 结论一致）和接线。
 */
public class TriageProbeParityTest {

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), StandardCharsets.UTF_8);
    }

    /** 与 V2SelfTest 6c / 杂项段逐条一致。 */
    static final Object[][] SELFTEST_TABLE = {
            {"获取今日华尔街日报", TriageDecider.Label.AMBIGUOUS},
            {"查一下今天的新闻", TriageDecider.Label.ASK},
            {"今天天气怎么样", TriageDecider.Label.ASK},
            {"比特币现在多少钱", TriageDecider.Label.ASK},
            {"打开蓝牙", TriageDecider.Label.DO_ACT},
            {"把亮度调到最低", TriageDecider.Label.DO_ACT},
            {"发短信给张三", TriageDecider.Label.DO_ACT},
            {"嗯", TriageDecider.Label.AMBIGUOUS},
            {"嗨比西比", TriageDecider.Label.AMBIGUOUS},
            {"那个那个", TriageDecider.Label.AMBIGUOUS},
            {"你好", TriageDecider.Label.ASK},
            {"新建笔记输入一段内容保存", TriageDecider.Label.DO_ACT},
            {"打开应用商店下载微信", TriageDecider.Label.DO_ACT},
    };

    @Test public void selfTestTableMatchesCurrentDecider() throws Exception {
        String st = src("app/src/com/onesay/assistant/v2/test/V2SelfTest.java");
        for (Object[] c : SELFTEST_TABLE) {
            TriageDecider.Decision d = TriageDecider.decide((String) c[0], TriageDecider.Signals.offline());
            assertEquals((String) c[0], c[1], d.label);
            if (d.label == TriageDecider.Label.AMBIGUOUS) assertFalse("垃圾句不进 AgentLoop: " + c[0], d.useAgentLoop);
            assertTrue("V2SelfTest 里有这条: " + c[0], st.contains("\"" + c[0] + "\"") && st.contains("TriageDecider.Label." + c[1]));
        }
    }

    @Test public void triageV2IsGoneAndProbeUsesCurrentDecider() throws Exception {
        File old = new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/router/TriageV2.java");
        assertFalse("TriageV2.java 已删除", old.exists());
        String trr = src("app/src/com/onesay/assistant/v2/test/TestRunReceiver.java");
        assertFalse(trr.contains("TriageV2."));
        assertTrue(trr.contains("[TRIAGE-PROBE]") && trr.contains("TriageDecider.decide(triageQuery"));
        assertTrue("探针用当前插件开关", trr.contains("triageSwitches(context)"));
        assertFalse(src("app/src/com/onesay/assistant/v2/test/V2SelfTest.java").contains("TriageV2."));
        String acc = src("acceptance/device_acceptance.sh");
        assertTrue("验收有 TRI-P", acc.contains("record TRI-P PASS"));
    }
}
