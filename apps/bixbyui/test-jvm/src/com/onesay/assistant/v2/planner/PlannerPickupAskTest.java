package com.onesay.assistant.v2.planner;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.util.TimeZone;

/**
 * 取件码问答（main 已有逻辑 planner-link 6a7a6fe 的修复，在 feat/shortcuts 上修）。
 * 真机：「我的取件码是多少」答得出，「取件码是 A1-2345 的快递在哪个驿站」答不上（落到大模型，模型看不到计划 App 里的件）；
 * 「取件码是 A1-2345 在哪里」还会被当成「存一个取件码，驿站 = 哪里」。
 */
public class PlannerPickupAskTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final long NOW = 1794000000000L;

    static final String[] BY_CODE = {"取件码是A1-2345的快递在哪个驿站", "取件码是 A1-2345 的快递在哪个驿站？", "取件码是A1-2345在哪里",
            "取件码A1-2345在哪", "查一下取件码A1-2345", "A1-2345这个取件码在哪取", "A1-2345是哪个驿站的取件码", "我的取件码A1-2345还在吗",
            "取件码是a1 2345在哪里？"};
    static final String[] LIST = {"我的取件码是多少", "我的取件码是多少？", "取件码是多少", "我有什么快递要取", "我有什么快递要取？", "有没有快递要取",
            "还有没有快递没取", "取件码呢", "取件码", "我还有快递没取吗", "待取的快递有哪些"};
    static final String[] SAVE = {"取件码是5-2-1234", "取件码是 A1-2345，在测试菜鸟驿站", "记一下取餐码 A088，在瑞幸", "我的取件码是 A1-2345"};
    static final String[] NEITHER = {"今天有什么安排", "帮我买张票", "快递到哪了", "记一记"};

    static PlannerBackend.Mem mem() {
        PlannerBackend.Mem b = new PlannerBackend.Mem();
        b.insert(PlannerContract.T_PICKUPS, PlannerMap.pickup("A1-2345", "取件码", "测试菜鸟驿站", "", 0, "test:demo:pk1", "test"));
        return b;
    }

    @Test public void questionsAreAsksNotSaves() {
        for (String q : BY_CODE) {
            PlannerVoice.PickupAsk a = PlannerVoice.pickupAsk(q);
            assertNotNull("按码查：" + q, a);
            assertEquals(q, "A12345", PlannerVoice.codeKey(a.code));
            assertNull("问句不是存：" + q, PlannerVoice.pickup(q));
        }
        for (String q : LIST) {
            PlannerVoice.PickupAsk a = PlannerVoice.pickupAsk(q);
            assertNotNull("列出待取：" + q, a);
            assertEquals(q, "", a.code);
            assertNull("问句不是存：" + q, PlannerVoice.pickup(q));
        }
        for (String q : SAVE) {
            assertNull("存不是问：" + q, PlannerVoice.pickupAsk(q));
            assertNotNull("照旧存：" + q, PlannerVoice.pickup(q));
        }
        assertEquals("测试菜鸟驿站", PlannerVoice.pickup("取件码是 A1-2345，在测试菜鸟驿站").station);
        for (String q : NEITHER) {
            assertNull(q, PlannerVoice.pickupAsk(q));
            assertNull(q, PlannerVoice.pickup(q));
        }
        // 「在哪里」不能被存成驿站（老正则 group(3) = 「哪里」）
        assertNull(PlannerVoice.pickup("取件码是A1-2345，在哪里"));
        assertNull(PlannerVoice.pickup("取件码是A1-2345 在哪个驿站"));
    }

    @Test public void answersFromPlannerPickups() {
        PlannerBackend.Mem b = mem();
        String a1 = PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("取件码是A1-2345的快递在哪个驿站"), NOW, TZ);
        assertTrue(a1, a1.contains("A1-2345") && a1.contains("测试菜鸟驿站") && a1.contains("还没取"));
        String a2 = PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("我的取件码是多少"), NOW, TZ);
        assertTrue(a2, a2.contains("A1-2345") && a2.contains("测试菜鸟驿站") && a2.startsWith("有 1 个件没取"));
        String a3 = PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("取件码是a1 2345在哪里？"), NOW, TZ);
        assertTrue("写法不同也认得：" + a3, a3.contains("测试菜鸟驿站"));
        String a4 = PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("取件码是B9-9999在哪里"), NOW, TZ);
        assertTrue("没有这个码：如实说，并列出待取的：" + a4, a4.startsWith("计划里没有取件码 B9-9999") && a4.contains("A1-2345"));
        String a5 = PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("取件码2345在哪"), NOW, TZ);
        assertTrue("只说后几位且唯一：" + a5, a5.contains("测试菜鸟驿站"));
        // 只读：没有新增 / 改动
        assertEquals(1, b.list(PlannerContract.T_PICKUPS).size());
        assertEquals(0L, PlannerMap.l(b.list(PlannerContract.T_PICKUPS).get(0), "taken_at"));
    }

    @Test public void takenAndEmpty() {
        PlannerBackend.Mem b = new PlannerBackend.Mem();
        assertNull("只问「取件码是多少」、计划里没有 → 交给记一记", PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("我的取件码是多少"), NOW, TZ));
        assertEquals("计划里没有待取的快递。", PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("我有什么快递要取"), NOW, TZ));
        long id = b.insert(PlannerContract.T_PICKUPS, PlannerMap.pickup("A1-2345", "取件码", "测试菜鸟驿站", "", NOW - 3600000L, null, "test"));
        String late = PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("取件码是A1-2345在哪里"), NOW, TZ);
        assertTrue(late, late.contains("已经过了"));
        java.util.Map<String, Object> v = new java.util.LinkedHashMap<String, Object>();
        v.put("taken_at", NOW);
        b.update(PlannerContract.T_PICKUPS, id, v);
        String done = PlannerHooks.pickupReply(b, PlannerVoice.pickupAsk("取件码是A1-2345在哪里"), NOW, TZ);
        assertTrue(done, done.contains("已经取了"));
    }

    @Test public void wiring() {
        String hooks = KernelWiring.src("app/src/com/onesay/assistant/v2/planner/PlannerHooks.java");
        assertTrue("claims 认问句", hooks.contains("PlannerVoice.pickupAsk(q) == null && !PlannerVoice.ticket(q)"));
        assertTrue("route 先判问句再判存", hooks.contains("PlannerVoice.Pickup p = ask != null ? null : PlannerVoice.pickup(q);")
                && hooks.contains("pickupReply(b, ask, System.currentTimeMillis(), TimeZone.getDefault())"));
        String pp = KernelWiring.src("app/src/com/onesay/assistant/v2/kernel/plugins/PlannerPlugin.java");
        assertTrue("「这个取件码」不被识屏指代抢走", pp.contains("PlannerVoice.pickupAsk(command) != null"));
    }
}
