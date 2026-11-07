package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** [R13] 唤醒卡片文案：调试味文案不上卡片、转写去壳、首行拆分。 */
public class WakeCopyTest {

    @Test public void debugCopyNeverReachesCard() {
        for (String s : new String[] {"OneSay · 唤醒确认", "随时对我说话，或点击下方文字输入...", "我在听，请讲...", "正在调度特权通道...",
                "这是一条复合任务，正在逐步执行…", "云端大模型思考中…", "本地直答 · 未调用云端", "✓ 物理硬件状态回读校验通过 (OK)",
                "⚠ 状态码: TIMEOUT (超时)", "共 3 步", "已跳转界面 · 面板即将让位", "云端识别中…", "", "  ", null}) {
            assertTrue(String.valueOf(s), WakeCopy.isDebugCopy(s));
            assertNull(WakeCopy.transcript(s));
        }
        assertFalse(WakeCopy.isDebugCopy("明天上午有什么安排"));
    }

    @Test public void transcriptUnwrap() {
        assertEquals("明天上午有什么安排", WakeCopy.transcript("你说：“明天上午有什么安排”"));
        assertEquals("打开蓝牙", WakeCopy.transcript("“打开蓝牙”"));
        assertEquals("现在几点", WakeCopy.transcript("问：“现在几点”"));
        assertEquals("读取收件箱", WakeCopy.transcript("正在：读取收件箱"));
        assertEquals("帮我看看", WakeCopy.transcript("  帮我看看 "));
    }

    @Test public void statusWordsOnlyFour() {
        assertEquals("正在听", WakeCopy.statusWord(WakeStage.Stage.LISTENING));
        assertEquals("正在想…", WakeCopy.statusWord(WakeStage.Stage.THINKING));
        assertEquals("没听清", WakeCopy.statusWord(WakeStage.Stage.ERROR));
        assertNull(WakeCopy.statusWord(WakeStage.Stage.RESULT));
    }

    @Test public void headlineSplit() {
        assertArrayEquals(new String[] {"已打开蓝牙", null}, WakeCopy.splitHeadline("已打开蓝牙"));
        assertArrayEquals(new String[] {"明天上午有两件事", "09:30 组会\n11:00 取快递"},
                WakeCopy.splitHeadline("明天上午有两件事\n09:30 组会\n11:00 取快递"));
        assertArrayEquals("第一句做首行（去句号）", new String[] {"现在是下午三点", "距离你的会议还有一小时，记得带电脑。"},
                WakeCopy.splitHeadline("现在是下午三点。距离你的会议还有一小时，记得带电脑。"));
        assertArrayEquals("问号保留语气", new String[] {"要现在出发吗？", "路上大约 25 分钟。"},
                WakeCopy.splitHeadline("要现在出发吗？路上大约 25 分钟。"));
        String longPara = "比特币现价约 86,258 美元，24 小时内上涨约 3.28%（Yahoo 财经数据），TradingView 显示为 86,282 美元左右，波动不大";
        assertNull("长段落没有首行", WakeCopy.splitHeadline(longPara)[0]);
        assertEquals(longPara, WakeCopy.splitHeadline(longPara)[1]);
        assertArrayEquals(new String[] {null, null}, WakeCopy.splitHeadline("  "));
    }

    @Test public void cleanBodyAndPseudoQuestions() {
        assertEquals("敏感操作安全拦截", WakeCopy.cleanBody("⚠️ 敏感操作安全拦截"));
        assertEquals("去填写邮箱授权码", WakeCopy.cleanBody("\uD83D\uDCE7 去填写邮箱授权码"));
        assertTrue(WakeCopy.isPseudoQuestion("任务结果"));
        assertTrue(WakeCopy.isPseudoQuestion("多步任务"));
        assertTrue(WakeCopy.isPseudoQuestion("屏幕内容识别"));
        assertFalse(WakeCopy.isPseudoQuestion("今天天气怎么样"));
        assertEquals("已完成", WakeCopy.resultLine(true, ""));
        assertEquals("没能完成", WakeCopy.resultLine(false, null));
        assertFalse("失败也不暴露状态码", WakeCopy.resultLine(false, "蓝牙打开失败").contains("状态码"));
    }

    @Test public void confirmCopyIsHuman() {
        assertEquals("这一步需要你确认", WakeCopy.confirmTitle(true));
        String red = WakeCopy.confirmBody(true, "清空记忆库", null, null);
        assertTrue(red.startsWith("清空记忆库"));
        assertFalse(red.contains("高危指令") || red.contains("⚠"));
        String y = WakeCopy.confirmBody(false, "发短信", "发给 妈妈（138****0000）", "确认后，我会继续完成剩余的步骤。");
        assertTrue("通信对象照写", y.contains("妈妈"));
        assertTrue(y.contains("继续完成剩余"));
    }
}
