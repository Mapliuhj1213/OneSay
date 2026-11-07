package com.onesay.assistant.v2.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;

import org.junit.Test;

/** [s3] 句式解析：五句规格例句 + 不接管的句子（相对时间 / 闹钟 / 没有钟点 / 陈述）+ 待办。 */
public class S3ParserTest {

    static final TimeZone SH = S3ScheduleTest.SH;
    static final long NOW = S3ScheduleTest.at(10, 9, 10, 0);   // 周五 10:00

    static S3Parser.Parsed p(String q) {
        return S3Parser.parse(q, NOW, SH);
    }

    @Test public void specExamples() {
        S3Parser.Parsed a = p("每天早上8点读一下QQ邮箱未读");
        assertEquals(S3Parser.Type.CREATE, a.type);
        assertEquals(S3Parser.KIND_AGENT, a.kind);
        assertEquals(S3Schedule.DAILY, a.repeat);
        assertEquals(8, a.hour);
        assertEquals("读一下QQ邮箱未读", a.command);

        S3Parser.Parsed b = p("每周一9点提醒我交周报");
        assertEquals(S3Parser.KIND_REMINDER, b.kind);
        assertEquals(S3Schedule.WEEKLY, b.repeat);
        assertEquals(1, b.weekMask);
        assertEquals("交周报", b.text);

        S3Parser.Parsed c = p("明天下午3点提醒我取快递");
        assertEquals(S3Schedule.ONCE, c.repeat);
        assertEquals(S3ScheduleTest.at(10, 10, 15, 0), c.onceAt);
        assertEquals("取快递", c.text);

        assertEquals(S3Parser.Type.LIST, p("我有哪些定时任务").type);
        S3Parser.Parsed e = p("取消读邮件那个");
        assertEquals(S3Parser.Type.CANCEL, e.type);
        assertEquals("读邮件", e.text);
    }

    @Test public void moreTimeShapes() {
        S3Parser.Parsed a = p("工作日早上9点半提醒我打卡");
        assertEquals(S3Schedule.WORKDAY, a.repeat);
        assertEquals(9, a.hour);
        assertEquals(30, a.minute);
        S3Parser.Parsed b = p("每周一三五晚上8点提醒我跑步");
        assertEquals(1 | 4 | 16, b.weekMask);
        assertEquals(20, b.hour);
        S3Parser.Parsed c = p("今晚8点提醒我倒垃圾");
        assertEquals(S3ScheduleTest.at(10, 9, 20, 0), c.onceAt);
        S3Parser.Parsed d = p("下周一上午10点提醒我开会");
        assertEquals(S3ScheduleTest.at(10, 12, 10, 0), d.onceAt);
        S3Parser.Parsed e = p("8点提醒我吃药");   // 10:00 已过 8 点、没说上下午 → 今晚 20:00
        assertEquals(S3ScheduleTest.at(10, 9, 20, 0), e.onceAt);
        S3Parser.Parsed f = p("10月20号下午2点提醒我交材料");
        assertEquals(S3ScheduleTest.at(10, 20, 14, 0), f.onceAt);
        S3Parser.Parsed g = p("明天早上8点帮我读一下邮件");
        assertEquals(S3Parser.KIND_AGENT, g.kind);
        assertEquals("读一下邮件", g.command);
        S3Parser.Parsed h = p("每个周末上午十点提醒我浇花");
        assertEquals((1 << 5) | (1 << 6), h.weekMask);
        assertEquals(10, h.hour);
    }

    @Test public void notTakenOver() {
        assertNull("相对时间走原闹钟", p("40分钟后提醒我喝水"));
        assertNull("闹钟", p("每天7点叫醒我"));
        assertNull("没有钟点", p("提醒我明天交报告"));
        assertNull("陈述", p("明天下午3点开会"));
        assertNull("今天 8 点已过：不猜", p("今天早上8点提醒我吃药"));
        assertNull(p("现在几点"));
        assertNull(p("记一下 车停在B2-17"));
        assertNull(p("删掉停车那条"));
        assertNull("问句", p("明天下午3点会下雨吗"));
    }

    @Test public void todos() {
        S3Parser.Parsed a = p("记个待办 买牛奶");
        assertEquals(S3Parser.Type.TODO_ADD, a.type);
        assertEquals("买牛奶", a.text);
        assertEquals(0L, a.dueAt);
        S3Parser.Parsed b = p("待办：周五前交报告");
        assertEquals("交报告", b.text);
        assertTrue("周五 = 今天，截止 23:59", b.dueAt == S3ScheduleTest.at(10, 9, 23, 59));
        assertEquals("今天 9:00 已过 → 不建提醒", 0L, b.remindAt);
        S3Parser.Parsed c = p("记个待办 明天下午3点前交材料");
        assertEquals(S3ScheduleTest.at(10, 10, 15, 0), c.dueAt);
        assertEquals("有钟点：提前 30 分钟", S3ScheduleTest.at(10, 10, 14, 30), c.remindAt);
        assertEquals(S3Parser.Type.TODO_LIST, p("我的待办").type);
        assertEquals(S3Parser.Type.TODO_LIST, p("我还有哪些待办").type);
        S3Parser.Parsed d = p("买牛奶做完了");
        assertEquals(S3Parser.Type.TODO_DONE, d.type);
        assertEquals("买牛奶", d.text);
        assertTrue(S3Parser.explicitTodoDone("待办买牛奶做完了"));
        assertTrue(!S3Parser.explicitTodoDone("买牛奶做完了"));
        assertNull("问句不是勾销", p("作业做完了吗"));
        assertEquals(S3Parser.Type.TODO_ADD, p("把买牛奶加到待办").type);
    }

    @Test public void cnNumbers() {
        assertEquals(8, S3Parser.cn("八"));
        assertEquals(12, S3Parser.cn("十二"));
        assertEquals(20, S3Parser.cn("二十"));
        assertEquals(2, S3Parser.cn("两"));
        assertEquals(30, S3Parser.cn("30"));
        assertNotNull(p("每天晚上十点半提醒我睡觉"));
        assertEquals(22, p("每天晚上十点半提醒我睡觉").hour);
    }

    @Test public void similarityForCancelAndDone() {
        assertTrue(S3Parser.similarity("读邮件", "读一下QQ邮箱未读") >= 0.5);
        assertTrue(S3Parser.similarity("交周报", "交周报") == 1.0);
        assertTrue(S3Parser.similarity("买牛奶", "跑步") < 0.5);
    }
}
