package com.onesay.assistant.v2.island;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ 11-04 午：进度反馈 —— 思考中 / 执行中：步骤、计时、第几步、8 s 真实状态补句、整词截断不加省略号。 */
public class ProgressFeedbackTest {

    static final ProgressFeedback.Phase T = ProgressFeedback.Phase.THINKING, W = ProgressFeedback.Phase.WORKING;

    @Test public void elapsedSecondsThenMinutes() {
        assertEquals("0 s", ProgressFeedback.elapsed(400L));
        assertEquals("12 s", ProgressFeedback.elapsed(12_900L));
        assertEquals("1:05", ProgressFeedback.elapsed(65_000L));
    }

    @Test public void stepCounterKnownAndUnknownTotal() {
        assertEquals("2/4", ProgressFeedback.stepCounter(2, 4));
        assertEquals("不知总数只显示步数", "第 3 步", ProgressFeedback.stepCounter(3, 0));
        assertEquals("只有 1 步不显示", "", ProgressFeedback.stepCounter(1, 0));
        assertEquals("2/4 · 12 s", ProgressFeedback.meta(2, 4, 12_000L));
        assertEquals("7 s", ProgressFeedback.meta(1, 0, 7_000L));
    }

    @Test public void twoPhases() {
        assertEquals("思考中", ProgressFeedback.headline(T, "读取：什么值得买"));
        assertEquals("执行中：读取 什么值得买", ProgressFeedback.headline(W, "读取：什么值得买"));
        assertEquals("执行中：设闹钟 3:53", ProgressFeedback.headline(W, "设闹钟：3:53"));
        assertEquals("执行中", ProgressFeedback.headline(W, null));
    }

    @Test public void stallLineIsRealStatusAfter8s() {
        assertNull("8 s 内不补句", ProgressFeedback.stallLine(W, "读取：什么值得买", 7_999L));
        assertEquals("还在读 什么值得买", ProgressFeedback.stallLine(W, "读取：什么值得买", 8_000L));
        assertEquals("还在搜 二手摩托车 价格", ProgressFeedback.stallLine(W, "搜索：二手摩托车 价格", 9_000L));
        assertEquals("还在等模型回复", ProgressFeedback.stallLine(T, null, 12_000L));
        assertEquals("还在读 什么值得买", ProgressFeedback.line(W, "读取：什么值得买", 20_000L));
        assertEquals("执行中：读取 什么值得买", ProgressFeedback.line(W, "读取：什么值得买", 1_000L));
        for (String s : new String[]{ProgressFeedback.stallLine(W, "读取：什么值得买", 9_000L), ProgressFeedback.stallLine(T, null, 9_000L),
                ProgressFeedback.stallLine(W, "操作：微信", 9_000L), ProgressFeedback.stallLine(W, "设定指定时间闹钟", 9_000L)}) {
            assertFalse("不许套话：" + s, s.contains("快好了") || s.contains("马上") || s.contains("稍等"));
        }
    }

    @Test public void longStepCutAtWordNoEllipsis() {
        String h = ProgressFeedback.headline(W, "搜索：二手摩托车 价格 2026 行情 铃木 UY125 本田");
        assertFalse(h.contains("…") || h.contains("..."));
        assertTrue(h.startsWith("执行中：搜索 二手摩托车"));
        assertFalse("不切断英文 / 数字串：" + h, h.matches(".*\\bUY1$|.*\\b20$"));
        assertTrue(IslandStepText.units(h) <= ProgressFeedback.MAX_UNITS);
    }

    @Test public void breathOnlyWhenThinking() {
        assertEquals(1f, ProgressFeedback.breath(W, 700L), 0.0001f);
        float lo = ProgressFeedback.breath(T, 0L), hi = ProgressFeedback.breath(T, 700L);
        assertTrue(lo < 0.5f && hi > 0.99f);
    }

    @Test public void islandModelTracksThinkingAndStepTime() {
        IslandModel m = new IslandModel();
        m.taskStarted(1000L, "帮我找二手摩托车价格", true);
        m.thinking(true);
        assertTrue(m.isThinking());
        m.step("搜索：二手摩托车 价格");
        assertFalse("有新步骤 = 执行中", m.isThinking());
        m.thinking(true);
        assertTrue(m.isThinking());
        m.thinking(false);
        assertFalse(m.isThinking());
    }

    @Test public void cutAtNeverAddsEllipsisNorSplitsWords() {
        String t = "近半年电脑价格的主线是：内存涨价把整机价格推高，7月出现短暂回落，10月又转为上涨。Galaxy Book4 Pro 价格稳定。";
        String c = IslandStepText.cutAt(t, 10, 40);
        assertFalse(c.endsWith("…"));
        assertTrue(t.startsWith(c));
        assertTrue("切在句读后：" + c, c.endsWith("推高") || c.endsWith("回落") || c.endsWith("主线是") || c.endsWith("。"));
        String e = "abc Galaxy Book4 Pro price";
        String c2 = IslandStepText.cutAt(e, 0, 9);
        assertFalse("不切断单词：" + c2, c2.endsWith("Gal"));
        assertEquals("放得下原样", t, IslandStepText.cutAt(t, 0, t.length()));
    }

    @Test public void alarmAndMailStepText() throws Exception {
        assertEquals("设闹钟：3:53", IslandStepText.forTool("clock.alarm.set", new org.json.JSONObject("{\"hour\":3,\"minute\":53}"), "设定指定时间闹钟"));
        assertEquals("读邮件", IslandStepText.forTool("mail.inbox.latest", new org.json.JSONObject("{\"count\":5}"), "读收件箱最新邮件（IMAP）"));
    }

    @Test public void wiring() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String loop = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/router/AgentLoop.java")), "UTF-8");
        assertTrue("等模型前进入思考中", loop.contains("emitThinking(cb, true);") && loop.contains("emitThinking(cb, false);"));
        String fss = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java")), "UTF-8");
        assertTrue(fss.contains("mSheetView.progressThinking(on)") && fss.contains("mSheetView.progressStep("));
        String view = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/island/IslandView.java")), "UTF-8");
        assertFalse("任务行不再用 TruncateAt.END 省略号（取件码除外）", view.replace("TextUtils.ellipsize(p.code, pPCode, bw, TextUtils.TruncateAt.END)", "").contains("TruncateAt.END"));
        String s1 = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/s1/S1Hooks.java")), "UTF-8");
        assertTrue("TTS 不阻塞执行", s1.contains("vs.beforeGui(0L)"));
    }
}
