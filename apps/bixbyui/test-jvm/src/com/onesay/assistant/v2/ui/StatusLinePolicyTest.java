package com.onesay.assistant.v2.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** [round7] 第 2 项：本地答案上屏后状态行不再停在「云端大模型思考中...」。 */
public class StatusLinePolicyTest {

    @Test public void transientParsingTextIsReplaced() {
        assertEquals(StatusLinePolicy.DONE_LOCAL, StatusLinePolicy.afterReply(StatusLinePolicy.PARSING, StatusLinePolicy.Source.LOCAL));
        assertEquals(StatusLinePolicy.DONE_CLOUD, StatusLinePolicy.afterReply(StatusLinePolicy.PARSING, StatusLinePolicy.Source.CLOUD));
        assertEquals(StatusLinePolicy.DONE_UNKNOWN, StatusLinePolicy.afterReply(StatusLinePolicy.PARSING, StatusLinePolicy.Source.UNKNOWN));
        assertEquals(StatusLinePolicy.DONE_UNKNOWN, StatusLinePolicy.afterReply("正在执行系统动作...", StatusLinePolicy.Source.UNKNOWN));
        assertEquals(StatusLinePolicy.DONE_LOCAL, StatusLinePolicy.afterReply("OneSay · 唤醒确认", StatusLinePolicy.Source.LOCAL));
        assertEquals(StatusLinePolicy.DONE_UNKNOWN, StatusLinePolicy.afterReply(null, null));
        assertTrue("终态文案本身不能再算过渡态（否则来回改）", !StatusLinePolicy.isTransient(StatusLinePolicy.DONE_LOCAL)
                && !StatusLinePolicy.isTransient(StatusLinePolicy.DONE_CLOUD) && !StatusLinePolicy.isTransient(StatusLinePolicy.DONE_UNKNOWN));
    }

    @Test public void explicitSubtitlesAreKept() {
        assertNull(StatusLinePolicy.afterReply("确认后才会执行该操作", StatusLinePolicy.Source.LOCAL));
        assertNull(StatusLinePolicy.afterReply("本轮任务已结束", StatusLinePolicy.Source.CLOUD));
        assertNull(StatusLinePolicy.afterReply("文字模式 · 回车或点「发送」提交", StatusLinePolicy.Source.LOCAL));
    }

    @Test public void viewAndServiceAreWired() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String view = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java").toPath()), StandardCharsets.UTF_8);
        String svc = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath()), StandardCharsets.UTF_8);
        int m = view.indexOf("public void showAskReply(String question, String answer, StatusLinePolicy.Source source)");
        assertTrue(m > 0);
        assertTrue("showAskReply 必须按策略收副标题", view.indexOf("StatusLinePolicy.afterReply(", m) > m
                && view.indexOf("StatusLinePolicy.afterReply(", m) < view.indexOf("mResultCard.setVisibility(VISIBLE);", m));
        assertTrue("ask.reply 派发要带路由来源", svc.contains("inv.source == Invocation.RouteSource.L2_LLM")
                && svc.contains("StatusLinePolicy.Source.CLOUD : StatusLinePolicy.Source.LOCAL"));
    }
}
