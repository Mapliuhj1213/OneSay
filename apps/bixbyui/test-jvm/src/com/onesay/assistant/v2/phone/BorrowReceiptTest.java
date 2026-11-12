package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ borrow-1112 ①：结构化回执 {ok, code, next}、STALE_ELEMENT（兼容旧码 REF_STALE）、wait_until 与等待期间画面变化。 */
public class BorrowReceiptTest {

    @Test public void codesAreFixedAndLegacyRefStaleMapsToStaleElement() {
        assertEquals(StepReceipt.Code.STALE_ELEMENT, StepReceipt.Code.of("REF_STALE"));
        assertEquals(StepReceipt.Code.STALE_ELEMENT, StepReceipt.Code.of("stale_element"));
        assertNull(StepReceipt.Code.of("随便写的"));
        assertEquals("{\"ok\":false,\"code\":\"STALE_ELEMENT\",\"next\":\"look\"}", StepReceipt.of(StepReceipt.Code.STALE_ELEMENT).json());
        assertEquals("{\"ok\":true,\"code\":\"OK\",\"next\":\"continue\"}", StepReceipt.of(StepReceipt.Code.OK).json());
        assertEquals(StepReceipt.Next.FIX, StepReceipt.defaultNext(StepReceipt.Code.NOT_FOUND));
        assertEquals(StepReceipt.Next.ASK_USER, StepReceipt.defaultNext(StepReceipt.Code.USER_REFUSED));
        assertEquals(StepReceipt.Next.RETRY_LATER, StepReceipt.defaultNext(StepReceipt.Code.TIMEOUT));
        assertTrue(StepReceipt.of(StepReceipt.Code.NO_CHANGE).ok);
        assertFalse(StepReceipt.of(StepReceipt.Code.NEEDS_USER_TAP).ok);
    }

    @Test public void effectsClassify() {
        assertEquals(StepReceipt.Code.OK, StepReceipt.ofEffect("tap", "已点击", "", true).code);
        assertEquals(StepReceipt.Code.NO_CHANGE, StepReceipt.ofEffect("tap", "已点击", "", false).code);
        assertEquals(StepReceipt.Code.OK, StepReceipt.ofEffect("type", "已输入", "", false).code);
        assertEquals(StepReceipt.Code.ACTION_FAILED, StepReceipt.ofEffect("tap", "点击失败", "", false).code);
        assertEquals(StepReceipt.Code.ACTION_FAILED, StepReceipt.ofEffect("launch", "打不开 x", "", false).code);
        assertEquals(StepReceipt.Code.TIMEOUT, StepReceipt.ofEffect("tap", "已点击", "没等到「x」出现（等了 5000ms）", true).code);
        assertEquals(StepReceipt.Code.UNSUPPORTED, StepReceipt.ofEffect("zz", "不支持", "", false).code);
        assertEquals(StepReceipt.Code.NOT_FOUND, StepReceipt.ofValidate("编号 [9] 不在当前界面"));
        assertEquals(StepReceipt.Code.BAD_ARGS, StepReceipt.ofValidate("launch 缺少 app 包名"));
    }

