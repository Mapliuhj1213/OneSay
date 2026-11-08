package com.onesay.assistant.v2.companion;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** 伴侣发现：装没装 → 签名 → link.hello（接口名 / 版本 / 能力）。版本不对、缺能力都不算 READY，但算「在场」（AgentLoop 不探测第三方）。 */
public class CompanionHandshakeTest {

    static Map<String, Object> hello(Object version, Object minClient, Object caps) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(CompanionContract.K_IFACE, CompanionContract.IFACE);
        m.put(CompanionContract.K_VERSION, version);
        m.put(CompanionContract.K_MIN_CLIENT, minClient);
        m.put(CompanionContract.K_CAPS, caps);
        return m;
    }

    static final String PLAN_CAPS = CompanionContract.joinCaps(CompanionContract.PLAN_CAPS);

    @Test public void absentAndUntrusted() {
        CompanionHandshake.Result r = CompanionHandshake.evaluate(false, false, null, CompanionContract.PLAN_REQUIRED);
        assertEquals(CompanionHandshake.State.ABSENT, r.state);
        assertFalse(r.state.present());
        r = CompanionHandshake.evaluate(true, false, hello(1, 1, PLAN_CAPS), CompanionContract.PLAN_REQUIRED);
        assertEquals("签名不同时连 hello 都不信", CompanionHandshake.State.UNTRUSTED, r.state);
        assertFalse(r.state.present());
    }

    @Test public void ready() {
        CompanionHandshake.Result r = CompanionHandshake.evaluate(true, true, hello(1, 1, PLAN_CAPS), CompanionContract.PLAN_REQUIRED);
        assertEquals(CompanionHandshake.State.READY, r.state);
        assertTrue(r.state.ready() && r.state.present());
        assertEquals(1, r.peerVersion);
        assertTrue(r.has(CompanionContract.F_DELETE_TASK));
    }

    @Test public void bundleValuesMayArriveAsStrings() {
        CompanionHandshake.Result r = CompanionHandshake.evaluate(true, true, hello("1", "1", PLAN_CAPS), CompanionContract.PLAN_REQUIRED);
        assertEquals(CompanionHandshake.State.READY, r.state);
        r = CompanionHandshake.evaluate(true, true, hello(1, 1, CompanionContract.CLOCK_CAPS), CompanionContract.CLOCK_REQUIRED);
        assertEquals("caps 也可以是 String[]", CompanionHandshake.State.READY, r.state);
    }

    @Test public void oldPeerWithoutHello() {
        CompanionHandshake.Result r = CompanionHandshake.evaluate(true, true, null, CompanionContract.PLAN_REQUIRED);
        assertEquals(CompanionHandshake.State.NO_LINK, r.state);
        assertTrue("同证书的旧版也算在场", r.state.present());
        assertFalse(r.state.ready());
    }

    @Test public void wrongInterfaceName() {
        Map<String, Object> h = hello(1, 1, PLAN_CAPS);
        h.put(CompanionContract.K_IFACE, "someone.else");
        assertEquals(CompanionHandshake.State.NO_LINK, CompanionHandshake.evaluate(true, true, h, CompanionContract.PLAN_REQUIRED).state);
    }

    @Test public void versionMismatchBothWays() {
        CompanionHandshake.Result r = CompanionHandshake.evaluate(true, true, hello(0, 0, PLAN_CAPS), CompanionContract.PLAN_REQUIRED);
        assertEquals("对端太旧", CompanionHandshake.State.VERSION_MISMATCH, r.state);
        r = CompanionHandshake.evaluate(true, true, hello(2, CompanionContract.VERSION + 1, PLAN_CAPS), CompanionContract.PLAN_REQUIRED);
        assertEquals("对端要求更新的助手", CompanionHandshake.State.VERSION_MISMATCH, r.state);
        assertTrue(r.state.present());
        assertFalse(r.state.ready());
        r = CompanionHandshake.evaluate(true, true, hello(2, 1, PLAN_CAPS), CompanionContract.PLAN_REQUIRED);
        assertEquals("对端更新但兼容 v1 客户端", CompanionHandshake.State.READY, r.state);
    }

    @Test public void missingCaps() {
        CompanionHandshake.Result r = CompanionHandshake.evaluate(true, true, hello(1, 1, "createTask,listTasks"), CompanionContract.PLAN_REQUIRED);
        assertEquals(CompanionHandshake.State.MISSING_CAPS, r.state);
        assertTrue(r.why.contains(CompanionContract.F_DELETE_TASK));
        assertTrue(r.state.present());
    }

    @Test public void companionPermissionNames() {
        assertEquals("com.onesay.assistant.permission.CONTROL", CompanionContract.assistantControlPermission(CompanionContract.ASSISTANT_PKG));
        assertEquals("com.onesay.assistant.debug.permission.CONTROL",
                CompanionContract.assistantControlPermission(CompanionContract.ASSISTANT_PKG + CompanionContract.DEBUG_SUFFIX));
    }

    @Test public void alarmActionMapRoundTrips() {
        for (String fn : new String[]{CompanionContract.F_CREATE_ALARM, CompanionContract.F_LIST_ALARMS, CompanionContract.F_DELETE_ALARM, CompanionContract.F_TOGGLE_ALARM}) {
            assertEquals(fn, CompanionAlarm.function(CompanionAlarm.action(fn)));
        }
        assertNull(CompanionAlarm.function("com.onesay.clock.action.NOPE"));
    }

    @Test public void assistantHelloIsOacpShaped() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(CompanionDescriptor.helloJson("com.onesay.assistant.debug", "{\"plan\":\"READY\"}"));
        assertEquals(CompanionContract.IFACE, o.getString(CompanionContract.K_IFACE));
        assertEquals("READY", o.getString("plan"));
        org.json.JSONObject d = o.getJSONObject(CompanionContract.K_DESCRIPTOR);
        assertEquals("0.3", d.getString("oacpVersion"));
        assertEquals("com.onesay.assistant.debug", d.getString("appId"));
        assertEquals(CompanionContract.F_GET_ASSISTANT_STATUS, d.getJSONArray("capabilities").getJSONObject(0).getString("id"));
    }
}
