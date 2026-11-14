package com.onesay.assistant.v2.companion;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 伴侣连接 · 路由优先级（11-08 真机：「删除待办 X」进了 AgentLoop，去 root 探测第三方笔记 App，失败）。
 * 自家计划 App 在场且握手通过 → 删 / 改待办只经连接执行，blocksAgentLoop；AgentLoop 的探针被拦；没装 → 原回落不变。
 */
public class CompanionRouterTest {

    /** 假的计划 App 连接：记下每次调用。 */
    static final class FakePlan implements CompanionRouter.PlanPort {
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        final List<String> calls = new ArrayList<String>();
        boolean down;
        long seq;

        FakePlan add(String title) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("_id", ++seq);
            r.put("title", title);
            r.put("done_at", 0L);
            rows.add(r);
            return this;
        }

        void up() {
            if (down) throw new IllegalStateException("计划 App 不在");
        }

        @Override public List<Map<String, Object>> listTasks(boolean includeDone) {
            up();
            calls.add("listTasks");
            return new ArrayList<Map<String, Object>>(rows);
        }

        Map<String, Object> find(long id) {
            for (Map<String, Object> r : rows) if (((Number) r.get("_id")).longValue() == id) return r;
            return null;
        }

        @Override public int deleteTask(long id) {
            up();
            calls.add("deleteTask:" + id);
            Map<String, Object> r = find(id);
            return r != null && rows.remove(r) ? 1 : 0;
        }

        @Override public int updateTask(long id, String title) {
            up();
            calls.add("updateTask:" + id + ":" + title);
            Map<String, Object> r = find(id);
            if (r == null) return 0;
            r.put("title", title);
            return 1;
        }

        @Override public int completeTask(long id) {
            up();
            calls.add("completeTask:" + id);
            return find(id) == null ? 0 : 1;
        }

