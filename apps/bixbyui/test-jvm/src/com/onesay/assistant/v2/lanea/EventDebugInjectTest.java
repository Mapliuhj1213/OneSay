package com.onesay.assistant.v2.lanea;

import com.onesay.assistant.v2.events.EventEngine;
import com.onesay.assistant.v2.events.EventSignal;
import com.onesay.assistant.v2.events.EventStore;
import com.onesay.assistant.v2.events.EventTask;
import com.onesay.assistant.v2.jobs.JobMachine;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;
import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.kernel.FeatureKernel;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-23 事件任务 debug 注入入口（只 debug 包）：注入充电插上 / 拔下、临时改小防抖 / 每天上限、模拟服务重启；
 * 引擎层的覆盖不改任务、不进授权指纹；release 一律不可达。
 */
public class EventDebugInjectTest {

    static final long SEC = 1000L;

    JobStore.Mem jobsStore;
    JobsEngine jobs;
    EventEngine ev;
    long id;
    long t0;

    @Before public void setUp() {
        jobsStore = new JobStore.Mem();
        jobs = new JobsEngine(jobsStore, LaneAEventsTest.TZ);
        ev = new EventEngine(new EventStore.Mem(), jobs, LaneAEventsTest.TZ);
        jobs.setResolver(ev);
        EventTask t = LaneAEventsTest.charging("播报今天的待办");
        id = ev.confirmCreate(ev.prepare(t), EnumSet.of(Perm.READ_DEVICE, Perm.READ_TODO), LaneAEventsTest.at(23, 8, 0));
        t0 = LaneAEventsTest.at(23, 10, 0);
    }

    /** 一次「拔下 → 插上」；返回插上那次的结果（dry = 新 Run 直接完成）。 */
    EventEngine.Outcome replug(long now, boolean dry) {
        ev.onSignal(EventSignal.charging(false, now - SEC), now - SEC, "n");
        EventEngine.Outcome o = ev.onSignal(EventSignal.charging(true, now), now, "n");
        if (dry) for (JobStore.Run r : o.runs) ev.debugCompleteDry(r, now);
        return o;
    }

    @Test public void guardOverrideShrinksDebounceAndCapWithoutTouchingTask() {
        assertEquals(1, replug(t0, true).runs.size());
        EventEngine.Outcome o = replug(t0 + 30 * SEC, true);
        assertTrue("默认防抖 10 分钟", o.runs.isEmpty() && o.skipped.toString().contains("防抖"));

        ev.setDebugGuards(20 * SEC, 2);
        assertEquals("防抖=20s 每天上限=2", ev.debugGuards());
        assertEquals("覆盖后 30 s 已过防抖 → 第 2 次", 1, replug(t0 + 60 * SEC, true).runs.size());
        o = replug(t0 + 70 * SEC, true);
        assertTrue("20 s 内 → 防抖", o.skipped.toString().contains("防抖"));
        o = replug(t0 + 120 * SEC, true);
        assertTrue("上限 2 → 第 3 次不触发", o.runs.isEmpty() && o.skipped.toString().contains("今天次数已满"));

        EventTask stored = ev.store().get(id);
        assertEquals("任务自己的值没改", EventTask.DEFAULT_DEBOUNCE_MS, stored.debounceMs);
        assertEquals(EventTask.DEFAULT_DAILY_CAP, stored.dailyCap);
        assertNotNull("授权仍有效（覆盖不进指纹，不用重新授权）", jobs.validGrant(stored.grantKey()));

        ev.setDebugGuards(-1L, -1);
        assertEquals("无覆盖", ev.debugGuards());
        assertEquals(EventTask.DEFAULT_DEBOUNCE_MS, ev.effectiveDebounceMs(stored));
        assertEquals(EventTask.DEFAULT_DAILY_CAP, ev.effectiveDailyCap(stored));
    }

    @Test public void dryRunCompletesWithoutBlockingNext() {
        ev.setDebugGuards(0L, 50);
        EventEngine.Outcome o = replug(t0, true);
        JobStore.Run r = jobsStore.run(o.runs.get(0).id);
        assertEquals("空跑直接完成", JobMachine.State.DONE, r.state);
        assertEquals(0, ev.store().get(id).failures);
        assertEquals("不挡下一次", 1, replug(t0 + 10 * SEC, true).runs.size());
    }

    /** 不空跑时 Run 停在待执行：下一次「上一次还没结束」——这就是 dry 存在的原因。 */
    @Test public void withoutDryPendingRunBlocksNext() {
        ev.setDebugGuards(0L, 50);
        assertEquals(1, replug(t0, false).runs.size());
        EventEngine.Outcome o = replug(t0 + 10 * SEC, false);
        assertTrue(o.runs.isEmpty() && o.skipped.toString().contains("上一次还没结束"));
    }

