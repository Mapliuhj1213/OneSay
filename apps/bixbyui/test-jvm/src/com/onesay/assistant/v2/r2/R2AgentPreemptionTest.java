package com.onesay.assistant.v2.r2;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.router.AgentPreemption;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * r2/fixes 第 14 项：只有新请求也进 AgentLoop 时才取消旧的复合任务；简单问答不取消后台任务，
 * 后台任务也不再往卡片上写（不盖住新答案、不把确认卡弹到别的请求上）。
 */
public class R2AgentPreemptionTest {

    static String fss() throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void policy() {
        assertTrue("新请求也进 AgentLoop → 取消旧任务", AgentPreemption.newRequestCancelsAgent(true));
        assertFalse("简单问答 / L0 / 插件 → 不取消", AgentPreemption.newRequestCancelsAgent(false));
        assertTrue(AgentPreemption.agentOwnsCard(5, 5));
        assertFalse("被新请求接管后不再写卡片", AgentPreemption.agentOwnsCard(5, 6));
        assertTrue("收起面板：还占着卡片的任务一起取消（✕ 取消语义不变）", AgentPreemption.teardownCancelsAgent(5, 5));
        assertFalse("已转后台的任务收起面板不受影响", AgentPreemption.teardownCancelsAgent(5, 7));
    }

    @Test public void wiring() throws Exception {
        String s = fss();
        // 新请求入口：只作废流式，不碰复合任务
        int submit = s.indexOf("beginForegroundRequest();   // ★ LC-5");
        assertTrue(submit > 0);
        String head = s.substring(submit - 400, submit);
        assertTrue(head.contains("cancelActiveStream(true);"));
        assertFalse(head.contains("cancelActiveStream();"));
        // 分诊判定进 AgentLoop 才取消旧任务（在 runAgentLoop 之前）
        int a = s.indexOf("cancelActiveAgent(cancel, \"新请求也进 AgentLoop\");");
        assertTrue(a > 0 && a < s.indexOf("runAgentLoop(command, cancel);", a));
        assertTrue(s.contains("cancelActiveAgent(cancel, \"识屏指代进 AgentLoop\");"));
        // routeStreaming（简单问答）分支不取消复合任务
        int rs = s.indexOf("mRouter.routeStreaming(command, new UnifiedRouter.StreamHandler() {");
        assertTrue(rs > 0);
        assertEquals("cancelActiveAgent 只出现在定义 + 两个进 AgentLoop 的入口", 3, count(s, "cancelActiveAgent("));
        // 识屏是新请求
        assertTrue(s.contains("cancelActiveStream(true);   // r2/fixes 14：识屏是新请求"));
        // 后台任务不写卡片：进度、确认、收工
        assertTrue(s.contains("!cancel.isCancelled() && agentOwnsCard(agentSeq)"));
        // [logcat-1114] 这个类的日志改走 SafeLog（写 logcat 前先脱敏），只跟着改名：仍要求紧跟在 agentOwnsCard 判断之后、同一句日志
        assertTrue(s.contains("if (!agentOwnsCard(agentSeq)) {\n                                SafeLog.i(TAG, \"[CONFIRM] 复合任务已转后台"));
        int fin = s.indexOf("if (mActiveAgent == cancel) mActiveAgent = null;   // r2/fixes 14");
        assertTrue(fin > 0);
        int own = s.indexOf("if (!agentOwnsCard(agentSeq)) {", fin);
        assertTrue("收工判断在写卡片之前", own > 0 && own < s.indexOf("mSheetView.showAskReply(\"任务结果\"", fin));
        // 派发时登记
        assertTrue(s.contains("final int agentSeq = mRequestSeq;"));
        assertTrue(s.contains("mActiveAgent = cancel;\n        mActiveAgentSeq = agentSeq;"));
    }

    static int count(String s, String sub) {
        int n = 0, i = -1;
        while ((i = s.indexOf(sub, i + 1)) >= 0) n++;
        return n;
    }
}
