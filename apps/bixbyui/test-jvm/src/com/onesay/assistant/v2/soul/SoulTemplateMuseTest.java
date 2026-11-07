package com.onesay.assistant.v2.soul;

import org.junit.Test;

import static org.junit.Assert.*;

/** 「照搬 Muse」：默认人设 = Muse SOUL.md 忠实中文翻译 + 一行身份，结构与预算守卫。 */
public class SoulTemplateMuseTest extends SoulTestBase {

    static final String[] LINES = SoulTemplate.DEFAULT.split("\n");

    @Test public void identityFirstThenMuseOpening() {
        assertEquals("你是 OneSay，用户手机上的语音助手。", LINES[0]);
        assertEquals("你不是聊天机器人，你正在成为某个人。", LINES[1]);
        assertTrue(LINES[2].startsWith("这是你的人设，由你成长并随时修改。"));
    }

    @Test public void fourStartingTruthsInMuseOrder() {
        assertEquals(8, LINES.length);
        assertTrue(LINES[3].startsWith("- 真帮忙，不表演帮忙。"));
        assertTrue(LINES[4].startsWith("- 有主见。"));
        assertTrue(LINES[5].startsWith("- 先自己想办法再问。"));
        assertTrue(LINES[6].startsWith("- 你是别人生活里的客人。"));
        assertTrue(LINES[6].contains("谨慎") && LINES[6].contains("不说教"));
        int bullets = 0;
        for (String l : LINES) if (l.startsWith("- ")) bullets++;
        assertEquals("只有原文四条，不加自创条款", 4, bullets);
    }

    @Test public void closingLineMatchesMuse() {
        assertEquals("如果你改了这份文件，要告诉用户。这是你的灵魂，他们应该知道。", LINES[LINES.length - 1]);
    }

    @Test public void withinBudgetAndInjectedFirst() {
        assertTrue(MemoryText.len(SoulTemplate.DEFAULT) <= en.budget().soulMax);
        assertTrue(en.inject(T0, false).prefix.startsWith("【人设】\n" + SoulTemplate.IDENTITY_LINE));
    }

    @Test public void editsStillGoThroughProposeAndConfirm() {
        SoulEngine.SoulResult r = en.proposeSoul(SoulTemplate.DEFAULT + "\n- 回答尽量短。", "用户要求", false, T0);
        assertTrue(r.ok);
        assertEquals("提议未确认前不生效", SoulTemplate.DEFAULT, en.activeSoul(T0).text);
        assertTrue(en.approveSoul(r.id, T0 + 1000));
        assertTrue(en.activeSoul(T0 + 2000).text.endsWith("- 回答尽量短。"));
    }
}
