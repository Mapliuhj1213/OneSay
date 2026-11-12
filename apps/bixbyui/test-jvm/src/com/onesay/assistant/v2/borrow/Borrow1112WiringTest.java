package com.onesay.assistant.v2.borrow;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** ★ borrow-1112：接线（源码断言）+ 用户可见文字不出现被禁用的字样 + NOTICE 署名。 */
public class Borrow1112WiringTest {

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    static String pkg() {
        return "com/onesay/assistant/v2";
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(root(), rel).toPath()), StandardCharsets.UTF_8);
    }

    static String app(String rel) throws Exception {
        return src("app/src/" + pkg() + "/" + rel);
    }

    @Test public void agentLoopRedactsAndPassesTaint() throws Exception {
        String a = app("router/AgentLoop.java");
        assertTrue(a.contains("new com.onesay.assistant.v2.privacy.RedactingLlm(".replace("com.onesay.assistant.v2", pkg().replace('/', '.'))));
        assertTrue(a.contains("RiskLabel.setTaskTaint(tainted)"));
        assertTrue(a.contains("RiskLabel.clearTaskTaint()"));
    }

    @Test public void phoneHooksWiresRedactorTaintAndTimeline() throws Exception {
        String h = app("phone/PhoneHooks.java");
        assertTrue(h.contains("PrivacySettings.REDACT.on(app)"));
        assertTrue(h.contains("RiskLabel.taskTainted()"));
        assertTrue(h.contains("TimelineNotes.post(line)"));
        assertTrue(h.contains("redactorRef[0].clear()"));
        assertFalse("脱敏只记类别个数，不记值", h.contains("Log.i(TAG, \"[PHONE] 脱敏：\" + agent.redactor)"));
    }

    @Test public void fallbackSourceAndTimelineListenerRegistered() throws Exception {
        assertTrue(app("router/L2LLMCompiler.java").contains("LlmFallback.setSource(new"));
        String fss = app("ui/FloatingSheetService.java");
        assertTrue(fss.contains("TimelineNotes.add(mTimelineNotes)"));
        assertTrue(fss.contains("TimelineNotes.remove(mTimelineNotes)"));
        assertTrue(app("net/LlmClient.java").contains("maybeFallback(jsonBody, r)"));
    }

    @Test public void todoVoiceRoutedAndRemindersNoted() throws Exception {
        String ph = app("planner/PlannerHooks.java");
        assertTrue(ph.contains("todoVoiceClaims(ctx, q)"));
        assertTrue(ph.contains("todoVoice(ctx, q)"));
        assertTrue(app("s3/S3Runner.java").contains("RecentReminds.note(f.task.todoId"));
        String link = app("planner/TodoDoneLink.java");
        assertTrue("同证书规则", link.contains("checkSignatures(c.getPackageName(), PlannerContract.PKG) != PackageManager.SIGNATURE_MATCH"));
        assertTrue("provider 归属", link.contains("PlannerContract.PKG.equals(pi.packageName)"));
    }

    @Test public void alarmViaSystemIntentSaysRequested() throws Exception {
        String a = app("apifirst/ApiFirstAndroid.java");
        assertTrue(a.contains("RiskLabel.requested(\"已设 \""));
        assertTrue(a.contains("RiskLabel.requested(\"已开 \""));
    }

    @Test public void backupKeyIsRegisteredSecret() throws Exception {
        assertTrue(app("security/SecretPrefs.java").contains("{\"bixby_llm_backup\", \"backup_key\"}"));
    }

    @Test public void userVisibleTextHasNoBannedWords() throws Exception {
        String[] files = {"phone/StepReceipt.java", "phone/RiskLabel.java", "privacy/Redactor.java", "privacy/PrivacySettings.java",
                "exec/ControlColor.java", "net/LlmFallback.java", "net/BackupModelConfig.java", "planner/TodoVoiceDone.java",
                "planner/TodoVoiceFlow.java", "planner/TodoDoneLink.java", "planner/RecentReminds.java", "timeline/TimelineNotes.java"};
        for (String f : files) {
            String s = app(f);
            for (String line : s.split("\n")) {
                if (line.trim().startsWith("package ") || line.trim().startsWith("import ") || line.contains("TAG =")) continue;
                String noPkg = line.replace("com.bixbyui", "").replace("bixby_", "").replace("bixbyplan", "");
                assertFalse(f + "：" + line, noPkg.contains("Bixby") || noPkg.contains("bixby"));
                assertFalse(f + "：" + line, line.contains("三星"));
            }
        }
        String adv = app("ui/console/AdvancedPage.java");
        int k = adv.indexOf("editBackup()");
        assertTrue(k > 0);
        String tail = adv.substring(adv.indexOf("void editBackup()"));
        tail = tail.substring(0, tail.indexOf("/** 插件列表"));
        assertFalse(tail.contains("Bixby"));
        assertFalse(tail.contains("三星"));
    }

    @Test public void noticeCreditsSources() throws Exception {
        File tp = new File(root(), "THIRD_PARTY_NOTICES.md");
        if (!tp.isFile()) tp = new File(root(), "../../THIRD_PARTY_NOTICES.md");   // 开源版在仓库根目录
        String n = new String(Files.readAllBytes(tp.toPath()), StandardCharsets.UTF_8);
        String notice = src("NOTICE");
        // 个人版 NOTICE 中文写「思路来源」，开源版英文写「idea source only」
        assertTrue(notice.contains("agent-vscreen") && (notice.contains("思路来源") || notice.contains("idea source only")) && notice.contains("android-remote-control-mcp"));
        assertEquals(notice, src("app/assets/NOTICE.txt"));
        assertTrue(n.contains("agent-vscreen"));
        assertTrue(n.contains("OpenPhoneUse"));
        assertTrue(n.contains("TaoSheng"));
        assertTrue(n.contains("android-remote-control-mcp"));
        assertTrue(n.contains("思路来源"));
    }
}
