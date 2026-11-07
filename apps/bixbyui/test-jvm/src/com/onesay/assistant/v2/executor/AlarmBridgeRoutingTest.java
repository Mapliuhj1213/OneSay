package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 闹钟路由：OneSay 时钟可用 → 时钟接口；没装 / 签名不同 / 版本太旧 → 系统 AlarmClock；系统也没有 App 能接 → 明确失败，不假装成功。
 */
public class AlarmBridgeRoutingTest {

    static Capability cap(String sign) {
        return new Capability.Builder(sign, sign + " 摘要").domain("test").category("clock").sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false).executor(AlarmBridge.EXECUTOR).schema(new ParamsSchema()).build();
    }

    /** 假通道：时钟维护一张内存闹钟表，按时钟 core/AlarmApi 的协议回话；系统 Intent 只记录。 */
    static final class FakeLink implements AlarmBridge.Link {
        AlarmBridge.ClockState state;
        AlarmBridge.SystemOutcome sys = AlarmBridge.SystemOutcome.STARTED;
        boolean clockSilent;          // 超时（null）
        boolean clockNoReceiver;      // 时钟太旧：RC_NONE
        final List<AlarmBridge.ClockCmd> calls = new ArrayList<AlarmBridge.ClockCmd>();
        final List<AlarmBridge.SystemAlarmSpec> sysCalls = new ArrayList<AlarmBridge.SystemAlarmSpec>();
        final List<int[]> alarms = new ArrayList<int[]>();   // {id,h,m,d,on}

        FakeLink(AlarmBridge.ClockState s) { state = s; }

        @Override public AlarmBridge.ClockState clockState() { return state; }

        @Override public AlarmBridge.ClockReply callClock(AlarmBridge.ClockCmd c) {
            calls.add(c);
            if (clockSilent) return null;
            if (clockNoReceiver) return new AlarmBridge.ClockReply(AlarmBridge.RC_NONE, null);
            int last = -1, code = AlarmBridge.RC_OK;
            if (c.action.endsWith("ALARM_SET")) {
                last = alarms.size() + 1;
                alarms.add(new int[]{last, c.hour, c.minute, c.days, 1});
            } else if (c.action.endsWith("ALARM_TOGGLE") || c.action.endsWith("ALARM_CANCEL")) {
                code = AlarmBridge.RC_NOT_FOUND;
                for (int i = 0; i < alarms.size(); i++) {
                    if (alarms.get(i)[0] != c.id) continue;
                    if (c.action.endsWith("ALARM_CANCEL")) alarms.remove(i); else alarms.get(i)[4] = c.on ? 1 : 0;
                    code = AlarmBridge.RC_OK; last = c.id; break;
                }
            }
            try {
                JSONArray a = new JSONArray();
                for (int[] x : alarms) {
                    a.put(new JSONObject().put("id", x[0]).put("h", x[1]).put("m", x[2]).put("d", x[3]).put("on", x[4] == 1)
                            .put("r", "smart").put("next", x[4] == 1 ? AlarmBridge.nextAt(System.currentTimeMillis(), x[1], x[2], x[3]) : 0L));
                }
                return new AlarmBridge.ClockReply(code, new JSONObject().put("ts", System.currentTimeMillis()).put("count", a.length())
                        .put("lastId", last).put("alarms", a));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public AlarmBridge.SystemOutcome startSystem(AlarmBridge.SystemAlarmSpec spec) {
            sysCalls.add(spec);
            return sys;
        }
    }

    static JSONObject args(String json) throws Exception { return new JSONObject(json); }

    @Test public void routeIsPure() {
        assertEquals(AlarmBridge.Route.CLOCK, AlarmBridge.route(AlarmBridge.ClockState.READY));
        assertEquals(AlarmBridge.Route.SYSTEM, AlarmBridge.route(AlarmBridge.ClockState.MISSING));
        assertEquals("签名不同的同名包不信任", AlarmBridge.Route.SYSTEM, AlarmBridge.route(AlarmBridge.ClockState.UNTRUSTED));
    }

    @Test public void clockInstalledGoesToClock() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.READY);
        ExecutionResult r = AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{\"hour\":7,\"minute\":0,\"days\":62}"), l, null);
        assertTrue(r.userMessage, r.ok);
        assertTrue(r.verified);
        assertEquals(1, l.calls.size());
        assertEquals("com.onesay.clock.action.ALARM_SET", l.calls.get(0).action);
        assertEquals(7, l.calls.get(0).hour);
        assertEquals(62, l.calls.get(0).days);
        assertTrue("不碰系统时钟", l.sysCalls.isEmpty());
        assertTrue(r.userMessage, r.userMessage.startsWith("已设定工作日的闹钟") && r.userMessage.contains("OneSay 时钟"));
        assertEquals("卡片用时钟回来的闹钟表", 1, new JSONObject(r.detail).getInt("count"));
    }

    @Test public void clockMissingFallsBackToSystemIntent() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.MISSING);
        ExecutionResult r = AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{\"hour\":6,\"minute\":45,\"days\":65}"), l, null);
        assertTrue(r.userMessage, r.ok);
        assertFalse("系统时钟无法回读核对", r.verified);
        assertTrue(l.calls.isEmpty());
        assertEquals(1, l.sysCalls.size());
        AlarmBridge.SystemAlarmSpec s = l.sysCalls.get(0);
        assertTrue(s.set);
        assertEquals(6, s.hour);
        assertEquals(45, s.minutes);
        assertEquals("OneSay", s.message);
        assertTrue("时间明确 → SKIP_UI", s.skipUi);
        assertEquals("周末 → 周六(7) 周日(1)", Arrays.asList(7, 1), s.days);
        assertTrue(r.userMessage, r.userMessage.startsWith("已交给系统时钟设定周末的闹钟"));
        assertTrue("没有闹钟表 → 不画卡片", r.detail == null || r.detail.isEmpty());
    }

    @Test public void untrustedOrOldClockAlsoFallsBack() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.UNTRUSTED);
        assertTrue(AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{\"hour\":7}"), l, null).ok);
        assertTrue("签名不同不发给它", l.calls.isEmpty());
        assertEquals(1, l.sysCalls.size());

        FakeLink old = new FakeLink(AlarmBridge.ClockState.READY);
        old.clockNoReceiver = true;
        ExecutionResult r = AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{\"hour\":7}"), old, null);
        assertTrue(r.userMessage, r.ok);
        assertEquals("时钟没有接口 → 系统时钟", 1, old.sysCalls.size());
    }

    @Test public void noResolverIsAClearFailure() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.MISSING);
        l.sys = AlarmBridge.SystemOutcome.NO_HANDLER;
        ExecutionResult r = AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{\"hour\":7,\"minute\":30}"), l, null);
        assertFalse("没有 App 能接 → 不能说成功", r.ok);
        assertEquals(ErrorCode.EXEC_FAILED, r.code);
        assertTrue(r.userMessage, r.userMessage.contains("没有设置成功") && r.userMessage.contains("OneSay 时钟"));

        l.sys = AlarmBridge.SystemOutcome.FAILED;
        ExecutionResult r2 = AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{\"hour\":7}"), l, null);
        assertFalse(r2.ok);
        assertTrue(r2.userMessage, r2.userMessage.contains("没有设置成功"));
    }

    @Test public void clockTimeoutDoesNotDoubleSet() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.READY);
        l.clockSilent = true;
        ExecutionResult r = AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{\"hour\":7}"), l, null);
        assertFalse(r.ok);
        assertTrue("超时可能已设上，不再交给系统重复设", l.sysCalls.isEmpty());
        assertTrue(r.userMessage, r.userMessage.contains("OneSay 时钟没有响应"));
    }

    @Test public void missingHourAsksInsteadOfDefaulting() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.MISSING);
        ExecutionResult r = AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{}"), l, null);
        assertEquals(ErrorCode.NEED_PARAM, r.code);
        assertTrue(l.sysCalls.isEmpty() && l.calls.isEmpty());
    }

    @Test public void cancelAndToggleThroughClock() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.READY);
        AlarmBridge.execute(cap(AlarmBridge.SIGN_SET), args("{\"hour\":8,\"minute\":0}"), l, null);
        ExecutionResult off = AlarmBridge.execute(cap(AlarmBridge.SIGN_TOGGLE), args("{\"hour\":8,\"on\":false}"), l, null);
        assertTrue(off.userMessage, off.ok && off.userMessage.startsWith("已关闭"));
        assertEquals(0, l.alarms.get(0)[4]);
        ExecutionResult del = AlarmBridge.execute(cap(AlarmBridge.SIGN_CANCEL), args("{\"hour\":8,\"minute\":0}"), l, null);
        assertTrue(del.userMessage, del.ok && del.userMessage.startsWith("已删除"));
        assertTrue(l.alarms.isEmpty());
        assertEquals("com.onesay.clock.action.ALARM_CANCEL", l.calls.get(l.calls.size() - 1).action);
    }

    @Test public void cancelWithoutClockSaysSoAndChangesNothing() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.MISSING);
        for (String sign : new String[]{AlarmBridge.SIGN_CANCEL, AlarmBridge.SIGN_TOGGLE, AlarmBridge.SIGN_CANCEL_ALL, AlarmBridge.SIGN_TOGGLE_ALL}) {
            ExecutionResult r = AlarmBridge.execute(cap(sign), args("{\"hour\":8}"), l, null);
            assertFalse(sign, r.ok);
            assertTrue(r.userMessage, r.userMessage.contains("没有安装 OneSay 时钟"));
        }
        assertTrue(l.calls.isEmpty() && l.sysCalls.isEmpty());
    }

    @Test public void queryWithoutClockOpensSystemList() throws Exception {
        FakeLink l = new FakeLink(AlarmBridge.ClockState.MISSING);
        ExecutionResult r = AlarmBridge.execute(cap(AlarmBridge.SIGN_QUERY), args("{}"), l, null);
        assertTrue(r.userMessage, r.ok);
        assertFalse(l.sysCalls.get(0).set);
        l.sys = AlarmBridge.SystemOutcome.NO_HANDLER;
        assertFalse(AlarmBridge.execute(cap(AlarmBridge.SIGN_QUERY), args("{}"), l, null).ok);
    }

    @Test public void calendarDaysMapping() {
        assertTrue(AlarmBridge.calendarDays(0).isEmpty());
        assertEquals(Arrays.asList(2, 3, 4, 5, 6, 7, 1), AlarmBridge.calendarDays(0x7F));
        assertEquals(Arrays.asList(2, 4, 6), AlarmBridge.calendarDays(0x2A));
    }

    @Test public void noLegacyPackageAnywhereInBridge() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        for (String f : new String[]{"AlarmBridge.java", "AndroidAlarmLink.java"}) {
            String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/executor/" + f)), "UTF-8");
            assertFalse(f, src.contains("morning" + "brief"));
        }
        String mf = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/AndroidManifest.xml")), "UTF-8");
        assertTrue(mf.contains("com.android.alarm.permission.SET_ALARM"));
        assertTrue(mf.contains("<uses-permission android:name=\"com.onesay.clock.permission.ALARM_CONTROL\" />"));
    }
}
