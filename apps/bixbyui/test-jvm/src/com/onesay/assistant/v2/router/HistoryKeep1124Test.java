package com.onesay.assistant.v2.router;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ fix/chat-vision-1124 第 4 条：历史对话被清。以前到期只按「上次问答」算 5 分钟，历史页每次打开都读快照（读时清），
 * 所以看一会儿再打开就空了。现在：面板开着不到期；离屏后从「上次问答 / 用户还在用」和「面板离屏」较晚者起算；
 * 打开历史页、回到首页算「还在用」。是否持久保存的规则不变（还是只在内存里）。
 */
public class HistoryKeep1124Test {

    static final class Clk implements ConversationMemory.Clock {
        long t = 1_000_000L;
        @Override public long now() { return t; }
    }

    static final long KEEP = ConversationMemory.DEFAULT_KEEP_MS;

    @Test public void reopeningHistoryKeepsTurnsAndChatResumes() {
        Clk c = new Clk();
        ConversationMemory m = ConversationMemory.newForTest(c);
        m.onPanelShown();
        m.record("北京明天天气怎么样", "晴，最高 8 度", false);
        c.t += 30_000;
        m.onPanelClosed();                       // 收起对话窗，去看历史
        for (int i = 0; i < 4; i++) {            // 同一次使用里反复打开历史页，每次隔 4 分钟（累计远超 5 分钟）
            c.t += 4 * 60_000L;
            assertTrue("第 " + (i + 1) + " 次打开还在", m.touch());
            List<ConversationMemory.Turn> s = m.snapshot();
            assertEquals("第 " + (i + 1) + " 次打开", 1, s.size());
        }
        c.t += 4 * 60_000L;
        m.onPanelShown();                        // 点「接着聊」回到对话窗
        assertEquals(1, m.size());
        assertFalse("接得上上一轮", m.priorPairs().isEmpty());
        m.record("那后天呢", "多云", false);
        assertEquals(2, m.size());
    }

    @Test public void panelOpenNeverExpiresWhileIdle() {
        Clk c = new Clk();
        ConversationMemory m = ConversationMemory.newForTest(c);
        m.onPanelShown();
        m.record("帮我记一下车位 B2-117", "记下了", false);
        c.t += 3 * KEEP;
        assertFalse(m.expireIfIdle());
        assertTrue(m.hasContext());
        assertEquals(1, m.snapshot().size());
    }

    @Test public void expiryCountsFromLaterOfLastTurnAndClose() {
        Clk c = new Clk();
        ConversationMemory m = ConversationMemory.newForTest(c);
        m.onPanelShown();
        m.record("a", "b", false);
        c.t += 10 * 60_000L;                     // 面板开着放了 10 分钟
        m.onPanelClosed();
        c.t += KEEP - 1000;
        assertTrue("离屏还没满 5 分钟", m.hasContext());
        c.t += 2000;
        assertFalse("离屏满 5 分钟才清", m.hasContext());
        assertTrue(m.isEmpty());
    }

    @Test public void touchDoesNotReviveExpired() {
        Clk c = new Clk();
        ConversationMemory m = ConversationMemory.newForTest(c);
        m.record("a", "b", false);
        m.onPanelClosed();
        c.t += KEEP + 1;
        assertFalse("到期的不复活", m.touch());
        assertTrue(m.isEmpty());
        assertTrue(m.snapshot().isEmpty());
    }

    @Test public void touchWithoutReuseStillExpiresLater() {
        Clk c = new Clk();
        ConversationMemory m = ConversationMemory.newForTest(c);
        m.record("a", "b", false);
        m.onPanelClosed();
        c.t += 4 * 60_000L;
        assertTrue(m.touch());
        c.t += KEEP;
        assertTrue("最后一次还在用之后满 5 分钟照样结束", m.expireIfIdle());
    }

    @Test public void historyPageAndHomeTouchBeforeRead() throws Exception {
        String h = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/ui/console/HistoryPage.java").toPath()), StandardCharsets.UTF_8);
        int t = h.indexOf("m.touch();");
        int s = h.indexOf("m.snapshot()");
        assertTrue("历史页先续期再读", t > 0 && s > t);
        String con = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/ui/console/Console.java").toPath()), StandardCharsets.UTF_8);
        int r = con.indexOf("public void onResume()");
        int end = con.indexOf("public boolean back()", r);
        assertTrue(con.substring(r, end).contains("ConversationMemory.getInstance().touch()"));
        // 是否持久保存的规则不变：页脚还说以前的对话不存在手机上
        assertTrue(h.contains("以前的对话不会存在手机上"));
    }
}
