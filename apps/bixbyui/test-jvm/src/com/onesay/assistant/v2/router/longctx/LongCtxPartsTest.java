package com.onesay.assistant.v2.router.longctx;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

/** ★ fix/1123-longctx-phone：各部件单独测（账本 / 事实 / 存档 / 重活隔离 / 出口自检 / 末尾块）。 */
public class LongCtxPartsTest {

    // ---------------------------------------------------------------- 第 3 条：账本
    @Test public void ledgerOpenCloseAndPending() {
        TurnLedger l = new TurnLedger();
        TurnLedger.Entry a = l.open("帮我查快递");
        assertEquals(TurnLedger.Status.DOING, a.status);
        TurnLedger.Entry b = l.open("天空为什么是蓝的");
        assertEquals("被新消息打断的旧条目降为待答", TurnLedger.Status.PENDING, l.byId(a.id).status);
        assertEquals(b.id, l.current().id);
        TurnLedger.Entry c = l.close("天空为什么是蓝的", "因为瑞利散射。", false);
        assertSame(b, c);
        assertEquals(TurnLedger.Status.DONE, c.status);
        assertEquals("因为瑞利散射。", c.conclusion);
        assertEquals("快递那条还没答完", 1, l.unfinished().size());
        // 不走路由的（识屏 / 圈选）直接记一条已结
        TurnLedger.Entry d = l.close("识别屏幕", "[视觉识屏回执] 屏幕识别完成：群里在说聚餐", true);
        assertEquals(3, d.id);
        assertTrue(d.tainted);
        assertEquals("回执头不进结论", "屏幕识别完成：群里在说聚餐", d.conclusion);
        // Agent 回执取「结论：」之后
        assertEquals("好了", TurnLedger.conclusionOf("[执行回执] Agent 循环已完成\n· 第1步\n结论：好了"));
        // 原话被改写过（「帮我查快递吧」→ 记的是「帮我查快递」）也能对上最近一条在做的
        l.open("帮我查一下快递到哪了");
        TurnLedger.Entry e = l.close("帮我查一下快递到哪了吧", "到杭州了", false);
        assertEquals(TurnLedger.Status.DONE, e.status);
        assertEquals(4, e.id);
    }

    // ---------------------------------------------------------------- 第 2 条：事实
    @Test public void factsFromUserWordsOnly() {
        FactNotes f = new FactNotes();
        f.observe(1, "我跟你说一下情况啊，下周三我要去成都出差，公司让我自己订票，我刚订好了，我的航班是 CA4502，早上八点二十起飞");
        String all = text(f);
        assertTrue(all, all.contains("CA4502") && all.contains("八点二十"));
        assertTrue(all, all.contains("下周三我要去成都出差"));
        assertFalse("开场白不算事实：" + all, all.contains("我跟你说一下情况"));
        f.observe(2, "对了，以后叫我小林就行，我在杭州上班，平时都是骑电动车通勤");
        all = text(f);
        assertTrue(all, all.contains("叫我小林") && all.contains("电动车"));
        // 问句不是事实
        int n = f.size();
        f.observe(3, "我那趟航班几点起飞来着");
        f.observe(4, "我航班号是多少");
        assertEquals("问句不摘", n, f.size());
        // 改口：同类后说的覆盖先说的
        f.observe(5, "我改签了，我的航班是 MU5101，下午三点");
        all = text(f);
        assertTrue(all, all.contains("MU5101") && !all.contains("CA4502"));
        // 敏感信息一律不摘
        f.observe(6, "我的银行卡密码是 123456");
        assertFalse(text(f).contains("123456"));
        // 状态摘要只取已折叠轮次的
        assertEquals(1, f.from(new HashSet<Integer>(Arrays.asList(5))).size());
    }

    static String text(FactNotes f) {
        StringBuilder b = new StringBuilder();
        for (FactNotes.Fact x : f.all()) b.append(x.text).append(" | ");
        return b.toString();
    }

