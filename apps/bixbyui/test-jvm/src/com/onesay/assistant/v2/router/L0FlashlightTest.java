package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ShellCommand;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 「手电筒」没命中 L0 快路径（rule#7-toggle-noun, l0=-）→ 退化进 AgentLoop。
 * 现在光名词「手电筒/闪光灯」→ torch.toggle，开/关说法 → torch.on / torch.off，全部 L0 直出、不进循环。
 */
public class L0FlashlightTest {

    static CapabilityRegistry reg;
    static L0RuleRouter l0;

    @BeforeClass public static void setUp() {
        reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        l0 = L0RuleRouter.forRegistry(reg);
    }

    static String sign(String q) {
        Invocation inv = l0.route(q);
        return inv == null ? null : inv.sign;
    }

    @Test public void bareNounTogglesFlashlight() {
        for (String q : new String[]{"手电筒", "闪光灯", "手电", "切换手电筒", "切换闪光灯", "手电筒切换", "帮我切换一下手电筒"}) {
            assertEquals(q, "torch.toggle", sign(q));
        }
    }

    @Test public void onOffPhrasingsHitExistingCaps() {
        for (String q : new String[]{"打开手电筒", "开手电筒", "手电筒打开", "帮我把手电筒打开", "打开一下手电筒",
                "打开闪光灯", "开闪光灯", "把闪光灯打开", "闪光灯开一下", "手电筒开一下", "开手电"}) {
            assertEquals(q, "torch.on", sign(q));
        }
        for (String q : new String[]{"关闭手电筒", "关掉手电筒", "关手电筒", "把手电筒关掉", "手电筒关闭",
                "关闭闪光灯", "关掉闪光灯", "关闪光灯", "把闪光灯关掉", "闪光灯关一下", "关手电"}) {
            assertEquals(q, "torch.off", sign(q));
        }
    }

    @Test public void triageNoLongerFallsIntoAgentLoop() {
        for (String q : new String[]{"手电筒", "闪光灯", "打开闪光灯", "关掉手电筒"}) {
            String s = sign(q);
            assertNotNull(q, s);
            TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(s, false, false));
            assertEquals(q + " → " + d, TriageDecider.Label.DO_ACT, d.label);
            assertFalse(q + " → " + d, d.useAgentLoop);
        }
        // 对照：没有 L0 信号时仍是 rule#7（规则层本身不变）
        TriageDecider.Decision off = TriageDecider.decide("手电筒", TriageDecider.Signals.offline());
        assertEquals("7-toggle-noun", off.rule);
    }

    @Test public void toggleCapIsSafeAndVerifiable() {
        Capability c = reg.describe("torch.toggle");
        assertNotNull(c);
        assertEquals(SideEffect.DEVICE_CONTROL, c.sideEffect);
        assertFalse("与 torch.on/off 同级：不需确认", c.confirm);
        // [ci-1114] 开源版手电筒不走 shell 模板（原厂商 torch-sec1 节点），executor=custom → SysActionBackend（CameraManager）
        assertNull("没有 shell 模板", c.cmdTemplate);
        assertEquals("custom", c.executorType);
        assertTrue(new com.onesay.assistant.v2.executor.backend.SysActionBackend().accepts(c, null));
        assertEquals("手电筒已打开", c.replyMap.get("on"));
        assertEquals("手电筒已关闭", c.replyMap.get("off"));
    }

    /** 回归：分诊全集（审计 70 句 + 补充）里与手电筒无关的句子，L0 绝不能被新模板抢走。 */
    @Test public void noRegressionOnTriageCorpus() {
        List<String> stolen = new ArrayList<String>();
        for (String[] c : TriageDeciderTest.all()) {
            String q = c[0];
            if (q.contains("手电") || q.contains("闪光灯")) continue;
            String s = sign(q);
            if (s != null && s.startsWith("torch.")) stolen.add(q + " → " + s);
        }
        for (String q : new String[]{"相机", "打开相机", "拍照", "闪光", "照亮", "灯", "开灯", "关灯", "台灯", "电筒", "手机"}) {
            String s = sign(q);
            if (s != null && s.startsWith("torch.") && !"照亮".equals(q)) stolen.add(q + " → " + s);
        }
        assertTrue(stolen.toString(), stolen.isEmpty());
    }
}
