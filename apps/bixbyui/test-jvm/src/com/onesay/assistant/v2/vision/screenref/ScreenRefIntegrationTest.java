package com.onesay.assistant.v2.vision.screenref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ShellCommand;
import com.onesay.assistant.v2.executor.ToolGate;
import com.onesay.assistant.v2.router.AgentLoop;

import org.json.JSONObject;
import org.junit.Test;

/**
 * [screenref] 2026-10-07 与 main（ExecutionGate / ShellCommand / 三态 verified / 污点）集成后的契约测试。
 * 只用纯 Java 部分；ScreenRefHooks 的 Android 依赖在 ctx=null 时全部短路。
 */
public class ScreenRefIntegrationTest {

    @Test public void sideEffectsDeclaredForGate() {
        assertEquals(SideEffect.PRIVATE_DATA_READ, ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_CONTEXT).sideEffect);
        assertEquals(SideEffect.DEVICE_CONTROL, ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_NAV).sideEffect);
        assertEquals(SideEffect.DEVICE_CONTROL, ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_CALENDAR).sideEffect);
        assertEquals(SideEffect.DEVICE_CONTROL, ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_OPEN_URL).sideEffect);
        assertEquals(SideEffect.NONE, ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_COPY).sideEffect);
    }

    @Test public void screenContextReadTaintsLoop() {
        assertTrue(AgentLoop.readsUntrustedContent(ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_CONTEXT)));
        assertFalse(AgentLoop.readsUntrustedContent(ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_COPY)));
        assertFalse(AgentLoop.readsUntrustedContent(ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_NAV)));
    }

    @Test public void gateUntaintedAllowsTaintedConfirms() throws Exception {
        JSONObject a = new JSONObject().put("address", "深圳市南山区高新南一道8号");
        for (String sign : new String[]{ScreenRefCapabilities.SIGN_NAV, ScreenRefCapabilities.SIGN_CALENDAR,
                ScreenRefCapabilities.SIGN_OPEN_URL}) {
            Capability c = ScreenRefCapabilities.get(sign);
            assertEquals(sign + " untainted", ToolGate.Decision.ALLOW, ToolGate.check(c, a, false).decision);
            assertEquals(sign + " tainted", ToolGate.Decision.REQUIRE_CONFIRM, ToolGate.check(c, a, true).decision);
        }
        // 读屏（screen.context）是本机只读 —— 污点下也放行，不弹卡（rel 上机 t05）
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_CONTEXT), a, true).decision);
        // 复制：只写剪贴板、用户可见 —— 污点下也放行
        Capability copy = ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_COPY);
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(copy, a, true).decision);
    }

    @Test public void navTemplateEscapedByShellCommand() throws Exception {
        Capability nav = ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_NAV);
        JSONObject a = new JSONObject().put("address", "北京路1号' ; reboot ; echo '&x=$(id)`");
        String cmd = ShellCommand.fill(nav.cmdTemplate, ShellCommand.slotValues(a, null, null));
        // 地址处在 URL 查询值位置 → 整体百分号编码，不会留下可闭合单引号或命令分隔符
        assertFalse(cmd, cmd.contains("reboot ;"));
        assertFalse(cmd, cmd.contains("$(id)"));
        assertTrue(cmd, cmd.contains("'geo:0,0?q=%"));
        assertTrue(cmd, cmd.contains("keyword=%"));
        // & 不再被删掉（旧 escapeShellValue 的问题），而是编码为 %26
        assertTrue(cmd, cmd.contains("%26"));
    }

    @Test public void calendarTemplateQuotesTitleAndForcesIntegers() throws Exception {
        Capability cal = ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_CALENDAR);
        JSONObject a = new JSONObject().put("title", "老王's 会议; rm -rf /").put("begin_ms", "1791529200000")
                .put("end_ms", "1791532800000").put("all_day", "false");
        String cmd = ShellCommand.fill(cal.cmdTemplate, ShellCommand.slotValues(a, null, null));
        assertTrue(cmd, cmd.contains("--es title '老王'\\''s 会议; rm -rf /'"));
        assertTrue(cmd, cmd.contains("--el beginTime 1791529200000 --el endTime 1791532800000 --ez allDay false"));
        try {
            ShellCommand.fill(cal.cmdTemplate, ShellCommand.slotValues(
                    new JSONObject().put("title", "x").put("begin_ms", "1;reboot").put("end_ms", "2").put("all_day", "false"), null, null));
            fail("非整数时间必须被拒绝");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test public void normalizeArgsFeedsIntegerTemplate() throws Exception {
        JSONObject a = new JSONObject().put("title", "开会").put("begin_ms", "明天下午3点");
        long now = 1791475200000L;   // 2026-10-09 00:00 +08:00 附近，具体值无关
        JSONObject f = ScreenRefCapabilities.normalizeArgs(ScreenRefCapabilities.SIGN_CALENDAR, a, now,
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        assertTrue(f.getString("begin_ms").matches("\\d{13}"));
        assertTrue(f.getString("end_ms").matches("\\d{13}"));
        Capability cal = ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_CALENDAR);
        ShellCommand.fill(cal.cmdTemplate, ShellCommand.slotValues(f, null, null));   // 不抛异常
    }

    @Test public void hooksAreInertWithoutContext() throws Exception {
        assertNull(ScreenRefHooks.handleLocalTool(null, ScreenRefCapabilities.SIGN_COPY, new JSONObject()));
        assertNull(ScreenRefHooks.executeForAgent(null, ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_COPY), new JSONObject()));
        assertNull(ScreenRefHooks.describe(null, ScreenRefCapabilities.SIGN_COPY));
        assertEquals("abc", ScreenRefHooks.withScreenHint(null, "复制这个", "abc"));
    }

    @Test public void screenInjectionDetection() {
        assertFalse(ScreenRefHooks.isScreenInjected("q", "q"));
        assertFalse(ScreenRefHooks.isScreenInjected("q", null));
        assertFalse("读不到屏幕时的说明不含屏幕内容", ScreenRefHooks.isScreenInjected("q", "q" + ScreenRefHooks.NO_SCREEN_NOTE));
        assertTrue(ScreenRefHooks.isScreenInjected("q", "q\n\n[屏幕上下文（唤醒瞬间采集…）]\n1. 电话：10086"));
    }
}
