package com.onesay.assistant.v2.diag;

import static org.junit.Assert.*;

import org.junit.Test;

/** [diag] R5 抱怨式否定：否定的感知/送达谓词 = 在描述症状；有祈使标记就不是。 */
public class DiagComplaintTest {

    @Test public void complaintShapes() {
        for (String q : new String[]{"微信来消息不提醒", "别人打电话我听不到", "短信来了不响", "钉钉来消息手机没反应", "电话来了没声音", "字看不清"}) {
            assertTrue(q, DiagMatcher.isComplaint(q));
        }
        for (String q : new String[]{"以后不要提醒我喝水", "别提醒我了", "帮我把通知设成不弹出", "微信消息不要提醒", "给妈妈打电话", "提醒我明天交报告",
                "把铃声调成静音", "关掉通知"}) {
            assertFalse(q, DiagMatcher.isComplaint(q));
        }
    }

    @Test public void complaintBypassesActGateOnlyWhenTableOrDomainMatches() {
        // 动作词「提醒/打电话」在场也能判成症状
        assertNotNull(DiagMatcher.match("微信来消息不提醒", "微信来消息不提醒", true));
        assertEquals("volume_low", DiagMatcher.match("别人打电话我听不到", "别人打电话我听不到", true).id());
        // 抱怨但不在可诊断领域 → 仍不是诊断
        assertNull(DiagMatcher.match("快递没收到", "快递没收到", false));
        assertNull(DiagMatcher.match("他没听到", "他没听到", false));
    }
}
