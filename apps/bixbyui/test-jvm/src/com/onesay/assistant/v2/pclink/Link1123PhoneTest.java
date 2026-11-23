package com.onesay.assistant.v2.pclink;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** [rel-1123-b] 手机侧纯逻辑：目标结果回传（PcGoals）、已装 App（PcAppList）、免扫码配对的核对码 / 地址（PcPairNearby）、离线说法（PcLinkText）。 */
public class Link1123PhoneTest {

    static long T;
    final List<PcGoals.Goal> sent = new CopyOnWriteArrayList<PcGoals.Goal>();
    volatile boolean online = true;

    @Before public void setUp() {
        PcGoals.reset();
        T = 1_000_000L;
        PcGoals.clock = new PcGoals.Clock() {
            @Override public long now() { return T; }
        };
        PcGoals.setSink(new PcGoals.Sink() {
            @Override public boolean send(PcGoals.Goal g) {
                if (!online) return false;
                sent.add(g);
                return true;
            }
        });
    }

    @After public void tearDown() {
        PcGoals.clock = null;
        PcGoals.reset();
    }

    // ================================================================== PcGoals

    @Test public void simpleCommandSettlesAfterQuietTime() {
        String id = PcGoals.begin("pc-1", "打开设置看看安卓版本");
        assertTrue(id.matches("g[0-9a-f]{18}"));
        PcGoals.shown(true, "上一轮的结果"); // 还没认领：不算
        PcGoals.submitted("打开设置看看安卓版本", 5);
        PcGoals.shown(true, "已打开设置");
        T += 1000;
        PcGoals.shown(true, "安卓版本是 8.0.0");
        T += PcGoals.SETTLE_MS - 1;
        PcGoals.tick();
        assertTrue("还没安静够", sent.isEmpty());
        T += 2;
        PcGoals.tick();
        assertEquals(1, sent.size());
        PcGoals.Goal g = sent.get(0);
        assertEquals(id, g.id);
        assertEquals(PcGoals.DONE, g.state());
        assertEquals("最后上屏的那句是结果", "安卓版本是 8.0.0", g.text());
        assertEquals(Arrays.asList("已打开设置", "安卓版本是 8.0.0"), g.steps());
        assertNull(PcGoals.active());
        assertEquals(PcGoals.DONE, PcGoals.status().get("state"));
    }

    @Test public void confirmCardHoldsSettleAndDenyIsDenied() {
        PcGoals.begin("pc-1", "给张三发短信");
        PcGoals.submitted("给张三发短信", 1);
        PcGoals.waiting(true);
        T += 60_000;
        PcGoals.tick();
        assertTrue("等人点确认卡时不收尾", sent.isEmpty());
        PcGoals.shownState(PcGoals.DENIED, "你在手机上拒绝了：发短信");
        T += PcGoals.SETTLE_MS;
        PcGoals.tick();
        assertEquals(1, sent.size());
        assertEquals(PcGoals.DENIED, sent.get(0).state());
        assertTrue(sent.get(0).text().contains("拒绝"));
    }

    @Test public void agentLoopWaitsForLoopEndIgnoringIntermediateResults() {
        PcGoals.begin("pc-1", "在京东搜拯救者，读一万以内的价格");
        PcGoals.submitted("在京东搜拯救者，读一万以内的价格", 9);
        PcGoals.agentStarted(8); // 别的请求号：不算
        PcGoals.agentStarted(9);
        PcGoals.shown(true, "已打开京东"); // 循环里中间一步
        T += 60_000;
        PcGoals.tick();
        assertTrue("复合任务：等循环结束", sent.isEmpty());
        PcGoals.agentEnded(9, true, false, "找到 3 款一万以内：Y7000P 7999 元…", null, Arrays.asList("打开京东", "搜索拯救者", "读价格"));
        assertEquals(1, sent.size());
        assertEquals(PcGoals.DONE, sent.get(0).state());
        assertTrue(sent.get(0).text().startsWith("找到 3 款"));
        assertEquals(3, sent.get(0).steps().size());
        // 没做完 / 停了
        PcGoals.begin("pc-1", "a");
        PcGoals.submitted("a", 10);
        PcGoals.agentStarted(10);
        PcGoals.agentEnded(10, false, false, "", "被登录页挡住了", null);
        assertEquals(PcGoals.FAILED, sent.get(1).state());
        assertEquals("被登录页挡住了", sent.get(1).text());
        PcGoals.begin("pc-1", "b");
        PcGoals.submitted("b", 11);
        PcGoals.agentStarted(11);
        PcGoals.agentEnded(11, false, true, null, null, null);
        assertEquals(PcGoals.STOPPED, sent.get(2).state());
    }

