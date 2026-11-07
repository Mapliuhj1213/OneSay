package com.onesay.assistant.v2.r2;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * r2/fixes 第 9 项：只给 debug 驱动（test 包）用的 31 个生产方法，第一句都是
 * {@code if (!BuildVariant.IS_DEBUG) return …;} —— release 里是空操作，以后就算有人从生产代码误调也不会生效。
 * JVM 按 release 口径编译（IS_DEBUG=false），所以这里还顺带跑一个真实调用确认返回的是空操作结果。
 */
public class R2DebugOnlyGateTest {

    static final String[] METHODS = (
            "conn/ConnSettings:putMcpServer conn/ConnSettings:removeMcpServer events/EventHooks:debugAddCharging "
          + "events/EventHooks:debugBaseline events/EventHooks:debugCharging events/EventHooks:debugResetCounters "
          + "events/EventHooks:debugRestart imgsave/ImageSaveHooks:debugSelect executor/MailBridge:clearConfig "
          + "executor/MailBridge:configLogInfo executor/MailBridge:debugExtractRawMime executor/MailBridge:debugParseProxy "
          + "executor/MailBridge:saveProxy notice/NoticeHooks:clearFakeEvents notice/NoticeHooks:injectFakeEvent "
          + "notice/NoticeHooks:rescanMail notice/NoticeHooks:simulateRestart notice/NoticeHooks:tickNow "
          + "roles/RoleHooks:debugCandidates s1/S1Hooks:debugCircle s1/S1Hooks:debugClearPickup s1/S1Hooks:debugClip "
          + "s1/S1Hooks:debugCollapse s1/S1Hooks:debugGeom s1/S1Hooks:debugInjectSms s1/S1Hooks:debugIsland "
          + "s1/S1Hooks:debugIslandDemo s2/S2Clock:setOffsetMs s2/S2Hooks:selfTestEngine summary/SummaryHooks:debugChip "
          + "wake/WakeService:daemonV8Seen notice/IslandSmartHooks:injectPrivacy notice/IslandSmartHooks:probePrivacy").split(" ");

    @Test public void everyDebugOnlyMethodStartsWithVariantGate() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        assertEquals(33, METHODS.length);   // integrate：+ IslandSmartHooks 两个 debug 注入
        for (String p : METHODS) {
            String f = "app/src/com/onesay/assistant/v2/" + p.split(":")[0] + ".java";
            String m = p.split(":")[1];
            String s = new String(Files.readAllBytes(new File(root, f).toPath()), StandardCharsets.UTF_8);
            Matcher mt = Pattern.compile("\\b" + m + "\\s*\\([^)]*\\)\\s*(?:throws [\\w., ]+)?\\{\\s*\\n\\s*if \\(!com\\.onesay\\.assistant\\.v2\\.BuildVariant\\.IS_DEBUG\\) return").matcher(s);
            assertTrue(f + " " + m + " 第一句要是 IS_DEBUG 门控", mt.find());
        }
    }

    @Test public void releaseCompiledCallIsNoOp() {
        assertFalse(com.onesay.assistant.v2.BuildVariant.IS_DEBUG);
        assertEquals("（仅 debug 包可用）", com.onesay.assistant.v2.executor.MailBridge.debugParseProxy("socks5://127.0.0.1:1080"));
    }
}
