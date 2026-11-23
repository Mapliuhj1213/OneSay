package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B09：句尾「谢谢」不把指令变成闲聊，也不进槽位（t20k router.metamorphic 143 条复现）。 */
public class B09ThanksTailTest {

    static L0RuleRouter L0;
    static final CompoundSplitter.Resolver RES = new CompoundSplitter.Resolver() {
        @Override public Invocation resolve(String c) {
            if (c == null || c.trim().isEmpty()) return null;
            Invocation i = L0.route(c);
            return i != null && SearchRouting.dropL0(i.sign, c) ? null : i;
        }
    };

    @BeforeClass public static void init() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        L0 = L0RuleRouter.forRegistry(reg);
    }

    /** 零 LLM 流水线（同 UnifiedRouter.triage）：本地执行返回 Invocation，否则 null。 */
    static Invocation local(String q) {
        FollowupEdit.clearForTest();
        UnifiedRouter.Triage ft = FastPathTriage.triage(q, RES);
        if (ft != null) return ft.l0;
        Invocation l0 = FastPathTriage.l0WithCore(q, RES);
        TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(l0 != null ? l0.sign : null, false, false));
        switch (d.label) {
            case ASK: case CLARIFY: case DIAGNOSE: case AMBIGUOUS: return null;
            default: return !d.useAgentLoop ? l0 : null;
        }
    }

    static void same(String base) {
        Invocation b = local(base);
        assertNotNull(base, b);
        for (String tail : new String[]{"，谢谢", " 谢谢", "谢谢", "，谢谢你", "，多谢", "。谢谢！", " thanks", "，谢谢谢谢"}) {
            Invocation v = local(base + tail);
            assertNotNull(base + tail, v);
            assertEquals(base + tail, b.sign, v.sign);
            assertEquals(base + tail, String.valueOf(b.args), String.valueOf(v.args));
        }
    }

    @Test public void commandsWithThanksStillRunLocally() {
        for (String q : new String[]{"关闭网络", "连接网络", "打开漫游流量", "闹钟声音多大", "删除软件微信", "打开蓝牙", "媒体音量调到5", "记一下买牛奶、鸡蛋和面包", "打开网页www.baidu.com"}) {
            same(q);
        }
    }

    @Test public void thanksAloneIsStillChat() {
        for (String q : new String[]{"谢谢", "谢谢你", "多谢", "不用谢谢", "谢谢谢谢"}) assertNull(q, local(q));
        assertEquals(TriageDecider.Label.ASK, TriageDecider.decide("谢谢", TriageDecider.Signals.offline()).label);
        assertEquals(TriageDecider.Label.ASK, TriageDecider.decide("谢谢你", TriageDecider.Signals.offline()).label);
    }

    @Test public void rawStripKeepsShortAndNegativeThanks() {
        assertEquals("删除软件微信", TriageDecider.stripTrailingThanksRaw("删除软件微信，谢谢"));
        assertEquals("谢谢", TriageDecider.stripTrailingThanksRaw("谢谢"));
        assertEquals("好谢谢", TriageDecider.stripTrailingThanksRaw("好谢谢"));
        assertEquals("不用谢谢", TriageDecider.stripTrailingThanksRaw("不用谢谢"));
        assertEquals("打开蓝牙", TriageDecider.stripTrailingThanksRaw("打开蓝牙 Thank You!"));
        assertEquals("记一下谢谢老师", TriageDecider.stripTrailingThanksRaw("记一下谢谢老师"));
    }
}
