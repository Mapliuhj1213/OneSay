package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.trip.TripIntent;
import org.junit.Test;

/** ★ trip-decompose（PLAN-trip-decompose）：出行安排 = 复合任务进 AgentLoop 规划模式；空洞「做不了」收工打回一次。 */
public class TripDecomposeTest {

    static final String[] TRIP = {
            "下个月10号我要去东京让它帮我安排一下",
            "下个月10号去东京帮我安排一下",
            "下周出差上海帮我安排一下",
            "国庆去成都玩两天帮我规划一下",
            "帮我准备下周的出差",
            "月底带爸妈去三亚帮我搞定",
            "这周末去杭州玩，帮我规划下行程",
            "12月5号飞北京开会，帮我安排",
            "春节回老家，帮我准备一下",
            "五一想去大理旅游帮我规划",
            "下星期三去深圳出差帮我准备一下",
            "元旦带孩子去哈尔滨玩帮我安排好",
            "后天去广州，帮我安排一下行程",
    };

    static final String[] NOT_TRIP = {"帮我安排一下明天的会议", "准备一下发言稿", "明天早上8点提醒我开会", "打开蓝牙", "东京现在几点"};

    @Test public void tripPhrasesGoToPlanMode() {
        for (String u : TRIP) {
            assertTrue(u, TripIntent.isTrigger(u));
            TriageDecider.Decision d = TriageDecider.decide(u, TriageDecider.Signals.offline());
            assertEquals(u, TriageDecider.RULE_TRIP_PLAN, d.rule);
            assertTrue(u, d.useAgentLoop);
            assertNotEquals(u, TriageDecider.Label.AMBIGUOUS, d.label);
            assertNull(u, FastPathTriage.triage(u, null));
        }
    }

    @Test public void nonTripUntouched() {
        for (String u : NOT_TRIP) {
            assertFalse(u, TripIntent.isTrigger(u));
            assertNotEquals(u, TriageDecider.RULE_TRIP_PLAN, TriageDecider.decide(u, TriageDecider.Signals.offline()).rule);
        }
    }

    @Test public void schedLocalDoesNotSwallowTrip() {
        for (String u : TRIP) assertFalse(u, com.onesay.assistant.v2.sched.SchedEngine.looksLikeSchedule(u));
        assertTrue(com.onesay.assistant.v2.sched.SchedEngine.looksLikeSchedule("明天下午3点开会"));
    }

    @Test public void hollowRefusalDetected() {
        assertTrue(TripIntent.isHollowRefusal("没接口，干不了"));
        assertTrue(TripIntent.isHollowRefusal("这个我还做不了，可以换个说法试试"));
        assertFalse(TripIntent.isHollowRefusal("已建好日历事件和闹钟；机票子目标卡在搜索超时，缺少航班数据，其余项已完成。"));
        assertFalse(TripIntent.isHollowRefusal("好的，11月10日出发，回程和人数你决定吗？"));
    }

    @Test public void planModeHollowRefusalIsBouncedOnceThenDecomposes() {
        for (String u : new String[]{TRIP[0], TRIP[2], TRIP[4]}) {
            AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
            llm.replies.add(AgentLoopTest.say("没接口，干不了"));
            llm.replies.add(AgentLoopTest.say("已拆成子目标：①日期 ②日程闹钟 ③机票 ④酒店 ⑤天气入境汇率 ⑥路线 ⑦清单。回程、人数、出发城市你定还是你决定？"));
            AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run(u, new AgentLoopTest.FakeExec("x.y"), 6);
            assertEquals(u, 2, llm.messagesSeen.size());
            String sys = llm.messagesSeen.get(0).optJSONObject(0).optString("content");
            assertTrue(u, sys.contains("[PLAN-MODE]"));
            assertTrue(u, llm.messagesSeen.get(1).toString().contains("[PLAN-CHECK]"));
            assertFalse(u, r.answer.contains("做不了"));
            assertTrue(u, r.answer.contains("子目标"));
        }
    }

    @Test public void tripSkillAssetPresent() throws Exception {
        String md = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/assets/skill_trip.md")), "UTF-8");
        assertTrue(md.contains("id: trip"));
        assertTrue(md.contains("只问一次"));
        assertTrue(md.contains("只推荐"));
        assertEquals(7, TripIntent.SUBGOALS.length);
    }
}
