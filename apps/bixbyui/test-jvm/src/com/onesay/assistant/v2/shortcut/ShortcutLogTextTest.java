package com.onesay.assistant.v2.shortcut;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.jobs.JobMachine;
import com.onesay.assistant.v2.jobs.JobStore;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 任务队列 / S3 日志行不再写内部标签「执行快捷指令#id（名字）」，用快捷指令名字。 */
public class ShortcutLogTextTest {

    @Test public void replacesInternalTagWithName() {
        assertEquals("q:快捷指令「加待办」", ShortcutModel.logText("q:" + ShortcutModel.runCommand(2, "加待办")));
        assertEquals("快捷指令「sc test1」", ShortcutModel.logText(ShortcutModel.runCommand(12, " sc test1 ")));
        assertEquals("快捷指令「快捷指令」", ShortcutModel.logText(ShortcutModel.runCommand(3, "")));
        assertEquals("关灯", ShortcutModel.logText("关灯"));
        assertEquals("", ShortcutModel.logText(null));
        assertFalse(ShortcutModel.logText("执行快捷指令#5").contains("#"));
    }

    @Test public void queueRunToStringUsesName() {
        JobStore.Run r = new JobStore.Run();
        r.id = 1;
        r.owner = "q:" + ShortcutModel.runCommand(2, "加待办");
        r.state = JobMachine.State.PENDING;
        r.maxAttempts = 1;
        String s = r.toString();
        assertTrue(s, s.startsWith("run#1[q:快捷指令「加待办」 PENDING a=0/1"));
        assertFalse(s, s.contains("执行快捷指令#"));
    }

    @Test public void s3AgentLogWired() throws Exception {
        String s = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/s3/S3Runner.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("\"[S3-AGENT] 开始 \" + id + \"：\" + com.onesay.assistant.v2.shortcut.ShortcutModel.logText(run.command)"));
    }
}