        @Override public long createTask(String title, long dueAt) {
            up();
            calls.add("createTask:" + title);
            add(title);
            return seq;
        }
    }

    static final class Env implements CompanionRouter.Env {
        CompanionHandshake.State plan = CompanionHandshake.State.READY, clock = CompanionHandshake.State.READY;
        boolean s3 = true;
        final FakePlan port = new FakePlan();

        @Override public CompanionHandshake.State plan() { return plan; }
        @Override public CompanionHandshake.State clock() { return clock; }
        @Override public boolean s3Todo() { return s3; }
        @Override public CompanionRouter.PlanPort planPort() { return port; }
    }

    static final String DEVICE_UTTERANCE = "删除待办 开源版测试待办";

    @Test public void deleteTodoWithCompanionReadyGoesOnlyThroughLink() {
        Env e = new Env();
        e.port.add("买牛奶").add("开源版测试待办");
        CompanionRouter.Outcome o = CompanionRouter.handle(DEVICE_UTTERANCE, e);
        assertEquals(CompanionRouter.Action.REPLY, o.action);
        assertTrue("不许进 AgentLoop", o.blocksAgentLoop());
        assertEquals(CompanionIntent.Kind.TODO_DELETE, o.intent.kind);
        assertEquals("开源版测试待办", o.intent.target);
        assertEquals("[listTasks, deleteTask:2]", e.port.calls.toString());
        assertEquals("[listTasks, deleteTask]", o.calls.toString());
        assertTrue(o.reply, o.reply.contains("已从计划里删掉待办「开源版测试待办」"));
        assertEquals(1, e.port.rows.size());
        assertEquals("买牛奶", e.port.rows.get(0).get("title"));
    }

    @Test public void classifyAloneNeverTouchesTheLink() {
        Env e = new Env();
        e.port.add("开源版测试待办");
        CompanionRouter.Outcome o = CompanionRouter.classify(DEVICE_UTTERANCE, e);
        assertTrue(o.blocksAgentLoop());
        assertTrue("claims 不执行", e.port.calls.isEmpty());
        assertEquals(1, e.port.rows.size());
    }

    @Test public void deletePhrasings() {
        String[][] cases = {
                {"删除待办 开源版测试待办", "开源版测试待办"},
                {"删掉待办：买牛奶", "买牛奶"},
                {"删除我的待办买牛奶", "买牛奶"},
                {"删除待办里的买牛奶", "买牛奶"},
                {"删掉买牛奶这条待办", "买牛奶"},
                {"删除买牛奶的待办", "买牛奶"},
                {"把买牛奶从待办里删了", "买牛奶"},
                {"买牛奶那条待办删掉", "买牛奶"},
                {"把待办买牛奶删掉", "买牛奶"},
                {"帮我删除待办买牛奶。", "买牛奶"},
        };
        for (String[] c : cases) {
            CompanionIntent.Parsed p = CompanionIntent.parse(c[0]);
            assertEquals(c[0], CompanionIntent.Kind.TODO_DELETE, p.kind);
            assertEquals(c[0], c[1], p.target);
        }
    }

    @Test public void deleteWithCompanionPresentButNoLinkStillNeverAgentLoop() {
        for (CompanionHandshake.State s : new CompanionHandshake.State[]{CompanionHandshake.State.NO_LINK, CompanionHandshake.State.VERSION_MISMATCH,
                CompanionHandshake.State.MISSING_CAPS}) {
            Env e = new Env();
            e.plan = s;
            e.port.add("开源版测试待办");
            CompanionRouter.Outcome o = CompanionRouter.handle(DEVICE_UTTERANCE, e);
            assertEquals(s.name(), CompanionRouter.Action.CLAIM, o.action);
            assertTrue(s.name(), o.blocksAgentLoop());
            assertTrue("连接没通不经连接改数据", e.port.calls.isEmpty());
        }
    }

    @Test public void companionAbsentOrUntrustedKeepsOldFallback() {
        for (CompanionHandshake.State s : new CompanionHandshake.State[]{CompanionHandshake.State.ABSENT, CompanionHandshake.State.UNTRUSTED}) {
            Env e = new Env();
            e.plan = s;
            CompanionRouter.Outcome o = CompanionRouter.handle(DEVICE_UTTERANCE, e);
            assertEquals(s.name(), CompanionRouter.Action.NONE, o.action);
            assertFalse(o.blocksAgentLoop());
            assertTrue(e.port.calls.isEmpty());
        }
    }

    @Test public void agentLoopProbeOfThirdPartyAppIsBlockedWhileCompanionPresent() {
        Env e = new Env();
        // 真机日志里的那一步：probe.app_intents args={"package":"<第三方笔记包>","goal":"删除名为「开源版测试待办」的待办事项"}
        String goal = "com.example.notes 删除名为「开源版测试待办」的待办事项";
        assertNotNull(CompanionRouter.agentPrecheck("probe.app_intents", goal, "com.example.notes", e));
        assertNotNull(CompanionRouter.agentPrecheck("probe.shell_read", "pm dump com.example.notes 删除待办", null, e));
        assertNotNull(CompanionRouter.agentPrecheck("app.launch", "打开笔记 删掉 开源版测试待办 这条待办", "com.example.notes", e));
        assertNotNull("闹钟意图同样拦（时钟在场）", CompanionRouter.agentPrecheck("probe.app_intents", "删除明早7点的闹钟", "com.example.clock", e));
        e.plan = CompanionHandshake.State.NO_LINK;
        assertNotNull("在场但连接没通也不许探测第三方", CompanionRouter.agentPrecheck("probe.app_intents", goal, "com.example.notes", e));
    }

    @Test public void agentPrecheckLeavesOtherWorkAlone() {
        Env e = new Env();
        assertNull("自家计划 App 不拦", CompanionRouter.agentPrecheck("probe.app_intents", "删除待办 X", CompanionContract.PLAN_PKG, e));
        assertNull("不碰别的 App 的工具不拦", CompanionRouter.agentPrecheck("web.search", "删除待办 X", null, e));
        assertNull("与待办无关不拦", CompanionRouter.agentPrecheck("probe.app_intents", "在淘宝搜蓝牙耳机", "com.taobao.taobao", e));
        assertNull("多步任务（发给别人）不拦", CompanionRouter.agentPrecheck("probe.app_intents", "把待办发给张三", "com.tencent.mm", e));
        e.plan = CompanionHandshake.State.ABSENT;
        e.clock = CompanionHandshake.State.ABSENT;
        assertNull("没装伴侣：原行为", CompanionRouter.agentPrecheck("probe.app_intents", "删除名为「X」的待办事项", "com.example.notes", e));
        e.plan = CompanionHandshake.State.UNTRUSTED;
        assertNull("签名不同：当第三方", CompanionRouter.agentPrecheck("probe.app_intents", "删除名为「X」的待办事项", "com.example.notes", e));
    }

    @Test public void updateTodoThroughLink() {
        Env e = new Env();
        e.port.add("买牛奶");
        CompanionRouter.Outcome o = CompanionRouter.handle("把待办买牛奶改成买酸奶", e);
        assertEquals(CompanionRouter.Action.REPLY, o.action);
        assertEquals("[listTasks, updateTask:1:买酸奶]", e.port.calls.toString());
        assertEquals("买酸奶", e.port.rows.get(0).get("title"));
    }

    @Test public void notFoundAndAmbiguousAnswerLocallyWithoutWrites() {
        Env e = new Env();
        e.port.add("买牛奶");
        CompanionRouter.Outcome o = CompanionRouter.handle("删除待办 交电费", e);
        assertEquals(CompanionRouter.Action.REPLY, o.action);
        assertTrue(o.reply.contains("没找到「交电费」"));
        assertEquals("[listTasks]", e.port.calls.toString());

        Env e2 = new Env();
        e2.port.add("给妈妈打电话问周末").add("给爸爸打电话问周末");
        CompanionRouter.Outcome o2 = CompanionRouter.handle("删除待办 打电话问周末", e2);
        assertTrue(o2.reply, o2.reply.contains("要删哪一条"));
        assertEquals("[listTasks]", e2.port.calls.toString());
        assertEquals(2, e2.port.rows.size());
    }

    @Test public void linkFailureIsReportedNotHandedToAgentLoop() {
        Env e = new Env();
        e.port.down = true;
        CompanionRouter.Outcome o = CompanionRouter.handle(DEVICE_UTTERANCE, e);
        assertEquals(CompanionRouter.Action.REPLY, o.action);
        assertTrue(o.blocksAgentLoop());
        assertTrue(o.reply.contains("计划 App 没响应"));
    }

    @Test public void clearAllStaysWithS3ConfirmCard() {
        Env e = new Env();
        e.port.add("买牛奶");
        CompanionRouter.Outcome o = CompanionRouter.handle("删除所有待办", e);
        assertEquals(CompanionIntent.Kind.TODO_CLEAR, o.intent.kind);
        assertEquals("S3 出确认卡，伴侣不直接清空", CompanionRouter.Action.CLAIM, o.action);
        assertTrue(e.port.calls.isEmpty());
    }

    @Test public void addListCompleteUseS3WhenItIsOnElseTheLink() {
        Env e = new Env();
        assertEquals(CompanionRouter.Action.CLAIM, CompanionRouter.handle("记个待办 买牛奶", e).action);
        assertEquals(CompanionRouter.Action.CLAIM, CompanionRouter.handle("我有哪些待办", e).action);
        assertTrue(e.port.calls.isEmpty());
        e.s3 = false;
        CompanionRouter.Outcome add = CompanionRouter.handle("记个待办 买牛奶", e);
        assertEquals(CompanionRouter.Action.REPLY, add.action);
        assertEquals("[createTask:买牛奶]", e.port.calls.toString());
        CompanionRouter.Outcome list = CompanionRouter.handle("我有哪些待办", e);
        assertTrue(list.reply, list.reply.contains("「买牛奶」"));
    }

    @Test public void alarmAndScheduleAreClaimedNotExecutedHere() {
        Env e = new Env();
        CompanionRouter.Outcome a = CompanionRouter.handle("删除明早7点的闹钟", e);
        assertEquals(CompanionIntent.Domain.CLOCK, a.intent.domain);
        assertEquals("闹钟由 FastIntents → AlarmBridge（连接优先）执行", CompanionRouter.Action.CLAIM, a.action);
        assertEquals(CompanionRouter.Action.CLAIM, CompanionRouter.handle("明天下午有什么日程", e).action);
        e.clock = CompanionHandshake.State.ABSENT;
        assertEquals(CompanionRouter.Action.NONE, CompanionRouter.handle("删除明早7点的闹钟", e).action);
    }

    @Test public void userNamingAnotherAppOrMultiStepIsNotClaimed() {
        Env e = new Env();
        assertEquals(CompanionRouter.Action.NONE, CompanionRouter.handle("在滴答清单里删除待办买牛奶", e).action);
        assertEquals(CompanionRouter.Action.NONE, CompanionRouter.handle("把我的待办发给张三", e).action);
        assertEquals(CompanionRouter.Action.NONE, CompanionRouter.handle("今天天气怎么样", e).action);
        assertEquals("「在计划里」是自家", CompanionIntent.Kind.TODO_DELETE, CompanionIntent.parse("在计划里删除待办买牛奶").kind);
    }
}