    @Test public void newCommandInterruptsAndNewGoalReplacesOld() {
        PcGoals.begin("pc-1", "打开设置");
        PcGoals.submitted("打开设置", 3);
        PcGoals.shown(true, "已打开设置");
        PcGoals.submitted("今天天气怎么样", 4); // 手机上又说了别的
        assertEquals(1, sent.size());
        assertEquals(PcGoals.INTERRUPTED, sent.get(0).state());
        assertTrue(sent.get(0).text().contains("已打开设置"));
        PcGoals.begin("pc-1", "第一个");
        PcGoals.begin("pc-1", "第二个");
        assertEquals(PcGoals.INTERRUPTED, sent.get(1).state());
        assertEquals("第一个", sent.get(1).goal);
        assertEquals("第二个", PcGoals.active().goal);
    }

    @Test public void panelNeverPicksUpOrTakesTooLong() {
        PcGoals.begin("pc-1", "打开设置");
        T += PcGoals.BIND_MS;
        PcGoals.tick();
        assertEquals(PcGoals.FAILED, sent.get(0).state());
        assertTrue(sent.get(0).text().contains("没接到"));
        PcGoals.begin("pc-1", "长任务");
        PcGoals.submitted("长任务", 2);
        PcGoals.agentStarted(2);
        T += PcGoals.TOTAL_MS;
        PcGoals.tick();
        assertEquals(PcGoals.TIMEOUT, sent.get(1).state());
        // 认领了但屏上一句结果都没有：不硬说做完了
        PcGoals.begin("pc-1", "打开相机");
        PcGoals.submitted("打开相机", 5);
        T += PcGoals.QUIET_MS;
        PcGoals.tick();
        assertEquals(PcGoals.UNKNOWN, sent.get(2).state());
        assertTrue(sent.get(2).text().contains("看手机屏幕"));
        sent.remove(2);
        // 手机上说「停止」（简单指令）
        PcGoals.begin("pc-1", "c");
        PcGoals.submitted("c", 3);
        PcGoals.stopped();
        assertEquals(PcGoals.STOPPED, sent.get(2).state());
    }

    @Test public void offlineResultsAreKeptAndFlushedOnReconnect() {
        online = false;
        for (int i = 0; i < PcGoals.OUTBOX_MAX + 2; i++) {
            PcGoals.begin("pc-1", "g" + i);
            PcGoals.submitted("g" + i, i);
            PcGoals.shown(true, "r" + i);
            T += PcGoals.SETTLE_MS;
            PcGoals.tick();
        }
        assertTrue(sent.isEmpty());
        assertEquals("最多留 " + PcGoals.OUTBOX_MAX + " 条", PcGoals.OUTBOX_MAX, PcGoals.pending());
        online = true;
        PcGoals.flush();
        assertEquals(PcGoals.OUTBOX_MAX, sent.size());
        assertEquals("留最新的", "r" + (PcGoals.OUTBOX_MAX + 1), sent.get(sent.size() - 1).text());
        assertEquals(0, PcGoals.pending());
        // 过期的不补发
        online = false;
        PcGoals.begin("pc-1", "old");
        PcGoals.submitted("old", 1);
        PcGoals.shown(true, "x");
        T += PcGoals.SETTLE_MS;
        PcGoals.tick();
        T += PcGoals.OUTBOX_MS + 1;
        online = true;
        PcGoals.flush();
        assertEquals(PcGoals.OUTBOX_MAX, sent.size());
    }

