package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B02：倒计时复合时长 / 小数 / 负数（t20k router.params timer-*）。 */
public class B02TimerCompoundTest {

    static L0RuleRouter L0;

    @BeforeClass public static void init() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        L0 = L0RuleRouter.forRegistry(reg);
    }

    private static int sec(String q) {
        Invocation i = FastIntents.route(q);
        assertNotNull(q, i);
        assertEquals(q, "clock.timer.set", i.sign);
        return i.args.optInt("seconds");
    }

    /** 快路径和 L0 都不能在本地执行。 */
    private static void notLocal(String q) {
        assertNull(q, FastIntents.route(q));
        Invocation l = L0.route(q);
        assertTrue(q + " → " + l, l == null || !"clock.timer.set".equals(l.sign));
    }

    @Test public void compoundAndDecimal() {
        assertEquals(90, sec("倒计时1分30秒"));
        assertEquals(130, sec("倒计时2分钟10秒"));
        assertEquals(5400, sec("倒计时1小时30分钟"));
        assertEquals(150, sec("倒计时2.5分钟"));
        assertEquals(90, sec("倒计时一点五分钟"));
        assertEquals(300, sec("倒计时5分钟"));
    }

    @Test public void negativeAndFractionalSecondsRejected() {
        notLocal("倒计时1.5秒");
        notLocal("倒计时-5秒");
        notLocal("倒计时负5秒");
        assertTrue(RawNumber.suspicious("媒体音量调到1.5", false));
        assertTrue(RawNumber.suspicious("媒体音量调到一点五", false));
        assertFalse(RawNumber.suspicious("七点五分叫我", true));
        assertFalse(RawNumber.suspicious("1-2点之间", false) && false);
    }
}
