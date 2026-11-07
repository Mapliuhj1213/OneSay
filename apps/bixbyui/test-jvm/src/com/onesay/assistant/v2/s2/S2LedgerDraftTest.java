package com.onesay.assistant.v2.s2;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * [s2] #20 闪记记账 —— <b>设计稿</b>（2026-10-08 用户调整：代码保留、默认关、不进分诊路由）。
 * 这里锁定两件事：①默认关时记账句式完全不被 S2 接管；②打开后（待用户确认）解析与求和的口径。
 */
public class S2LedgerDraftTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final S2Engine.Flags DEFAULT = new S2Engine.Flags(true, true, false);
    static final S2Engine.Flags ON = new S2Engine.Flags(true, true, true);
    InMemoryMemoryStore st;
    S2Engine en;

    static long t(int month, int day, int h) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, month - 1, day, h, 0, 0);
        return c.getTimeInMillis();
    }

    @Before public void setUp() {
        st = new InMemoryMemoryStore();
        en = new S2Engine(st, st, TZ);
    }

    @Test public void defaultOffNotRouted() {
        assertFalse("记账默认关", S2Settings.defaultOf(S2Settings.KEY_LEDGER));
        assertTrue(S2Settings.defaultOf(S2Settings.KEY_MEMORY));
        assertTrue(S2Settings.defaultOf(S2Settings.KEY_HABIT));
        String[] q = {"午饭花了35", "昨天买菜花了六十二块五", "这个月花了多少", "最近记的账", "删掉刚才那笔"};
        for (String s : q) assertNull(s, en.handle(s, t(10, 8, 12), DEFAULT));
        assertFalse(S2Engine.bypassScreenRef("删掉刚才那笔", DEFAULT));
        // 「打车 28 块」在记账关闭时也不被当成打车（不是光意图）
        assertNull(en.handle("打车 28 块", t(10, 8, 12), DEFAULT));
    }

    @Test public void amountParsing() {
        long now = t(10, 8, 12);
        assertEquals(3500, LedgerParser.parse("午饭花了35", now, TZ).amountFen);
        assertEquals(2800, LedgerParser.parse("打车 28 块", now, TZ).amountFen);
        assertEquals(6250, LedgerParser.parse("昨天买菜花了六十二块五", now, TZ).amountFen);
        assertEquals(12000, LedgerParser.parse("买衣服花了一百二", now, TZ).amountFen);
        assertEquals(1250, LedgerParser.parse("咖啡12.5元", now, TZ).amountFen);
        assertEquals(50, LedgerParser.parse("停车花了五毛", now, TZ).amountFen);
        assertEquals(2000, LedgerParser.parse("买了3斤苹果花了20", now, TZ).amountFen);
        assertEquals(250000, LedgerParser.parse("房租交了两千五", now, TZ).amountFen);
        assertNull("问句不是记账", LedgerParser.parse("午饭花了多少", now, TZ));
        assertNull("没有花钱语境", LedgerParser.parse("28块", now, TZ));
        assertNull("数量不是金额", LedgerParser.parse("买了3斤苹果", now, TZ));
    }

    @Test public void categoryAndDay() {
        long now = t(10, 8, 12);
        LedgerParser.Item it = LedgerParser.parse("昨天买菜花了六十二块五", now, TZ);
        assertEquals("买菜", it.category);
        assertEquals("2026-10-07", it.day);
        assertEquals("餐饮", LedgerParser.parse("午饭花了35", now, TZ).category);
        assertEquals("交通", LedgerParser.parse("打车 28 块", now, TZ).category);
        assertEquals("2026-10-06", LedgerParser.parse("前天加油花了300", now, TZ).day);
        assertEquals("2026-10-05", LedgerParser.parse("10月5日看电影花了80", now, TZ).day);
        assertEquals("其他", LedgerParser.parse("记一笔账 99", now, TZ).category);
    }

    @Test public void sumsByPeriodAndCategory() {
        // 2026-10-08 是周四
        add("午饭花了35", t(10, 8, 12));
        add("打车 28 块", t(10, 8, 13));
        add("昨天买菜花了六十二块五", t(10, 8, 14));
        add("晚饭花了50", t(10, 2, 19));      // 上周五
        add("午饭花了20", t(9, 20, 12));      // 上个月（不在上周）
        long now = t(10, 8, 20);
        S2Engine.Result m = en.handle("这个月花了多少", now, ON);
        assertEquals(S2Engine.Type.LEDGER_QUERY, m.type);
        assertTrue(m.reply, m.reply.contains("¥175.5（4 笔）"));
        S2Engine.Result w = en.handle("本周吃饭花了多少", now, ON);
        assertTrue(w.reply, w.reply.contains("¥35（1 笔）"));
        assertTrue(en.handle("上周花了多少", now, ON).reply.contains("¥50"));
        assertTrue(en.handle("今天花了多少钱", now, ON).reply.contains("¥63"));
        assertTrue(en.handle("上个月花了多少", now, ON).reply.contains("¥20"));
        assertTrue(en.handle("最近记的账", now, ON).reply.contains("合计"));
        // 删刚才那笔
        S2Engine.Result d = en.handle("删掉刚才那笔", now, ON);
        assertEquals(S2Engine.Type.LEDGER_DELETE, d.type);
        assertTrue(en.execute(d.sign, d.args, now).ok);
        assertEquals(4, st.count(MemoryEntry.KIND_LEDGER));
        // 世界信息不是账本查询
        assertNull(en.handle("比特币现在多少钱", now, ON));
        assertNull(en.handle("去北京要花多少钱", now, ON));
    }

    void add(String q, long now) {
        S2Engine.Result r = en.handle(q, now, ON);
        assertEquals(q, S2Engine.Type.LEDGER_ADD, r.type);
        assertTrue(en.execute(r.sign, r.args, now).ok);
    }
}
