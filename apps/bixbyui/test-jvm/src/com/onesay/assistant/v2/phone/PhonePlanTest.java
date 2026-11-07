package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ fast-screen-v2：模型输出解析与单步校验。 */
public class PhonePlanTest {

    @Test public void parsesBatchWithConditionsAndAliases() {
        PhonePlan p = PhonePlan.parse("好的：\n{\"steps\":[{\"do\":\"click\",\"id\":12,\"expect\":\"WLAN\"},"
                + "{\"do\":\"input\",\"id\":3,\"text\":\"北京天气\",\"enter\":true,\"wait_stable\":true},"
                + "{\"do\":\"scroll\",\"dir\":\"up\",\"timeout_s\":60},{\"do\":\"open\",\"app\":\"com.android.settings\",\"expect_app\":\"com.android.settings\"},"
                + "{\"do\":\"long_press\",\"id\":7,\"hold_ms\":99999}],\"note\":\"一次排完\"}");
        assertEquals(PhonePlan.Kind.STEPS, p.kind);
        assertEquals(5, p.steps.size());
        assertEquals("一次排完", p.note);
        PhonePlan.Step a = p.steps.get(0);
        assertEquals("tap", a.op);
        assertEquals(12, a.id);
        assertTrue(a.hasCondition());
        PhonePlan.Step t = p.steps.get(1);
        assertEquals("type", t.op);
        assertTrue(t.enter && t.waitStable);
        assertEquals(-1, p.steps.get(2).dir);
        assertEquals(20000, p.steps.get(2).timeoutMs);   // 上限 20 s
        assertEquals("launch", p.steps.get(3).op);
        assertEquals("long", p.steps.get(4).op);
        assertEquals(3000, p.steps.get(4).holdMs);       // 上限 3 s
    }

    @Test public void parsesTerminalKinds() {
        assertEquals(PhonePlan.Kind.DONE, PhonePlan.parse("{\"done\":\"已打开 WLAN\"}").kind);
        assertEquals("已打开 WLAN", PhonePlan.parse("{\"done\":\"已打开 WLAN\"}").answer);
        assertEquals(PhonePlan.Kind.ASK, PhonePlan.parse("{\"ask\":\"选哪个？\"}").kind);
        assertEquals(PhonePlan.Kind.SHOT, PhonePlan.parse("{\"shot\":true}").kind);
        PhonePlan c = PhonePlan.parse("{\"collect\":{\"until\":\"关于手机\",\"max_pages\":99}}");
        assertEquals(PhonePlan.Kind.COLLECT, c.kind);
        assertEquals("关于手机", c.collectUntil);
        assertEquals(20, c.collectPages);
    }

    @Test public void toleratesSingleStepForm() {
        PhonePlan p = PhonePlan.parse("{\"do\":\"back\"}");
        assertEquals(PhonePlan.Kind.STEPS, p.kind);
        assertEquals("back", p.steps.get(0).op);
    }

    @Test public void rejectsGarbageAndUnknownOps() {
        assertEquals(PhonePlan.Kind.INVALID, PhonePlan.parse(null).kind);
        assertEquals(PhonePlan.Kind.INVALID, PhonePlan.parse("我不知道").kind);
        assertEquals("输出不是 JSON", PhonePlan.parse("我不知道").error);
        assertEquals(PhonePlan.Kind.INVALID, PhonePlan.parse("{\"steps\":[]}").kind);
        PhonePlan u = PhonePlan.parse("{\"steps\":[{\"do\":\"rm -rf\"}]}");
        assertEquals(PhonePlan.Kind.INVALID, u.kind);
        assertTrue(u.error.contains("不认识的动作"));
        assertEquals(PhonePlan.Kind.INVALID, PhonePlan.parse("{\"steps\":[{\"do\":}]}").kind);
    }

    @Test public void capsAtMaxSteps() {
        StringBuilder b = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < 30; i++) b.append(i > 0 ? "," : "").append("{\"do\":\"back\"}");
        b.append("]}");
        assertEquals(PhonePlan.MAX_STEPS, PhonePlan.parse(b.toString()).steps.size());
    }

    @Test public void validateAgainstScreen() {
        UiTree u = FakeScreens.screen("p", "a").button("确定").edit("搜索", "q").password("pwd").ui(null);
        PhonePlan.Step tap = new PhonePlan.Step();
        tap.op = "tap";
        assertEquals("缺少元素编号 id", PhonePlan.validate(tap, u));
        tap.id = 1;
        assertNull(PhonePlan.validate(tap, u));
        tap.id = 99;
        assertTrue(PhonePlan.validate(tap, u).contains("不在当前界面"));
        PhonePlan.Step type = new PhonePlan.Step();
        type.op = "type";
        type.id = 1;
        type.text = "x";
        assertTrue(PhonePlan.validate(type, u).contains("不是输入框"));
        type.id = 2;
        assertNull(PhonePlan.validate(type, u));
        type.id = 3;
        assertEquals("不替用户填密码", PhonePlan.validate(type, u));
        type.id = -1;   // 当前焦点
        assertNull(PhonePlan.validate(type, u));
        type.text = "";
        assertEquals("输入内容为空", PhonePlan.validate(type, u));
        PhonePlan.Step key = new PhonePlan.Step();
        key.op = "key";
        key.key = "power";
        assertNotNull(PhonePlan.validate(key, u));
        key.key = "enter";
        assertNull(PhonePlan.validate(key, u));
        PhonePlan.Step l = new PhonePlan.Step();
        l.op = "launch";
        assertNotNull(PhonePlan.validate(l, u));
    }

    @Test public void stepSigAndToString() {
        PhonePlan p = PhonePlan.parse("{\"steps\":[{\"do\":\"type\",\"id\":3,\"text\":\"北京\"},{\"do\":\"type\",\"id\":3,\"text\":\"上海\"}]}");
        assertNotEquals(p.steps.get(0).sig(), p.steps.get(1).sig());
        assertEquals("type [3] 「北京」", p.steps.get(0).toString());
    }
}
