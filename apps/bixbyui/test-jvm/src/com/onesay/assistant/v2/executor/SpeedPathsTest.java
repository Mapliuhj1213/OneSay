package com.onesay.assistant.v2.executor;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ 11-04 午：单步 / 邮件提速 —— 闹钟一次往返（OneSay 时钟接口）；「展开第 N 封」按 UID 直读 + 本地短摘要。
 */
public class SpeedPathsTest {

    @Test public void alarmGoesThroughClockApiNotShell() throws Exception {
        // 闹钟不再经 root 守护：OneSay 时钟接口（有序广播）一次往返就拿到结果，没装时交给系统时钟
        AlarmBridgeRoutingTest.FakeLink l = new AlarmBridgeRoutingTest.FakeLink(AlarmBridge.ClockState.READY);
        ExecutionResult r = AlarmBridge.execute(AlarmBridgeRoutingTest.cap(AlarmBridge.SIGN_SET), new JSONObject("{\"hour\":3,\"minute\":53}"), l, null);
        assertTrue(r.userMessage, r.ok);
        assertEquals("设闹钟只调时钟一次", 1, l.calls.size());
    }

    @Test public void mailBriefDropsBoilerplate() {
        String body = "如果您不愿意继续接收到此类邮件，请点击 退订\n三星 W27 正式发布，即日起登记预约可享 12 期免息。\n预约用户另赠原装保护壳。\nhttps://www.samsung.com/cn/x\n版权所有 © 2026 三星电子";
        String b = MailDigest.brief(body, 120);
        assertFalse(b, b.contains("退订") || b.contains("版权所有") || b.contains("http"));
        assertTrue(b, b.contains("三星 W27 正式发布"));
        assertTrue(b.length() <= 120);
        assertFalse(b.endsWith("…"));
    }

    @Test public void mailBriefCutsAtSentence() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append("第").append(i).append("句话的内容比较长需要被截断。");
        String b = MailDigest.brief(sb.toString(), 60);
        assertTrue(b.length() <= 60);
        assertTrue("整句保留：" + b, b.endsWith("。"));
    }

    @Test public void expandReplyCarriesSubjectVerbatim() {
        String subj = "三星 W27正式发布丨立即登记预约";
        String r = MailDigest.expandReply(1, "三星电子 <samsung.memberclub@member.samsung.com.cn>", subj, 0L, "即日起登记预约。", 0L);
        assertTrue("标题逐字", r.contains("「" + subj + "」"));
        assertTrue(r.startsWith("第1封："));
        assertTrue(r.contains("三星电子"));
        assertTrue(r.contains("大意：即日起登记预约。"));
        String empty = MailDigest.expandReply(2, "a@b.com", "图片邮件", 0L, "", 0L);
        assertTrue(empty.contains("没有可读的正文"));
    }

    @Test public void uidRememberedForExpand() {
        List<MailDigest.Item> all = new ArrayList<MailDigest.Item>();
        long now = System.currentTimeMillis();
        all.add(new MailDigest.Item("张三 <z@corp.com>", "周五项目评审材料", now - 1000, false, false, 4321L));
        all.add(new MailDigest.Item("李四 <l@corp.com>", "合同修改意见", now - 2000, false, false, 4320L));
        MailDigest.render(10, all, now, false);
        assertEquals("周五项目评审材料", MailDigest.subjectAt(1, now));
        assertEquals(4321L, MailDigest.uidAt(1, now));
        assertEquals(4320L, MailDigest.uidAt(2, now));
        assertEquals(-1L, MailDigest.uidAt(3, now));
        assertEquals("过期不认", -1L, MailDigest.uidAt(1, now + MailDigest.TTL_MS + 1));
        assertEquals(4321L, MailBridge.parseUid("4321"));
        assertEquals(-1L, MailBridge.parseUid("43a1"));
        assertEquals(-1L, MailBridge.parseUid(""));
    }

    @Test public void expandFastIntentCarriesUid() throws Exception {
        List<MailDigest.Item> all = new ArrayList<MailDigest.Item>();
        long now = System.currentTimeMillis();
        all.add(new MailDigest.Item("张三 <z@corp.com>", "周五项目评审材料", now - 1000, false, false, 777L));
        MailDigest.render(1, all, now, false);
        com.onesay.assistant.v2.router.Invocation inv = com.onesay.assistant.v2.router.FastIntents.route("展开第一封");
        assertNotNull(inv);
        assertEquals("mail.inbox.latest", inv.sign);
        assertEquals("777", inv.args.optString("uid"));
        assertEquals("周五项目评审材料", inv.args.optString("keyword"));
        assertEquals(1, inv.args.optInt("index"));
    }

    @Test public void wiringHeadersFetchedInOneGo() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String mb = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/executor/MailBridge.java")), "UTF-8");
        assertTrue("摘要与本地过滤都批量取头部", mb.split("prefetchHeaders\\(folder,", -1).length - 1 >= 2);
        assertTrue(mb.contains("getMessageByUID(wantUid)"));
        String br = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/executor/AlarmBridge.java")), "UTF-8");
        assertFalse("闹钟不再走 root shell", br.contains("ShellRunner") || br.contains("am broadcast"));
    }

    @Test public void readerSettlesWithoutPrice() {
        assertTrue(com.onesay.assistant.v2.errand.web.ReaderPolicy.settledWithoutPrice(0, 3461, 3461, true, false));
        assertFalse("还在长", com.onesay.assistant.v2.errand.web.ReaderPolicy.settledWithoutPrice(0, 3461, 2000, true, false));
        assertFalse("还在加载", com.onesay.assistant.v2.errand.web.ReaderPolicy.settledWithoutPrice(0, 3461, 3461, true, true));
        assertFalse("有价格节点走原来的稳定判定", com.onesay.assistant.v2.errand.web.ReaderPolicy.settledWithoutPrice(3, 3461, 3461, true, false));
        assertFalse("太短（空壳 / 跳转中）", com.onesay.assistant.v2.errand.web.ReaderPolicy.settledWithoutPrice(0, 120, 120, true, false));
        assertTrue(com.onesay.assistant.v2.errand.web.ReaderPolicy.AGENT_PAGE_TIMEOUT_MS < com.onesay.assistant.v2.errand.web.ReaderPolicy.PAGE_TIMEOUT_MS);
    }
}
