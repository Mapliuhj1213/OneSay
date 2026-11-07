package com.onesay.assistant.v2.router;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ int/1102：triage-fast 留下的两个模板 —— 导航（device-1102 ShellPrograms.navigate）与重复闹钟（clock.alarm.set days）。 */
public class Int1102TemplatesTest {

    private static FastIntents.Hit m(String q) {
        return FastIntents.match(q);
    }

    @Test public void navTemplate() throws Exception {
        String[][] ok = {
                {"导航到公司", "公司"}, {"帮我导航到首都机场", "首都机场"}, {"带我去最近的加油站", "最近的加油站"}, {"导航去北京南站吧", "北京南站"},
                {"送我去火车站", "火车站"}, {"去西湖怎么走", "西湖"}, {"怎么去上海虹桥站", "上海虹桥站"}, {"开导航去中关村", "中关村"},
                {"导航至望京soho", "望京soho"}, {"带我到人民广场", "人民广场"}};
        for (String[] c : ok) {
            FastIntents.Hit h = m(c[0]);
            assertNotNull(c[0], h);
            assertEquals(c[0], "screen.nav.to", h.sign);
            assertEquals(c[0], c[1], h.args.getString("address"));
            assertFalse("模板不带包名：角色层（排在分诊之前）有默认地图时自己走 s2.nav.app 带包名", h.args.has("pkg"));
        }
        for (String no : new String[]{"导航栏怎么隐藏", "打开导航", "导航到这里", "导航到刚复制的地址", "导航键在哪", "导航", "打车去机场"}) {
            FastIntents.Hit h = m(no);
            assertTrue(no + " → " + h, h == null || !"screen.nav.to".equals(h.sign));
        }
        // 执行侧：screen.nav.to 走 ShellPrograms.navigate（定位前置），且 address 是唯一必填参数（不会缺参反问）
        assertNotNull(com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("screen.nav.to"));
        assertEquals("[address]", new java.util.ArrayList<String>(com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.get("screen.nav.to").paramsSchema.getRequired()).toString());
        // 复合句：先开定位再导航 → 零 LLM 计划，两步
        Invocation plan = CompoundSplitter.split("打开定位然后导航到公司", new CompoundSplitter.Resolver() {
            @Override public Invocation resolve(String clause) { return null; }
        });
        assertNotNull(plan);
        assertEquals(2, plan.steps.size());
        assertEquals("screen.nav.to", plan.steps.get(1).sign);
    }

    @Test public void repeatingAlarmTemplate() throws Exception {
        Object[][] ok = {
                {"每天早上7点叫醒我", 7, 0, 0x7F}, {"工作日7点半叫我起床", 7, 30, 0x3E}, {"周末9点叫醒我", 9, 0, 0x41},
                {"每个工作日早上八点叫我", 8, 0, 0x3E}, {"设个每天7点的闹钟", 7, 0, 0x7F}, {"周一到周五6点半叫醒我", 6, 30, 0x3E},
                {"天天早上七点喊我起床", 7, 0, 0x7F}, {"每天晚上10点的闹钟", 22, 0, 0x7F}, {"每天6点50叫我", 6, 50, 0x7F}};
        for (Object[] c : ok) {
            FastIntents.Hit h = m((String) c[0]);
            assertNotNull((String) c[0], h);
            assertEquals("clock.alarm.set", h.sign);
            assertEquals(c[0] + " hour", ((Integer) c[1]).intValue(), h.args.getInt("hour"));
            assertEquals(c[0] + " minute", ((Integer) c[2]).intValue(), h.args.getInt("minute"));
            assertEquals(c[0] + " days", ((Integer) c[3]).intValue(), h.args.getInt("days"));
            assertEquals("alarm.repeat", h.rule);
        }
        // 一次性闹钟不带 days
        FastIntents.Hit once = m("明天早上6点叫醒我");
        assertEquals("clock.alarm.set", once.sign);
        assertFalse(once.args.has("days"));
        assertEquals(0, com.onesay.assistant.v2.executor.AlarmBridge.ClockCmd.set(6, 0, 0).days);
        assertEquals(62, com.onesay.assistant.v2.executor.AlarmBridge.ClockCmd.set(7, 30, 0x3E).days);
        assertTrue("只响一次：系统兜底不带 EXTRA_DAYS", com.onesay.assistant.v2.executor.AlarmBridge.systemSet(6, 0, 0).days.isEmpty());
        assertEquals("工作日的", com.onesay.assistant.v2.executor.AlarmBridge.repeatLabel(0x3E));
        assertEquals("每天的", com.onesay.assistant.v2.executor.AlarmBridge.repeatLabel(0x7F));
        assertEquals("每周一三五的", com.onesay.assistant.v2.executor.AlarmBridge.repeatLabel(0x2A));
        // 能力表：days 可选（0–127，默认 0）
        com.onesay.assistant.v2.capability.CapabilityRegistry r = new com.onesay.assistant.v2.capability.CapabilityRegistry();
        com.onesay.assistant.v2.capability.BuiltinCapabilities.registerAll(r);
        com.onesay.assistant.v2.capability.Capability c = r.describe("clock.alarm.set");
        assertTrue(c.paramsSchema.getRules().containsKey("days"));
        assertFalse(c.paramsSchema.getRequired().contains("days"));
    }

