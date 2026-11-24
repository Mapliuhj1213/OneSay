package com.onesay.assistant.v2.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ★ fix/chat-vision-1124 第 1 条：下单时「送不了这个地址」。以前模型自己点一个别的地址接着下单；
 * 现在先问用户换成哪个（确认卡列编号），只让模型点用户选的那条；没有可选的只打开地址列表看；都没有就停下说清楚。
 */
public class DeliveryBlock1124Test {

    static final String PKG = "com.example.shop";
    static final String CUR = "张三 138****0000 北京市朝阳区建国路88号1栋";
    static final String LI = "李四 北京市海淀区中关村大街27号3单元";
    static final String WANG = "王五 上海市浦东新区世纪大道100号";

    static void plain(String s) {
        assertFalse(s, s.contains("啥") || s.contains("咋") || s.contains("呗"));
    }

    /** 能回话的宿主：确认卡上回 answer（其余照 FakeScreens.Host）。 */
    static final class AnsHost implements PhoneAgent.Host {
        final FakeScreens.Host h = new FakeScreens.Host();
        final java.util.List<String> confirms = h.confirms;
        String answer;
        final java.util.List<String> titles = new java.util.ArrayList<String>();

        @Override public void onStep(String w, int n) { h.onStep(w, n); }

        @Override public boolean confirm(String title, String detail, String[] out) {
            titles.add(title);
            boolean ok = h.confirm(title, detail, out);
            if (out != null && out.length > 0) out[0] = answer;
            return ok;
        }

        @Override public String takeoverRequested() { return h.takeoverRequested(); }

        @Override public PhoneAgent.Resume awaitResume(String why, String[] sup) { return h.awaitResume(why, sup); }

        @Override public boolean stopRequested() { return h.stopRequested(); }
    }

    static FakeScreens.B checkout(boolean blocked) {
        FakeScreens.B b = FakeScreens.screen(PKG, "x.Checkout");
        if (blocked) b.text("该地址不在配送范围内，请更换地址");
        return b.button(CUR).button(LI).button(WANG).button("提交订单");
    }

    static String tap(String label) {
        return "{\"steps\":[{\"do\":\"tap\",\"id\":{id:" + label + "}}]}";
    }

    // ------------------------------------------------------------------ 纯判断

    @Test public void recognizesBlockedWording() {
        for (String s : new String[] {"无法配送", "当前地址超出配送范围", "该地址不在配送范围内", "地址不在服务范围", "收货地址不在配送范围", "超出配送距离",
                "该地址暂不支持配送", "该地址无法送达", "该地址送不了"}) {
            assertTrue(s, DeliveryBlock.blockedText(s));
        }
        for (String s : new String[] {"配送费 3 元", "预计 30 分钟送达", "免配送费", "提交订单", null, ""}) {
            assertFalse(String.valueOf(s), DeliveryBlock.blockedText(s));
        }
    }

    @Test public void addressHeuristic() {
        assertTrue(DeliveryBlock.looksLikeAddress(CUR));
        assertTrue(DeliveryBlock.looksLikeAddress(LI));
        assertTrue(DeliveryBlock.looksLikeAddress("浙江省杭州市西湖区文三路"));
        assertFalse(DeliveryBlock.looksLikeAddress("切换地址"));
        assertFalse(DeliveryBlock.looksLikeAddress("新增收货地址"));
        assertFalse(DeliveryBlock.looksLikeAddress("提交订单"));
        assertFalse(DeliveryBlock.looksLikeAddress("满 30 减 5"));
    }

    @Test public void pickByAnswer() {
        assertEquals(0, DeliveryBlock.pick(null, 3));
        assertEquals(0, DeliveryBlock.pick("好的", 3));
        assertEquals(1, DeliveryBlock.pick("2", 3));
        assertEquals(2, DeliveryBlock.pick("换第 3 个", 3));
        assertEquals(1, DeliveryBlock.pick("第二个", 3));
        assertEquals(-1, DeliveryBlock.pick("5", 3));
        assertEquals(-1, DeliveryBlock.pick("1", 0));
    }

