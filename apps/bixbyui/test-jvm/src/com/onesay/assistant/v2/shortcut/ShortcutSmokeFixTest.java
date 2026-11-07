package com.onesay.assistant.v2.shortcut;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.jobs.JobQueue;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * 2026-11-06 真机冒烟（Note 8）发现的快捷指令问题（OneSay 一侧）：
 * <ol>
 *   <li>结果通知 / 岛 / 队列的标题是内部指令「执行快捷指令#1（9106）」→ 开始只说「正在执行快捷指令」，结束标题 = 名字、正文「已执行」；</li>
 *   <li>点击 / 桌面图标运行后岛上残留「执行快捷指… +1」胶囊（结果进了灵动岛消息提醒、一直挂着）→ 这类运行的结果不进消息提醒；</li>
 *   <li>「命中」日志 trigger=time，实际是 tap / launcher → 按排队中的请求行取 trigger_info。</li>
 * </ol>
 */
public class ShortcutSmokeFixTest {

    static final String CMD = ShortcutModel.runCommand(1, "9106");

    @Test public void titlesNeverShowTheInternalCommand() {
        assertEquals("执行快捷指令#1（9106）", CMD);
        assertTrue(ShortcutModel.isRunCommand(CMD));
        assertFalse(ShortcutModel.isRunCommand("查明天天气"));
        assertEquals("9106", ShortcutModel.commandName(CMD));
        assertEquals("晚安（周末）", ShortcutModel.commandName(ShortcutModel.runCommand(3, "晚安（周末）")));
        assertEquals("", ShortcutModel.commandName("查明天天气"));
        assertEquals("正在执行快捷指令", ShortcutModel.RUNNING_TITLE);
        assertEquals(ShortcutRunner.ANNOUNCE, ShortcutModel.RUNNING_TITLE);
        assertEquals("9106", ShortcutModel.doneTitle(CMD));
        assertEquals("快捷指令", ShortcutModel.doneTitle("执行快捷指令#7（）"));
        assertEquals("9106 已执行", ShortcutModel.doneLine(CMD, true, null));
        assertEquals("9106 没完成：没网", ShortcutModel.doneLine(CMD, false, "没网"));
        assertEquals("快捷指令「9106」", ShortcutModel.displayTitle(CMD));
        assertEquals("查明天天气", ShortcutModel.displayTitle("查明天天气"));
        for (String s : new String[]{ShortcutModel.doneTitle(CMD), ShortcutModel.doneLine(CMD, true, null), ShortcutModel.displayTitle(CMD)}) {
            assertFalse(s, s.contains("#") || s.contains("执行快捷指令"));
        }
    }

    @Test public void queueShowsOnlyRunningShortcutWhileItRuns() {
        JobsEngine e = new JobsEngine(new JobStore.Mem(), TimeZone.getTimeZone("Asia/Shanghai"));
        JobQueue q = new JobQueue(e);
        JobStore.Run a = q.enqueue(CMD, 1);
        assertEquals("排队项标题不是内部指令", "快捷指令「9106」", a.title);
        assertEquals("命令原样保留（执行时按 id 找）", CMD, a.command);
        q.enqueue("查天气", 2);
        e.begin(q.next(), 3);
        assertEquals("正在执行快捷指令 · 还有 1 个排队", q.progressText());
        assertFalse(q.statusReply().contains("#"));
    }

    @Test public void triggerComesFromTheQueuedRequest() {
        assertEquals(ShortcutModel.TRIG_VOICE, ShortcutModel.effectiveTrigger(false, null));
        assertEquals("定时触发（没有请求行）", ShortcutModel.TRIG_TIME, ShortcutModel.effectiveTrigger(true, null));
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("trigger_info", "tap");
        assertEquals(ShortcutModel.TRIG_TAP, ShortcutModel.effectiveTrigger(true, req));
        req.put("trigger_info", "launcher");
        assertEquals(ShortcutModel.TRIG_LAUNCHER, ShortcutModel.effectiveTrigger(true, req));
        req.put("trigger_info", "");
        assertEquals(ShortcutModel.TRIG_TIME, ShortcutModel.effectiveTrigger(true, req));
        assertEquals("说名字时不看请求行", ShortcutModel.TRIG_VOICE, ShortcutModel.effectiveTrigger(false, req));
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    @Test public void wiring() throws Exception {
        String hooks = src("app/src/com/onesay/assistant/v2/shortcut/ShortcutHooks.java");
        assertTrue("日志 / 执行记录的 trigger 按请求行", hooks.contains("ShortcutModel.effectiveTrigger(h.byCommand, h.byCommand ? store(ctx).queued(sc.id) : null)"));
        assertFalse(hooks.contains("String trigger = h.byCommand ? ShortcutModel.TRIG_TIME : ShortcutModel.TRIG_VOICE;"));
        String run = src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        assertTrue("开始只说「正在执行快捷指令」", run.contains("shortcut ? com.onesay.assistant.v2.shortcut.ShortcutModel.RUNNING_TITLE"));
        assertTrue("结束标题 = 名字", run.contains("run.title = com.onesay.assistant.v2.shortcut.ShortcutModel.doneTitle(run.command);"));
        int end = run.indexOf("run.title = com.onesay.assistant.v2.shortcut.ShortcutModel.doneTitle(run.command);");
        int fin = run.indexOf("ob = je.finish(run, ok,");
        assertTrue("改标题在 finish（生成结果条目）之前", end > 0 && fin > end);
        assertTrue("点击 / 桌面运行的结果不进消息提醒", run.contains("if (shortcut && run.owner.startsWith(com.onesay.assistant.v2.jobs.JobQueue.OWNER_PREFIX))"));
        assertTrue(run.contains("je.delivered(ob, \"shortcut-notify\", now)"));
        assertTrue("定时快捷指令的授权提示也不出现内部指令", run.contains("run.title = com.onesay.assistant.v2.shortcut.ShortcutModel.displayTitle(f.task.command);"));
    }
}
