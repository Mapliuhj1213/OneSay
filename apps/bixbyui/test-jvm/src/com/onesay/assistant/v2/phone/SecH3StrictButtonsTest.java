package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import com.onesay.assistant.plug.action.core.RiskGate;

import org.junit.Test;

/**
 * ★ sec-audit-1118 H3（严格）：开通 / 自动续费 / 订阅 / 确认收货 / Place your order / Buy with 1-Click / 签署 / 同意授权 / 立即付 / Agree and pay
 * 在 SensitiveGuard 和 RiskGate 两处都拦；超过 10 字的长文字不再当导航放行；西里尔同形字、U+034F、零宽字符绕不过去。
 */
public class SecH3StrictButtonsTest {

    static final String[] WORDS = {"立即开通", "开通自动续费", "订阅", "Subscribe", "确认收货", "Place your order", "Buy with 1-Click",
            "签署", "同意授权", "立即付", "Agree and pay"};

    static String tapButton(String label) {
        return SensitiveGuardTest.tap(FakeScreens.screen("p", "a").button(label).ui(null), label);
    }

    static String tapRow(String label) {
        return SensitiveGuardTest.tap(FakeScreens.screen("p", "a").row(label, null).ui(null), label);
    }

    @Test public void strictWordsTriggerSensitiveGuard() {
        for (String w : WORDS) assertNotNull(w, tapButton(w));
        assertTrue(tapButton("签署").contains("签署"));
        assertTrue(tapButton("立即付").contains("付款"));
    }

    @Test public void longLabelsWithStrictWordsAreNotNavigation() {
        assertNotNull(tapRow("立即开通超级会员享受专属特权服务"));          // > 10 字且以「服务」收尾，以前当导航
        assertNotNull(tapRow("同意授权并继续使用全部功能和个性化推荐"));
        assertNotNull(tapRow("订阅本频道以获取每日最新内容推送"));            // 只有「订阅」：取消「超长当导航」
        assertNotNull(tapRow("Place your order and agree to the terms of use"));
        assertNotNull(tapRow("开通自动续费 首月仅需 6 元 可随时取消"));
    }

    @Test public void lookalikeAndInvisibleCharsFolded() {
        assertNotNull(tapButton("\u0405ubscribe"));                 // 西里尔 Ѕ
        assertNotNull(tapButton("Pl\u0430\u0441\u0435 your order")); // 西里尔 а с е
        assertNotNull(tapButton("立即\u034F开通"));
        assertNotNull(tapButton("确\u200B认收货"));
        assertNotNull(tapButton("Agree \u0430nd p\u0430y"));
        assertNotNull(tapButton("簽署"));
    }

    @Test public void navigationAndBenignStillPass() {
        assertNull(tapRow("订阅管理"));
        assertNull(tapRow("订阅号消息"));
        assertNull(tapButton("订阅号"));
        assertNull(tapRow("密码与安全"));
        assertNull(tapButton("确定"));
        assertNull(tapButton("下一步"));
    }

    @Test public void riskGateStrict() {
        assertEquals(RiskGate.Irrev.ORDER, RiskGate.detect("tap", "立即开通", null, false));
        assertEquals(RiskGate.Irrev.PAY, RiskGate.detect("tap", "开通自动续费", null, false));
        assertEquals(RiskGate.Irrev.ORDER, RiskGate.detect("tap", "Subscribe", null, false));
        assertEquals(RiskGate.Irrev.PAY, RiskGate.detect("tap", "确认收货", null, false));
        assertEquals(RiskGate.Irrev.ORDER, RiskGate.detect("tap", "Place your order", null, false));
        assertEquals(RiskGate.Irrev.ORDER, RiskGate.detect("tap", "Buy with 1-Click", null, false));
        assertEquals(RiskGate.Irrev.SUBMIT, RiskGate.detect("tap", "签署", null, false));
        assertEquals(RiskGate.Irrev.SUBMIT, RiskGate.detect("tap", "同意授权", null, false));
        assertEquals(RiskGate.Irrev.PAY, RiskGate.detect("tap", "立即付", null, false));
        assertEquals(RiskGate.Irrev.PAY, RiskGate.detect("tap", "Agree and pay", null, false));
        // 长文字后半句的严格词不再被截掉
        assertEquals(RiskGate.Irrev.ORDER, RiskGate.detect("tap", "这是一段很长很长很长很长很长的说明文字最后写着立即开通", null, false));
        // 同形字 / U+034F
        assertEquals(RiskGate.Irrev.ORDER, RiskGate.detect("tap", "\u0405ubscribe", null, false));
        assertEquals(RiskGate.Irrev.PAY, RiskGate.detect("tap", "P\u0430\u034Fy", null, false));
        assertEquals(RiskGate.Irrev.PAY, RiskGate.detect("tap", "支\u034F付", null, false));
        // 资源 id
        assertEquals(RiskGate.Irrev.ORDER, RiskGate.detect("tap", "", "com.x:id/btn_subscribe", false));
        // 不误伤
        assertNull(RiskGate.detect("tap", "订阅号", null, false));
        assertNull(RiskGate.detect("tap", "确定", null, false));
        assertNull(RiskGate.detect("tap", "立即开通", null, true));
        assertNull(RiskGate.detect("swipe", "立即开通", null, false));
    }
}
