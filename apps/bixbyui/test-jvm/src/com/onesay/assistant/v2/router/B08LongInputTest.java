package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** ★ rel-1118 B08：分诊算术正则灾难性回溯 + 输入无长度上限（t20k router.robustness 7 条复现）。 */
public class B08LongInputTest {

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

    /** 与 UnifiedRouter.triage 的零 LLM 部分一致，返回耗时（ms）。 */
    static long pipeMs(String q) {
        long t0 = System.nanoTime();
        FollowupEdit.clearForTest();
        UnifiedRouter.Triage ft = FastPathTriage.triage(q, RES);
        if (ft == null) {
            Invocation l0 = FastPathTriage.l0WithCore(q, RES);
            TriageDecider.decide(q, new TriageDecider.Signals(l0 != null ? l0.sign : null, false, false));
        }
        return (System.nanoTime() - t0) / 1000000;
    }

    static String rep(String head, String unit, int len) {
        StringBuilder b = new StringBuilder(head);
        while (b.length() < len) b.append(unit);
        return b.toString();
    }

    @Test public void longChineseDigitsDoNotBacktrack() {
        pipeMs("打开蓝牙");   // 预热
        for (int len : new int[]{1000, 1999, 2000, 5000, 20000, 100000}) {
            long ms = pipeMs(rep("音量调到", "九", len));
            assertTrue("len=" + len + " 用了 " + ms + "ms", ms < 1500);
        }
    }

    @Test public void otherLongInputsAreFast() {
        Random r = new Random(100000);
        StringBuilder g = new StringBuilder();
        while (g.length() < 100000) g.append((char) (0x4e00 + r.nextInt(0x5000)));
        String[] in = {
                rep("", "打开蓝牙然后关闭蓝牙", 100000), g.toString(), rep("", "\u200b", 100000),
                rep("", "请", 100000) + "打开蓝牙", rep("", "1+", 100000),
        };
        for (String q : in) {
            long ms = pipeMs(q);
            assertTrue("用了 " + ms + "ms", ms < 1500);
        }
    }

    @Test public void tooLongGoesToModelNotLocal() {
        String q = rep("", "打开蓝牙然后关闭蓝牙", 3000);
        assertNull(FastPathTriage.triage(q, RES));
        assertNull(FastPathTriage.l0WithCore(q, RES));
        TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline());
        assertEquals(TriageDecider.Label.AMBIGUOUS, d.label);
        assertEquals("0-too-long", d.rule);
        assertEquals(TriageDecider.MAX_RULE_LEN, d.core.length());
        assertFalse(TriageDecider.tooLongForRules(rep("", "九", TriageDecider.MAX_RULE_LEN)));
        assertTrue(TriageDecider.tooLongForRules(rep("", "九", TriageDecider.MAX_RULE_LEN + 1)));
    }

    @Test public void arithmeticStillAsk() {
        for (String q : new String[]{"3加5等于几", "九加九", "12x3", "一百除以四", "7减2", "8乘9", "1米等于多少厘米", "5斤换算成公斤"}) {
            TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline());
            assertEquals(q, TriageDecider.Label.ASK, d.label);
        }
    }

    /** 新式（find + 独占量词）与原式（整句 matches）在随机短串上判定一致。 */
    @Test public void newArithEquivalentToOld() throws Exception {
        Pattern oldP = Pattern.compile(".*([0-9零一二两三四五六七八九十百千万]+(加|减|乘以|乘|除以|除|\\+|-|\\*|/|x)[0-9零一二两三四五六七八九十百千万]+).*"
                + "|.*(等于几|等于多少|等于多少米|是多少米|多少公斤|换算).*");
        java.lang.reflect.Field f = TriageDecider.class.getDeclaredField("ARITH");
        f.setAccessible(true);
        Pattern newP = (Pattern) f.get(null);
        String alpha = "0123九十百万加减乘以除+-*/x 等于几多少米公斤换算音量调到a";
        Random r = new Random(8);
        for (int i = 0; i < 20000; i++) {
            StringBuilder b = new StringBuilder();
            int n = 1 + r.nextInt(14);
            for (int k = 0; k < n; k++) b.append(alpha.charAt(r.nextInt(alpha.length())));
            String s = b.toString();
            assertEquals(s, oldP.matcher(s).matches(), newP.matcher(s).find());
        }
    }
}
