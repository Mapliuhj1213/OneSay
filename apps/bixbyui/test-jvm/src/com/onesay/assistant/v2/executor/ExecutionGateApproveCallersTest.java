package com.onesay.assistant.v2.executor;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * r2/fixes 第 10 项：{@link ExecutionGate#approve}（= 代表用户点了确认）只允许白名单里的生产文件调用，
 * 另有只进 debug 包的 test 包。新增调用方必须先改 {@link ExecutionGate#APPROVE_CALLERS} 并说明理由。
 * 同时守住 MailConfirmActivity 的防点击劫持。
 */
public class ExecutionGateApproveCallersTest {

    static final Pattern CALL = Pattern.compile("\\bapprove\\s*\\(");

    static void walk(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) walk(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    @Test public void onlyWhitelistedFilesApproveTokens() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        File base = new File(root, "app/src/com/onesay/assistant/v2");
        List<File> all = new ArrayList<File>();
        walk(base, all);
        assertTrue(all.size() > 100);
        List<String> allowed = Arrays.asList(ExecutionGate.APPROVE_CALLERS);
        List<String> offenders = new ArrayList<String>();
        int seen = 0;
        for (File f : all) {
            String rel = base.toURI().relativize(f.toURI()).getPath();
            if (rel.startsWith("test/")) continue;   // debug 包专用（release 不编译 test 包）
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            boolean calls = s.contains("ExecutionGate.approve(") || s.contains("ExecutionGate\n") && CALL.matcher(s).find();
            if (rel.startsWith("executor/") && !rel.equals("executor/ExecutionGate.java") && CALL.matcher(s).find()
                    && s.contains("approve(t)")) calls = true;   // 同包内可能不写类名
            if (!calls) continue;
            seen++;
            if (!allowed.contains(rel)) offenders.add(rel);
        }
        assertTrue("至少应看到 PendingConfirm / AgentLoop / LaneBMailPorts", seen >= 3);
        assertTrue("以下文件调用了 ExecutionGate.approve，不在白名单：" + offenders, offenders.isEmpty());
    }

    @Test public void mailConfirmCardFiltersObscuredTouches() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String s = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/ui/MailConfirmActivity.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("send.setFilterTouchesWhenObscured(true);"));
        assertTrue(s.contains("sv.setFilterTouchesWhenObscured(true);"));
    }
}
