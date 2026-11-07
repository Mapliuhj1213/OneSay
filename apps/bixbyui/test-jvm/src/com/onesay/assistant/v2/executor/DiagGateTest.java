package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.InterfaceKind;
import com.onesay.assistant.v2.diag.DiagCapabilities;
import com.onesay.assistant.v2.diag.DiagHooks;
import com.onesay.assistant.v2.diag.DiagKnowledge;
import com.onesay.assistant.v2.diag.DiagSettings;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * [diag] 修复芯片 → ExecutionGate → UnifiedExecutor → DiagBackend 的门禁口径：
 * 修复 = 黄卡（需确认、有令牌才执行、不是红卡）；撤销 = 放行；只读诊断不是能力调用。
 */
public class DiagGateTest {

    static final class MapShell implements ShellRunner {
        final Map<String, String> s = new HashMap<String, String>();
        int puts;

        @Override public ShellExecutor.ShellResponse execute(String c) {
            if (c.startsWith("settings get ")) {
                String[] p = c.split(" ");
                String v = s.get(p[2] + "/" + p[3]);
                return new ShellExecutor.ShellResponse(0, v == null ? "null" : v, false, false);
            }
            if (c.startsWith("settings put ")) {
                String[] p = c.split(" ");
                s.put(p[2] + "/" + p[3], p[4].replace("'", ""));
                puts++;
            }
            return new ShellExecutor.ShellResponse(0, "", false, false);
        }
    }

    MapShell sh;
    UnifiedExecutor ex;

    @Before public void setUp() {
        DiagSettings.sOverride = Boolean.TRUE;
        DiagHooks.undoStore().clear();
        sh = new MapShell();
        sh.s.put("system/screen_off_timeout", "30000");
        ex = new UnifiedExecutor(null, sh, null, null);
    }

    @After public void tearDown() {
        DiagSettings.sOverride = null;
        DiagHooks.undoStore().clear();
    }

    @Test public void fixNeedsYellowConfirmAndToken() {
        Capability cap = DiagCapabilities.fix(DiagKnowledge.fix("timeout_5m"), "熄屏改为 5 分钟：30 秒 → 5 分钟");
        JSONObject args = DiagCapabilities.fixArgs("timeout_5m");
        ExecutionGate.Decision g = ExecutionGate.evaluate(cap, args, false);
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, g.decision);
        assertEquals("黄卡（可恢复，不是红卡）", ConsentPolicy.TIER_YELLOW, g.tier);
        // 没有令牌：不执行
        ExecutionResult noTok = ex.execute(cap, args, null, false);
        assertFalse(noTok.ok);
        assertEquals(ErrorCode.RISK_GATED, noTok.code);
        assertEquals(0, sh.puts);
        // 用户点「允许」
        assertTrue(ExecutionGate.approve(g.token));
        ExecutionResult r = ex.execute(cap, args, g.token, false);
        assertTrue(r.userMessage, r.ok);
        assertEquals(ExecutionResult.Verification.VERIFIED, r.verification);
        assertEquals("300000", sh.s.get("system/screen_off_timeout"));
        // 令牌一次性
        assertFalse(ex.execute(cap, args, g.token, false).ok);
    }

    @Test public void undoIsAllowedWithoutConfirm() {
        Capability cap = DiagCapabilities.fix(DiagKnowledge.fix("timeout_5m"), "x");
        JSONObject args = DiagCapabilities.fixArgs("timeout_5m");
        ExecutionGate.Decision g = ExecutionGate.evaluate(cap, args, false);
        ExecutionGate.approve(g.token);
        assertTrue(ex.execute(cap, args, g.token, false).ok);
        Capability undo = DiagCapabilities.undo(null);
        JSONObject ua = DiagCapabilities.undoArgs(null);
        assertEquals(ToolGate.Decision.ALLOW, ExecutionGate.evaluate(undo, ua, false).decision);
        ExecutionResult r = ex.execute(undo, ua, null, false);
        assertTrue(r.userMessage, r.ok);
        assertEquals("30000", sh.s.get("system/screen_off_timeout"));
    }

    @Test public void taintedFixIsRed() {
        Capability cap = DiagCapabilities.fix(DiagKnowledge.fix("saver_on"), "x");
        assertEquals(ConsentPolicy.TIER_RED, ExecutionGate.evaluate(cap, DiagCapabilities.fixArgs("saver_on"), true).tier);
    }

    @Test public void allFixCapsAreYellowNotDelete() {
        for (DiagKnowledge.Fix f : DiagKnowledge.FIXES.values()) {
            if (!f.writes()) continue;
            Capability cap = DiagCapabilities.fix(f, f.label);
            assertNotEquals(f.id, InterfaceKind.Kind.DELETE, InterfaceKind.of(cap));
            assertEquals(f.id, ConsentPolicy.TIER_YELLOW, ConsentPolicy.tierOf(cap, false));
            assertTrue(f.id, DiagCapabilities.isLocal(cap));
        }
    }

    @Test public void mismatchedArgsRejectedByBackend() {
        Capability cap = DiagCapabilities.fix(DiagKnowledge.fix("timeout_5m"), "x");
        JSONObject args = DiagCapabilities.fixArgs("saver_on");
        ExecutionGate.Decision g = ExecutionGate.evaluate(cap, args, false);
        ExecutionGate.approve(g.token);
        ExecutionResult r = ex.execute(cap, args, g.token, false);
        assertFalse(r.ok);
        assertEquals(0, sh.puts);
    }

    @Test public void disabledSwitchRefusesNewFixes() {
        DiagSettings.sOverride = Boolean.FALSE;
        Capability cap = DiagCapabilities.fix(DiagKnowledge.fix("timeout_5m"), "x");
        JSONObject args = DiagCapabilities.fixArgs("timeout_5m");
        ExecutionGate.Decision g = ExecutionGate.evaluate(cap, args, false);
        ExecutionGate.approve(g.token);
        assertFalse(ex.execute(cap, args, g.token, false).ok);
        assertEquals(0, sh.puts);
    }
}
