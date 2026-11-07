package com.onesay.assistant.v2.selftest;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.UnifiedExecutor;
import com.onesay.assistant.v2.router.ActionLexicon;
import com.onesay.assistant.v2.router.CapabilityRetriever;
import com.onesay.assistant.v2.router.IntentClassifier;
import com.onesay.assistant.v2.router.TimeContext;
import com.onesay.assistant.v2.wake.WakeWordStripper;
import org.junit.Test;

/**
 * 审计 R-3：把设备自测 V2SelfTest 里<b>纯逻辑、无副作用</b>的专项搬到 JVM（逐条照搬原断言），
 * V2SelfTest 对应段落删除、只留一行「已迁到 JVM」。真机上跑系统调用的部分仍留在 V2SelfTest。
 * 搬过来的段落：专项 1/4 唤醒词零泄漏、专项 2/4 Markdown 净化、[相对时间] 13.6.1–13.6.8。
 */
public class V2SelfTestPortedTest {

    /** 原 [专项 1/4]：剥离后不得残留唤醒词（含近音变体）。 */
    @Test public void wakeWordStripZeroLeak() {
        String[] leakCases = {"嗨比西比打开蓝牙", "比西比打开wifi", "海比西比调高亮度",
                "必须别打开微信", "黑必自比现在几点", "嗨比西调高亮度"};
        for (String c : leakCases) {
            String stripped = WakeWordStripper.strip(c);
            assertFalse("唤醒词泄漏: [" + c + "] -> [" + stripped + "]", WakeWordStripper.containsWakeWord(stripped));
        }
    }

    /** 原 [专项 2/4]：TTS 文本 Markdown 净化。 */
    @Test public void markdownCleanForTts() {
        String cleaned = UnifiedExecutor.cleanMarkdown("**2026年10月1日**，当前为 #测试环境 `指令` 已生效");
        assertFalse(cleaned, cleaned.contains("*"));
        assertFalse(cleaned, cleaned.contains("#"));
        assertFalse(cleaned, cleaned.contains("`"));
    }

    /** 原 13.6.1 / 13.6.2 / 13.6.3：相对时间换算。 */
    @Test public void relativeTimeArithmetic() {
        String[][] relCases = {{"40分钟后叫醒我", "40"}, {"两小时后叫我起床", "120"}, {"半小时后提醒我吃药", "30"},
                {"三十分钟后叫我", "30"}, {"十分钟后叫我起床", "10"}, {"十分钟后提醒我看直播", "10"},
                {"两小时后喊我起床", "120"}, {"一个半小时后提醒我关火", "90"}, {"两个半小时后叫我", "150"}};
        for (String[] rc : relCases) {
            TimeContext.Relative r = TimeContext.parseRelative(rc[0]);
            assertNotNull("未解析: " + rc[0], r);
            long want = Long.parseLong(rc[1]) * 60L;
            assertTrue(rc[0] + " 期望 " + want + "s 实际 " + r.deltaSeconds + "s", Math.abs(r.deltaSeconds - want) <= 1);
        }
        TimeContext.Relative rs = TimeContext.parseRelative("90秒后提醒我");
        assertNotNull(rs);
        assertEquals(90, rs.deltaSeconds);
        assertNull(TimeContext.parseRelative("打开蓝牙"));
        assertNull(TimeContext.parseRelative("把亮度调到最低"));
    }

    /** 原 13.6.4 / 13.6.4b：换算提示含绝对时刻与「不要反问」；普通指令不注入。 */
    @Test public void relativeTimeHint() {
        TimeContext.Relative rel = TimeContext.parseRelative("40分钟后叫醒我");
        String hint = TimeContext.hint("40分钟后叫醒我");
        assertNotNull(hint);
        assertTrue(hint, hint.contains("24 小时制"));
        assertTrue(hint, hint.contains("不要反问"));
        assertTrue(hint, hint.contains(rel.hhmm()));
        assertNull(TimeContext.hint("打开蓝牙"));
        assertEquals("打开蓝牙", TimeContext.withHint("打开蓝牙"));
    }

    /** 原 13.6.5：带相对时间的叫醒 / 提醒归入动作通道，不判 ASK。 */
    @Test public void wakeRemindIsAction() {
        for (String w : new String[]{"40分钟后叫醒我", "两小时后叫我起床", "半小时后提醒我吃药",
                "十分钟后叫我起床", "两小时后喊我起床", "一会儿提醒我喝水"}) {
            assertNotEquals(w, ActionLexicon.Intent.NONE, ActionLexicon.classify(w));
            assertNotEquals(w, IntentClassifier.Kind.ASK, IntentClassifier.classify(w, false));
        }
    }

    /** 原 13.6.6：相对时间的「后」不是时序连接词；真复合句仍判多步。 */
    @Test public void relativeTimeNotMultiAction() {
        assertFalse(IntentClassifier.looksLikeMultiAction("40分钟后叫醒我"));
        assertFalse(IntentClassifier.looksLikeMultiAction("十分钟后提醒我看直播"));
        assertTrue(IntentClassifier.looksLikeMultiAction("打开相机后截一张图"));
        assertTrue(IntentClassifier.looksLikeMultiAction("把亮度调到最低，然后告诉我几点"));
    }

    /** 原 13.6.7：闹钟接口进 Top-12 召回。 */
    @Test public void alarmInTopK() {
        boolean found = false;
        for (Capability c : CapabilityRetriever.getTopK("40分钟后叫醒我", 12)) {
            if ("clock.alarm.set".equals(c.sign)) { found = true; break; }
        }
        assertTrue("clock.alarm.set 未进 Top-12", found);
    }

    /** 原 13.6.8：相对时间指令不落缓存；稳定指令仍可缓存。 */
    @Test public void timeSensitiveCacheGate() {
        for (String pc : new String[]{"40分钟后叫醒我", "两小时后叫我起床", "半小时后提醒我吃药"}) {
            assertTrue(pc, IntentClassifier.isTimeSensitive(pc));
        }
        assertFalse(IntentClassifier.isTimeSensitive("把亮度调到最低"));
        assertFalse(IntentClassifier.isTimeSensitive("打开蓝牙"));
    }

    /** 搬走的段落已从设备自测删除（不重复维护两份）；R-1 改名后旧名不再出现在代码里。 */
    @Test public void portedSegmentsRemovedAndRenameDone() {
        String st = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/test/V2SelfTest.java");
        assertFalse(st.contains("13.6.1 相对时间"));
        assertFalse(st.contains("String[] leakCases"));
        assertTrue(st.contains("已迁到 JVM（V2SelfTestPortedTest"));
        assertTrue(new java.io.File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/router/CapabilityRetriever.java").exists());
        assertFalse(new java.io.File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/router/TriageEngine.java").exists());
    }
}
