package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;

/** [s1] 胶囊停止键：AgentLoop 在已取消时不再调模型、不再执行后续步骤，以 CANCELLED 收尾。 */
public class AgentLoopCancelTest {

    static CapabilityRegistry registry() {
        return CapabilityRegistry.getInstance();
    }

    /** 可取消的假宿主：执行 cancelAfter 步后进入取消状态；取消后 execute 返回 CANCELLED（与 FSS 一致）。 */
    static final class CancellableHost implements AgentLoop.CancellableGatedCallback {
        final int cancelAfter;
        final List<String> executed = new ArrayList<String>();
        volatile boolean cancelled;

        CancellableHost(int cancelAfter) {
            this.cancelAfter = cancelAfter;
        }

        @Override public boolean isCancelled() { return cancelled; }

        @Override public ExecutionResult execute(Capability cap, JSONObject args) {
            return execute(cap, args, null, false);
        }

        @Override public ExecutionResult execute(Capability cap, JSONObject args, ExecutionGate.ConfirmToken token, boolean tainted) {
            if (cancelled) return ExecutionResult.failed(ErrorCode.CANCELLED, "已取消", "cancelled", cap, args);
            executed.add(cap.sign);
            if (executed.size() >= cancelAfter) cancelled = true;
            return ExecutionResult.success(cap, args, "ok", ExecutionResult.Verification.VERIFIED, "ok");
        }

        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason) {
            return AgentLoop.ConfirmVerdict.DENY;
        }

        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason, int tier, boolean tainted) {
            return AgentLoop.ConfirmVerdict.DENY;
        }
    }

    @Test public void stopsBeforeNextRoundOnceCancelled() {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.call("mail__inbox__latest", "{}"));
        llm.replies.add(AgentLoopTest.call("app__launch", "{\"app\":\"QQ邮箱\"}"));
        llm.replies.add(AgentLoopTest.say("完成"));
        CancellableHost host = new CancellableHost(1);
        AgentLoop.Result r = new AgentLoop(registry(), llm).run("查一下QQ邮箱", host, 6);
        assertFalse(r.finished);
        assertEquals(AgentLoop.REASON_CANCELLED, r.reason);
        assertEquals("取消后不再调模型", 1, llm.toolsSeen.size());
        assertEquals(1, host.executed.size());
    }

    @Test public void cancelledStepObservationEndsLoop() {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.call("mail__inbox__latest", "{}"));
        llm.replies.add(AgentLoopTest.say("完成"));
        CancellableHost host = new CancellableHost(99);
        host.cancelled = false;
        // 模型调用前一刻被取消 → 第一轮之前就收尾
        host.cancelled = true;
        AgentLoop.Result r = new AgentLoop(registry(), llm).run("查一下QQ邮箱", host, 6);
        assertEquals(AgentLoop.REASON_CANCELLED, r.reason);
        assertEquals(0, llm.toolsSeen.size());
        assertTrue(host.executed.isEmpty());
    }

    @Test public void plainCallbackUnaffected() {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.call("mail__inbox__latest", "{}"));
        llm.replies.add(AgentLoopTest.say("最新邮件是……"));
        AgentLoopTest.FakeExec exec = new AgentLoopTest.FakeExec("mail.inbox.latest");
        AgentLoop.Result r = new AgentLoop(registry(), llm).run("查一下QQ邮箱", exec, 6);
        assertTrue(r.finished);
        assertEquals(2, llm.toolsSeen.size());
    }
}
