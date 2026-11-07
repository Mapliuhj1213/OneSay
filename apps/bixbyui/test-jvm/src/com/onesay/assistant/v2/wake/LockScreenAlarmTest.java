package com.onesay.assistant.v2.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.SideEffect;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 锁屏下「7点叫我 / 30分钟后提醒我」原被 ScreenOffPolicy.unlockDenyReason（在 ToolGate 之前）拒绝。
 * 新建闹钟 / 计时器锁屏放行；删除 / 修改闹钟、发消息、改设置照旧要先解锁。
 */
public class LockScreenAlarmTest {

    @Before public void lock() {
        ScreenOffPolicy.setKeyguardProbe(new ScreenOffPolicy.KeyguardProbe() {
            @Override public boolean locked() { return true; }
        });
    }

    @After public void unlock() { ScreenOffPolicy.setKeyguardProbe(null); }

    @Test public void realAlarmAndTimerCreateAllowedWhenLocked() {
        Capability alarm = CapabilityRegistry.getInstance().describe("clock.alarm.set");
        Capability timer = CapabilityRegistry.getInstance().describe("clock.timer.set");
        assertNotNull(alarm);
        assertNotNull(timer);
        assertNull("新建闹钟锁屏放行", ScreenOffPolicy.unlockDenyReason(alarm));
        assertNull("新建计时器锁屏放行", ScreenOffPolicy.unlockDenyReason(timer));
    }

    @Test public void deleteOrEditAlarmStillNeedsUnlock() {
        Capability del = new Capability.Builder("clock.alarm.delete", "删除闹钟").risk(2, true).sideEffect(SideEffect.DESTRUCTIVE).cmd("x").build();
        Capability edit = new Capability.Builder("clock.alarm.update", "修改闹钟").risk(1, false).sideEffect(SideEffect.DEVICE_CONTROL).cmd("x").build();
        // 同名 sign 但被声明为破坏性：isBenignCreate 不放行
        Capability fakeSet = new Capability.Builder("clock.alarm.set", "x").risk(2, true).sideEffect(SideEffect.DESTRUCTIVE).cmd("x").build();
        Capability sms = new Capability.Builder("t.sms", "发短信").risk(2, true).sideEffect(SideEffect.PUBLIC_COMMS).cmd("x").build();
        Capability wifi = CapabilityRegistry.getInstance().describe("settings.brightness.set");
        assertEquals(ScreenOffPolicy.UNLOCK_REASON, ScreenOffPolicy.unlockDenyReason(del));
        assertEquals(ScreenOffPolicy.UNLOCK_REASON, ScreenOffPolicy.unlockDenyReason(edit));
        assertEquals(ScreenOffPolicy.UNLOCK_REASON, ScreenOffPolicy.unlockDenyReason(fakeSet));
        assertEquals(ScreenOffPolicy.UNLOCK_REASON, ScreenOffPolicy.unlockDenyReason(sms));
        if (wifi != null && ScreenOffPolicy.requiresUnlock(wifi.sideEffect)) {
            assertEquals(ScreenOffPolicy.UNLOCK_REASON, ScreenOffPolicy.unlockDenyReason(wifi));
        }
    }
}
