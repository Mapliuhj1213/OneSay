package com.onesay.assistant.v2.s1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.TimeZone;

import org.json.JSONObject;
import org.junit.Test;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ShellCommand;
import com.onesay.assistant.v2.executor.ToolGate;
import com.onesay.assistant.v2.vision.screenref.ScreenAction;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;
import com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities;

/** [s1] 剪贴板地址芯片：敏感过滤、自家写入/重复去抖、芯片挑选、动作映射与门禁、打车模板转义、开关裁决。 */
public class ClipChipPlannerTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final long NOW = 1791590400000L;
    static final String ADDR = "广州市天河区珠江新城华夏路10号富力中心28楼";

    static ClipChipPlanner.Decision plan(String text) {
        return ClipChipPlanner.plan(text, false, null, 0L, NOW, TZ);
    }

    @Test public void addressGetsNavAndTaxi() {
        ClipChipPlanner.Decision d = plan(ADDR);
        assertTrue(d.toString(), d.show);
        assertEquals(ClipChipPlanner.ChipKind.NAV, d.chips.get(0).kind);
        assertEquals(ClipChipPlanner.ChipKind.TAXI, d.chips.get(1).kind);
        assertEquals(ScreenEntity.Type.ADDRESS, d.chips.get(0).entity.type);
        assertTrue(d.title, d.title.startsWith("复制了地址："));
        assertFalse(d.entities.isEmpty());
    }

    @Test public void addressInsideChatSentenceAndPhoneUrlTracking() {
        ClipChipPlanner.Decision d = plan("地址：" + ADDR + "，到了打 13612345678");
        assertTrue(d.show);
        assertEquals(3, d.chips.size());
        assertEquals(ClipChipPlanner.ChipKind.CALL, d.chips.get(2).kind);
        ClipChipPlanner.Decision p = plan("13612345678");
        assertTrue(p.show);
        assertEquals(ClipChipPlanner.ChipKind.CALL, p.chips.get(0).kind);
        ClipChipPlanner.Decision u = plan("https://example.com/a?b=1&c=2");
        assertTrue(u.show);
        assertEquals(ClipChipPlanner.ChipKind.OPEN_URL, u.chips.get(0).kind);
        ClipChipPlanner.Decision t = plan("顺丰 SF1234567890123");
        assertTrue(t.show);
        assertEquals(ClipChipPlanner.ChipKind.TRACK, t.chips.get(0).kind);
    }

    @Test public void ownWriteAndDuplicatesIgnored() {
        assertFalse(ClipChipPlanner.plan(ADDR, true, null, 0L, NOW, TZ).show);
        assertEquals("本 App 写入", ClipChipPlanner.plan(ADDR, true, null, 0L, NOW, TZ).reason);
        assertEquals("重复复制", ClipChipPlanner.plan(ADDR, false, ADDR + " ", NOW - 1000L, NOW, TZ).reason);
        assertTrue("超出去抖窗口后同样内容再复制会再弹",
                ClipChipPlanner.plan(ADDR, false, ADDR, NOW - ClipChipPlanner.DUP_WINDOW_MS - 1L, NOW, TZ).show);
        assertEquals("空内容", plan("   ").reason);
        assertEquals("无可操作实体", plan("今天天气不错").reason);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 40; i++) longText.append(ADDR);
        assertEquals("内容过长", plan(longText.toString()).reason);
    }

    @Test public void sensitiveClipsNeverChip() {
        String[] secrets = {
                "您的验证码为 482913，请勿泄露",
                "482913",
                "6222020200112233446",           // 通过 Luhn 的 19 位卡号 → 打码规则命中
                "440106199001011234",            // 身份证样式
                "密码：abc12345",
                "P@ssw0rd!2026",
                // 假密钥在运行时拼出来：源码里不出现 sk-xxxx 字面量，交接包的密钥扫描（grep sk-）不会误报
                "sk" + "-" + "abcdefghijklmnopqrstuvwxyz" + "123456",
                "AKIA" + "ABCDEFGHIJKLMNOP",
                "Your OTP is 123456"};
        for (String s : secrets) {
            ClipChipPlanner.Decision d = plan(s);
            assertFalse(s + " → " + d, d.show);
            assertTrue(s + " → " + d.reason, d.reason.startsWith("敏感"));
            assertTrue(d.entities.isEmpty());
        }
        assertNull("普通地址不敏感", ClipChipPlanner.sensitiveReason(ADDR));
        assertNull("网址不算密码", ClipChipPlanner.sensitiveReason("https://Example.com/A1"));
        assertNull("快递单号不算密码", ClipChipPlanner.sensitiveReason("SF1234567890123"));
    }

    @Test public void clipboardMemoryOwnWriteAndTtl() {
        ClipboardMemory.clear();
        ClipboardMemory.markOwnWrite("5-2-1234", 1000L);
        assertFalse(ClipboardMemory.consumeOwnWrite("别的内容", 1100L));
        assertTrue(ClipboardMemory.consumeOwnWrite("5-2-1234", 1100L));
        assertFalse("命中一次即作废", ClipboardMemory.consumeOwnWrite("5-2-1234", 1200L));
        ClipboardMemory.markOwnWrite("x", 1000L);
        assertFalse("超出窗口不算自家写入", ClipboardMemory.consumeOwnWrite("x", 1000L + ClipboardMemory.OWN_WRITE_WINDOW_MS + 1));

        ClipChipPlanner.Decision d = plan(ADDR);
        ClipboardMemory.remember(d.entities, 5000L);
        assertEquals(d.entities.size(), ClipboardMemory.recentEntities(5000L + 60000L).size());
        assertTrue(ClipboardMemory.recentEntities(5000L + ClipboardMemory.TTL_MS + 1).isEmpty());
        ClipboardMemory.clear();
        assertTrue(ClipboardMemory.recentEntities(5000L).isEmpty());
    }

    @Test public void chipActionsGoThroughGateUntainted() throws Exception {
        ClipChipPlanner.Decision d = plan("地址：" + ADDR + "，电话 13612345678");
        ScreenAction nav = S1Capabilities.actionFor(d.chips.get(0));
        assertEquals(ScreenRefCapabilities.SIGN_NAV, nav.sign);
        assertEquals(ADDR, nav.args.getString("address"));
        ScreenAction taxi = S1Capabilities.actionFor(d.chips.get(1));
        assertEquals(S1Capabilities.SIGN_TAXI, taxi.sign);
        ScreenAction call = S1Capabilities.actionFor(d.chips.get(2));
        assertEquals("phone.call", call.sign);
        assertEquals("13612345678", call.args.getString("number"));

        Capability taxiCap = S1Capabilities.localCapability(S1Capabilities.SIGN_TAXI);
        assertNotNull(taxiCap);
        assertEquals(ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_NAV), S1Capabilities.localCapability(ScreenRefCapabilities.SIGN_NAV));
        // 芯片是用户亲手点选 → 按未污染过闸：导航 / 打车 risk 0 直接放行（与识屏芯片 D1 同口径）
        assertEquals(ToolGate.Decision.ALLOW, ExecutionGate.evaluate(taxiCap, taxi.args, false).decision);
        assertEquals(ToolGate.Decision.ALLOW,
                ExecutionGate.evaluate(S1Capabilities.localCapability(ScreenRefCapabilities.SIGN_NAV), nav.args, false).decision);
        // 污染状态下拉起界面需要确认（防提示注入）
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, ExecutionGate.evaluate(taxiCap, taxi.args, true).decision);
    }

    @Test public void taxiTemplateEscapesAddress() throws Exception {
        JSONObject a = new JSONObject();
        a.put("address", "科技园'; rm -rf / & $(id) 8号");
        String cmd = ShellCommand.fill(S1Capabilities.taxi().cmdTemplate, ShellCommand.slotValues(a, null, null));
        assertFalse(cmd, cmd.contains("$(id)"));
        assertFalse(cmd, cmd.contains("rm -rf / &"));
        assertTrue(cmd, cmd.contains("dname=%E7%A7%91%E6%8A%80%E5%9B%AD%27%3B%20rm"));
        assertTrue(cmd, cmd.contains("%26%20%24%28id%29"));
        assertTrue(cmd, cmd.contains("geo:0,0?q="));
    }

    @Test public void settingsDecide() {
        assertTrue(S1Settings.decide(null, false, false, true));
        assertFalse(S1Settings.decide(null, true, false, true));
        assertFalse(S1Settings.decide(null, false, true, true));
        assertFalse(S1Settings.decide(null, false, false, false));
        assertTrue(S1Settings.decide(Boolean.TRUE, true, true, false));
        assertFalse(S1Settings.decide(Boolean.FALSE, false, false, true));
        assertEquals(Arrays.asList("导航", "打车", "拨打", "打开", "查快递"), Arrays.asList(
                ClipChipPlanner.ChipKind.NAV.label, ClipChipPlanner.ChipKind.TAXI.label, ClipChipPlanner.ChipKind.CALL.label,
                ClipChipPlanner.ChipKind.OPEN_URL.label, ClipChipPlanner.ChipKind.TRACK.label));
    }
}