    @Test public void everyExecutedStepCarriesAReceiptAndTheStopStepIsNotOk() {
        FakeScreens.Phone ph = PhoneAgentTest.settingsPhone();
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN}}]}", "{\"steps\":[{\"do\":\"tap\",\"id\":99}]}", "{\"done\":\"好\"}");
        PhoneAgent.Result r = new PhoneAgent(ph, pl, new FakeScreens.Host()).run("开 WLAN", null);
        String u = pl.users.get(1);
        assertTrue(u, u.contains("执行了 1/1 步："));
        assertTrue(u, u.contains("回执 {\"ok\":true,\"code\":\"OK\",\"next\":\"continue\"}"));
        String u2 = pl.users.get(2);
        assertTrue(u2, u2.contains("执行了 0/1 步（停在第 1 步"));
        assertTrue(u2, u2.contains("回执 {\"ok\":false,\"code\":\"NOT_FOUND\",\"next\":\"fix\"}"));
        assertEquals(2, r.receipts.size());
        assertTrue(r.receipts.get(0).ok);
        assertFalse(r.receipts.get(1).ok);
    }

    @Test public void staleRefReceiptKeepsOldTextAndAddsStaleElement() {
        final StableReadTest.Anim d = new StableReadTest.Anim().show(StableReadTest.shop());
        StableReadTest.Thinking p = new StableReadTest.Thinking("{\"steps\":[{\"do\":\"tap\",\"id\":{id:查看详情}}]}", "{\"done\":\"没点\"}");
        p.hookAt = 1;
        p.hook = new Runnable() {
            @Override public void run() {
                d.show(FakeScreens.screen("com.shop", "x.Popup").text("领取优惠券").button("关闭"));
            }
        };
        PhoneAgent a = StableReadTest.agent(d, p, true);
        PhoneAgent.Result r = a.run("买", null);
        String second = p.s.users.get(1);
        assertTrue("旧码文字保留", second.contains(StableRead.REF_STALE));
        assertTrue(second, second.contains("\"code\":\"STALE_ELEMENT\""));
        assertEquals(StepReceipt.Code.STALE_ELEMENT, r.receipts.get(0).code);
        assertTrue(d.log.isEmpty());
    }

    @Test public void refusedAndPayFinalReceipts() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("chat", FakeScreens.screen("com.tencent.mobileqq", "Chat").button("发送"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:发送}}]}", "{\"done\":\"没发\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("发", null);
        assertEquals(StepReceipt.Code.USER_REFUSED, r.receipts.get(0).code);
        assertEquals(StepReceipt.Next.ASK_USER, r.receipts.get(0).next);

        FakeScreens.Phone pay = new FakeScreens.Phone();
        pay.add("pay", FakeScreens.screen("com.taobao.taobao", "Cashier").button("确认支付"));
        FakeScreens.Script pp = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:确认支付},\"risk\":\"none\"}]}", "{\"done\":\"停\"}");
        PhoneAgent.Result r2 = new PhoneAgent(pay, pp, new FakeScreens.Host()).run("买", null);
        assertEquals(StepReceipt.Code.NEEDS_USER_TAP, r2.receipts.get(0).code);
    }

    // ------------------------------------------------------------------ wait_until

    @Test public void parseWaitUntil() {
        PhonePlan p = PhonePlan.parse("{\"steps\":[{\"do\":\"wait_until\",\"text\":\"支付成功\",\"timeout_s\":99},"
                + "{\"do\":\"wait_for\",\"gone\":\"正在加载\"},{\"do\":\"wait_until\",\"until\":{\"el\":\"loading\"}},{\"do\":\"wait_until\",\"el_gone\":\"spinner\",\"timeout_s\":3}]}");
        assertEquals(PhonePlan.Kind.STEPS, p.kind);
        PhonePlan.Step a = p.steps.get(0), b = p.steps.get(1), c = p.steps.get(2), d = p.steps.get(3);
        assertEquals("wait_until", a.op);
        assertEquals("支付成功", a.untilText);
        assertEquals("text 是等的字，不是要输入的", "", a.text);
        assertEquals("超过 30 秒按 30 秒算", PhonePlan.UNTIL_MAX_MS, a.timeoutMs);
        assertEquals("wait_until", b.op);
        assertEquals("正在加载", b.untilGone);
        assertEquals(PhonePlan.UNTIL_DEFAULT_MS, b.timeoutMs);
        assertEquals("loading", c.untilEl);
        assertEquals("spinner", d.untilElGone);
        assertEquals(3000, d.timeoutMs);
        PhonePlan.Step empty = PhonePlan.parse("{\"steps\":[{\"do\":\"wait_until\"}]}").steps.get(0);
        assertNotNull(PhonePlan.validate(empty, null));
        assertNull(PhonePlan.validate(a, null));
    }

    static FakeScreens.B loading(int left) {
        return FakeScreens.screen("com.pay", "x.Pay").text("正在支付").text("倒计时 " + left + " 秒");
    }

    @Test public void waitUntilTextAppearsAndMeanwhileGoesToTimeline() {
        StableReadTest.Anim d = new StableReadTest.Anim().show(loading(3));
        FakeScreens.B done = FakeScreens.screen("com.pay", "x.Done").text("支付成功").button("完成");
        d.frames.add(loading(3));
        d.frames.add(loading(2));
        d.frames.add(loading(2));
        d.frames.add(loading(1));
        d.frames.add(loading(1));
        d.frames.add(done);
        d.frames.add(done);
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"wait_until\",\"text\":\"支付成功\",\"timeout_s\":20}]}", "{\"done\":\"付好了\"}");
        PhoneAgent a = StableReadTest.agent(d, p, true);
        final java.util.List<String> tl = new java.util.ArrayList<String>();
        a.meanwhile = new PhoneAgent.Meanwhile() {
            @Override public void onChange(String line) {
                tl.add(line);
            }
        };
        PhoneAgent.Result r = a.run("等支付结果", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        String u = p.users.get(1);
        assertTrue(u, u.contains("条件已满足"));
        assertTrue(u, u.contains("期间画面出现过"));
        assertTrue(u, u.contains("「倒计时 2 秒」"));
        assertTrue(u, u.contains("「支付成功」"));
        assertTrue(u, u.contains("\"code\":\"OK\""));
        assertFalse(tl.isEmpty());
        assertTrue(tl.toString(), tl.get(0).startsWith("等待中画面变化："));
    }

    @Test public void waitUntilGoneTimesOutWithinCap() {
        StableReadTest.Anim d = new StableReadTest.Anim().show(loading(9));
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"wait_until\",\"gone\":\"正在支付\",\"timeout_s\":2}]}", "{\"done\":\"没等到\"}");
        PhoneAgent a = StableReadTest.agent(d, p, true);
        long t0 = d.clock;
        a.run("等", null);
        String u = p.users.get(1);
        assertTrue(u, u.contains("没等到「正在支付」消失"));
        assertTrue(u, u.contains("\"code\":\"TIMEOUT\""));
        assertTrue("不超过上限太多 clock=" + d.clock, d.clock - t0 < 2000 + 2000);
    }

    @Test public void waitUntilElementGoneByResourceId() {
        FakeScreens.B spin = FakeScreens.screen("com.x", "A").button("", "com.x:id/loading_spinner").text("内容");
        FakeScreens.B ok = FakeScreens.screen("com.x", "A").text("内容").text("更多");
        StableReadTest.Anim d = new StableReadTest.Anim().show(spin, spin, spin, ok, ok);
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"wait_until\",\"el_gone\":\"spinner\"}]}", "{\"done\":\"好\"}");
        StableReadTest.agent(d, p, false).run("等", null);
        assertTrue(p.users.get(1), p.users.get(1).contains("条件已满足"));
    }

    @Test public void promptDocumentsReceiptWaitUntilRiskAndPlaceholders() {
        String s = PhonePrompt.system();
        assertTrue(s.contains("wait_until"));
        assertTrue(s.contains("STALE_ELEMENT"));
        assertTrue(s.contains("risk"));
        assertTrue(s.contains("[手机号#1]"));
        assertFalse(s.contains("Bixby"));
        assertFalse(s.contains("三星"));
    }
}
