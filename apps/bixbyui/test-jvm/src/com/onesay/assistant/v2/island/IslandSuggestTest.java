package com.onesay.assistant.v2.island;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ feat/suggest：岛上主动建议卡（渲染复用 NOTE 结果卡）—— 能否放、计时、按钮、上滑 / 横滑、触碰暂停、撤卡、被顶掉。 */
public class IslandSuggestTest {

    IslandModel m;
    static final List<String> ACTS = Arrays.asList("分析", "忽略", "不再提示", "多余的第四个");

    @Before public void setUp() {
        m = new IslandModel();
        m.setMode(IslandModel.Mode.ISLAND);
        m.drainTimers();
    }

    private IslandModel.Timer only(String name) {
        for (IslandModel.Timer t : m.drainTimers()) if (name.equals(t.name)) return t;
        fail("没有计时 " + name);
        return null;
    }

    @Test public void showRendersExpandedNoteCardWithThumbAndAtMostThreeActions() {
        assertTrue(m.canSuggest());
        assertEquals(Arrays.asList(IslandModel.Effect.RENDER), m.suggestShow("s1", "截图了", "来自 设置", "/sdcard/a.png", ACTS, 6000L, 1000L));
        IslandModel.Snapshot v = m.view();
        assertEquals(IslandModel.Kind.EXP_NOTE, v.kind);
        assertTrue(v.suggest);
        assertEquals("/sdcard/a.png", v.noteThumb);
        assertEquals(IslandModel.SUGGEST_META, v.noteMeta);
        assertEquals("来自 设置", v.noteDetail);
        assertEquals(3, v.noteActions.size());
        assertTrue(m.suggestShowing("s1"));
        assertEquals("D1：卡片停留 6 s", 6000L, only("suggestHide").delayMs);
    }

    @Test public void timeoutRemovesAndReportsTimeout() {
        m.suggestShow("s1", "t", null, null, ACTS, 6000L, 0L);
        IslandModel.Timer t = only("suggestHide");
        assertEquals(Arrays.asList(IslandModel.Effect.SUGGEST_TIMEOUT, IslandModel.Effect.RENDER), m.timerDue(t.name, t.seq, t.target));
        assertEquals("s1", m.lastTarget());
        assertFalse(m.suggestShowing("s1"));
        assertEquals(IslandModel.Kind.HIDDEN, m.view().kind);
    }

    @Test public void actionReportsSuggestActionNotNoteAction() {
        m.suggestShow("s1", "t", null, null, ACTS, 6000L, 0L);
        assertEquals(Arrays.asList(IslandModel.Effect.SUGGEST_ACTION, IslandModel.Effect.RENDER), m.noteAction(1));
        assertEquals("s1#1", m.lastTarget());
        assertFalse(m.suggestShowing("s1"));
    }

    @Test public void swipeUpAndTuckAreIgnoreNotTuck() {
        m.suggestShow("s1", "t", null, null, ACTS, 6000L, 0L);
        assertEquals(Arrays.asList(IslandModel.Effect.SUGGEST_SWIPED, IslandModel.Effect.RENDER),
                m.collapseExpanded(IslandModel.CollapseReason.SWIPE_UP));
        assertEquals("s1", m.lastTarget());
        assertFalse(m.suggestShowing("s1"));

        m.suggestShow("s2", "t", null, null, ACTS, 6000L, 0L);
        assertEquals(Arrays.asList(IslandModel.Effect.SUGGEST_SWIPED, IslandModel.Effect.RENDER), m.tuck());
        assertFalse("横滑建议卡不进暂避", m.isTucked());
        assertFalse(m.suggestShowing("s2"));
    }