    /** 插着电重启：粘性基线 = 插着 → 之后再来一次「插上」不算新事件；忘掉记忆后的第一次「拔下」照常算。 */
    @Test public void restartWhileChargingDoesNotMisfire() {
        replug(t0, true);
        ev.forgetStates();                                           // 模拟进程重启
        assertNull("重启后没有充电基线", ev.lastState(EventSignal.charging(true, t0)));
        ev.baseline(EventSignal.charging(true, t0 + 11 * 60 * SEC));  // 粘性广播 / 注入的基线：插着
        EventEngine.Outcome o = ev.onSignal(EventSignal.charging(true, t0 + 12 * 60 * SEC), t0 + 12 * 60 * SEC, "n");
        assertTrue("同一状态重复到来不触发", o.runs.isEmpty() && o.skipped.contains("状态没变"));
        assertEquals(Boolean.TRUE, ev.lastState(EventSignal.charging(true, 0)));
    }

    @Test public void resetCountersKeepsGrant() {
        ev.setDebugGuards(0L, 1);
        replug(t0, true);
        assertTrue(replug(t0 + 5 * SEC, true).skipped.toString().contains("今天次数已满"));
        assertEquals(1, ev.resetCounters(0));
        EventTask t = ev.store().get(id);
        assertEquals(0, t.firesToday);
        assertEquals(0L, t.lastFireAt);
        assertNotNull(jobs.validGrant(t.grantKey()));
        assertEquals("清零后又能触发", 1, replug(t0 + 10 * SEC, true).runs.size());
        assertEquals("id 不存在 → 0 条", 0, ev.resetCounters(999));
    }

    // ------------------------------------------------------------------ 只在 debug 包

    static String src(String rel) throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), rel);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    @Test public void releaseCannotReachIt() throws Exception {
        assertFalse("单测按 release 口径编译：IS_DEBUG = false", com.onesay.assistant.v2.BuildVariant.IS_DEBUG);
        String hooks = src("app/src/com/onesay/assistant/v2/events/EventHooks.java");
        int dbg = hooks.indexOf("// ================================================================== debug 上机注入");
        assertTrue(dbg > 0);
        String head = hooks.substring(0, dbg), tail = hooks.substring(dbg);
        for (String api : new String[]{"setDebugGuards(", "forgetStates(", "resetCounters("}) {
            assertFalse("正常路径不碰 " + api, head.contains(api));
        }
        // 空跑只在 fire(..., dry=true) 时发生；真实广播 / 通知走 dispatch → fire(..., false, "")，dry=true 只有 debug 段会传
        assertTrue(head.contains("fire(app, s, false, \"\");"));
        assertTrue(head.contains("if (dry) {") && head.indexOf("e.debugCompleteDry(") > head.indexOf("if (dry) {"));
        assertFalse("正常路径没有 dry=true 的调用", head.replace("boolean dry", "").matches("(?s).*fire\\([^;]*, true,.*"));
        // 每个 public static debugXxx 方法第一句就是 release 拒绝
        Matcher m = Pattern.compile("public static String (debug\\w+)\\([^)]*\\) \\{\\n\\s*(.*)\\n").matcher(tail);
        int n = 0;
        while (m.find()) {
            n++;
            assertEquals(m.group(1) + " 先查变体", "if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return RELEASE_REFUSE;", m.group(2).trim());
        }
        assertEquals("plug/unplug、baseline、guard、restart、reset、status、add", 7, n);
        // 只有 EventHooks 的 debug 段调用引擎的覆盖接口
        for (String f : new String[]{"app/src/com/onesay/assistant/v2/s3/S3Runner.java", "app/src/com/onesay/assistant/v2/events/EventConsole.java",
                "app/src/com/onesay/assistant/v2/kernel/plugins/CallbackPlugin.java", "app/src/com/onesay/assistant/v2/jobs/JobsHooks.java"}) {
            String s = src(f);
            assertFalse(f, s.contains("setDebugGuards(") || s.contains("debugCompleteDry(") || s.contains("EventHooks.debug"));
        }
        // 驱动在 test 包（release 构建剔除），经插件声明分发
        assertTrue(new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/test/EventTestDriver.java").exists());
        assertTrue(src("build_linux.sh").contains("-path \"$ROOT/app/src/com/onesay/assistant/v2/test\" -prune"));
        assertTrue(src("app/src/com/onesay/assistant/v2/test/PluginDebugDrivers.java").contains("if (\"event\".equals(extra)) { EventTestDriver.handle(ctx, intent); return true; }"));
        assertEquals("callback", FeatureKernel.get().debugExtras().get("event"));
        // 覆盖值不落库：EventTask 的 JSON / 授权规格里没有它
        String task = src("app/src/com/onesay/assistant/v2/events/EventTask.java");
        assertFalse(task.contains("Debug"));
    }

    @Test public void driverCommands() throws Exception {
        String d = src("app/src/com/onesay/assistant/v2/test/EventTestDriver.java");
        for (String c : new String[]{"\"status\"", "\"plug\"", "\"unplug\"", "\"guard\"", "\"baseline\"", "\"restart\"", "\"reset\"", "\"add\""}) {
            assertTrue("驱动缺 " + c, d.contains(c + ".equals(cmd)"));
        }
        assertTrue("dry 参数", d.contains("it.getBooleanExtra(\"dry\", false)"));
        String h = src("app/src/com/onesay/assistant/v2/events/EventHooks.java");
        String add = h.substring(h.indexOf("public static String debugAddCharging"));
        assertTrue("建任务照常过权限卡，不绕过授权", add.contains("PermissionCards.forNewEvent(") && !add.contains("confirmCreate("));
    }
}
