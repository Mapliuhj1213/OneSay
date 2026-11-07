package com.onesay.assistant.v2.island;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.onesay.assistant.v2.island.IslandModel.Effect;
import com.onesay.assistant.v2.island.IslandModel.Mode;
import com.onesay.assistant.v2.island.IslandModel.Sheet;
import com.onesay.assistant.v2.ui.wake.WindowFlowPolicy;

/**
 * [lane C 第 1 项] 岛模型在「新流程」下：任务一开始就排很短的收起；确认卡挡住收起时，裁决后重新排；
 * 完成后（任意长度结论）在岛上展开结果卡、不挂回卡片；岛上确认卡可允许 / 拒绝。
 */
public class IslandLaneCFlowTest {

    static IslandModel fresh(long delay) {
        IslandModel x = new IslandModel();
        x.setMode(Mode.ISLAND);
        x.configure(true, true, delay);
        x.drainTimers();
        return x;
    }

    static IslandModel.Timer find(List<IslandModel.Timer> l, String n) {
        for (IslandModel.Timer t : l) if (t.name.equals(n)) return t;
        return null;
    }

    @Test public void taskCollapsesImmediately() {
        IslandModel x = fresh(WindowFlowPolicy.collapseDelayMs(true));
        x.taskStarted(1000L, "整理今天的邮件");
        IslandModel.Timer t = find(x.drainTimers(), "autoCollapse");
        assertTrue(t != null);
        assertEquals(WindowFlowPolicy.COLLAPSE_DELAY_NEW_MS, t.delayMs);
        List<Effect> e = x.autoCollapseDue(t.seq);
        assertTrue(e.contains(Effect.DETACH_SHEET));
        assertEquals(Sheet.COLLAPSED, x.sheet());
    }

    @Test public void classicKeepsSixSeconds() {
        IslandModel x = new IslandModel();
        x.setMode(Mode.ISLAND);
        x.configure(true, true);
        x.drainTimers();
        x.taskStarted(1000L, "t");
        assertEquals(IslandModel.AUTO_COLLAPSE_MS, find(x.drainTimers(), "autoCollapse").delayMs);
    }

    @Test public void confirmOnFullCardThenCollapse() {
        IslandModel x = fresh(WindowFlowPolicy.COLLAPSE_DELAY_NEW_MS);
        x.taskStarted(1000L, "发邮件给老板");
        x.drainTimers();
        x.confirmRequired("发送邮件", "收件人：boss");      // 还没收起：确认卡在全屏卡片上
        assertTrue("确认未决不能收", x.autoCollapseDue(x.taskSeq()).isEmpty());
        x.confirmResolved();
        IslandModel.Timer t = find(x.drainTimers(), "autoCollapse");
        assertTrue("裁决后任务还在跑：重新排收起", t != null);
        assertTrue(x.autoCollapseDue(t.seq).contains(Effect.DETACH_SHEET));
    }

    @Test public void classicConfirmDoesNotReschedule() {
        IslandModel x = new IslandModel();
        x.setMode(Mode.ISLAND);
        x.configure(true, true);
        x.taskStarted(1000L, "t");
        x.drainTimers();
        x.confirmRequired("a", "b");
        x.confirmResolved();
        assertTrue(find(x.drainTimers(), "autoCollapse") == null);
    }

    @Test public void confirmOnIslandAllowDeny() {
        IslandModel x = fresh(WindowFlowPolicy.COLLAPSE_DELAY_NEW_MS);
        x.taskStarted(1000L, "t");
        x.autoCollapseDue(x.taskSeq());
        x.drainTimers();
        List<Effect> e = x.confirmRequired("打开飞行模式", "断网类开关");
        assertFalse("收在岛上：不挂回卡片", e.contains(Effect.ATTACH_SHEET));
        assertEquals(IslandModel.K_TASK, x.expandedKey());
    }

    @Test public void longResultStaysOnIsland() {
        IslandModel x = fresh(WindowFlowPolicy.COLLAPSE_DELAY_NEW_MS);
        x.taskStarted(1000L, "查一下明天的会议");
        x.autoCollapseDue(x.taskSeq());
        x.drainTimers();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('长');
        String[] hb = WindowFlowPolicy.islandSummary(sb.toString());
        List<Effect> e = x.taskEndedShort(false, false, true, hb[0], hb[1], "查看", null, 5000L);
        assertFalse("长结论也不挂回卡片", e.contains(Effect.ATTACH_SHEET));
        assertTrue(e.contains(Effect.RENDER));
    }

    @Test public void islandOffFallsBackToSheet() {
        IslandModel x = new IslandModel();
        x.setMode(Mode.NONE);
        x.configure(true, true, WindowFlowPolicy.COLLAPSE_DELAY_NEW_MS);
        x.taskStarted(1000L, "t");
        assertTrue("没有浮层：不排收起", find(x.drainTimers(), "autoCollapse") == null);
    }
}
