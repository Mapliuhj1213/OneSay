package com.onesay.assistant.v2.planner;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.sched.SchedEvent;
import com.onesay.assistant.v2.sched.SchedStore;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** feat/planner-link 适配层：计划 App 在 / 不在两种回落、出错回落、迁移包、提醒去重、认票边界、语音说法。 */
public class PlannerLinkTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar k = Calendar.getInstance(TZ);
        k.clear();
        k.set(y, mo - 1, d, h, mi, 0);
        return k.getTimeInMillis();
    }

    static final long NOW = at(2026, 11, 5, 11, 30);

    static SchedEvent ev(String t, long start) {
        SchedEvent e = new SchedEvent();
        e.title = t;
        e.startAt = start;
        return e;
    }

    // ------------------------------------------------------------------ 回落

    @Test public void plannerOnWritesToPlannerOnly() {
        PlannerBackend.Mem b = new PlannerBackend.Mem();
        SchedStore.Memory local = new SchedStore.Memory();
        PlannerGate.Fixed g = new PlannerGate.Fixed(true);
        SwitchStores.Sched s = new SwitchStores.Sched(local, new PlannerSchedStore(b), g);
        long id = s.insert(ev("评审会", at(2026, 11, 6, 15, 0)));
        assertTrue(id > 0);
        assertEquals(1, b.list(PlannerContract.T_EVENTS).size());
        assertEquals("评审会", b.list(PlannerContract.T_EVENTS).get(0).get("title"));
        assertTrue("本地库不写", local.all(true).isEmpty());
        assertEquals("评审会", s.get(id).title);
    }

    @Test public void plannerAbsentFallsBackToLocal() {
        PlannerBackend.Mem b = new PlannerBackend.Mem();
        SchedStore.Memory local = new SchedStore.Memory();
        SwitchStores.Sched s = new SwitchStores.Sched(local, new PlannerSchedStore(b), new PlannerGate.Fixed(false));
        s.insert(ev("吃饭", at(2026, 11, 6, 19, 0)));
        assertEquals(1, local.all(true).size());
        assertTrue(b.list(PlannerContract.T_EVENTS).isEmpty());
    }

    @Test public void plannerErrorMidwayFallsBackAndCoolsDown() {
        PlannerBackend.Mem b = new PlannerBackend.Mem();
        b.down = true;   // 例如计划 App 被卸载 / 进程死了
        SchedStore.Memory local = new SchedStore.Memory();
        PlannerGate.Fixed g = new PlannerGate.Fixed(true);
        SwitchStores.Sched s = new SwitchStores.Sched(local, new PlannerSchedStore(b), g);
        s.insert(ev("例会", at(2026, 11, 7, 9, 50)));
        assertEquals("失败记一次", 1, g.failures);
        assertFalse("冷却期内不再试计划 App", g.on());
        assertEquals("这次写进了本地，功能不丢", 1, local.all(true).size());
    }

    @Test public void softDeleteMapsToDeletedAt() {
        PlannerBackend.Mem b = new PlannerBackend.Mem();
        PlannerSchedStore p = new PlannerSchedStore(b);
        long id = p.insert(ev("删我", at(2026, 11, 6, 10, 0)));
        assertTrue(p.setDeleted(id, true, NOW));
        assertEquals(0, p.all(false).size());
        assertEquals(1, p.all(true).size());
        assertTrue(((Number) b.get(PlannerContract.T_EVENTS, id).get("deleted_at")).longValue() > 0);
    }

    // ------------------------------------------------------------------ 迁移

    @Test public void migrationBundleSinceFiltersAndCounts() throws Exception {
        Map<String, List<Map<String, Object>>> t = new HashMap<String, List<Map<String, Object>>>();
        List<Map<String, Object>> todos = new ArrayList<Map<String, Object>>();
        Map<String, Object> old = new LinkedHashMap<String, Object>();
        old.put("_id", 1L); old.put("title", "旧的"); old.put("created_at", 1000L);
        Map<String, Object> neu = new LinkedHashMap<String, Object>();
        neu.put("_id", 2L); neu.put("title", "新的"); neu.put("created_at", 5000L);
        todos.add(old); todos.add(neu);
        t.put("s3_todo", todos);
        JSONObject all = MigrationBundle.build(t, "[{\"code\":\"5-2-1\",\"created\":4000}]", 0, NOW);
        assertEquals(PlannerContract.BUNDLE_FORMAT, all.getString("format"));
        assertEquals(3, MigrationBundle.count(all));
        JSONObject since = MigrationBundle.build(t, "[{\"code\":\"5-2-1\",\"created\":4000}]", 4500, NOW);
        assertEquals("补导只带计划 App 不在期间新增的", 1, MigrationBundle.count(since));
        assertEquals("新的", since.getJSONArray("s3_todo").getJSONObject(0).getString("title"));
        assertFalse("模板任务 / 联网监控不导", all.has("jobs"));
    }

    // ------------------------------------------------------------------ 去重

    @Test public void islandYieldsToPlannerNotifications() {
        List<String> planned = Arrays.asList("sched:1", "sched:2");
        assertTrue("计划 App 在：岛上不重复", PlannerDedupe.island(planned, new PlannerGate.Fixed(true)).isEmpty());
        assertEquals("计划 App 不在：岛照旧", planned, PlannerDedupe.island(planned, new PlannerGate.Fixed(false)));
        assertEquals(planned, PlannerDedupe.island(planned, null));
        PlannerGate boom = new PlannerGate() {
            @Override public boolean on() { throw new RuntimeException("x"); }
            @Override public void failed(Throwable t) {}
        };
        assertEquals("判断出错按不在处理", planned, PlannerDedupe.island(planned, boom));
        PlannerDedupe.KeyOf<String> id = new PlannerDedupe.KeyOf<String>() {
            @Override public String key(String s) { return "bixbyui:" + s; }
        };
        assertEquals(Arrays.asList("sched:2"), PlannerDedupe.dropOwned(planned, id, new HashSet<String>(Arrays.asList("bixbyui:sched:1"))));
    }

    // ------------------------------------------------------------------ 认票边界（与计划 App core/TicketTextTest 同一组页面）

    // ---- 2026-11-05 票根边界测试页（device/bp-ticket/*.html 的可见文字，内容全是假的）----
    static final String PAGE_A = "测试影票 · 购票成功\n⚠ 测试页面，非真实票据，所有信息均为虚构（Bixby 识别边界测试）\n测试影片：边界之外（测试）\n2D 国语 · 120 分钟\n影院\n测试影城（虚构路 1 号店）\n场次\n2026年11月8日 周日 19:30\n影厅 / 座位\n9 号厅 · 7排8座、7排9座\n取票码\nTEST 8888 0001\n订单号\nTESTORDER20261108001\n凭二维码或取票码在测试影城自助机取票\n实付\n¥0.00（测试）";
    static final String PAGE_B_FIRST = "测试影票 · 订单详情\n⚠ 测试页面，非真实票据，所有信息均为虚构（Bixby 识别边界测试）\n订单状态\n已出票（取票码稍后短信发送）\n订单号\nTESTORDER20261109002\n商品\n电影票 × 1\n实付\n¥0.00（测试）\n支付方式\n测试钱包\n";
    static final String PAGE_B_FULL = "测试影票 · 订单详情\n⚠ 测试页面，非真实票据，所有信息均为虚构（Bixby 识别边界测试）\n订单状态\n已出票（取票码稍后短信发送）\n订单号\nTESTORDER20261109002\n商品\n电影票 × 1\n实付\n¥0.00（测试）\n支付方式\n测试钱包\n退改签说明\n开场前 60 分钟可退（测试文字）。\n测试说明文字。\n测试影片：边界之外（测试）\n场次\n11月9日 14:00\n影院\n测试影城（虚构路 2 号店）\n座位\n5 号厅 3排12座";
    static final String PAGE_C = "测试影票 · 选座\n⚠ 测试页面，非真实票据，所有信息均为虚构（Bixby 识别边界测试）\n测试影片：边界之外（测试）\n测试影城（虚构路 1 号店） · 11月10日 20:15 · 9 号厅\n银幕\n已选\n7排8座、7排9座\n待支付\n¥0.00（测试） · 请在 14:59 内完成支付\n确认支付（测试，不会扣款）";

    @Test public void ticketBoundaryPages() {
        PRow a = PlannerTicketText.parse(PAGE_A, NOW, TZ);
        assertNotNull(a);
        assertEquals("movie", a.s("kind"));
        assertEquals(at(2026, 11, 8, 19, 30), a.l("depart_at"));
        assertEquals("TEST88880001", a.s("order_no"));
        assertEquals("测试影城 9号厅", a.s("venue"));
        assertNull("B 首屏：只有订单号", PlannerTicketText.parse(PAGE_B_FIRST, NOW, TZ));
        assertEquals("TESTORDER20261109002", PlannerTicketText.parse(PAGE_B_FULL, NOW, TZ).s("order_no"));
        assertNull("C 选座待支付：不建", PlannerTicketText.parse(PAGE_C, NOW, TZ));
    }

    @Test public void ticketVoicePhrases() {
        for (String q : new String[]{"把这张票存起来", "帮我存一下这张票", "帮我记下这张票", "记一下这张电影票", "票放进计划里"}) assertTrue(q, PlannerVoice.ticket(q));
        for (String q : new String[]{"帮我买张票", "票价多少", "记一记"}) assertFalse(q, PlannerVoice.ticket(q));
        assertTrue(PlannerVoice.open("打开计划"));
    }
}
