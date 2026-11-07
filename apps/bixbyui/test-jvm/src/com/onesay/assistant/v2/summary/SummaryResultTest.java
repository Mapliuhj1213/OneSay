package com.onesay.assistant.v2.summary;

import static org.junit.Assert.*;

import org.junit.Test;

/** [summary] 输出解析与兜底：标准格式 / 编号要点 / 整段话 / 只有要点 / 令牌 / 空 / 封顶 / 擦除 / 面板与纯文本格式。 */
public class SummaryResultTest {

    @Test public void standardFormat() {
        SummaryResult r = SummaryResult.parse("一句话：共享单车改变了城市通勤，但停放仍难管。\n- 共享单车让短途通勤更方便\n- 乱停乱放影响市容\n- 作者建议划定电子围栏。");
        assertEquals(SummaryResult.Kind.OK, r.kind);
        assertEquals("共享单车改变了城市通勤，但停放仍难管", r.gist);
        assertEquals(3, r.bullets.size());
        assertEquals("作者建议划定电子围栏", r.bullets.get(2));
        assertTrue(r.usable());
        String f = r.format();
        assertTrue(f.startsWith("一句话：共享单车"));
        assertTrue(f.contains("\n• 乱停乱放影响市容"));
        assertEquals("共享单车改变了城市通勤，但停放仍难管：共享单车让短途通勤更方便；乱停乱放影响市容；作者建议划定电子围栏", r.plain(200));
        assertTrue(r.plain(10).length() <= 10);
    }

    @Test public void numberedBulletsMarkdownAndHeader() {
        SummaryResult r = SummaryResult.parse("**一句话总结：** 群里定了周五聚餐\n\n要点：\n1. 时间周五晚七点\n2) 地点公司楼下川菜馆\n（3）小王负责订座");
        assertEquals(SummaryResult.Kind.OK, r.kind);
        assertEquals("群里定了周五聚餐", r.gist);
        assertEquals(3, r.bullets.size());
        assertEquals("小王负责订座", r.bullets.get(2));
    }

    @Test public void paragraphFallback() {
        SummaryResult r = SummaryResult.parse("这篇文章讲了城市骑行的现状。作者认为共享单车提高了效率。同时停放问题需要治理！");
        assertEquals(SummaryResult.Kind.FALLBACK, r.kind);
        assertEquals("这篇文章讲了城市骑行的现状", r.gist);
        assertEquals(2, r.bullets.size());
        assertTrue(r.usable());
    }

    @Test public void bulletsWithoutGist() {
        SummaryResult r = SummaryResult.parse("- 第一点\n- 第二点很重要\n- 第三点");
        assertEquals(SummaryResult.Kind.FALLBACK, r.kind);
        assertEquals("第一点", r.gist);
        assertEquals(3, r.bullets.size());
    }

    @Test public void tokensAndEmpty() {
        assertEquals(SummaryResult.Kind.SENSITIVE, SummaryResult.parse("SENSITIVE").kind);
        assertEquals(SummaryResult.Kind.SENSITIVE, SummaryResult.parse(" sensitive。").kind);
        assertEquals(SummaryResult.Kind.EMPTY, SummaryResult.parse("EMPTY").kind);
        assertEquals(SummaryResult.Kind.FAIL, SummaryResult.parse("").kind);
        assertEquals(SummaryResult.Kind.FAIL, SummaryResult.parse(null).kind);
        assertFalse(SummaryResult.parse("EMPTY").usable());
    }

    @Test public void capsAndDedup() {
        StringBuilder sb = new StringBuilder("一句话：");
        for (int i = 0; i < 80; i++) sb.append('长');
        for (int i = 0; i < 8; i++) sb.append("\n- 要点").append(i % 6).append("：").append(new String(new char[60]).replace('\0', '字'));
        SummaryResult r = SummaryResult.parse(sb.toString());
        assertEquals(SummaryResult.MAX_BULLETS, r.bullets.size());
        assertTrue(r.gist.length() <= SummaryResult.GIST_MAX);
        for (String b : r.bullets) assertTrue(b.length() <= SummaryResult.BULLET_MAX);
    }

    @Test public void scrubsSensitiveOutput() {
        SummaryResult r = SummaryResult.parse("一句话：一条银行短信\n- 验证码是 482913\n- 提醒不要告诉他人");
        assertEquals(1, r.scrubbed);
        assertEquals(1, r.bullets.size());
        assertTrue(r.format().contains("已略去 1 条疑似敏感信息"));
        SummaryResult all = SummaryResult.parse("- 验证码 123456\n- 校验码 654321");
        assertEquals(SummaryResult.Kind.SENSITIVE, all.kind);
    }
}
