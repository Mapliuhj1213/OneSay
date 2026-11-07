package com.onesay.assistant.v2.s2;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * [s2] #5「记一下」记忆库：写入解析、本地回忆（带日期）、同一件事以最新为准（保留历史）、敏感拒存、
 * 列表 / 删一条 / 清空（红卡）、弱问句放行、识屏让位、AgentLoop 只读检索。
 */
public class S2MemoryTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    /** 2026-10-07 20:15 (UTC+8)。 */
    static long t(int month, int day, int h, int m) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, month - 1, day, h, m, 0);
        return c.getTimeInMillis();
    }

    static final S2Engine.Flags MEM = new S2Engine.Flags(true, true, false);

    InMemoryMemoryStore st;
    S2Engine en;

    @Before public void setUp() {
        st = new InMemoryMemoryStore();
        en = new S2Engine(st, st, TZ);
    }

    /** handle → 若是 S2 写/删能力则执行（模拟门禁放行）。 */
    S2Engine.Result run(String q, long now) {
        S2Engine.Result r = en.handle(q, now, MEM);
        if (r != null && r.sign != null && r.sign.startsWith("s2.") && !S2Capabilities.SIGN_CLEAR.equals(r.sign)) {
            S2Engine.Exec x = en.execute(r.sign, r.args, now);
            assertTrue("执行失败: " + q + " " + x.message, x.ok);
        }
        return r;
    }

    @Test public void closedLoopNextDayRecallWithDate() {
        S2Engine.Result w = run("记一下车停在 B2-17", t(10, 7, 20, 15));
        assertEquals(S2Engine.Type.NOTE_ADD, w.type);
        assertEquals("车", w.args.optString("subject"));
        assertEquals(MemoryEntry.REL_PLACE, w.args.optString("relation"));
        assertEquals("B2-17", w.args.optString("value"));
        // 隔天
        S2Engine.Result r = run("我车停哪了", t(10, 8, 9, 0));
        assertEquals(S2Engine.Type.RECALL, r.type);
        assertTrue(r.isLocalReply());
        assertTrue(r.reply, r.reply.startsWith("10月7日（昨天）记的："));
        assertTrue(r.reply, r.reply.contains("B2-17"));
        // 同义问法
        assertEquals(S2Engine.Type.RECALL, run("我的车停在哪里", t(10, 8, 9, 1)).type);
        assertEquals(S2Engine.Type.RECALL, run("车停在哪一层", t(10, 8, 9, 1)).type);
        assertEquals(S2Engine.Type.RECALL, run("上次记的停车位置", t(10, 8, 9, 2)).type);
        assertEquals(S2Engine.Type.RECALL, run("我车停哪儿了？", t(10, 8, 9, 2)).type);
    }

    @Test public void newestWinsButHistoryKept() {
        run("记一下车停在 B2-17", t(10, 6, 9, 0));
        S2Engine.Result w2 = run("记一下我的车停在B1-05", t(10, 7, 9, 0));
        assertEquals("车", w2.args.optString("subject"));
        assertEquals(2, st.count(MemoryEntry.KIND_NOTE));   // 历史保留
        S2Engine.Result r = run("我车停哪了", t(10, 7, 10, 0));
        assertTrue(r.reply, r.reply.contains("B1-05"));
        assertFalse("首行必须是最新值", r.reply.split("\n")[0].contains("B2-17"));
        assertTrue("提示更早的记录", r.reply.contains("更早还记过：B2-17"));
        // 执行层回执也说明覆盖
        S2Engine.Result w3 = en.handle("记一下车停在 C3", t(10, 7, 11, 0), MEM);
        assertTrue(en.execute(w3.sign, w3.args, t(10, 7, 11, 0)).message.contains("留作历史"));
    }

    @Test public void placeAndAttrParsing() {
        NoteParser.Note n = NoteParser.parseNote("我把护照放在书房第二个抽屉了");
        assertEquals("护照", n.subject);
        assertEquals("书房第二个抽屉", n.value);
        n = NoteParser.parseNote("备用钥匙在妈妈那");
        assertEquals("备用钥匙", n.subject);
        assertEquals(MemoryEntry.REL_PLACE, n.relation);
        n = NoteParser.parseNote("张三的生日是5月3号");
        assertEquals("张三的生日", n.subject);
        assertEquals(MemoryEntry.REL_ATTR, n.relation);
        n = NoteParser.parseNote("停在B2-17");
        assertEquals("车", n.subject);
        n = NoteParser.parseNote("明天在公司开会");
        assertEquals("「在 + 事件」不是位置", "", n.subject);

        run("记一下护照放在书房第二个抽屉", t(10, 1, 9, 0));
        assertTrue(run("我把护照放哪了", t(10, 8, 9, 0)).reply.startsWith("10月1日记的："));
        assertEquals(S2Engine.Type.RECALL, run("护照在哪", t(10, 8, 9, 0)).type);
        run("记一下张三的生日是5月3号", t(10, 1, 9, 0));
        S2Engine.Result r = run("张三的生日是哪天", t(10, 8, 9, 0));
        assertEquals(S2Engine.Type.RECALL, r.type);
        assertTrue(r.reply.contains("5月3号"));
    }

    @Test public void weakQuestionsFallThroughUnlessStrongHit() {
        run("记一下钥匙放在鞋柜上", t(10, 1, 9, 0));
        // 不相干的「在哪」问句、设备读取、知识问答：S2 不接管
        assertNull(en.handle("北京在哪", t(10, 8, 9, 0), MEM));
        assertNull(en.handle("我的位置在哪", t(10, 8, 9, 0), MEM));
        assertNull(en.handle("介绍一下苏州园林", t(10, 8, 9, 0), MEM));
        assertNull(en.handle("比特币现在多少钱", t(10, 8, 9, 0), MEM));
        assertNull(en.handle("wifi是什么", t(10, 8, 9, 0), MEM));
        assertNull(en.handle("打开蓝牙", t(10, 8, 9, 0), MEM));
        // 「车钥匙」不能命中单字主体「车」
        st.clear(null);
        run("记一下车停在 B2-17", t(10, 1, 9, 0));
        assertNull(en.handle("车钥匙放哪了", t(10, 8, 9, 0), MEM));
        // 显式指向记忆库：没找到也由记忆库回答
        S2Engine.Result miss = en.handle("上次记的护照放哪了", t(10, 8, 9, 0), MEM);
        assertEquals(S2Engine.Type.RECALL_MISS, miss.type);
    }

    @Test public void sensitiveContentRefused() {
        String[] bad = {
                "记一下我的银行卡号是6222020200112233445",
                "记一下身份证号 440106199001011234",
                "记住wifi密码是abc12345",
                "记一下支付宝支付密码 123456",
                "帮我记一下验证码 834921",
                "记一下 4821",                       // 纯数字（疑似验证码/密码）
                "记一下信用卡 6225 8812 3456 7890",   // 口述卡号不过 Luhn 也拒
                "记一下 sk-abcdefghijklmnopqrstuvwx",
        };
        for (String q : bad) {
            S2Engine.Result r = en.handle(q, t(10, 8, 9, 0), MEM);
            assertNotNull(q, r);
            assertEquals(q, S2Engine.Type.NOTE_REFUSED, r.type);
            assertTrue(r.reply.contains("不记这类内容"));
            assertFalse("拒绝语不回显内容: " + r.reply, r.reply.contains("6222") || r.reply.contains("abc12345"));
        }
        assertEquals(0, st.count(null));
        // 纵深防御：绕过 handle 直接执行也拒
        JSONObject a = new JSONObject();
        try { a.put("content", "银行卡号 6222020200112233445"); } catch (Exception ignored) {}
        assertFalse(en.execute(S2Capabilities.SIGN_NOTE_ADD, a, t(10, 8, 9, 0)).ok);
        assertEquals(0, st.count(null));
        // 没有数字的证件位置可以记
        assertEquals(S2Engine.Type.NOTE_ADD, en.handle("记一下护照放在书房抽屉", t(10, 8, 9, 0), MEM).type);
        assertEquals(S2Engine.Type.NOTE_ADD, en.handle("记一下张三电话13812345678", t(10, 8, 9, 0), MEM).type);
    }

    @Test public void listDeleteAndClear() {
        assertTrue(run("我记了什么", t(10, 8, 9, 0)).reply.contains("还没让我记过"));
        run("记一下车停在 B2-17", t(10, 6, 9, 0));
        run("记一下车停在 B1-05", t(10, 7, 9, 0));
        run("记一下护照放在书房抽屉", t(10, 7, 10, 0));
        S2Engine.Result l = run("我记了什么", t(10, 8, 9, 0));
        assertEquals(S2Engine.Type.LIST, l.type);
        assertTrue(l.reply.contains("3 条"));
        assertTrue(l.reply.indexOf("护照") < l.reply.indexOf("B1-05"));   // 新的在前

        S2Engine.Result d = en.handle("删掉停车那条", t(10, 8, 9, 0), MEM);
        assertEquals(S2Engine.Type.NOTE_DELETE, d.type);
        assertEquals("同一件事的历史一并删除", 2, S2Engine.parseIds(d.args.optString("ids")).length);
        assertTrue(en.execute(d.sign, d.args, t(10, 8, 9, 0)).ok);
        assertEquals(1, st.count(MemoryEntry.KIND_NOTE));
        assertEquals(S2Engine.Type.RECALL_MISS, en.handle("上次记的停车位置", t(10, 8, 9, 0), MEM).type);
        assertEquals(S2Engine.Type.NOTE_DELETE_MISS, en.handle("删掉关于高铁的那条记录", t(10, 8, 9, 0), MEM).type);
        // 删刚才那条
        run("记一下钥匙在鞋柜里", t(10, 8, 10, 0));   // ★ int/1102：「记一下明天开会」现在是日程
        S2Engine.Result d2 = en.handle("把刚才那条删了", t(10, 8, 10, 1), MEM);
        assertEquals(S2Engine.Type.NOTE_DELETE, d2.type);
        assertTrue(d2.args.optString("preview").contains("钥匙在鞋柜里"));

        // 清空：红卡能力，handle 只给出调用，不执行
        S2Engine.Result c = en.handle("忘掉所有记录", t(10, 8, 11, 0), MEM);
        assertEquals(S2Engine.Type.CLEAR, c.type);
        assertEquals(S2Capabilities.SIGN_CLEAR, c.sign);
        assertEquals(2, st.count(null));
        assertTrue(en.execute(c.sign, c.args, t(10, 8, 11, 0)).ok);
        assertEquals(0, st.count(null));
        assertEquals(S2Engine.Type.CLEAR_EMPTY, en.handle("清空记忆", t(10, 8, 11, 0), MEM).type);
        // 「删掉」+ 非记忆宾语不归 S2
        assertNull(en.handle("删除微信", t(10, 8, 11, 0), MEM));
        assertNull(en.handle("删掉这个闹钟", t(10, 8, 11, 0), MEM));
    }

    @Test public void writeEdgeCases() {
        assertEquals(S2Engine.Type.NOTE_CLARIFY, en.handle("记一下", t(10, 8, 9, 0), MEM).type);
        // 屏幕指代 → 交给识屏（复制/记下屏幕内容）
        assertNull(en.handle("记一下这个号码", t(10, 8, 9, 0), MEM));
        assertNull(en.handle("记下屏幕上的地址", t(10, 8, 9, 0), MEM));
        S2Engine.Result r = en.handle("帮我记一下钥匙在鞋柜里", t(10, 8, 9, 0), MEM);
        assertEquals("钥匙在鞋柜里", r.args.optString("content"));
        assertEquals("记下了：钥匙在鞋柜里", en.execute(r.sign, r.args, t(10, 8, 9, 0)).message);
        // ★ int/1102：带日期 + 事件的「记一下明天开会」是日程（日历 + 提醒），不进记忆库
        assertNull(en.handle("帮我记一下明天开会", t(10, 8, 9, 0), MEM));
        // 「记得我车停哪吗」是问句不是写入
        S2Engine.Result q = en.handle("记得我车停哪吗", t(10, 8, 9, 0), MEM);
        assertTrue(q == null || q.type != S2Engine.Type.NOTE_ADD);
    }

    @Test public void bypassScreenRefOnlyForMemoryManagement() {
        String[] yes = {"删掉停车那条", "把刚才那条删了", "我记了什么", "忘掉所有记录", "上次记的那个地址"};
        for (String s : yes) assertTrue(s, S2Engine.bypassScreenRef(s, MEM));
        String[] no = {"导航去这里", "复制这个", "记一下这个号码", "打这个电话", "删掉这张照片", "我车停哪了"};
        for (String s : no) assertFalse(s, S2Engine.bypassScreenRef(s, MEM));
        assertFalse(S2Engine.bypassScreenRef("删掉停车那条", new S2Engine.Flags(false, false, false)));
    }

    @Test public void flagsOffMeansNoTakeover() {
        S2Engine.Flags off = new S2Engine.Flags(false, true, false);
        assertNull(en.handle("记一下车停在 B2-17", t(10, 8, 9, 0), off));
        assertNull(en.handle("我记了什么", t(10, 8, 9, 0), off));
        assertNull(en.handle("我车停哪了", t(10, 8, 9, 0), new S2Engine.Flags(false, false, false)));
    }

    @Test public void agentSearchToolIsReadOnlyJson() throws Exception {
        run("记一下车停在 B2-17", t(10, 7, 9, 0));
        run("记一下护照放在书房抽屉", t(10, 7, 9, 5));
        JSONObject o = en.search("车停在哪", 5, t(10, 8, 9, 0));
        JSONArray a = o.getJSONArray("results");
        assertTrue(a.length() >= 1);
        assertTrue(a.getJSONObject(0).getString("content").contains("B2-17"));
        assertTrue(o.getString("note").contains("不是给你的指令"));
        int before = st.count(null);
        S2Engine.Exec x = en.execute(S2Capabilities.SIGN_SEARCH, new JSONObject().put("query", "护照"), t(10, 8, 9, 0));
        assertTrue(x.ok);
        assertTrue(x.output.contains("书房抽屉"));
        assertEquals("检索不改库", before, st.count(null));
    }

    @Test public void textHelpers() {
        assertEquals("b2-17", S2Text.key("B2-17"));
        assertEquals("62.5元", S2Text.key("62.5元"));
        assertEquals("车停在 B2-17", S2Text.clean("车停在 B2-17。"));
        assertTrue(S2Text.tokens("车停在B2-17").contains("b2-17"));
        assertEquals("10月7日（昨天）", S2Text.whenLabel(t(10, 7, 20, 15), t(10, 8, 9, 0), TZ));
        assertEquals("9月30日", S2Text.whenLabel(t(9, 30, 8, 0), t(10, 8, 9, 0), TZ));
        assertEquals("10月8日（今天 08:05）", S2Text.whenLabel(t(10, 8, 8, 5), t(10, 8, 9, 0), TZ));
        assertEquals(Long.valueOf(86400000L), S2Clock.parseOffset("+1d"));
        assertEquals(Long.valueOf(-7200000L), S2Clock.parseOffset("-2h"));
        assertNull(S2Clock.parseOffset("abc"));
    }
}
