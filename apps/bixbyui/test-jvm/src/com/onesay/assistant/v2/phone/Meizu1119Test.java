package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.exec.ExecState;

import org.junit.Test;

import static org.junit.Assert.*;

/** [overnight-1119] 魅族遗留：识屏用不了就不再要截图；暂停中的任务让给新任务，正在动的任务说清楚怎么停。 */
public class Meizu1119Test {

    @Test public void noVisionStopsAskingForScreenshots() {
        FakeScreens.Phone p = MikaPhoneTest.settings();
        FakeScreens.Script s = new FakeScreens.Script("{\"shot\":true}", "{\"shot\":true}", "{\"shot\":true}", "{\"done\":\"不该走到这\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());
        PhoneAgent.Result r = a.run("打开设置看看安卓版本", null);
        assertEquals("截屏拿不到描述只试一次", 1, p.describes);
        assertEquals(PhoneAgent.Status.FAILED, r.status);
        assertTrue(r.reason, r.reason.contains("看不了屏幕图像"));
        assertEquals(3, s.systems.size());
        assertTrue("一开始还给「要看画面」", s.systems.get(0).contains("{\"shot\":true}"));
        assertFalse("识屏用不了之后不再给", s.systems.get(2).contains("{\"shot\":true}"));
        assertTrue(s.users.get(2), s.users.get(2).contains("看不了屏幕图像"));
    }

    @Test public void noVisionStillFinishesFromTheTree() {
        FakeScreens.Phone p = MikaPhoneTest.settings();
        FakeScreens.Script s = new FakeScreens.Script("{\"shot\":true}", "{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN}}]}", "{\"done\":\"已打开 WLAN\"}");
        PhoneAgent.Result r = new PhoneAgent(p, s, new FakeScreens.Host()).run("打开 WLAN", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals("wlan", p.cur);
        assertEquals(1, p.describes);
    }

    @Test public void pausedTaskGivesWayRunningTaskSaysHowToStop() {
        assertEquals(ExecState.NewTask.RUN, ExecState.onNewTask(false, false, false));
        assertEquals(ExecState.NewTask.REPLACE, ExecState.onNewTask(true, true, false));
        assertEquals(ExecState.NewTask.REPLACE, ExecState.onNewTask(true, false, true));
        assertEquals(ExecState.NewTask.REFUSE, ExecState.onNewTask(true, false, false));
        String t = ExecState.refuseText("打开设置看看安卓版本是多少啊这个句子很长");
        assertTrue(t, t.startsWith("已有一个操作任务在进行（「打开设置看看安卓版本是多少啊这个…」）"));
        assertTrue(t.contains("停止"));
        // 失败分类还认这句（读屏助手不可用或正忙）
        com.onesay.assistant.v2.agent.PhoneFailure f = com.onesay.assistant.v2.agent.PhoneFailure.classify(false, t, "");
        assertEquals(com.onesay.assistant.v2.agent.Outcome.Reason.DEVICE_UNAVAILABLE, f.reason);
    }
}