    /** 审计修的「删除所有闹钟反问几点」：批量闹钟零 LLM、无必填参数；分诊不再判 CLARIFY。 */
    @Test public void batchAlarmNeverAsksTime() throws Exception {
        String[][] ok = {{"删除所有闹钟", "alarm.cancel.all"}, {"把闹钟全删了", "alarm.cancel.all"}, {"清空闹钟", "alarm.cancel.all"},
                {"取消全部闹钟", "alarm.cancel.all"}, {"关掉所有闹钟", "alarm.toggle.all"}, {"把闹钟全部关了", "alarm.toggle.all"},
                {"打开所有闹钟", "alarm.toggle.all"}};
        for (String[] c : ok) {
            FastIntents.Hit h = m(c[0]);
            assertNotNull(c[0], h);
            assertEquals(c[0], c[1], h.sign);
        }
        assertTrue(m("打开所有闹钟").args.getBoolean("on"));
        assertFalse(m("关掉所有闹钟").args.getBoolean("on"));
        assertNull("单删一个闹钟不归批量", m("删除闹钟"));
        for (String q : new String[]{"删除所有闹钟", "关掉全部闹钟", "把闹钟都关了"}) {
            TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(null, false, false));
            assertNotEquals(q + " → " + d, TriageDecider.Label.CLARIFY, d.label);
        }
        assertEquals("单个闹钟没给时间仍追问", TriageDecider.Label.CLARIFY,
                TriageDecider.decide("设个闹钟", new TriageDecider.Signals(null, false, false)).label);
    }

    @Test public void richGenAndFollowupAreAsk() {
        for (String q : new String[]{"画个流程图说明用户注册流程", "给段python快速排序代码", "写个java单例模式的例子", "用mermaid画个时序图",
                "画一个饼图表示收入构成", "给我一段正则匹配手机号"}) {
            TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(null, false, false));
            assertEquals(q + " → " + d, TriageDecider.Label.ASK, d.label);
            assertFalse(d.useAgentLoop);
        }
        for (String q : new String[]{"再说一遍", "详细一点", "展开说说"}) {
            assertEquals(q, "3f-answer-followup", TriageDecider.decide(q, new TriageDecider.Signals(null, false, false)).rule);
        }
        assertEquals("6m-memory-manage", TriageDecider.decide("忘掉我的工号", new TriageDecider.Signals(null, false, false)).rule);
        // 设备动作不被富内容规则吃掉
        assertNotEquals(TriageDecider.Label.ASK, TriageDecider.decide("截图", new TriageDecider.Signals("ui.screenshot", false, false)).label);
    }

    @Test public void repeatingAlarmMapsToSystemDays() {
        assertEquals(java.util.Arrays.asList(2, 3, 4, 5, 6), com.onesay.assistant.v2.executor.AlarmBridge.calendarDays(0x3E));
    }
}
