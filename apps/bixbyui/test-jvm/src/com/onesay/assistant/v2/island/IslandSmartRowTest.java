package com.onesay.assistant.v2.island;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 岛上的新能力：计时（通话时长 / 计时器倒计时）、点的颜色、「静音 / 免提」点完卡片不收。
 * 以及接线：通知监听最先调智能筛选、面板服务起来时登记智能来源、日志不带通知正文 / 号码 / 验证码。
 */
public class IslandSmartRowTest {

    IslandModel m;

    @Before public void setUp() {
        m = new IslandModel();
        m.setMode(IslandModel.Mode.ISLAND);
        m.drainTimers();
    }

    static IslandModel.NoticeRow call(String id, String a0) {
        return new IslandModel.NoticeRow(id, "通话中 · 张三", "", "通话", false, Arrays.asList(a0, "免提", "挂断"),
                1_000_000L, false, "call", 0b011);
    }

    @Test public void chronoTextUpAndDown() {
        IslandModel.NoticeRow up = new IslandModel.NoticeRow("a", "t", "", "", false, null, 1_000_000L, false, "", 0);
        assertEquals("00:00", up.chronoText(999_000L));
        assertEquals("01:05", up.chronoText(1_065_000L));
        assertEquals("1:00:01", up.chronoText(1_000_000L + 3_601_000L));
        IslandModel.NoticeRow down = new IslandModel.NoticeRow("b", "t", "", "", false, null, 1_300_000L, true, "", 0);
        assertEquals("05:00", down.chronoText(1_000_000L));
        assertEquals("00:01", down.chronoText(1_299_500L));
        assertEquals("00:00", down.chronoText(2_000_000L));
        IslandModel.NoticeRow none = new IslandModel.NoticeRow("c", "t", "", "", false, null);
        assertEquals("", none.chronoText(5L));
        assertFalse(none.countdown);
    }

    @Test public void rowEqualityIncludesNewFields() {
        assertEquals(call("x", "静音"), call("x", "静音"));
        assertNotEquals("按钮文字变了要重画", call("x", "静音"), call("x", "取消静音"));
        assertNotEquals(new IslandModel.NoticeRow("a", "t", "", "", false, null, 0L, false, "mic", 0),
                new IslandModel.NoticeRow("a", "t", "", "", false, null, 0L, false, "cam", 0));
    }

    @Test public void keepOpenActionDoesNotCollapseOrRemove() {
        List<IslandModel.NoticeRow> rows = new ArrayList<IslandModel.NoticeRow>(Arrays.asList(call("c", "静音")));
        m.setNotices(rows, false, false);
        m.tapMain();
        assertEquals(IslandModel.Kind.EXP_NOTICE, m.view().kind);
        List<IslandModel.Effect> e = m.noticeAction(0);   // 静音
        assertTrue(e.contains(IslandModel.Effect.NOTICE_ACTION));
        assertEquals("c#0", m.lastTarget());
        assertEquals("静音后卡片不收", IslandModel.Kind.EXP_NOTICE, m.view().kind);
        assertEquals("这一行还在", 1, m.noticeCount());
        // 来源更新按钮文字（setNotices 同 id 新内容）→ 仍展开
        m.setNotices(new ArrayList<IslandModel.NoticeRow>(Arrays.asList(call("c", "取消静音"))), false, false);
        assertEquals(IslandModel.Kind.EXP_NOTICE, m.view().kind);
        assertEquals("取消静音", m.view().notices.get(0).actions.get(0));
        // 挂断（不在掩码里）→ 照旧移除 + 收起
        m.noticeAction(2);
        assertEquals(0, m.noticeCount());
        assertFalse(m.view().kind == IslandModel.Kind.EXP_NOTICE);
    }

    @Test public void dotColorsAndCompactText() {
        assertEquals("mic", new IslandModel.NoticeRow("a", "t", "", "", false, null, 0L, false, "mic", 0).dotClass());
        assertEquals("cam", new IslandModel.NoticeRow("a", "t", "", "", false, null, 0L, false, "call", 0).dotClass());
        assertEquals("warn", new IslandModel.NoticeRow("a", "t", "", "", true, null, 0L, false, "warn", 0).dotClass());
        assertEquals("high", new IslandModel.NoticeRow("a", "t", "", "", true, null).dotClass());
        assertEquals("default", new IslandModel.NoticeRow("a", "t", "", "", false, null).dotClass());
        m.setNotices(new ArrayList<IslandModel.NoticeRow>(Arrays.asList(call("c", "静音"))), false, false);
        IslandModel.Snapshot s = m.view();
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, s.kind);
        assertEquals("通话中 · 张三 01:40", IslandModel.compactNoticeText(s, 1_100_000L));
        assertTrue("有计时的紧凑态要每秒重画", IslandModel.noticeChronoShown(s));
        m.setNotices(new ArrayList<IslandModel.NoticeRow>(Arrays.asList(new IslandModel.NoticeRow("p", "t", "", "", false, null))), false, false);
        assertFalse("没计时不额外重画", IslandModel.noticeChronoShown(m.view()));
    }

}
