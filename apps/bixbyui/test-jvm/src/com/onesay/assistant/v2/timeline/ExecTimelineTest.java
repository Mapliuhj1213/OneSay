package com.onesay.assistant.v2.timeline;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** ★ feat/api-first：执行时间轴 —— 合并、状态、审批卡算一步、失败带原因、停止、完成后折叠。 */
public class ExecTimelineTest {

    @Test public void consecutiveSameToolMerges() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("搜索：江门 到 上海 高铁");
        t.toolResult(true, "");
        t.toolStart("搜索：夜间卧铺");
        assertEquals("正在调用 搜索", t.title());
        assertEquals("正在调用 搜索 ▲", t.header());
        assertEquals(Arrays.asList("● 正在调用 搜索（第2次）"), t.lines());
        t.toolResult(true, "");
        t.toolStart("读取：携程");
        t.toolResult(true, "");
        t.toolStart("搜索：中转");
        assertEquals(Arrays.asList("✓ 已调用 搜索 2次", "✓ 已调用 读取", "● 正在调用 搜索"), t.lines());
    }

    @Test public void nextStartImpliesPreviousSucceeded() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("查记忆");
        t.toolStart("设闹钟：7:30");   // 上一步没报结果 → 算成功
        assertEquals(Arrays.asList("✓ 已调用 查记忆", "● 正在调用 设闹钟"), t.lines());
    }

    @Test public void failureCarriesReasonAndSplitsMergedRow() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("读取：京东");
        t.toolResult(true, "");
        t.toolStart("读取：淘宝");
        t.toolResult(false, "要登录");
        assertEquals(Arrays.asList("✓ 已调用 读取", "✗ 读取 失败：要登录"), t.lines());
        // 只有 note（老路径，没有原因）也记失败
        t.toolStart("搜索：x");
        t.note("搜索失败：x");
        assertEquals("✗ 搜索 失败：没成功", t.lines().get(2));
        // 失败行之后同一工具再调用：不并进失败行
        t.toolStart("搜索：y");
        assertEquals("● 正在调用 搜索", t.lines().get(3));
    }

    @Test public void approvalIsAStep() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("搜索：x");
        t.toolResult(true, "");
        t.approval("发短信给 妈妈");
        assertEquals("等你确认", t.title());
        assertEquals("等你确认", t.statusLine());
        assertEquals("? 等你确认：发短信给 妈妈", t.lines().get(1));
        t.approvalResolved(true);
        assertEquals("✓ 已确认：发短信给 妈妈", t.lines().get(1));
        t.approval("删除全部闹钟");
        t.approvalResolved(false);
        assertEquals("✗ 没批准：删除全部闹钟", t.lines().get(2));
    }

    @Test public void finishCollapsesToDone() {
        ExecTimeline t = new ExecTimeline();
        assertFalse("没有步骤不显示", t.visible());
        t.thinking(true);
        assertEquals("正在思考", t.title());
        t.toolStart("日历");
        assertTrue(t.visible());
        assertTrue(t.expanded());
        assertTrue(t.live());
        t.finish(true);
        assertEquals("已完成", t.title());
        assertEquals("已完成 ▼", t.header());
        assertFalse(t.expanded());
        assertFalse(t.live());
        assertEquals(Arrays.asList("✓ 已调用 日历"), t.lines());
        assertTrue(t.toggle());
        assertEquals("已完成 ▲", t.header());
        t.toolStart("迟到的事件");   // 结束后的事件不再改时间轴
        assertEquals(1, t.lines().size());
    }

    @Test public void notDoneOutcome() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("操作：美团");
        t.finish(false);
        assertEquals("没完成", t.title());
    }

    @Test public void stoppedRecorded() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("搜索：x");
        t.toolResult(true, "");
        t.toolStart("操作：12306");
        t.stopped();
        assertEquals("已停止", t.title());
        assertEquals(Arrays.asList("✓ 已调用 搜索", "■ 操作 已停止", "■ 已停止"), t.lines());
        t.finish(true);   // 停止后再来的结局不覆盖「已停止」
        assertEquals("已停止", t.title());
        assertFalse(t.expanded());
    }

    @Test public void statusLineForBackground() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("读取：什么值得买");
        assertEquals("调用中：读取", t.statusLine());
        t.toolResult(true, "");
        assertEquals("调用中", t.statusLine());
    }

    @Test public void labelFromIslandStepText() {
        assertEquals("搜索", ExecTimeline.labelOf("搜索：二手摩托车 价格"));
        assertEquals("设闹钟", ExecTimeline.labelOf("设闹钟：7:30"));
        assertEquals("读屏幕", ExecTimeline.labelOf("读屏幕"));
        assertEquals("处理", ExecTimeline.labelOf("正在处理"));
        assertEquals("一二三四五六七八九十…", ExecTimeline.labelOf("一二三四五六七八九十十一"));
    }

    @Test public void noBrandWordsInUserText() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("搜索：x");
        t.approval("y");
        t.stopped();
        for (String s : t.lines()) {
            assertFalse(s, s.contains("Bixby") || s.contains("三星"));
        }
    }
}
