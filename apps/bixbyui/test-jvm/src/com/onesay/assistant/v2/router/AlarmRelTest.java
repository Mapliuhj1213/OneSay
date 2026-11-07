package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ToolGate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.*;

/** ★ 11-04 E：「N 分钟 / 半小时 / 一个半小时后叫醒我」= 闹钟快路径（零 LLM）；新建闹钟 / 计时器污点下免确认。 */
public class AlarmRelTest {

    static long at(int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 4, h, m, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @After public void tearDown() {
        FastIntents.sNowForTest = 0L;
    }

    static void expect(String q, int h, int m) throws Exception {
        Invocation f = FastIntents.route(q);
        assertNotNull(q, f);
        assertEquals(q, "clock.alarm.set", f.sign);
        assertEquals(q, h, f.args.getInt("hour"));
        assertEquals(q, m, f.args.getInt("minute"));
    }

    @Test public void relativeWakeUps() throws Exception {
        FastIntents.sNowForTest = at(2, 41);
        expect("30分钟后叫醒我", 3, 11);
        expect("三十分钟后叫醒我", 3, 11);
        expect("半小时后叫醒我", 3, 11);
        expect("一个半小时后叫醒我", 4, 11);
        expect("两个小时后叫醒我", 4, 41);
        expect("10分钟之后叫我起床", 2, 51);
        FastIntents.sNowForTest = at(23, 50);
        expect("20分钟后叫醒我", 0, 10);   // 跨午夜
    }

    @Test public void notRelativeAlarm() {
        assertNull(FastIntents.alarmRel("半分钟后叫醒我"));
        assertNull(FastIntents.alarmRel("30分钟后提醒我开会"));
        assertNull(FastIntents.alarmRel("三十小时后叫醒我"));   // > 24 小时
    }

    @Test public void benignCreateSkipsConfirmEvenWhenTainted() throws Exception {
        CapabilityRegistry reg = CapabilityRegistry.getInstance();
        Capability alarm = reg.describe("clock.alarm.set");
        Capability timer = reg.describe("clock.timer.set");
        assertNotNull(alarm);
        assertNotNull(timer);
        JSONObject a = new JSONObject().put("hour", 3).put("minute", 11);
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(alarm, a, true).decision);
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(alarm, a, false).decision);
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(timer, new JSONObject().put("seconds", 600), true).decision);
        assertTrue(ToolGate.isBenignCreate(alarm));
        // 其他设备控制在污点下仍要确认
        Capability bri = reg.describe("settings.brightness.set");
        assertNotNull(bri);
        assertFalse(ToolGate.isBenignCreate(bri));
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, ToolGate.check(bri, new JSONObject().put("level", 100), true).decision);
    }
}
