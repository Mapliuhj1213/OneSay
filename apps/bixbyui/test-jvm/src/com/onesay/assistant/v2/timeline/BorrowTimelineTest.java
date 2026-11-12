package com.onesay.assistant.v2.timeline;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ borrow-1112：时间轴说明行（等待期间画面变化 / 已切换到备用模型）不打乱工具行。 */
public class BorrowTimelineTest {

    @Test public void infoRowsDoNotDisturbToolRows() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("点界面：微信");
        t.info("等待中画面变化：出现「支付成功」");
        t.info("等待中画面变化：出现「支付成功」");   // 连着一样的只记一次
        assertEquals("正在调用 点界面", t.title());
        t.toolResult(true, "");
        assertEquals(2, t.rows().size());
        assertEquals("· 等待中画面变化：出现「支付成功」", t.lines().get(1));
        t.toolStart("点界面：微信");
        assertEquals("说明行后面的同名工具不并进说明行", 3, t.rows().size());
        t.finish(true);
        assertEquals("已完成", t.title());
        t.info("晚到的");
        assertEquals(3, t.rows().size());
    }

    @Test public void infoCappedAndStoppedLeavesInfoAlone() {
        ExecTimeline t = new ExecTimeline();
        t.toolStart("搜索：x");
        for (int i = 0; i < 30; i++) t.info("变化 " + i);
        int infos = 0;
        for (ExecTimeline.Row r : t.rows()) if (r.info) infos++;
        assertEquals(ExecTimeline.MAX_INFO, infos);
        t.stopped();
        for (ExecTimeline.Row r : t.rows()) if (r.info) assertEquals(ExecTimeline.State.DONE, r.state);
    }

    @Test public void notesFanOutToListeners() {
        final java.util.List<String> got = new java.util.ArrayList<String>();
        TimelineNotes.Listener l = new TimelineNotes.Listener() {
            @Override public void onNote(String text) {
                got.add(text);
            }
        };
        TimelineNotes.add(l);
        try {
            TimelineNotes.post("  已切换到备用模型（x）  ");
            TimelineNotes.post("");
            TimelineNotes.post(null);
        } finally {
            TimelineNotes.remove(l);
        }
        assertEquals(java.util.Collections.singletonList("已切换到备用模型（x）"), got);
    }
}
