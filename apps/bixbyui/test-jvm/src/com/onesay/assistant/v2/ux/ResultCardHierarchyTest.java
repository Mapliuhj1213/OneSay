package com.onesay.assistant.v2.ux;

import com.onesay.assistant.v2.ui.wake.WakeCopy;
import com.onesay.assistant.v2.util.SpeechText;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * [r2/uxcompare] 结果卡信息层级：开头的客套 / 来源铺垫不占大字首行。
 * 对标：小爱 / 小艺 / Siri 的结果卡第一行就是结论（"佛山 多云 23~28℃"），从不出现"根据检索结果"。
 */
public class ResultCardHierarchyTest {

    @Test public void stripsCourtesyAndSourceLeadIns() {
        assertEquals("已打开微信", SpeechText.stripLeadFiller("好的，已打开微信"));
        assertEquals("佛山今天多云，23~28℃。", SpeechText.stripLeadFiller("根据检索结果，佛山今天多云，23~28℃。"));
        assertEquals("比特币现价约 6.8 万美元。", SpeechText.stripLeadFiller("好的！根据最新资料显示：比特币现价约 6.8 万美元。"));
        assertEquals("明天有雨。", SpeechText.stripLeadFiller("根据搜索到的信息，明天有雨。"));
        assertEquals("可以。", SpeechText.stripLeadFiller("当然可以，可以。"));
    }

    @Test public void keepsRealContent() {
        // 没有标点跟着的"好的"是正文的一部分
        assertEquals("好的天气适合晾被子。", SpeechText.stripLeadFiller("好的天气适合晾被子。"));
        // "根据"后面不是资料来源 → 不动
        assertEquals("根据交规，黄灯要减速。", SpeechText.stripLeadFiller("根据交规，黄灯要减速。"));
        // 去完不剩东西 → 原样
        assertEquals("好的。", SpeechText.stripLeadFiller("好的。"));
        assertNull(SpeechText.stripLeadFiller(null));
        // "当然，"不算铺垫（常是转折）
        assertEquals("当然，这取决于路况。", SpeechText.stripLeadFiller("当然，这取决于路况。"));
    }

    @Test public void headlineIsTheAnswerNotTheLeadIn() {
        String[] hb = WakeCopy.splitHeadline("根据检索结果，佛山今天多云。气温 23 到 28 度，东北风 2 级。");
        assertEquals("佛山今天多云", hb[0]);
        assertEquals("气温 23 到 28 度，东北风 2 级。", hb[1]);
        assertEquals("已打开蓝牙", WakeCopy.resultLine(true, "好的，已打开蓝牙"));
        // 旧行为不变：emoji 前缀照样去
        assertEquals("敏感操作安全拦截", WakeCopy.cleanBody("⚠️ 敏感操作安全拦截"));
    }
}