    @Test public void longTextClippedOnCharBoundary() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 2000; i++) b.append("汉");
        b.append("\uD83D\uDE00");
        String c = PcGoals.clip(b.toString(), 2001);
        assertFalse("不截半个代理对", Character.isHighSurrogate(c.charAt(c.length() - 2)));
        PcGoals.begin("pc-1", "x");
        PcGoals.submitted("x", 1);
        PcGoals.shown(true, b.toString());
        T += PcGoals.SETTLE_MS;
        PcGoals.tick();
        assertTrue(sent.get(0).text().length() <= PcGoals.TEXT_MAX + 1);
        assertTrue(sent.get(0).text().endsWith("…"));
    }

    @Test public void goalFrameIsSignedForThePc() throws Exception {
        PcGoals.begin("pc-1", "打开设置");
        PcGoals.submitted("打开设置", 1);
        PcGoals.shown(true, "好了");
        T += PcGoals.SETTLE_MS;
        PcGoals.tick();
        byte[] key = new byte[32];
        PcLanClient.Peer p = new PcLanClient.Peer("phone-x", "pc-1", key, "", 47820, "");
        String f = PcRx.goalFrame(p, sent.get(0), System.currentTimeMillis());
        Map<String, Object> env = PcJson.obj(f);
        assertEquals("pc-1", env.get("to"));
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) env.get("body");
        assertEquals("goal_result", body.get("kind"));
        assertEquals("done", body.get("state"));
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertEquals(PcProtocol.Check.OK, PcProtocol.verify(key, String.valueOf(env.get("id")), "phone-x", "pc-1", body, System.currentTimeMillis(), new PcProtocol.ReplayGuard()));
        assertFalse("不带屏幕内容", f.contains("screen") || f.contains("png"));
    }

    // ================================================================== PcAppList

    @Test public void appListDedupSortFilterAndCap() {
        List<PcAppList.App> l = new ArrayList<PcAppList.App>();
        l.add(new PcAppList.App("微信", "com.tencent.mm"));
        l.add(new PcAppList.App("百度地图", "com.baidu.BaiduMap"));
        l.add(new PcAppList.App("微信", "com.tencent.mm"));
        l.add(new PcAppList.App("", "com.example.noname"));
        l.add(new PcAppList.App("高德 地图", "com.autonavi.minimap"));
        PcLanClient.Result r = PcAppList.view(l, "", 300);
        assertTrue(r.ok);
        assertEquals(4L, r.data.get("total"));
        assertTrue(r.text, r.text.startsWith("手机桌面上有 4 个 App"));
        assertFalse("不带图标", String.valueOf(r.data).contains("icon"));
        PcLanClient.Result q = PcAppList.view(l, "地图", 300);
        assertEquals(2L, q.data.get("total"));
        assertTrue(q.text, q.text.contains("百度地图") && q.text.contains("高德 地图"));
        PcLanClient.Result none = PcAppList.view(l, "支付宝", 300);
        assertEquals(0L, none.data.get("total"));
        assertTrue(none.text.contains("没找到"));
        PcLanClient.Result cap = PcAppList.view(l, "", 2);
        assertEquals(Boolean.TRUE, cap.data.get("truncated"));
        assertEquals(2L, cap.data.get("shown"));
        assertTrue(cap.text.contains("只列了前 2 个"));
        assertTrue(PcRxPolicy.known(PcRxPolicy.APP_LIST));
        assertFalse(PcRxPolicy.dangerous(PcRxPolicy.APP_LIST));
        assertTrue("默认值按开源版现有规则（只读、不是危险项 → 开；总开关照旧默认关）", PcRxPolicy.defaultOn(PcRxPolicy.APP_LIST));
        assertEquals(LinkPolicy.Cat.READ_PRIVATE, LinkPolicy.phoneCat("app_list"));
        assertNull(PcRxPolicy.check(PcRxPolicy.APP_LIST, new TreeMap<String, Object>()));
        Map<String, Object> big = new TreeMap<String, Object>();
        big.put("query", "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一");
        assertNotNull(PcRxPolicy.check(PcRxPolicy.APP_LIST, big));
    }

    @Test public void appListIsHandledWithToggle() {
        final java.util.Set<String> on = new java.util.HashSet<String>();
        PcRx.Handler h = new PcRx.Handler() {
            @Override public boolean enabled(String cmd) { return on.contains(cmd); }
            @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) { return PcAppList.view(new ArrayList<PcAppList.App>(), "", 300); }
            @Override public void record(String cmd, boolean ok, String text) { }
        };
        PcLanClient.Result off = PcRx.handle(h, "app_list", null);
        assertFalse(off.ok);
        assertTrue(off.text, off.text.contains("看手机上装了哪些 App") && off.text.contains("没开"));
        on.add("app_list");
        assertTrue(PcRx.handle(h, "app_list", null).ok);
    }

    // ================================================================== 免扫码配对

    @Test public void pairCodeMatchesDesktopAlgorithm() {
        // 同一组输入在电脑端 LanPair.code 里也是 4053（Pair1123Test 里锁同一个值；两边算法一样，核对码才对得上）
        assertEquals("4053", PcPairNearby.code("AAAA", "BBBB", "n-phone-123456789", "n-pc-123456789012"));
        assertEquals("ZQSDbH5snzVBbFAWkBFPXELnxL8k6CsEKOK1qmAxVSo", PcPairNearby.commit("abc"));
        assertNotEquals("证书一换码就变", PcPairNearby.code("AAAA", "CCCC", "n-phone-123456789", "n-pc-123456789012"), PcPairNearby.code("AAAA", "BBBB", "n-phone-123456789", "n-pc-123456789012"));
        for (int i = 0; i < 50; i++) assertTrue(PcPairNearby.code("a" + i, "b", "c", "d").matches("\\d{4}"));
    }

    @Test public void privateAddressesOnly() throws Exception {
        for (String ok : new String[]{"192.168.1.5", "10.0.0.2", "172.20.1.1", "100.101.102.103", "127.0.0.1", "169.254.3.4", "fd7a:115c:a1e0::1", "fe80::1"})
            assertTrue(ok, PcPairNearby.privateAddr(InetAddress.getByName(ok)));
        for (String bad : new String[]{"8.8.8.8", "1.1.1.1", "100.128.0.1", "172.32.0.1", "2001:4860:4860::8888"})
            assertFalse(bad, PcPairNearby.privateAddr(InetAddress.getByName(bad)));
        assertEquals(47920, PcPairNearby.invitePort("{\"t\":\"pinv\",\"v\":1,\"port\":47920}"));
        assertEquals(-1, PcPairNearby.invitePort("{\"t\":\"ann\",\"port\":47920}"));
        assertEquals(-1, PcPairNearby.invitePort("not json"));
        PcPairNearby.Outcome o = PcPairNearby.request(new java.net.InetSocketAddress("8.8.8.8", 47820), "phone-x", "x", PcTls.generate("x"), null, 1000);
        assertFalse("公网地址不去连", o.ok);
        assertTrue(o.why.contains("局域网"));
        assertEquals("电脑上点了拒绝", PcPairNearby.whyZh("denied"));
        assertEquals("试得太快了，等 10 秒再试", PcPairNearby.whyZh("rate"));
    }

    // ================================================================== 离线说法

    @Test public void offlineTextsSayWhy() {
        PcLanClient.Result ok = new PcLanClient.Result(true, "在线", null, "");
        String[] a = PcLinkText.probe(ok, true, false, false, "我的电脑", "192.168.1.5");
        assertEquals("在线", a[0]);
        assertTrue("在线但没开接受电脑指令：提醒电脑那边看是离线", a[2].contains("接受电脑指令"));
        assertFalse(PcLinkText.probe(ok, true, true, true, "我的电脑", "")[2].contains("接受电脑指令"));
        String[] auth = PcLinkText.probe(PcLanClient.Result.failure("auth", "x"), true, true, false, "我的电脑", "");
        assertEquals("配对失效", auth[0]);
        assertEquals(PcLinkText.BAD, auth[1]);
        String[] wifi = PcLinkText.probe(PcLanClient.Result.failure("offline", "x"), false, true, false, "我的电脑", "");
        assertTrue(wifi[2].contains("没连 Wi-Fi"));
        String[] net = PcLinkText.probe(PcLanClient.Result.failure("offline", "x"), true, true, false, "我的电脑", "");
        assertTrue(net[2].contains("同一个网络"));
        assertTrue(PcLinkText.rxState(PcRx.S_WAIT, "").contains("同一个网络"));
        for (String[] s : new String[][]{a, auth, wifi, net}) for (String x : s) {
            assertFalse(x, x.contains("Bixby") || x.contains("三星") || x.contains("啥") || x.contains("咋") || x.contains("呗"));
        }
    }
}
