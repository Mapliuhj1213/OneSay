package com.onesay.assistant.v2.seclogic;

import com.onesay.assistant.v2.jobs.JobMachine;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** 岛不可用 → 任务停在「等你确认」持久态（可补确认重跑），不再直接判拒中止。 */
public class SecLogicParkConfirmTest {

    @Test public void park_sets_waiting_confirm_with_reason() {
        JobStore st = new JobStore.Mem();
        JobsEngine e = new JobsEngine(st, TimeZone.getTimeZone("Asia/Shanghai"));
        JobStore.Run r = new JobStore.Run();
        r.owner = "s3:1";
        r.title = "每天发日报";
        r.state = JobMachine.State.RUNNING;
        st.addRun(r);
        e.parkForConfirm(r, "调用远程工具「conn.x.send」", 1000L);
        assertEquals(JobMachine.State.WAITING_CONFIRM, r.state);
        assertTrue(r.waitReason, r.waitReason.startsWith(JobsEngine.WAIT_NO_ISLAND));
        assertTrue(r.waitReason.contains("conn.x.send"));
    }

    @Test public void s3runner_parks_instead_of_silent_deny() throws Exception {
        String src = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/s3/S3Runner.java").toPath()), StandardCharsets.UTF_8);
        int a = src.indexOf("if (!island().ready() || !island().islandMode()) {");
        int b = src.indexOf("return AgentLoop.ConfirmVerdict.DENY;", a);
        String branch = src.substring(a, b);
        assertTrue(branch.contains("parkForConfirm(run"));
        assertTrue(branch.contains("blocked = noIslandConfirmText"));
        assertTrue("仍然绝不自动批准", src.contains("不会自动批准"));
        String eng = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/jobs/JobsEngine.java").toPath()), StandardCharsets.UTF_8);
        assertTrue("重新授权会把它放回待执行", eng.contains("waitReason.startsWith(WAIT_NO_ISLAND)") && eng.contains("resumable(r.waitReason)"));
    }
}
