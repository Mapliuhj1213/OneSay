package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.onesay.assistant.v2.ui.wake.WakeStage.Event;
import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;
import com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.Body;

/** [lane C 第 1 项] 窗口交互：回答全屏 / 任务立刻进岛 / 完成只在岛上反馈；⌨ 不再把上一条回答刷掉。 */
public class WindowFlowPolicyTest {

    /**
     * 复现用户报告的 bug：一问一答结束（RESULT，正文已定稿）→ 点 ⌨（TEXT_MODE）→ 正文区必须仍是上一条回答。
     * 修复前 body(TEXT, …) 落进 default 分支返回 NONE，结果卡被 GONE 掉。
     */
    @Test public void keyboardAfterAnswerKeepsPreviousAnswer() {
        WakeStage s = new WakeStage();
        s.on(Event.WAKE);
        s.on(Event.THINK);
        s.on(Event.ANSWER_STREAM);
        s.on(Event.RESULT);
        assertEquals(Body.FINAL, WindowFlowPolicy.body(s.stage(), true, true));
        WakeStage.Transition t = s.on(Event.TEXT_MODE);
        assertEquals(Stage.TEXT, t.to);
        assertEquals("点键盘后上一条回答不能被刷掉", Body.FINAL, WindowFlowPolicy.body(s.stage(), true, true));
    }

    @Test public void keyboardWithoutAnswerShowsNothing() {
        assertEquals(Body.NONE, WindowFlowPolicy.body(Stage.TEXT, false, false));
        assertEquals("流式没收完就点了键盘：不展示半截正文", Body.NONE, WindowFlowPolicy.body(Stage.TEXT, true, false));
    }

    @Test public void bodyTableUnchangedForOtherStages() {
        assertEquals(Body.THINKING_PLACEHOLDER, WindowFlowPolicy.body(Stage.THINKING, false, false));
        assertEquals(Body.STREAM, WindowFlowPolicy.body(Stage.THINKING, true, false));
        assertEquals(Body.STREAM, WindowFlowPolicy.body(Stage.ANSWERING, true, false));
        assertEquals(Body.FINAL, WindowFlowPolicy.body(Stage.RESULT, true, true));
        assertEquals(Body.NONE, WindowFlowPolicy.body(Stage.RESULT, false, false));
        assertEquals(Body.TASK, WindowFlowPolicy.body(Stage.TASK, false, false));
        assertEquals(Body.FINAL, WindowFlowPolicy.body(Stage.CONFIRM, true, true));
        assertEquals(Body.NONE, WindowFlowPolicy.body(Stage.CONFIRM, true, false));
        assertEquals(Body.NONE, WindowFlowPolicy.body(Stage.LISTENING, true, true));
        assertEquals(Body.NONE, WindowFlowPolicy.body(Stage.ERROR, true, true));
        assertEquals(Body.NONE, WindowFlowPolicy.body(null, true, true));
    }

    @Test public void fullWindowOnlyForNewUi() {
        assertTrue(WindowFlowPolicy.fullWindow(true, null));
        assertTrue(WindowFlowPolicy.fullWindow(true, "full"));
        assertTrue("写错按默认 full", WindowFlowPolicy.fullWindow(true, "xx"));
        assertFalse(WindowFlowPolicy.fullWindow(true, " WRAP "));
        assertFalse("旧界面一律 WRAP_CONTENT", WindowFlowPolicy.fullWindow(false, "full"));
    }

    @Test public void cardFullWhileAnswering() {
        assertTrue(WindowFlowPolicy.cardFull(Stage.ANSWERING));
        assertTrue(WindowFlowPolicy.cardFull(Stage.RESULT));
        assertTrue("确认卡在全屏卡上可用", WindowFlowPolicy.cardFull(Stage.CONFIRM));
        assertTrue(WindowFlowPolicy.cardFull(Stage.TEXT));
        assertFalse(WindowFlowPolicy.cardFull(Stage.LISTENING));
        assertFalse("思考可能马上转任务：不先拉满", WindowFlowPolicy.cardFull(Stage.THINKING));
        assertFalse(WindowFlowPolicy.cardFull(Stage.TASK));
        assertFalse(WindowFlowPolicy.cardFull(Stage.ERROR));
        assertFalse(WindowFlowPolicy.cardFull(Stage.ISLAND));
        assertFalse(WindowFlowPolicy.cardFull(null));
    }

    @Test public void collapseImmediatelyOnNewUi() {
        assertTrue(WindowFlowPolicy.collapseDelayMs(true) <= 200L);
        assertEquals(6000L, WindowFlowPolicy.collapseDelayMs(false));
        assertTrue(WindowFlowPolicy.islandOnlyAfterTask(true));
        assertFalse(WindowFlowPolicy.islandOnlyAfterTask(false));
    }

    /** [r2/bike-chooser ⑤] C1 回归：结论带应用网格（卡片上才有的可点内容）→ 不能只进岛，要挂回卡片。 */
    @Test public void taskWithCardOnlyContentReturnsToCard() {
        assertTrue(WindowFlowPolicy.islandOnlyAfterTask(true, false));
        assertFalse(WindowFlowPolicy.islandOnlyAfterTask(true, true));
        assertFalse(WindowFlowPolicy.islandOnlyAfterTask(false, true));
        assertFalse(WindowFlowPolicy.islandOnlyAfterTask(false, false));
    }

    @Test public void islandSummaryFitsAnyLength() {
        assertNull(WindowFlowPolicy.islandSummary(null));
        assertNull(WindowFlowPolicy.islandSummary("  "));
        String[] a = WindowFlowPolicy.islandSummary("alarm");
        assertEquals("闹钟已设好", a[0]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('字');
        String[] l = WindowFlowPolicy.islandSummary(sb.toString());
        assertNotNull(l);
        assertTrue(l[0].length() <= WindowFlowPolicy.HEADLINE_MAX);
        assertTrue(l[0].endsWith("…"));
        String[] two = WindowFlowPolicy.islandSummary("邮件整理好了\n3 封需要你回复\n第三行\n第四行");
        assertNotNull(two[0]);
        assertFalse(two[0].contains("\n"));
        if (two[1] != null) assertFalse(two[1].contains("\n"));
    }
}
