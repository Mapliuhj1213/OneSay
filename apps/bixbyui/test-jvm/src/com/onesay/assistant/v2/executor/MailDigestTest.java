package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.router.FastIntents;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static org.junit.Assert.*;

/** ★ 11-04 F / G：邮件数量与「有价值的邮件」格式；「展开第 N 封」接续；答案标题逐字核对。 */
public class MailDigestTest {

    static long at(int d, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, d, h, m, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static final long NOW = at(4, 9, 0);

    static List<MailDigest.Item> inbox() {
        List<MailDigest.Item> l = new ArrayList<MailDigest.Item>();   // 新到旧
        l.add(new MailDigest.Item("京东 <newsletter@jd.com>", "【京东】双11限时秒杀，领券立减", at(4, 8, 30), false, false));
        l.add(new MailDigest.Item("\"张三\" <zs@example.com>", "Q4 周报：请周五前确认排期", at(4, 7, 50), false, false));
        l.add(new MailDigest.Item("QQ邮箱 <noreply@qq.com>", "登录验证码 382910", at(4, 7, 0), false, false));
        l.add(new MailDigest.Item("李四 <ls@example.com>", "合同修改意见", at(3, 21, 15), true, true));
        l.add(new MailDigest.Item("hr@corp.cn", "面试时间确认", at(3, 18, 0), false, false));
        l.add(new MailDigest.Item("王五 <ww@example.com>", "周末聚餐地点", at(2, 12, 0), true, false));
        l.add(new MailDigest.Item("赵六 <zl@example.com>", "旧项目归档", at(1, 12, 0), true, false));
        return l;
    }

    @Test public void countFormat() {
        String t = MailDigest.render(12, inbox(), NOW, false);
        String[] lines = t.split("\n");
        assertTrue(t, lines[0].startsWith("你的收件箱未读 12 封，今天新收 3 封（只算收件箱）；最近一封是 京东 的「【京东】双11限时秒杀，领券立减」（今天 8:30）。"));
        assertEquals("值得看的几封：", lines[1]);
        assertEquals("加星优先", "1. 合同修改意见 — 李四", lines[2]);
        assertEquals("2. Q4 周报：请周五前确认排期 — 张三", lines[3]);
        assertEquals("3. 面试时间确认 — hr", lines[4]);
        assertEquals("最多 4 封", "4. 周末聚餐地点 — 王五", lines[5]);
        assertEquals(MailDigest.ASK_EXPAND, lines[lines.length - 1]);
        assertFalse("广告不列", t.contains("2. 【京东】") || t.contains("验证码 382910"));
        assertFalse("无 Markdown", t.contains("**") || t.contains("#"));
    }

    @Test public void valueModeAndEmpty() {
        String v = MailDigest.render(12, inbox(), NOW, true);
        assertTrue(v, v.startsWith("最近值得看的有 4 封（收件箱未读 12 封，今天新收 3 封）"));
        List<MailDigest.Item> ads = new ArrayList<MailDigest.Item>();
        ads.add(new MailDigest.Item("noreply@x.com", "账号安全提醒", NOW - 1000, false, false));
        String a = MailDigest.render(-1, ads, NOW, true);
        assertTrue(a, a.startsWith("最近的邮件里没挑出值得看的（都是广告或通知）"));
        assertTrue(a, a.contains("未读数没读到"));
        assertEquals("收件箱（未读 0 封，今天新收 0 封），最近没有新邮件。", MailDigest.render(0, new ArrayList<MailDigest.Item>(), NOW, false));
    }

    @Test public void expandNthContinues() throws Exception {
        MailDigest.render(12, inbox(), System.currentTimeMillis(), false);
        assertEquals(1, MailDigest.expandIndex("展开第一封"));
        assertEquals(2, MailDigest.expandIndex("第二封说了什么？"));
        assertEquals(3, MailDigest.expandIndex("展开说说第3封"));
        assertEquals(0, MailDigest.expandIndex("打开第一个应用"));
        assertEquals("合同修改意见", MailDigest.subjectAt(1, System.currentTimeMillis()));
        assertNull("过期", MailDigest.subjectAt(1, System.currentTimeMillis() + MailDigest.TTL_MS + 1));
        assertNull(MailDigest.subjectAt(9, System.currentTimeMillis()));
        com.onesay.assistant.v2.router.Invocation f = FastIntents.route("展开第一封");
        assertNotNull(f);
        assertEquals("mail.inbox.latest", f.sign);
        assertEquals("合同修改意见", f.args.getString("keyword"));
        assertTrue("只读那一封正文", f.args.getBoolean("body"));
        assertEquals(1, f.args.getInt("count"));
    }

    @Test public void fastPathRoutesCountAndValue() throws Exception {
        com.onesay.assistant.v2.router.Invocation c = FastIntents.route("我有多少邮件");
        assertNotNull(c);
        assertEquals("mail.inbox.latest", c.sign);
        assertTrue(c.args.getBoolean("digest"));
        assertFalse(c.args.getBoolean("value"));
        assertFalse(c.args.getBoolean("body"));
        com.onesay.assistant.v2.router.Invocation v = FastIntents.route("我最近有哪些比较有价值的邮件");
        assertNotNull(v);
        assertEquals("mail.inbox.latest", v.sign);
        assertTrue(v.args.getBoolean("value"));
    }

    @Test public void verifyTitlesAgainstToolResults() {
        String made = "最近值得看的有 2 封：\n1. 年终奖发放通知 — 财务部\n2. 项目评审会议纪要 — 王经理\n需要展开说说哪封吗？";
        String v = MailDigest.verify(made, false, "", null);
        assertTrue("没调邮件工具就不许列", v.startsWith("我这一轮没有读到你的邮件"));
        assertFalse(v.contains("年终奖"));
        String obs = "{\"ok\":true,\"items\":[{\"subject\":\"项目评审会议纪要\",\"from\":\"王经理\"}]}";
        String k = MailDigest.verify(made, true, obs, null);
        assertFalse("编的那行删掉", k.contains("年终奖"));
        assertTrue(k.contains("项目评审会议纪要"));
        assertTrue(MailDigest.verify("最近一封是张三发来的「随便编的」。", true, obs, null).startsWith("读到的邮件里找不到"));
        String ok = "最近一封是王经理发来的「项目评审会议纪要」。";
        assertEquals(ok, MailDigest.verify(ok, true, obs, null));
        // 如实说明里引用的界面名不是邮件标题（不能被当成编造删掉）
        String plan = "我没读到邮件。你可以：①在 QQ 邮箱网页版「设置→账户」开启 IMAP；②打开控制台 →「邮箱（IMAP）配置」；也可以说「打开QQ邮箱」。";
        assertEquals(plan, MailDigest.verify(plan, false, "", null));
        assertTrue(MailDigest.mailQuestion("我最近有哪些比较有价值的邮件"));
        assertFalse(MailDigest.mailQuestion("今天天气怎么样"));
    }
}
