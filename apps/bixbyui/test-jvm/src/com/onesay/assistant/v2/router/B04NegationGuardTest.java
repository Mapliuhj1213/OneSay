package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B04：否定 / 反悔 / 只是在问 → 快路径不接、决策表落 ASK（t20k router.negation 54 条复现）。 */
public class B04NegationGuardTest {

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

    /** 零 LLM 流水线（同 UnifiedRouter.triage）：返回 true = 会在本地执行。 */
    static boolean executesLocally(String q) {
        FollowupEdit.clearForTest();
        UnifiedRouter.Triage ft = FastPathTriage.triage(q, RES);
        if (ft != null) return true;
        Invocation l0 = FastPathTriage.l0WithCore(q, RES);
        TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(l0 != null ? l0.sign : null, false, false));
        switch (d.label) {
            case ASK: case CLARIFY: case DIAGNOSE: case AMBIGUOUS: return false;
            default: return !d.useAgentLoop && l0 != null;
        }
    }

    static final String[] MUST_NOT_EXECUTE = {
            "不许7点叫我", "先别7点叫我", "不用7点叫我", "我没说要7点叫我", "没让你7点30分叫我", "别给我7点30分叫我", "勿7点叫我",
            "呼叫10086，算了不用了", "删除软件微信，算了不用了", "通知音量调到5，算了不用了", "记录买牛奶，算了不用了",
            "拨打号码10086？不，算了", "媒体音量5？不，算了", "屏幕亮度50？不，算了",
            "你能打电话给10086吗？我只是问问", "你能亮度调到50吗？我只是问问",
            "卸载微信个屁", "记一下买牛奶个屁", "音乐音量调到5个屁",
            "别30秒后息屏", "别给我30秒后息屏",
            "打电话给10086要多久", "亮度调成50对手机有害吗", "打电话给10086会不会费电", "闹钟音量调到5的按钮在哪",
            "教我怎么7点叫我", "为什么会自动7点30分叫我", "关机吗", "卸载微信吗",
    };

    @Test public void negatedRetractedOrQuestionNeverExecutes() {
        StringBuilder bad = new StringBuilder();
        for (String q : MUST_NOT_EXECUTE) {
            assertNotNull(q, NegationGuard.reason(q));
            if (executesLocally(q)) bad.append(q).append(" | ");
        }
        assertEquals("", bad.toString());
    }

    @Test public void normalCommandsStillLocal() {
        String[] ok = {"7点叫我", "打开蓝牙", "呼叫10086", "媒体音量调到5", "能不能帮我锁屏呀", "关闭网络", "打开勿扰模式"};
        for (String q : ok) {
            assertNull(q, NegationGuard.reason(q));
        }
        // 取消类照旧放行（不是否定式命令）
        assertNull(NegationGuard.reason("不要提醒我了"));
        assertNull(NegationGuard.reason("别忘了明天8点提醒我开会"));
        assertNull(NegationGuard.reason("勿扰模式打开"));
        assertNull(NegationGuard.reason("能帮我关机吗"));
        assertNull(NegationGuard.reason("呼叫10086好吗"));
        assertNull(NegationGuard.reason("删掉闹钟可以吗"));
        assertNull(NegationGuard.reason("打电话给10086嘛"));
    }
}
