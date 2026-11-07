package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ fast-screen-v2：任务预算（模型调用 / 总时长 / 无进展 / 重复动作 / 安全上限）。 */
public class TaskBudgetTest {

    @Test public void okWithinBudget() {
        TaskBudget b = new TaskBudget(0);
        for (int i = 0; i < 5; i++) {
            b.onModelCall();
            b.onTurn("s" + i);
        }
        assertEquals(TaskBudget.Verdict.OK, b.check(1000));
    }

    @Test public void modelCallLimitAsksThenExtends() {
        TaskBudget b = new TaskBudget(0);
        for (int i = 0; i < TaskBudget.DEF_MODEL_CALLS; i++) {
            b.onModelCall();
            b.onTurn("s" + i);
        }
        assertEquals(TaskBudget.Verdict.LIMIT, b.check(1000));
        assertTrue(b.reason.contains("模型 " + TaskBudget.DEF_MODEL_CALLS));
        b.extend();
        assertEquals(TaskBudget.DEF_MODEL_CALLS + TaskBudget.EXTEND_CALLS, b.maxCalls);
        assertEquals(TaskBudget.Verdict.OK, b.check(1000));
    }

    @Test public void totalTimeExcludesPausedTime() {
        TaskBudget b = new TaskBudget(1000);
        long late = 1000 + TaskBudget.DEF_TOTAL_MS + 1;
        assertEquals(TaskBudget.Verdict.LIMIT, b.check(late));
        b.pausedMs = 60_000;   // 用户接手 / 确认的一分钟不算
        assertEquals(TaskBudget.Verdict.OK, b.check(late));
        b.extend();
        assertEquals(TaskBudget.DEF_TOTAL_MS + TaskBudget.EXTEND_MS, b.maxMs);
    }

    @Test public void noProgressAfterThreeUnchangedTurns() {
        TaskBudget b = new TaskBudget(0);
        b.onTurn("a");
        b.onTurn("a");
        b.onTurn("a");
        assertEquals(TaskBudget.Verdict.OK, b.check(0));   // 需要 1 + 3 个签名
        b.onTurn("a");
        assertEquals(TaskBudget.Verdict.STUCK, b.check(0));
        b.resetProgress();
        assertEquals(TaskBudget.Verdict.OK, b.check(0));
    }

    @Test public void changingScreenIsProgress() {
        TaskBudget b = new TaskBudget(0);
        b.onTurn("a");
        b.onTurn("a");
        b.onTurn("b");
        b.onTurn("b");
        assertEquals(TaskBudget.Verdict.OK, b.check(0));
    }

    @Test public void repeatedSameActionWithoutChangeIsStuck() {
        TaskBudget b = new TaskBudget(0);
        b.onAction("tap:5", false);
        b.onAction("tap:5", false);
        assertEquals(TaskBudget.Verdict.OK, b.check(0));
        b.onAction("tap:5", false);
        assertEquals(TaskBudget.Verdict.STUCK, b.check(0));
        assertTrue(b.reason.contains("同一个动作"));
    }

    @Test public void repeatedActionThatChangesScreenIsFine() {
        TaskBudget b = new TaskBudget(0);
        for (int i = 0; i < 10; i++) b.onAction("scroll:down", true);   // 翻页
        assertEquals(TaskBudget.Verdict.OK, b.check(0));
        b.onAction("tap:1", false);
        b.onAction("tap:2", false);
        b.onAction("tap:1", false);
        assertEquals(TaskBudget.Verdict.OK, b.check(0));
    }

    @Test public void hardStopTrumpsEverything() {
        TaskBudget b = new TaskBudget(0);
        for (int i = 0; i < TaskBudget.HARD_ACTIONS; i++) b.onAction("x" + i, true);
        assertEquals(TaskBudget.Verdict.HARD_STOP, b.check(0));
    }

    @Test public void infoMentionsAllAxes() {
        TaskBudget b = new TaskBudget(0);
        b.onModelCall();
        String s = b.info(5000);
        assertTrue(s, s.contains("1/" + TaskBudget.DEF_MODEL_CALLS) && s.contains("5/") && s.contains("动作 0"));
    }

    // ★ fast-screen-v3：有人值守 30 次 / 8 分钟；无人值守 8 次 / 3 分钟、到上限收尾注明「未完成」、不续
    @Test public void attendedBudgetIs30CallsAnd8Minutes() {
        TaskBudget b = TaskBudget.forTask(false, 0);
        assertEquals(30, b.maxCalls);
        assertEquals(8 * 60_000L, b.maxMs);
        assertFalse(b.unattended);
        for (int i = 0; i < 30; i++) { b.onModelCall(); b.onTurn("s" + i); }
        assertEquals(TaskBudget.Verdict.LIMIT, b.check(1));
        assertTrue(b.canAskUser(TaskBudget.Verdict.LIMIT));
        assertTrue(b.canAskUser(TaskBudget.Verdict.STUCK));
        assertFalse(b.canAskUser(TaskBudget.Verdict.HARD_STOP));
    }

    @Test public void unattendedBudgetIs8CallsAnd3MinutesAndNeverAsksOrExtends() {
        TaskBudget b = TaskBudget.forTask(true, 0);
        assertEquals(8, b.maxCalls);
        assertEquals(3 * 60_000L, b.maxMs);
        assertTrue(b.unattended);
        for (int i = 0; i < 7; i++) { b.onModelCall(); b.onTurn("s" + i); }
        assertEquals(TaskBudget.Verdict.OK, b.check(1));
        b.onModelCall(); b.onTurn("s7");
        assertEquals(TaskBudget.Verdict.LIMIT, b.check(1));
        assertFalse(b.canAskUser(TaskBudget.Verdict.LIMIT));
        assertFalse(b.canAskUser(TaskBudget.Verdict.STUCK));
        assertTrue(b.unfinishedReason(), b.unfinishedReason().startsWith("未完成："));
        b.extend();
        assertEquals("无人值守不续", 8, b.maxCalls);
        assertEquals(3 * 60_000L, b.maxMs);
    }

    @Test public void unattendedTimeLimitIsThreeMinutes() {
        TaskBudget b = TaskBudget.unattended(0);
        assertEquals(TaskBudget.Verdict.OK, b.check(3 * 60_000L - 1));
        assertEquals(TaskBudget.Verdict.LIMIT, b.check(3 * 60_000L));
        assertTrue(b.reason.contains("180"));
    }
}
