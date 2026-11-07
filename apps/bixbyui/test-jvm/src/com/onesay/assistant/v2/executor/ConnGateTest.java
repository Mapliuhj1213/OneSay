package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.conn.ConnBackend;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.ConnTool;
import com.onesay.assistant.v2.conn.Connector;
import com.onesay.assistant.v2.conn.ConnectorRegistry;
import com.onesay.assistant.v2.conn.ToolEffect;
import com.onesay.assistant.v2.executor.backend.ExecutorBackend;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** [conn] 有副作用的连接器工具：黄卡 → 令牌 → UnifiedExecutor 消费令牌 → ConnBackend；没令牌到不了远端。 */
public class ConnGateTest {

    static final class Recorder implements Connector {
        final List<String> calls = new ArrayList<String>();
        @Override public String id() { return "demo"; }
        @Override public String title() { return "演示"; }
        @Override public boolean isReady() { return true; }
        @Override public boolean exposeToAgent() { return true; }
        @Override public List<ConnTool> tools() {
            return Arrays.asList(new ConnTool("demo", "read_it", "读", null, ToolEffect.READ),
                    new ConnTool("demo", "write_it", "写", null, ToolEffect.WRITE));
        }
        @Override public ConnResult refreshTools() { return ConnResult.ok(""); }
        @Override public ConnResult call(String toolName, JSONObject args) {
            calls.add(toolName);
            return ConnResult.ok("done " + toolName);
        }
        @Override public String status() { return ""; }
    }

    @After public void tearDown() {
        ExecutionGate.setStrictConfirm(true);   // r2/fixes 11：恢复生产默认（严格）
    }

    @Test public void writeNeedsYellowCardAndToken() throws Exception {
        ExecutionGate.setStrictConfirm(true);
        Recorder rec = new Recorder();
        ConnectorRegistry reg = new ConnectorRegistry();
        reg.register(rec);
        List<ExecutorBackend> bes = new ArrayList<ExecutorBackend>();
        bes.add(ConnBackend.fixed(reg));
        UnifiedExecutor ex = new UnifiedExecutor(null, new UnifiedExecutorTest.FakeShell(), new UnifiedExecutorTest.FakeApps(), bes);

        Capability w = reg.describe("conn.demo.write_it");
        JSONObject args = new JSONObject().put("title", "开会");
        ExecutionGate.Decision d = ExecutionGate.evaluate(w, args, false);
        assertTrue(d.needsConfirm());
        assertEquals("可信来源：黄卡", ConsentPolicy.TIER_YELLOW, d.tier);
        assertEquals("被污染：红卡", ConsentPolicy.TIER_RED, ExecutionGate.evaluate(w, args, true).tier);

        ExecutionResult noTok = ex.execute(w, args, null, false);
        assertFalse(noTok.ok);
        assertEquals(ErrorCode.RISK_GATED, noTok.code);
        assertTrue("没令牌不触远端", rec.calls.isEmpty());
        ExecutionResult legacy = ex.executeInternal(w, args, true, false);
        assertFalse("严格模式下旧布尔也放不过", legacy.ok);
        assertTrue(rec.calls.isEmpty());

        assertTrue(ExecutionGate.approve(d.token));
        ExecutionResult ok = ex.execute(w, args, d.token, false);
        assertTrue(ok.userMessage, ok.ok);
        assertEquals(Arrays.asList("write_it"), rec.calls);
        ExecutionResult again = ex.execute(w, args, d.token, false);
        assertFalse("令牌一次性", again.ok);
        assertEquals(1, rec.calls.size());

        // 改了参数的令牌无效
        ExecutionGate.Decision d2 = ExecutionGate.evaluate(w, args, false);
        ExecutionGate.approve(d2.token);
        assertFalse(ex.execute(w, new JSONObject().put("title", "删库"), d2.token, false).ok);
        assertEquals(1, rec.calls.size());

        // 只读：不确认直接过
        Capability r = reg.describe("conn.demo.read_it");
        assertTrue(ExecutionGate.evaluate(r, new JSONObject(), false).allowed());
        assertTrue("被污染后读也要确认", ExecutionGate.evaluate(r, new JSONObject(), true).needsConfirm());
    }
}