    @Test public void checkExcludesOriginalAndMarked() {
        UiTree t = checkout(true).ui(new UiTree.Ids());
        DeliveryBlock.Verdict v = DeliveryBlock.check(t, false);
        assertTrue(v.blocked);
        assertEquals("离「送不了」最近的那条是原地址，不算可换的", 2, v.options.size());
        assertEquals(LI, v.options.get(0).label);
        assertEquals(WANG, v.options.get(1).label);
        // 列表里标着「超出配送范围」的不列
        UiTree list = FakeScreens.screen(PKG, "x.AddrList").button(CUR + " 超出配送范围").button(LI).ui(new UiTree.Ids());
        DeliveryBlock.Verdict v2 = DeliveryBlock.check(list, false);
        assertTrue(v2.blocked);
        assertEquals(1, v2.options.size());
        assertEquals(LI, v2.options.get(0).label);
        // 没写送不了：什么都不管
        assertFalse(DeliveryBlock.check(checkout(false).ui(new UiTree.Ids()), false).blocked);
        String ask = DeliveryBlock.askText(v);
        assertTrue(ask, ask.startsWith("原地址送不了，换成「" + LI + "」吗？"));
        assertTrue(ask, ask.contains("1. " + LI) && ask.contains("2. " + WANG));
        assertTrue(ask, ask.contains("付款前我还是会停下"));
        plain(ask);
        plain(DeliveryBlock.stuckText(v));
    }

    // ------------------------------------------------------------------ 整条任务

