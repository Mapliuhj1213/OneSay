package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ExecutionResult;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ audit-J 6：模型照抄的「<不可信数据>…</不可信数据>」不出现在任何面向用户的出口；拆块流式也不漏半截；本地问答不被当成不可信。 */
public class AuditJUntrustedTagsTest {

    @Test public void stripVariants() {
        assertEquals("现在是北京时间 19:30。", UntrustedTags.strip("<不可信数据>现在是北京时间 19:30。</不可信数据>"));
        assertEquals("今天星期三。", UntrustedTags.strip("< 不可信数据 >今天星期三。</ 不可信数据 >"));
        assertEquals("今天星期三。", UntrustedTags.strip("<不可信数据 >今天星期三。</不可信数据 >"));
        assertEquals("好的", UntrustedTags.strip("＜不可信数据＞好的＜/不可信数据＞"));
        assertEquals("a\n\nb", UntrustedTags.strip("a\n<不可信数据>\n\n\nb"));
        assertEquals("1 < 2 很正常", UntrustedTags.strip("1 < 2 很正常"));
        assertNull(UntrustedTags.strip(null));
        assertFalse(UntrustedTags.has("没有标签"));
    }

    /** 逐字切块喂累计文本：任何一帧都不能出现「<不」「</不可信」这类半截。 */
    @Test public void streamingSplitChunksNeverLeak() {
        String full = "<不可信数据>今天是星期三。</不可信数据>";
        String acc = "";
        String last = "";
        for (int i = 0; i < full.length(); i++) {
            acc += full.charAt(i);
            String shown = UntrustedTags.stripStreaming(acc);
            assertFalse(acc + " → " + shown, shown.contains("<") || shown.contains("不可信"));
            assertTrue("不回缩", shown.startsWith(last) || last.startsWith(shown));
            last = shown;
        }
        assertEquals("今天是星期三。", last);
        assertEquals("比较 a<b", UntrustedTags.stripStreaming("比较 a<b"));
        assertEquals("结果", UntrustedTags.stripStreaming("结果 </不可"));
        assertEquals("结果 <html", UntrustedTags.stripStreaming("结果 <html"));
    }

    @Test public void memoryAndSpeechStripped() {
        ConversationMemory m = ConversationMemory.getInstance();
        m.clear();
        m.record("今天星期几", "<不可信数据>今天星期三。</不可信数据>", false);
        assertEquals("今天星期三。", m.snapshot().get(m.snapshot().size() - 1).answer);
        m.clear();
        assertEquals("今天星期三。", com.onesay.assistant.v2.util.SpeechText.forSpeech("<不可信数据>今天星期三。</不可信数据>", 200));
    }

    @Test public void agentResultStripped() {
        AgentLoop.Result r = new AgentLoop.Result("<不可信数据>现在 19:30</不可信数据>", new java.util.ArrayList<AgentLoop.Step>(), true, "done");
        assertEquals("现在 19:30", r.answer);
    }

    @Test public void ruleForbidsEchoingTags() {
        assertTrue(AgentLoop.UNTRUSTED_RULE.contains("绝不要输出"));
        // 包裹仍然存在（外部内容照包），只是出口剥掉
        assertTrue(AgentLoop.wrapUntrusted("x").startsWith(AgentLoop.UNTRUSTED_OPEN));
    }

    @Test public void localAnswersNotTainted() throws Exception {
        CapabilityRegistry reg = CapabilityRegistry.getInstance();
        for (String s : new String[]{"time.now", "clock.alarm.set", "alarm.query", "ask.reply"}) {
            Capability c = reg.describe(s);
            if (c == null) continue;
            ExecutionResult ok = ExecutionResult.success(c, new JSONObject(), "现在是北京时间 19:30。", true, "");
            assertFalse(s + " 本地结果不置污点", AgentLoop.taintsAfter(c, ok));
        }
        ConversationMemory.Turn t = new ConversationMemory.Turn("现在几点", "[执行回执] 查询当前系统时间（time.now）→ 成功", false);
        assertFalse("本地回执不包标签", t.isTainted());
    }
}