    // ---------------------------------------------------------------- 第 4 条：存档
    @Test public void archiveScopedSearchAndPersist() throws Exception {
        TurnArchive a = new TurnArchive();
        File dir = Files.createTempDirectory("lcarch").toFile();
        File f = new File(dir, "conv_archive.jsonl");
        a.setFile(f);
        a.add("c1", 1, "我的航班是 CA4502，早上八点二十起飞", "好的，记下了", false, 1);
        a.add("c1", 2, "天空为什么是蓝色的", "瑞利散射", false, 2);
        a.add("c1", 3, "我的银行卡密码是 123456", "这个别告诉我", false, 3);
        a.add("c2", 1, "我的航班是 MU5101", "好的", false, 4);
        List<TurnArchive.Hit> h = a.search("c1", "我航班号是多少", null, 2, 0.25);
        assertEquals(1, h.size());
        assertEquals("只在同一条接力链里找", 1, h.get(0).entry.id);
        assertTrue(h.get(0).entry.question.contains("CA4502"));
        assertTrue("排除逐字还在历史里的", a.search("c1", "我航班号是多少", new HashSet<Integer>(Arrays.asList(1)), 2, 0.25).isEmpty());
        assertTrue("别的链找不到", a.search("c9", "航班", null, 2, 0.1).isEmpty());
        String disk = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        assertTrue("落盘留原文", disk.contains("CA4502"));
        assertFalse("敏感条目不落盘", disk.contains("123456"));
        assertEquals("内存里仍有（本会话可调）", 4, a.size());
        // 重开：行数接上，超过 2×上限时重写
        TurnArchive b = new TurnArchive();
        b.setFile(f);
        for (int i = 0; i < TurnArchive.MAX_ENTRIES * 2 + 5; i++) b.add("c3", i + 1, "问题" + i, "答" + i, false, i);
        long lines = Files.readAllLines(f.toPath()).size();
        assertTrue("落盘有上限：" + lines, lines <= TurnArchive.MAX_ENTRIES * 2);
        assertEquals(TurnArchive.MAX_ENTRIES, b.size());
        b.wipe();
        assertFalse(f.exists());
    }

    // ---------------------------------------------------------------- 第 5 条：重活隔离
    @Test public void heavyReceiptsCompacted() {
        StringBuilder r = new StringBuilder("[执行回执] Agent 循环已完成，共 3 次调用：\n");
        for (int i = 1; i <= 3; i++) r.append("· 第").append(i).append("步 gui.unfold{target:x} → 成功；屏幕文字：很多很多字\n");
        r.append("结论：显示设置里有亮度和护眼模式。");
        String c = HeavyIsolation.compact(r.toString());
        assertFalse("过程行不进主上下文：" + c, c.contains("gui.unfold"));
        assertTrue(c.startsWith("[执行回执] Agent 循环已完成"));
        assertTrue(c.contains("结论：显示设置里有亮度和护眼模式。"));
        assertTrue(c.endsWith(HeavyIsolation.NOTE));
        // 长识屏：只留开头一段
        StringBuilder s = new StringBuilder("[视觉识屏回执] 屏幕识别完成：群里在说周末聚餐。\n");
        for (int i = 0; i < 60; i++) s.append(i).append(". 群消息：周末聚餐改到周六晚上七点，地点在万达三楼烤肉店\n");
        String sc = HeavyIsolation.compact(s.toString());
        assertTrue("精简后很短：" + sc.length(), sc.length() < 400);
        assertTrue(sc.startsWith("[视觉识屏回执] 屏幕识别完成：群里在说周末聚餐。"));
        // 短回执 / 普通答复不动（同一对象）
        String shortR = "[执行回执] 打开蓝牙（bt.on）→ 成功；物理回读核对通过";
        assertSame(shortR, HeavyIsolation.compact(shortR));
        String plain = "天空是蓝色的，因为散射。";
        assertSame(plain, HeavyIsolation.compact(plain));
        String shortScreen = "[视觉识屏回执] 屏幕识别完成：\n这是微信聊天页，最后一条是「晚上见」。";
        assertSame("短识屏答复原样保留", shortScreen, HeavyIsolation.compact(shortScreen));
    }

    // ---------------------------------------------------------------- 第 6 条：出口自检
    @Test public void replyCheckVerdicts() {
        List<String[]> prior = new ArrayList<String[]>();
        prior.add(new String[]{"天空为什么是蓝色的", "天空是蓝色，是因为阳光里的蓝光被空气散射得最多，所以满天都是蓝色。"});
        prior.add(new String[]{"绿茶和红茶有什么区别", "绿茶不发酵、红茶全发酵，所以绿茶清爽、红茶醇厚，红茶更养胃。"});
        String q = "鸡蛋煮几分钟最好";
        String good = "鸡蛋水开后煮六分钟是溏心，煮九到十分钟是全熟。";
        assertEquals(ReplyCheck.Verdict.OK, ReplyCheck.check(q, good, prior).verdict);
        assertEquals(ReplyCheck.Verdict.ECHO, ReplyCheck.check(q, "你问的是「鸡蛋煮几分钟最好」。" + good, prior).verdict);
        assertEquals(ReplyCheck.Verdict.ECHO, ReplyCheck.check(q, "鸡蛋煮几分钟最好？" + good, prior).verdict);
        assertEquals("正常开头带关键词不算复述", ReplyCheck.Verdict.OK,
                ReplyCheck.check("天空为什么是蓝色的", "天空是蓝色的，因为阳光里的蓝光被空气散射得最多。", null).verdict);
        ReplyCheck.Result rep = ReplyCheck.check(q, prior.get(1)[1], prior);
        assertEquals(ReplyCheck.Verdict.REPEAT, rep.verdict);
        assertEquals("绿茶和红茶有什么区别", rep.other);
        assertEquals("用户要再说一遍不算复读", ReplyCheck.Verdict.OK, ReplyCheck.check("刚才那个再说一遍", prior.get(1)[1], prior).verdict);
        assertEquals("同一个问题再问一次不算复读", ReplyCheck.Verdict.OK, ReplyCheck.check("绿茶和红茶有什么区别", prior.get(1)[1], prior).verdict);
        // 偏题：答的是旧问题（换了说法，不完全一样）
        ReplyCheck.Result off = ReplyCheck.check("跑步膝盖疼怎么办", "天空看起来是蓝色，主要因为大气对短波长的光散射更强。", prior);
        assertEquals(ReplyCheck.Verdict.OFF_TOPIC, off.verdict);
        assertEquals("省略式追问不判偏题", ReplyCheck.Verdict.OK, ReplyCheck.check("那晚霞呢", "晚霞是傍晚阳光穿过更厚的空气，蓝光散射没了。", prior).verdict);
        assertEquals(ReplyCheck.Verdict.OK, ReplyCheck.check("", "x", prior).verdict);
        assertTrue(LongCtx.redoNote(off).contains("天空为什么是蓝色的"));
        assertTrue(LongCtx.redoNote(rep).startsWith("【系统提示】"));
    }

