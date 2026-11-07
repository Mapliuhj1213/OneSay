package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** 系统能力 → 开关可用性。未知（null）一律按可用，保证默认行为不变。 */
public class CapabilitiesTest {
    static Capabilities.Env env(int sdk) {
        Capabilities.Env e = new Capabilities.Env();
        e.sdkInt = sdk;
        return e;
    }

    @Test public void unknownMeansAvailable() {
        for (Capabilities.Status s : Capabilities.all(env(26))) {
            assertTrue(s.toString(), s.available);
            assertEquals("", s.reason);
        }
    }

    @Test public void notificationsOff() {
        Capabilities.Env e = env(34);
        e.notificationsEnabled = false;
        Capabilities.Status s = Capabilities.of(Capabilities.Feature.NOTIFY, e);
        assertFalse(s.available);
        assertTrue(s.reason.contains("通知权限"));
        assertFalse("锁屏票码跟着不可用", Capabilities.of(Capabilities.Feature.LOCKSCREEN_TICKET, e).available);
    }

    @Test public void channelOff() {
        Capabilities.Env e = env(26);
        e.notificationsEnabled = true;
        e.channelEnabled = false;
        assertFalse(Capabilities.of(Capabilities.Feature.NOTIFY, e).available);
    }

    @Test public void exactAlarmDenied() {
        Capabilities.Env e = env(33);
        e.exactAlarmAllowed = false;
        Capabilities.Status s = Capabilities.of(Capabilities.Feature.EXACT_ALARM, e);
        assertFalse(s.available);
        assertTrue(Capabilities.of(Capabilities.Feature.NOTIFY, e).available);
    }

    @Test public void oldAndroid() {
        assertFalse(Capabilities.of(Capabilities.Feature.NOTIFY, env(23)).available);
    }

    @Test public void oemBatteryHintIsAdvisoryOnly() {
        Capabilities.Env e = env(34);
        e.manufacturer = "samsung";
        e.ignoringBatteryOptimizations = false;
        Capabilities.Status s = Capabilities.of(Capabilities.Feature.BACKGROUND, e);
        assertTrue(s.available);
        assertTrue(s.reason.length() > 0);
        e.manufacturer = "Google";
        assertEquals("", Capabilities.of(Capabilities.Feature.BACKGROUND, e).reason);
        e.manufacturer = "samsung";
        e.ignoringBatteryOptimizations = true;
        assertEquals("", Capabilities.of(Capabilities.Feature.BACKGROUND, e).reason);
    }

    @Test public void oemMatching() {
        assertTrue(Capabilities.aggressiveOem("Xiaomi"));
        assertTrue(Capabilities.aggressiveOem("HUAWEI"));
        assertFalse(Capabilities.aggressiveOem("Google"));
        assertFalse(Capabilities.aggressiveOem(null));
    }
}
