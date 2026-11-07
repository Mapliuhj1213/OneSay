package com.onesay.assistant.v2.r2;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.wake.CaptureSessionPolicy;
import com.onesay.assistant.v2.wake.WakeTuning;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * r2/fixes 第 12 项：隐私自检「上传窗起点规则」按实际生效的规则描述，不再写死「唤醒时刻 + 1100ms」。
 * R14 起按键唤醒 +300、短提示音 +450、长应答音 +1100；D1 按键预录从唤醒那一刻起补，绝不早于唤醒。
 */
public class R2PrivacyReportTextTest {

    static String rule(boolean shortChime, boolean preRoll, long preMs) {
        return CaptureSessionPolicy.captureRuleText(shortChime, preRoll, preMs, 300, 450, 1100);
    }

    @Test public void defaultConfigDescribesEachSource() {
        String r = rule(false, true, 300);
        assertTrue(r, r.contains("一律晚于唤醒时刻"));
        assertTrue(r, r.contains("唤醒词唤醒 +1100ms（长应答音模式"));
        assertTrue(r, r.contains("按键唤醒 +300ms"));
        assertTrue(r, r.contains("最多补 300ms") && r.contains("绝不早于唤醒"));
        assertFalse(r.contains("唤醒时刻 + 1100ms"));
    }

    @Test public void shortChimeAndPreRollOff() {
        String r = rule(true, false, 300);
        assertTrue(r, r.contains("唤醒词唤醒 +300ms（按下即听·短提示音模式，唤醒词与应答音不进缓冲"));
        assertTrue(r, r.contains("按键唤醒 +300ms，提示音期间预录关"));
        assertFalse(r.contains("最多补"));
        assertTrue("preroll_ms=0 等于不预录", rule(false, true, 0).contains("预录关"));
        assertTrue("预录长度描述不超过按键起点", rule(false, true, 5000).contains("最多补 300ms"));
    }

    @Test public void reportUsesTheRealPolicy() throws Exception {
        // WakeService 的 <clinit> 碰 Looper，JVM 里加载不了；用源码核对：文案走 captureRuleText，参数与算起点的是同一组常量
        String w = new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/wake/WakeService.java").toPath()), StandardCharsets.UTF_8);
        int a = w.indexOf("public static String privacyReport()");
        String body = w.substring(a, w.indexOf("return sb.toString();", a));
        assertTrue(body.contains("CaptureSessionPolicy.captureRuleText(shortChime, preRoll, preMs,"));
        assertTrue(body.contains("WAKE_LOCAL_GUARD_MS, WakeTuning.SHORT_CHIME_CAPTURE_FROM_MS, CAPTURE_FROM_OFFSET_MS)"));
        assertTrue(w.contains("WakeTuning.SHORT_CHIME_CAPTURE_FROM_MS, CAPTURE_FROM_OFFSET_MS);"));   // captureFromOffsetMs 同一组
        assertFalse(body.contains("唤醒时刻 + 1100ms"));
        assertTrue(w.contains("public static final long CAPTURE_FROM_OFFSET_MS = 1100;"));
        assertTrue(w.contains("private static final long WAKE_LOCAL_GUARD_MS = 300;"));
        assertEquals(450L, WakeTuning.SHORT_CHIME_CAPTURE_FROM_MS);
        String def = rule(WakeTuning.DEF_SHORT_CHIME != 0, WakeTuning.DEF_KEY_PREROLL != 0, WakeTuning.DEF_PREROLL_MS);
        assertTrue("默认配置（r2/sysux 按下即听）：唤醒词 +300 预录补齐、按键 +300 预录开", def.contains("唤醒词唤醒 +300ms（按下即听") && def.contains("从唤醒那一刻起预录") && def.contains("按键唤醒 +300ms，提示音期间预录开"));
    }

    @Test public void selfTestAssertsTheNewText() throws Exception {
        String s = new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/test/V2SelfTest.java").toPath()), StandardCharsets.UTF_8);
        assertFalse("自测不再断言写死的 1100ms 文案", s.contains("privacyReport().contains(\"1100ms\")"));
        assertTrue(s.contains("pr.contains(\"唤醒词唤醒 +\" + voiceFrom + \"ms\")"));
        assertTrue(s.contains("pr.contains(\"按键唤醒 +\" + keyFrom + \"ms\")"));
    }
}
