package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.phone.TaskBudget;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ★ fast-screen-v2：AgentLoop 的 8 轮死上限改成 TaskBudget（调用数 / 时长 / 无进展），到上限先问用户。 */
public class AgentLoopBudgetTest {

    static String src(String rel) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("bixby.root"), rel)), "UTF-8");
    }

    @Test public void defaultBudgetIsTaskBudgetAndCallerCanOnlyTighten() {
        assertEquals(TaskBudget.DEF_MODEL_CALLS, AgentLoop.newBudget(0, 0).maxCalls);
        assertEquals(6, AgentLoop.newBudget(6, 0).maxCalls);
        assertEquals(TaskBudget.DEF_MODEL_CALLS, AgentLoop.newBudget(999, 0).maxCalls);
    }

    @Test public void identicalRoundsAreNoProgress() throws Exception {
        TaskBudget tb = new TaskBudget(0);
        Map<String, String> last = new HashMap<String, String>();
        List<AgentLoop.Step> steps = new ArrayList<AgentLoop.Step>();
        JSONArray calls = new JSONArray();
        calls.put(new JSONObject().put("id", "1"));
        for (int i = 0; i < 4; i++) {
            steps.add(new AgentLoop.Step("probe.apps", new JSONObject().put("q", "qq"), "{\"apps\":[]}", true, false));
            tb.onTurn(AgentLoop.roundSignature(calls, steps, tb, last));
        }
        assertEquals(TaskBudget.Verdict.STUCK, tb.check(0));
    }

    @Test public void changingObservationsAreProgress() throws Exception {
        TaskBudget tb = new TaskBudget(0);
        Map<String, String> last = new HashMap<String, String>();
        List<AgentLoop.Step> steps = new ArrayList<AgentLoop.Step>();
        JSONArray calls = new JSONArray();
        calls.put(new JSONObject());
        for (int i = 0; i < 6; i++) {
            steps.add(new AgentLoop.Step("mail.read", new JSONObject().put("page", i), "{\"n\":" + i + "}", true, false));
            tb.onTurn(AgentLoop.roundSignature(calls, steps, tb, last));
        }
        assertEquals(TaskBudget.Verdict.OK, tb.check(0));
    }

    @Test public void hostWiring() throws Exception {
        String loop = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertTrue(loop.contains("cb instanceof BudgetAsk && !(cb instanceof Unattended)"));   // 定时任务不问
        assertFalse("不再死卡 8 轮", loop.contains("round <= budget"));
        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        // ★ arch/agent-loop：宿主改为 LoopAwareCallback（= BudgetAskingCallback + 账本 / ask_user 事件）
        assertTrue(fss.contains("new AgentLoop.BudgetAskingCallback()") || fss.contains("new AgentLoop.LoopAwareCallback()"));
        assertTrue(fss.contains("public boolean askContinue(String reason, String info)"));
        String eh = src("app/src/com/onesay/assistant/v2/errand/ErrandHooks.java");
        assertTrue(eh.contains("agent.setBudgetAsk("));
        assertTrue(eh.contains("MODE_BUDGET.equals(t.mode)"));
    }

    // ★ fast-screen-v3：定时任务（Unattended）= 8 次 / 3 分钟，收尾原因带「未完成」
    @Test public void unattendedBudgetAndUnfinishedReason() throws Exception {
        TaskBudget tb = AgentLoop.newBudget(0, 0, true);
        assertEquals(8, tb.maxCalls);
        assertEquals(3 * 60_000L, tb.maxMs);
        assertEquals(6, AgentLoop.newBudget(6, 0, true).maxCalls);
        assertEquals(8, AgentLoop.newBudget(30, 0, true).maxCalls);
        assertEquals(30, AgentLoop.newBudget(0, 0, false).maxCalls);
        String loop = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertTrue(loop.contains("newBudget(maxRounds, nowMs(), cb instanceof Unattended)"));
        assertTrue(loop.contains("REASON_BUDGET + \"（\" + com.onesay.assistant.v2.phone.TaskBudget.UNFINISHED + \"）：\""));
    }
}
