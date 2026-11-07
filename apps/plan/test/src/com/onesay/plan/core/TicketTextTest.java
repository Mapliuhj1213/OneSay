package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.TimeZone;

/** 从短信 / 订单文字认票：认得出的要准，认不出的不猜。固件全部虚构（人名、订单号、取票码、影城均为假数据）。 */
public class TicketTextTest {

    static final TimeZone TZ = RemindersTest.TZ;
    static final long NOW = RemindersTest.NOW;

    @Test public void train12306Sms() {
        Row r = TicketText.parse("【铁路客服】订单E123456789,张三您已购11月8日G1234次05车12F号,上海虹桥站14:30开,检票口:A12。", NOW, TZ);
        assertNotNull(r);
        assertEquals("train", r.s("kind"));
        assertEquals("G1234", r.s("number"));
        assertEquals(RemindersTest.at(2026, 11, 8, 14, 30), r.l("depart_at"));
        assertEquals("05车12F号", r.s("seat"));
        assertEquals("A12", r.s("gate"));
        assertEquals("E123456789", r.s("order_no"));
        assertEquals("上海虹桥", r.s("from_place"));
        assertTrue(Validator.validate(Contract.T_TICKETS, without(r), true, null).ok());
    }

    @Test public void trainRoute() {
        Row r = TicketText.parse("D3125次 杭州东-南京南 11月9日 08:05开 07车03A号", NOW, TZ);
        assertNotNull(r);
        assertEquals("杭州东", r.s("from_place"));
        assertEquals("南京南", r.s("to_place"));
        assertEquals("D3125", r.s("number"));
    }

    @Test public void flight() {
        Row r = TicketText.parse("您预订的11月20日 CA1858 航班 上海浦东-北京首都 08:30起飞，登机口 23，座位 32C。", NOW, TZ);
        assertNotNull(r);
        assertEquals("flight", r.s("kind"));
        assertEquals("CA1858", r.s("number"));
        assertEquals(RemindersTest.at(2026, 11, 20, 8, 30), r.l("depart_at"));
        assertEquals("23", r.s("gate"));
        assertEquals("32C", r.s("seat"));
    }

    @Test public void movie() {
        Row r = TicketText.parse("《流浪地球3》 示例影城(虚构路店) 5号厅 11月7日 19:40 7排8座 取票码 832917", NOW, TZ);
        assertNotNull(r);
        assertEquals("movie", r.s("kind"));
        assertEquals("流浪地球3", r.s("title"));
        assertTrue(r.s("venue").contains("5号厅"));
        assertEquals("7排8座", r.s("seat"));
        assertEquals("832917", r.s("order_no"));
    }

    @Test public void dateRollsToNextYear() {
        Row r = TicketText.parse("G7 次 1月3日 10:00开 北京南站", NOW, TZ);
        assertNotNull(r);
        assertEquals(RemindersTest.at(2027, 1, 3, 10, 0), r.l("depart_at"));
    }

    @Test public void notATicket() {
        assertNull(TicketText.parse("您的验证码是 123456，5 分钟内有效", NOW, TZ));
        assertNull(TicketText.parse("今晚 19:00 一起吃饭吗", NOW, TZ));
        assertNull(TicketText.parse(null, NOW, TZ));
        assertNull(TicketText.parse("车次信息查询", NOW, TZ));
    }

    static Row without(Row r) {
        Row x = new Row(r);
        x.remove("source");
        return x;
    }
    // ---- 2026-11-05 票根边界测试页（device/bp-ticket/*.html 的可见文字，内容全是假的）----
    static final String PAGE_A = "测试影票 · 购票成功\n⚠ 测试页面，非真实票据，所有信息均为虚构（Bixby 识别边界测试）\n测试影片：边界之外（测试）\n2D 国语 · 120 分钟\n影院\n测试影城（虚构路 1 号店）\n场次\n2026年11月8日 周日 19:30\n影厅 / 座位\n9 号厅 · 7排8座、7排9座\n取票码\nTEST 8888 0001\n订单号\nTESTORDER20261108001\n凭二维码或取票码在测试影城自助机取票\n实付\n¥0.00（测试）";
    static final String PAGE_B_FIRST = "测试影票 · 订单详情\n⚠ 测试页面，非真实票据，所有信息均为虚构（Bixby 识别边界测试）\n订单状态\n已出票（取票码稍后短信发送）\n订单号\nTESTORDER20261109002\n商品\n电影票 × 1\n实付\n¥0.00（测试）\n支付方式\n测试钱包\n";
    static final String PAGE_B_FULL = "测试影票 · 订单详情\n⚠ 测试页面，非真实票据，所有信息均为虚构（Bixby 识别边界测试）\n订单状态\n已出票（取票码稍后短信发送）\n订单号\nTESTORDER20261109002\n商品\n电影票 × 1\n实付\n¥0.00（测试）\n支付方式\n测试钱包\n退改签说明\n开场前 60 分钟可退（测试文字）。\n测试说明文字。\n测试影片：边界之外（测试）\n场次\n11月9日 14:00\n影院\n测试影城（虚构路 2 号店）\n座位\n5 号厅 3排12座";
    static final String PAGE_C = "测试影票 · 选座\n⚠ 测试页面，非真实票据，所有信息均为虚构（Bixby 识别边界测试）\n测试影片：边界之外（测试）\n测试影城（虚构路 1 号店） · 11月10日 20:15 · 9 号厅\n银幕\n已选\n7排8座、7排9座\n待支付\n¥0.00（测试） · 请在 14:59 内完成支付\n确认支付（测试，不会扣款）";

    @Test public void boundaryPageA_successHasPickupCodeNotOrderNo() {
        Row r = TicketText.parse(PAGE_A, NOW, TZ);
        assertNotNull(r);
        assertEquals("movie", r.s("kind"));
        assertEquals(RemindersTest.at(2026, 11, 8, 19, 30), r.l("depart_at"));
        assertEquals("测试影城 9号厅", r.s("venue"));
        assertEquals("7排8座", r.s("seat"));
        assertEquals("取票码优先、去空格", "TEST88880001", r.s("order_no"));
        assertEquals("没有《》书名号时片名认不出，只写「电影」", "电影", r.s("title"));
    }

    @Test public void boundaryPageB_firstScreenNotEnoughFullTextOk() {
        assertNull("首屏只有订单号没有影院 / 场次：不建", TicketText.parse(PAGE_B_FIRST, NOW, TZ));
        Row r = TicketText.parse(PAGE_B_FULL, NOW, TZ);
        assertNotNull(r);
        assertEquals(RemindersTest.at(2026, 11, 9, 14, 0), r.l("depart_at"));
        assertEquals("取票码还没发：退回订单号", "TESTORDER20261109002", r.s("order_no"));
    }

    @Test public void boundaryPageC_unpaidSeatSelectionIsNotATicket() {
        assertNull(TicketText.parse(PAGE_C, NOW, TZ));
        assertNull(TicketText.parse("示例影城 3号厅 11月8日 19:30 7排8座 待支付 ¥45", NOW, TZ));
        assertNotNull("付过了的照样认", TicketText.parse("示例影城 3号厅 11月8日 19:30 7排8座 支付成功 取票码 1234567", NOW, TZ));
    }
}
