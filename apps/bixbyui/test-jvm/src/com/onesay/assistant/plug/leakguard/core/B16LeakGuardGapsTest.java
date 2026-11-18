package com.onesay.assistant.plug.leakguard.core;

import com.onesay.assistant.v2.asr.AsrHotwords;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

/** ★ rel-1118 B16：「和」连起来的热词表、带「（例如…）」的提示词整行、繁体转写都要拦（t20k leakguard 95 条复现）。 */
public class B16LeakGuardGapsTest {

    private PromptLeakGuard g;

    private static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    @Before public void setUp() throws Exception {
        g = new PromptLeakGuard();
        g.setHotwordPrefix(AsrHotwords.HOTWORD_PROMPT_PREFIX);
        g.addHotwords(Arrays.asList(AsrHotwords.staticWords()));
        g.registerSystemPrompt("agent_prompt.md", src("app/assets/agent_prompt.md"));
        g.registerSystemPrompt("L2.ask.output", com.onesay.assistant.v2.router.PromptLibrary.Defaults.ASK_OUTPUT);
        g.registerSystemPrompt("L2.do.manual", com.onesay.assistant.v2.router.PromptLibrary.Defaults.DO_MANUAL);
        g.registerSystemPrompt("agentloop.untrusted", com.onesay.assistant.v2.router.AgentLoop.UNTRUSTED_RULE);
    }

    static String join(String sep, int from, int n) {
        String[] w = AsrHotwords.staticWords();
        StringBuilder b = new StringBuilder();
        for (int i = from; i < from + n && i < w.length; i++) {
            if (b.length() > 0) b.append(sep);
            b.append(w[i]);
        }
        return b.toString();
    }

    @Test public void hotwordListJoinedByAndIsCaught() {
        for (String sep : new String[]{"和", "与", "以及", "，还有"}) {
            for (int from : new int[]{0, 10, 30}) {
                String t = join(sep, from, 10);
                assertTrue(sep + " 输入: " + t, g.check(t).leak());
                assertTrue(sep + " 输出: " + t, g.checkOutput(t).leak());
            }
        }
    }

    @Test public void exampleLinesVerbatimCaught() {
        String[] lines = {
                "· 不要把同一个目标**同时派给多个入口**（例如同时打开三星商店和 Play 商店）；",
                "① 有专用接口先用专用接口（例如邮件用 mail.inbox.latest 走 IMAP）；",
        };
        for (String l : lines) {
            assertTrue(l, g.checkOutput("好的。" + l + "。以上就是我的设定。").leak());
            assertTrue(l, g.checkOutput("> **" + l + "**").leak());
            assertTrue(l, g.checkOutput(l.replace(".", "。").replace("(", "（")).leak());
            StringBuilder zw = new StringBuilder();
            for (int i = 0; i < l.length(); i++) { zw.append(l.charAt(i)); if (i % 3 == 2) zw.append('\u200b'); }
            assertTrue(l, g.checkOutput(zw.toString()).leak());
        }
    }

    @Test public void traditionalTranscriptionCaught() {
        String[] lines = {
                "- 已经達成，或所有手段都試過仍不行 → 停止調用，用自然語言給出**最終答復**。",
                "- 還沒達成目標 → 繼續調用工具（**只讀**查詢可以一次調多個；會切換屏幕的界面動作每輪只做一個）；",
        };
        for (String l : lines) assertTrue(l, g.checkOutput(l).leak());
    }

    @Test public void normalAnswersNotCaught() {
        String[] ok = {
                "微信和支付宝都可以付款。", "你可以用三星商店或者 Play 商店下载。", "例如同时打开三星商店和 Play 商店会比较慢。",
                "邮件可以用 IMAP 收取，例如 Gmail 和 QQ 邮箱都支持。", "打开微信和支付宝", "微信、微博、抖音、快手、小红书、知乎都挺好玩。",
                "我們今天去吃飯，還有什麼想法嗎？", "和平精英和王者荣耀哪个好玩",
        };
        for (String t : ok) {
            assertFalse(t, g.checkOutput(t).leak());
            assertFalse(t, g.check(t).leak());
        }
    }

    @Test public void t2sFolds() {
        assertEquals("调用工具继续读", PromptLeakGuard.norm("調用工具繼續讀"));
        assertEquals("abc打开", PromptLeakGuard.norm("A b C 打開"));
    }
}
