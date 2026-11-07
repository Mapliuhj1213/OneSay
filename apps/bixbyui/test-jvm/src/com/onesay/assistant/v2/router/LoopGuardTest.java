package com.onesay.assistant.v2.router;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** ★ 11-04 A：原地打转检测（同动作同参数 ≥3、连续 6 步无进展、碰墙 ≥3 / 预订类 ≥2）。 */
public class LoopGuardTest {

    static LoopGuard.S s(String tool, String args, String obs, boolean ok) {
        return new LoopGuard.S(tool, args, obs, ok);
    }

    static String longText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 40; i++) b.append("杭州西湖附近酒店价格每晚三百到五百元。");
        return b.toString();
    }

    @Test public void sameCallThreeTimes() {
        List<LoopGuard.S> l = new ArrayList<LoopGuard.S>();
        l.add(s("phone.operate", "{\"app\":\"携程\"}", "{\"ok\":true}", true));
        l.add(s("phone.operate", "{\"app\":\"携程\"}", "{\"ok\":true}", true));
        assertNull("两次不算", LoopGuard.check(l, "帮我看看携程"));
        l.add(s("phone.operate", "{\"app\":\"携程\"}", "{\"ok\":true}", true));
        LoopGuard.Stop st = LoopGuard.check(l, "帮我看看携程");
        assertNotNull(st);
        assertEquals("same", st.kind);
        assertEquals(3, st.count);
    }

    @Test public void probesAreNeutral() {
        List<LoopGuard.S> l = new ArrayList<LoopGuard.S>();
        for (int i = 0; i < 4; i++) l.add(s("probe.apps", "{\"keyword\":\"携程\"}", "{\"ok\":true,\"found\":false}", true));
        assertNull("探针连发不算打转（另有探针预算）", LoopGuard.check(l, "帮我订个杭州的酒店"));
    }

    @Test public void bookingStopsAtSecondWall() {
        List<LoopGuard.S> l = new ArrayList<LoopGuard.S>();
        l.add(s("web.read", "{\"url\":\"https://hotels.ctrip.com/hz\"}", "页面要登录才能查看价格", false));
        assertNull(LoopGuard.check(l, "帮我订个杭州的酒店"));
        l.add(s("web.read", "{\"url\":\"https://www.klook.com/hotels\"}", "反爬校验页，没渲染出内容", false));
        LoopGuard.Stop st = LoopGuard.check(l, "帮我订个杭州的酒店");
        assertNotNull(st);
        assertEquals("walls", st.kind);
        assertTrue(st.where, st.where.contains("携程") && st.where.contains("客路"));
        assertNull("非预订类 2 次墙还不停", LoopGuard.check(l, "查一下杭州酒店价格"));
        l.add(s("web.read", "{\"url\":\"https://www.trip.com/x\"}", "HTTP 404", false));
        assertEquals("walls", LoopGuard.check(l, "查一下杭州酒店价格").kind);
    }

    @Test public void sixStepsWithoutProgress() {
        List<LoopGuard.S> l = new ArrayList<LoopGuard.S>();
        for (int i = 0; i < 5; i++) l.add(s("web.search", "{\"query\":\"杭州酒店 " + i + "\"}", "[]", true));
        assertNull("5 步不停", LoopGuard.check(l, "查一下杭州酒店价格"));
        l.add(s("web.search", "{\"query\":\"杭州酒店 5\"}", "[]", true));
        LoopGuard.Stop st = LoopGuard.check(l, "查一下杭州酒店价格");
        assertNotNull(st);
        assertEquals("no_progress", st.kind);
        assertEquals(6, st.count);
    }

    @Test public void realReadResetsProgress() {
        List<LoopGuard.S> l = new ArrayList<LoopGuard.S>();
        for (int i = 0; i < 4; i++) l.add(s("web.search", "{\"query\":\"q" + i + "\"}", "[]", true));
        l.add(s("web.read", "{\"url\":\"https://www.dianping.com/x\"}", longText(), true));
        for (int i = 0; i < 4; i++) l.add(s("web.search", "{\"query\":\"r" + i + "\"}", "[]", true));
        assertNull("读到正文算进展，计数从头来", LoopGuard.check(l, "查一下杭州酒店价格"));
    }

    @Test public void messageIsHonestAndNeverClaimsOrder() {
        LoopGuard.Stop st = LoopGuard.check(java.util.Arrays.asList(
                s("web.read", "{\"url\":\"https://hotels.ctrip.com/hz\"}", "需要登录", false),
                s("web.read", "{\"url\":\"https://hotels.ctrip.com/hz2\"}", "需要登录", false)), "帮我订个杭州的酒店");
        String m = LoopGuard.message(st, "帮我订个杭州的酒店");
        assertTrue(m, m.startsWith("我先停下了") && m.contains("携程"));
        assertTrue(m, m.contains("没有下单，也没有付款"));
        assertFalse(m, m.contains("已下单") || m.contains("已付款") || m.contains("已预订"));
        assertTrue(m, m.contains("算了"));
    }
}