    @Test public void touchPausesTimerUpToThirtySecondsAndNeverOpensApp() {
        m.suggestShow("s1", "t", null, null, ACTS, 6000L, 0L);
        IslandModel.Timer first = only("suggestHide");
        assertEquals(Arrays.asList(IslandModel.Effect.RENDER), m.noteOpen());
        assertTrue("点正文不执行任何动作、不打开 App", m.suggestShowing("s1"));
        IslandModel.Timer second = only("suggestHide");
        assertEquals(IslandModel.SUGGEST_TOUCH_MAX_MS, second.delayMs);
        assertTrue("旧计时作废", m.timerDue(first.name, first.seq, first.target).isEmpty());
        assertTrue(m.suggestShowing("s1"));
        assertEquals(IslandModel.Effect.SUGGEST_TIMEOUT, m.timerDue(second.name, second.seq, second.target).get(0));
    }

    @Test public void updateKeepsTimerAndWithdrawRemoves() {
        m.suggestShow("s1", "t", "连拍 1 张", null, ACTS, 6000L, 0L);
        IslandModel.Timer t = only("suggestHide");
        assertEquals(Arrays.asList(IslandModel.Effect.RENDER), m.suggestUpdate("s1", "连拍 3 张"));
        assertEquals("连拍 3 张", m.view().noteDetail);
        assertTrue("改副标题不重置计时", m.drainTimers().isEmpty());
        assertEquals(Arrays.asList(IslandModel.Effect.RENDER), m.suggestWithdraw("s1"));
        assertFalse(m.suggestShowing("s1"));
        assertTrue("撤卡后的到时不再报超时", m.timerDue(t.name, t.seq, t.target).isEmpty());
        assertTrue(m.suggestWithdraw("s1").isEmpty());
        assertTrue(m.suggestUpdate("nope", "x").isEmpty());
    }

    @Test public void onlyOneSuggestCardNewReplacesOld() {
        m.suggestShow("s1", "a", null, null, ACTS, 6000L, 0L);
        assertTrue("已展开的是建议卡 → 可替换", m.canSuggest());
        assertNotNull(m.suggestShow("s2", "b", null, null, ACTS, 6000L, 0L));
        assertFalse(m.suggestShowing("s1"));
        assertTrue(m.suggestShowing("s2"));
        assertEquals("s2", m.expandedKey());
    }

    @Test public void refusedWhenIslandBusyOrHidden() {
        m.setFullscreen(true);
        assertFalse("全屏沉浸", m.canSuggest());
        assertNull(m.suggestShow("s1", "t", null, null, ACTS, 6000L, 0L));
        m.setFullscreen(false);

        m.taskStarted(0L, "任务");
        assertFalse("前台任务在跑", m.canSuggest());
        assertNull(m.suggestShow("s1", "t", null, null, ACTS, 6000L, 0L));
    }

    @Test public void refusedWhenNotIslandModeOrDisabled() {
        IslandModel n = new IslandModel();
        assertFalse(n.canSuggest());
        m.configure(false, true);
        assertFalse(m.canSuggest());
    }

    @Test public void refusedWhenAnotherCardIsExpanded() {
        m.noteStarted("n1", "记一记", 0L);
        m.noteResult("n1", true, "取件码", "5-2-1234", "记一记", Arrays.asList("复制"), 0L);
        assertEquals("n1", m.expandedKey());
        assertFalse("别的卡展开着 → 不顶掉它", m.canSuggest());
        assertNull(m.suggestShow("s1", "t", null, null, ACTS, 6000L, 0L));
    }

    @Test public void suggestCardOnlyVisibleWhileExpanded() {
        m.suggestShow("s1", "t", null, null, ACTS, 6000L, 0L);
        m.noteResult("n1", true, "取件码", "5-2-1234", "记一记", Arrays.asList("复制"), 0L);   // 记一记结果卡顶掉建议卡的展开
        assertEquals("n1", m.expandedKey());
        IslandModel.Snapshot v = m.view();
        assertEquals("n1", v.key);
        assertFalse("建议卡不再占位", "s1".equals(v.key) || "s1".equals(v.bubbleKey));
    }
}