    @Test public void asksThenOnlyTapsTheChosenAddress() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("co", checkout(true)).add("ok", checkout(false));
        p.on("co", WANG, "ok");
        FakeScreens.Script s = new FakeScreens.Script(tap(WANG), "{\"done\":\"地址换好了，停在付款前\"}");
        AnsHost h = new AnsHost();
        h.answer = "2";
        PhoneAgent.Result r = new PhoneAgent(p, s, h).run("帮我下单，付款前停下", null);
        assertEquals(r.trace.toString(), PhoneAgent.Status.DONE, r.status);
        assertEquals("问了一次", 1, h.confirms.size());
        assertEquals("原地址送不了", h.titles.get(0));
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("换成「" + LI + "」吗") && h.confirms.get(0).contains("2. " + WANG));
        String u0 = s.users.get(0);
        assertTrue(u0, u0.contains("用户同意换成「" + WANG + "」") && u0.contains("不要自己挑别的地址"));
        assertTrue(p.log.toString(), p.log.contains("tap " + WANG));
        assertEquals("ok", p.cur);
        assertTrue(r.trace.contains("delivery ask → 2"));
        assertTrue(r.trace.contains("delivery chosen tapped"));
        assertFalse("付款按钮没点", p.log.contains("tap 提交订单"));
    }

    @Test public void modelTappingAnotherAddressIsBlocked() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("co", checkout(true)).add("ok", checkout(false));
        p.on("co", WANG, "ok").on("co", LI, "ok");
        FakeScreens.Script s = new FakeScreens.Script(tap(LI), tap(WANG), "{\"done\":\"好了\"}");
        AnsHost h = new AnsHost();
        h.answer = "2";   // 用户要王五
        PhoneAgent.Result r = new PhoneAgent(p, s, h).run("帮我下单，付款前停下", null);
        assertEquals(r.trace.toString(), PhoneAgent.Status.DONE, r.status);
        assertFalse("模型自己挑的李四没点", p.log.contains("tap " + LI));
        assertTrue(r.trace.toString(), r.trace.toString().contains("delivery tap blocked"));
        assertTrue(s.users.get(1), s.users.get(1).contains("换哪个地址要用户定"));
        assertTrue(p.log.contains("tap " + WANG));
    }

    @Test public void noOptionsButSwitchEntryOpensListOnlyThenAsks() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("co", FakeScreens.screen(PKG, "x.Checkout").text("当前地址超出配送范围").button(CUR).button("切换地址").button("提交订单"));
        p.add("list", FakeScreens.screen(PKG, "x.AddrList").button(LI).button(WANG).button("新增收货地址"));
        p.add("ok", checkout(false));
        p.on("co", "切换地址", "list").on("list", LI, "ok").on("list", "新增收货地址", "list");
        FakeScreens.Script s = new FakeScreens.Script(tap("切换地址"), tap(LI), "{\"done\":\"好了\"}");
        AnsHost h = new AnsHost();   // 不说话 = 同意第 1 条
        PhoneAgent.Result r = new PhoneAgent(p, s, h).run("帮我下单，付款前停下", null);
        assertEquals(r.trace.toString(), PhoneAgent.Status.DONE, r.status);
        String u0 = s.users.get(0);
        assertTrue(u0, u0.contains("打开地址列表看看") && u0.contains("不要选任何地址"));
        assertTrue(r.trace.contains("delivery list-only"));
        assertEquals("打开列表后问一次", 1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("换成「" + LI + "」吗"));
        assertTrue(p.log.contains("tap 切换地址"));
        assertTrue(p.log.contains("tap " + LI));
        assertEquals("ok", p.cur);
    }

    @Test public void listModeModelCannotCreateNewAddress() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("co", FakeScreens.screen(PKG, "x.Checkout").text("当前地址超出配送范围").button("切换地址").button("提交订单"));
        p.add("list", FakeScreens.screen(PKG, "x.AddrList").button("新增收货地址"));
        p.on("co", "切换地址", "list");
        FakeScreens.Script s = new FakeScreens.Script(tap("切换地址"), tap("新增收货地址"), "{\"done\":\"好了\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        h.resumeWith = PhoneAgent.Resume.STOP;
        PhoneAgent.Result r = new PhoneAgent(p, s, h).run("帮我下单，付款前停下", null);
        // 列表里没有能选的地址 → 停下说清楚（不让模型新建）
        assertEquals(r.trace.toString(), PhoneAgent.Status.STOPPED, r.status);
        assertFalse(p.log.contains("tap 新增收货地址"));
        assertEquals(1, h.resumes.size());
        assertTrue(h.resumes.get(0), h.resumes.get(0).contains("找不到能换的地址"));
    }

    @Test public void nothingToSwitchStopsAndSaysWhere() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("co", FakeScreens.screen(PKG, "x.Checkout").text("超出配送范围").button("提交订单"));
        FakeScreens.Script s = new FakeScreens.Script("{\"done\":\"不该问到模型\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        h.resumeWith = PhoneAgent.Resume.STOP;
        PhoneAgent.Result r = new PhoneAgent(p, s, h).run("帮我下单，付款前停下", null);
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
        assertTrue(r.reason, r.reason.contains("原地址送不了"));
        assertEquals("没问模型", 0, s.users.size());
        assertTrue(h.resumes.get(0), h.resumes.get(0).contains("超出配送范围") && h.resumes.get(0).contains("手动换好地址后点继续"));
        plain(h.resumes.get(0));
        plain(r.reason);
    }

    @Test public void userRefusesStops() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("co", checkout(true));
        FakeScreens.Script s = new FakeScreens.Script("{\"done\":\"不该问到模型\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent.Result r = new PhoneAgent(p, s, h).run("帮我下单，付款前停下", null);
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
        assertTrue(r.reason, r.reason.contains("没让换地址"));
        assertEquals(0, s.users.size());
        assertFalse(p.log.toString(), p.log.toString().contains("tap "));
    }

    @Test public void normalPageUntouchedAndGateOffIsOldBehavior() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("co", checkout(false));
        FakeScreens.Script s = new FakeScreens.Script("{\"done\":\"好了\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent.Result r = new PhoneAgent(p, s, h).run("看看订单", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals(0, h.confirms.size());
        assertFalse(s.users.get(0).contains("收货地址送不了"));
        // 改前口径：模型自己点了别的地址（复现原问题）
        PhoneAgent.DELIVERY_GATE = false;
        try {
            FakeScreens.Phone p2 = new FakeScreens.Phone();
            p2.add("co", checkout(true)).add("ok", checkout(false));
            p2.on("co", LI, "ok");
            FakeScreens.Script s2 = new FakeScreens.Script(tap(LI), "{\"done\":\"好了\"}");
            FakeScreens.Host h2 = new FakeScreens.Host();
            new PhoneAgent(p2, s2, h2).run("帮我下单，付款前停下", null);
            assertTrue("改前：没问就换了地址", p2.log.contains("tap " + LI));
            assertEquals(0, h2.confirms.size());
        } finally {
            PhoneAgent.DELIVERY_GATE = true;
        }
    }
}
