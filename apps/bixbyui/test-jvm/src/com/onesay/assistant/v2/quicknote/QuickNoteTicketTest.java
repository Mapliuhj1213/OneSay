package com.onesay.assistant.v2.quicknote;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.vision.screenref.EntityExtractor;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * 记一记认票（2026-11-05）：选座 / 待支付页不建日程、不记账、不进票夹；取票码 / 检票码认成码；0 元订单标题用片名 / 订单号。
 */
public class QuickNoteTicketTest {

    private static final TimeZone CN = TimeZone.getTimeZone("Asia/Shanghai");
    /** 2026-10-10 10:40 CST */
    private static final long NOW = 1791600000000L;
    private static final String MAOYAN = "com.sankuai.movie";

    private static QuickNoteClassifier.Note cls(String pkg, String... lines) {
        List<String> l = Arrays.asList(lines);
        return QuickNoteClassifier.classify(pkg, l, EntityExtractor.extractFromTexts(l, NOW, CN), NOW);
    }

    private static boolean hasCalendar(QuickNoteClassifier.Note n) {
        QuickNoteEntry e = new QuickNoteBook(new InMemoryQuickNoteStore(), CN).toEntry(n, NOW, null);
        for (QuickNoteActions.Action a : QuickNoteActions.all(e, CN, NOW)) if (QuickNoteActions.CALENDAR.equals(a.type)) return true;
        return false;
    }

    @Test
    public void unpaidPage_noScheduleNoLedger() {
        QuickNoteClassifier.Note n = cls(MAOYAN, "待支付", "《流浪地球3》", "万达影城(CBD店) 5号厅", "10月12日 19:30", "7排8座",
                "实付 ¥45.00", "支付剩余时间 14:52", "立即支付");
        assertTrue(n.saved());
        assertTrue(n.title, n.title.startsWith("待支付"));
        assertTrue(n.title, n.title.contains("流浪地球3"));
        assertTrue(n.tags.contains("待支付"));
        assertEquals(0L, n.eventAt);
        assertEquals("不记账", 0L, n.amountFen);
        assertFalse("不给加日程", hasCalendar(n));
        assertNotEquals(QuickNoteClassifier.Category.SCHEDULE, n.category);

        QuickNoteClassifier.Note seat = cls(MAOYAN, "选座", "万达影城(CBD店) 3号厅", "今天 20:10", "已选 5排6座", "¥39.9 确认选座");
        assertTrue(seat.tags.contains("待支付"));
        assertEquals(0L, seat.eventAt);
        assertEquals(0L, seat.amountFen);
        assertFalse(hasCalendar(seat));
    }

    @Test
    public void paidTicket_pickupAndCheckinCodeAreCodes() {
        QuickNoteClassifier.Note n = cls(MAOYAN, "购票成功", "《流浪地球3》", "万达影城(CBD店) 5号厅", "10月12日 19:30", "7排8座",
                "取票码 8826 3301", "实付 ¥45.00");
        assertEquals(QuickNoteClassifier.Category.PICKUP, n.category);
        assertEquals("取票码", n.codeKind);
        assertEquals("88263301", n.codeValue);
        assertFalse(n.tags.contains("待支付"));
        assertTrue("已付的票照样可加日程", n.eventAt > 0);

        QuickNoteClassifier.Note g = cls("com.taobao.movie.android", "出票成功", "检票码：TK20391", "国家大剧院 话剧《茶馆》", "10月20日 19:30");
        assertEquals("检票码", g.codeKind);
        assertEquals("TK20391", g.codeValue);
        assertNull("取票码不是密码 / 验证码", com.onesay.assistant.v2.s2.SensitiveGuard.reason("取票码 8826 3301"));
        // 值机选座不是待支付
        assertFalse(QuickNoteClassifier.unpaidTicketPage("航班 MU5101 已值机 在线选座 10月12日 08:00"));
    }

    @Test
    public void zeroYuanOrder_titleUsesFilmOrOrderNo_andHooksSkipUnpaidMirror() throws Exception {
        QuickNoteClassifier.Note n = cls(MAOYAN, "订单详情", "《流浪地球3》 兑换券观影", "订单号 E8820193", "实付 ¥0.00");
        assertEquals(QuickNoteClassifier.Category.ORDER, n.category);
        assertFalse(n.title, n.title.contains("¥0"));
        assertEquals("订单 《流浪地球3》", n.title);
        assertEquals(0L, n.amountFen);

        QuickNoteClassifier.Note o = cls("com.taobao.taobao", "订单详情", "订单号 E8820193", "实付 ¥0.00", "已完成");
        assertEquals("订单 E8820193", o.title);

        QuickNoteClassifier.Note paid = cls("com.taobao.taobao", "订单详情", "订单号 E8820193", "实付 ¥45.00", "已完成");
        assertEquals("订单 ¥45.00", paid.title);

        String root = System.getProperty("bixby.root", ".");
        String hooks = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/quicknote/QuickNoteHooks.java").toPath()), StandardCharsets.UTF_8);
        assertTrue("待支付页不进计划 App 票夹", hooks.contains("e.tags.contains(\"待支付\") ? \"\""));
    }
}