    // ---------------------------------------------------------------- 末尾块 / 状态摘要
    @Test public void tailAndStateFormat() {
        List<TurnLedger.Entry> open = new ArrayList<TurnLedger.Entry>();
        TurnLedger l = new TurnLedger();
        for (int i = 0; i < 6; i++) l.close("问题" + i, "答" + i, false);
        TurnLedger.Entry p = l.open("帮我查快递");
        open.add(p);
        String t = LongCtx.tail(Arrays.asList(1, 2, 3, 5, 6), open, null, 9, "那明天呢", null);
        String[] lines = t.split("\n");
        assertEquals("最后一行是最新一句", LongCtx.LATEST + "#9 用户：那明天呢", lines[lines.length - 1]);
        assertTrue(t.contains("已结：#1–#3、#5–#6"));
        assertTrue(t.contains("没答完：#7「帮我查快递」（在做）"));
        assertTrue("守则在最新一句前一行", lines[lines.length - 2].startsWith("【守则·每轮】"));
        assertTrue(LongCtx.RULES.contains("不用「啥」「咋」「呗」"));
        String r = LongCtx.tail(new ArrayList<Integer>(), new ArrayList<TurnLedger.Entry>(), null, 2, "嗯", "【系统提示】重答");
        assertTrue(r.indexOf("【系统提示】") < r.indexOf(LongCtx.LATEST));
        // 状态摘要：事实 / 已结（标已结）/ 没答完 / 技能；不是「问：…→答：…」流水账
        FactNotes f = new FactNotes();
        f.observe(1, "我的航班是 CA4502");
        List<TurnLedger.Entry> folded = l.entries();
        String st = LongCtx.renderState(false, f.all(), folded, Arrays.asList("出行规划"), 2048);
        assertTrue(st, st.startsWith(LongCtx.STATE_HEAD));
        assertTrue(st, st.contains("已知事实") && st.contains("CA4502"));
        assertTrue(st, st.contains("已结问题（都答完了，不要再答"));
        assertTrue(st, st.contains("#7 帮我查快递（在做）"));
        assertTrue(st, st.contains("已加载技能：出行规划"));
        assertFalse("不写流水账", st.contains("问："));
        // 放不下：先丢最早的已结
        TurnLedger big = new TurnLedger();
        for (int i = 0; i < 200; i++) big.close("一个比较长的问题第" + i + "个", "一个比较长的回答，第" + i + "条，内容还挺多的，凑够字数凑够字数", false);
        String bs = LongCtx.renderState(true, f.all(), big.entries(), null, 600);
        assertTrue(bs.startsWith(LongCtx.RELAY_HEAD));
        assertTrue("事实永远在：" + bs, bs.contains("CA4502"));
        assertTrue(bs.contains("更早的") && bs.contains("第199个"));
        assertFalse(bs.contains("第0个"));
        assertTrue(com.onesay.assistant.v2.router.ctx.TokenEstimator.estimate(bs) <= 600);
    }

    @Test public void textSimBasics() {
        assertTrue(TextSim.content("我航班号是多少").contains("航班"));
        assertFalse(TextSim.content("我航班号是多少").contains("多少"));
        assertTrue(TextSim.grams("git reset HEAD").contains("git"));
        assertEquals(1.0, TextSim.containment(TextSim.grams("蓝色"), TextSim.grams("天空是蓝色的")), 1e-9);
        assertEquals(0, TextSim.jaccard(null, TextSim.grams("x")), 1e-9);
    }
}
