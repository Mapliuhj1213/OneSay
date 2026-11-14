package com.onesay.assistant.plug.vyrse.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

/** compat-vyrse：协议校验、配对、动作白名单 / 映射、响铃恢复音量、长轮询（全部 JVM，不碰 Android）。 */
public class VyrseCoreTest {

    static final String BASE = "http://192.168.1.20:47630";
    static final String TOKEN = "k3y_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcd";

    // ================================================================== 协议 / 输入

    @Test public void normalizeBaseMatchesVyrseBridgeBase() {
        assertEquals(BASE, VyrseProtocol.normalizeBase("192.168.1.20"));
        assertEquals(BASE, VyrseProtocol.normalizeBase(" 192.168.1.20:47630 "));
        assertEquals(BASE, VyrseProtocol.normalizeBase("http://192.168.1.20/"));
        assertEquals(BASE, VyrseProtocol.normalizeBase("HTTP://192.168.1.20/phone/hello"));
        assertEquals("http://10.0.0.5:8080", VyrseProtocol.normalizeBase("10.0.0.5:8080"));
        assertEquals("http://172.16.4.2:47630", VyrseProtocol.normalizeBase("172.16.4.2"));
    }

    @Test public void normalizeBaseRejectsNonHomeAddresses() {
        for (String a : new String[]{null, "", "   ", "https://192.168.1.20", "ws://192.168.1.20", "8.8.8.8", "100.64.1.1",
                "172.32.0.1", "my-pc.local", "evil.example.com", "user@192.168.1.20", "192.168.1.20:0", "192.168.1.20:70000",
                "192.168.1.20:abc", "192.168.1", "192.168.1.256", "::1"}) {
            assertNull(String.valueOf(a), VyrseProtocol.normalizeBase(a));
        }
    }

    @Test public void privateAddressTableSameAsVyrseTest() {
        // 与 VYRSE desktop/test/phone-bridge.test.ts 的两张表一致（IPv6 回环 ::1 VYRSE 接受；我们只做 IPv4，单独排除）
        for (String ip : new String[]{"192.168.1.20", "::ffff:192.168.1.20", "10.0.0.5", "172.16.4.2", "172.31.255.1", "127.0.0.1", "169.254.3.3"})
            assertTrue(ip, VyrseProtocol.isPrivateIpv4(ip));
        for (String ip : new String[]{"8.8.8.8", "172.32.0.1", "100.64.1.1", "192.169.1.1", "", null, "garbage", "::ffff:1.2.3.4"})
            assertFalse(String.valueOf(ip), VyrseProtocol.isPrivateIpv4(ip));
    }

    @Test public void codeAndName() {
        assertTrue(VyrseProtocol.validCode("012345"));
        assertTrue(VyrseProtocol.validCode(" 123456 "));
        for (String c : new String[]{null, "", "12345", "1234567", "12a456", "１２３４５６"}) assertFalse(String.valueOf(c), VyrseProtocol.validCode(c));
        assertEquals("OneSay phone", VyrseProtocol.cleanName("  "));
        assertEquals("ab", VyrseProtocol.cleanName("<a\u0007b>"));
        assertEquals(40, VyrseProtocol.cleanName("0123456789012345678901234567890123456789XYZ").length());
    }

    @Test public void protocolConstantsMatchVyrseSource() {
        assertEquals(47630, VyrseProtocol.PORT);
        assertEquals("X-Hermes-Session-Token", VyrseProtocol.HEADER_TOKEN);
        assertEquals("/phone/commands", VyrseProtocol.PATH_COMMANDS);
        assertEquals("/phone/command-result", VyrseProtocol.PATH_RESULT);
        assertEquals("/phone/pair", VyrseProtocol.PATH_PAIR);
        assertEquals(35000, VyrseProtocol.POLL_READ_TIMEOUT_MS);
        assertTrue("长轮询读超时要比电脑挂起时间长", VyrseProtocol.POLL_READ_TIMEOUT_MS > VyrseProtocol.PC_POLL_HOLD_MS);
        assertEquals(2000L, VyrseProtocol.BACKOFF_MIN_MS);
        assertEquals(60000L, VyrseProtocol.BACKOFF_MAX_MS);
    }

    // ================================================================== 配对

    @Test public void pairSuccessHelloThenPair() throws Exception {
        VyrseFakes.Http h = new VyrseFakes.Http()
                .then(200, "{\"app\":\"vyrse\",\"pc\":\"DESKTOP-1\"}")
                .then(200, new JSONObject().put("token", TOKEN).put("deviceId", "dev-1").put("pc", "DESKTOP-1").toString());
        VyrsePairing.Result r = VyrsePairing.pair(h, "192.168.1.20", "123456", "My <phone>");
        assertTrue(r.error, r.ok);
        assertEquals(BASE, r.base);
        assertEquals(TOKEN, r.token);
        assertEquals("dev-1", r.deviceId);
        assertEquals("DESKTOP-1", r.pcName);
        assertEquals(2, h.reqs.size());
        assertEquals("GET", h.reqs.get(0).method);
        assertEquals(BASE + "/phone/hello", h.reqs.get(0).url);
        assertEquals("POST", h.reqs.get(1).method);
        assertEquals(BASE + "/phone/pair", h.reqs.get(1).url);
        assertNull("配对请求不带 key", h.reqs.get(1).token);
        JSONObject body = new JSONObject(h.reqs.get(1).body);
        assertEquals("123456", body.getString("code"));
        assertEquals("My phone", body.getString("name"));
        assertFalse("toString 不含 key", r.toString().contains(TOKEN));
    }

    @Test public void pairErrorsTranslated() throws Exception {
        String hello = "{\"app\":\"vyrse\",\"pc\":\"PC\"}";
        VyrsePairing.Result wrong = VyrsePairing.pair(new VyrseFakes.Http().then(200, hello).then(403, "{\"error\":\"Wrong code.\"}"), "192.168.1.20", "000000", "x");
        assertFalse(wrong.ok);
        assertTrue(wrong.error, wrong.error.contains("配对码不对"));
        VyrsePairing.Result closed = VyrsePairing.pair(new VyrseFakes.Http().then(200, hello)
                .then(403, "{\"error\":\"Pairing isn\u2019t open. On the PC: Settings \u2192 Phone \u2192 Pair a phone.\"}"), "192.168.1.20", "000000", "x");
        assertTrue(closed.error, closed.error.contains("没在配对"));
        VyrsePairing.Result shortTok = VyrsePairing.pair(new VyrseFakes.Http().then(200, hello).then(200, "{\"token\":\"x\"}"), "192.168.1.20", "123456", "x");
        assertFalse("太短的 key 不收", shortTok.ok);
    }

    @Test public void pairRefusesNonVyrseAndBadInputWithoutSendingCode() throws Exception {
        VyrseFakes.Http other = new VyrseFakes.Http().then(200, "{\"app\":\"something-else\"}");
        VyrsePairing.Result r = VyrsePairing.pair(other, "192.168.1.20", "123456", "x");
        assertFalse(r.ok);
        assertEquals("对面不是 VYRSE 就不发配对码", 1, other.reqs.size());
        VyrseFakes.Http none = new VyrseFakes.Http();
        assertFalse(VyrsePairing.pair(none, "8.8.8.8", "123456", "x").ok);
        assertFalse(VyrsePairing.pair(none, "192.168.1.20", "12345", "x").ok);
        assertFalse(VyrsePairing.pair(none, "https://192.168.1.20", "123456", "x").ok);
        assertEquals("公网 / 格式不对：一个请求都不发", 0, none.reqs.size());
        VyrsePairing.Result down = VyrsePairing.pair(new VyrseFakes.Http().thenFail(), "192.168.1.20", "123456", "x");
        assertTrue(down.error, down.error.contains("连不上电脑"));
    }

    // ================================================================== 白名单

    @Test public void allowlistIsExactlyTheV1Set() {
        assertEquals(new HashSet<String>(Arrays.asList("find", "flashlight", "notifications", "notify", "battery", "status", "open_app")),
                VyrseDispatcher.SUPPORTED);
        for (String a : VyrseDispatcher.SUPPORTED) assertFalse(a, VyrseDispatcher.UNSUPPORTED.contains(a));
        assertTrue(VyrseDispatcher.allowed("find"));
        assertFalse(VyrseDispatcher.allowed("tap"));
        assertFalse(VyrseDispatcher.allowed(null));
    }

    @Test public void fullControlAndOtherVyrseActionsAnswerUnsupported() throws Exception {
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        VyrseDispatcher d = new VyrseDispatcher(p);
        for (String a : new String[]{"screen", "screenshot", "tap", "long_press", "type", "swipe", "key", "open_url", "send_file", "media", "volume"}) {
            JSONObject r = d.run(a, new JSONObject().put("text", "Pay").put("n", 1).put("confirmed", "yes"));
            assertFalse(a, r.getBoolean("ok"));
            assertTrue(a, r.optBoolean("unsupported"));
        }
        assertTrue("不支持的动作没碰任何能力", p.launched.isEmpty() && p.posted.isEmpty() && p.torch == null && !p.sound);
    }

    @Test public void unknownActionsRejectedLikeVyrse() throws Exception {
        VyrseDispatcher d = new VyrseDispatcher(new VyrseFakes.Phone());
        for (String a : new String[]{"", "shell", "rm -rf /", "FIND", "find ", "install", "call", "sms"}) {
            JSONObject r = d.run(a, null);
            assertFalse(a, r.getBoolean("ok"));
            assertFalse(a, r.optBoolean("unsupported"));
            assertTrue(a, r.getString("error").startsWith("Unknown phone action"));
        }
        assertFalse(d.run(null, null).getBoolean("ok"));
    }

    // ================================================================== 响铃

    @Test public void findRingsAtMaxAlarmVolumeAndAutoStopsRestoringVolume() throws Exception {
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        VyrseDispatcher d = new VyrseDispatcher(p);
        JSONObject r = d.run("find", new JSONObject());
        assertTrue(r.getBoolean("ok"));
        assertTrue(r.getBoolean("ringing"));
        assertEquals(30, r.getInt("seconds"));
        assertEquals("闹钟音量拉满", 15, p.vol);
        assertTrue(p.sound && p.vib);
        assertEquals(Long.valueOf(30000L), p.delays.get(0));
        p.fireTimers();
        assertFalse("30 秒后自动停", p.sound || p.vib);
        assertEquals("恢复原音量", 3, p.vol);
        assertFalse(d.ring().ringing());
    }

    @Test public void findStopAndRestartKeepOriginalVolume() throws Exception {
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        VyrseDispatcher d = new VyrseDispatcher(p);
        d.run("find", new JSONObject().put("state", "start"));
        d.run("find", new JSONObject().put("state", "start"));   // 再响一次
        assertEquals("旧定时器被取消，只剩一个", 1, p.timers.size());
        JSONObject s = d.run("find", new JSONObject().put("state", "stop"));
        assertTrue(s.getBoolean("ok"));
        assertFalse(s.getBoolean("ringing"));
        assertEquals("恢复的是第一次记下的音量，不是拉满后的", 3, p.vol);
        assertTrue(p.timers.isEmpty());
        assertFalse(d.run("find", new JSONObject().put("state", "loud")).getBoolean("ok"));
    }

    @Test public void findFailureRestoresVolume() throws Exception {
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        p.soundFails = true;
        JSONObject r = new VyrseDispatcher(p).run("find", null);
        assertFalse(r.getBoolean("ok"));
        assertEquals(3, p.vol);
        assertTrue(p.timers.isEmpty());
    }

    // ================================================================== 其它动作

    @Test public void flashlight() throws Exception {
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        VyrseDispatcher d = new VyrseDispatcher(p);
        assertEquals("on", d.run("flashlight", null).getString("flashlight"));   // 默认 toggle
        assertEquals("off", d.run("flashlight", new JSONObject().put("state", "toggle")).getString("flashlight"));
        assertEquals("on", d.run("flashlight", new JSONObject().put("state", "on")).getString("flashlight"));
        assertEquals("off", d.run("flashlight", new JSONObject().put("state", "off")).getString("flashlight"));
        assertFalse(d.run("flashlight", new JSONObject().put("state", "strobe")).getBoolean("ok"));
        p.flash = false;
        assertEquals("This phone has no flashlight.", d.run("flashlight", new JSONObject().put("state", "on")).getString("error"));
    }

    @Test public void notificationsFilteredRedactedAndClamped() throws Exception {
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        long now = System.currentTimeMillis();
        p.notices.add(new PhoneAbilities.Notice("com.tencent.mm", "微信", "妈妈", "晚上回来吃饭吗", now - 120000));
        p.notices.add(new PhoneAbilities.Notice("com.android.mms", "信息", "10690", "【某银行】您的验证码是 482913，5 分钟内有效", now));
        p.notices.add(new PhoneAbilities.Notice("com.eg.android.AlipayGphone", "支付宝", "到账", "收到 100.00 元", now));
        p.notices.add(new PhoneAbilities.Notice("com.example.bank", "Example Bank", "Balance", "1234", now));
        p.notices.add(new PhoneAbilities.Notice("com.onesay.assistant", "OneSay", "自己", "不交出去", now));
        p.notices.add(new PhoneAbilities.Notice("com.x", "Mail", "Order 20261108", "shipped", now));
        VyrseDispatcher d = new VyrseDispatcher(p);
        JSONObject r = d.run("notifications", new JSONObject().put("limit", 999));
        assertTrue(r.getBoolean("ok"));
        JSONArray list = r.getJSONArray("notifications");
        assertEquals(3, r.getInt("count"));
        assertEquals("微信", list.getJSONObject(0).getString("app"));
        assertEquals(2, list.getJSONObject(0).getInt("minutesAgo"));
        String otp = list.getJSONObject(1).getString("text");
        assertFalse("验证码打码", otp.contains("482913"));
        assertTrue(otp.contains("****"));
        assertEquals("没有验证码字样的数字不动", "Order 20261108", list.getJSONObject(2).getString("title"));
        String all = list.toString();
        assertFalse("支付 / 银行 / 本 App 的通知不交给电脑", all.contains("100.00") || all.contains("Balance") || all.contains("不交出去"));
        assertEquals(1, d.run("notifications", new JSONObject().put("limit", 1)).getInt("count"));
        assertEquals(1, d.run("notifications", new JSONObject().put("limit", -5)).getInt("count"));
        p.noticeAccess = false;
        assertTrue(d.run("notifications", null).getString("error").startsWith("Notification access is off"));
    }

    @Test public void notifyBatteryStatus() throws Exception {
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        VyrseDispatcher d = new VyrseDispatcher(p);
        assertTrue(d.run("notify", new JSONObject().put("text", "hi")).getBoolean("shown"));
        assertEquals("VYRSE", p.posted.get(0)[0]);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) big.append('x');
        d.run("notify", new JSONObject().put("title", big.toString()).put("text", big.toString()));
        assertEquals(80, p.posted.get(1)[0].length());
        assertEquals(1000, p.posted.get(1)[1].length());
        p.canPost = false;
        assertFalse(d.run("notify", new JSONObject().put("text", "x")).getBoolean("ok"));
        JSONObject b = d.run("battery", null);
        assertEquals(77, b.getInt("percent"));
        assertTrue(b.getBoolean("charging"));
        JSONObject s = d.run("status", null);
        assertFalse(s.getBoolean("fullControl"));
        assertFalse(s.getBoolean("accessibility"));
        assertEquals("Test Phone", s.getString("model"));
    }

    @Test public void openAppMatchesLikeVyrseAndRefusesMoneyAndPasswords() throws Exception {
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        p.apps.add(new PhoneAbilities.AppEntry("com.tencent.mm", "微信"));
        p.apps.add(new PhoneAbilities.AppEntry("com.netease.cloudmusic", "网易云音乐"));
        p.apps.add(new PhoneAbilities.AppEntry("com.netease.mail", "网易邮箱大师"));
        p.apps.add(new PhoneAbilities.AppEntry("com.eg.android.AlipayGphone", "支付宝"));
        p.apps.add(new PhoneAbilities.AppEntry("com.unknown.mbank", "招商银行"));
        p.apps.add(new PhoneAbilities.AppEntry("com.x8bit.bitwarden", "Bitwarden"));
        p.apps.add(new PhoneAbilities.AppEntry("com.onesay.assistant", "OneSay"));
        p.apps.add(new PhoneAbilities.AppEntry("com.android.vending", "Play Store"));
        VyrseDispatcher d = new VyrseDispatcher(p);
        assertEquals("微信", d.run("open_app", new JSONObject().put("name", "微信")).getString("opened"));
        assertEquals("网易云音乐", d.run("open_app", new JSONObject().put("name", "网易云")).getString("opened"));
        for (String n : new String[]{"支付宝", "招商银行", "银行", "Bitwarden", "OneSay", "Play Store", "wallet", "Password manager"}) {
            JSONObject r = d.run("open_app", new JSONObject().put("name", n));
            assertFalse(n, r.getBoolean("ok"));
            assertTrue(n, r.getString("error").contains("off limits"));
        }
        assertFalse(d.run("open_app", new JSONObject().put("name", "不存在的App")).getBoolean("ok"));
        assertFalse(d.run("open_app", new JSONObject()).getBoolean("ok"));
        assertEquals(Arrays.asList("com.tencent.mm", "com.netease.cloudmusic"), p.launched);
    }

    @Test public void appGuardTable() {
        assertTrue(AppGuard.blocked("com.icbc", "中国工商银行", null));
        assertTrue(AppGuard.blocked("com.foo.bar", "XX 银行", null));
        assertTrue(AppGuard.blocked("com.foo.bar", "1Password", null));
        assertTrue(AppGuard.blocked("com.foo.walletapp", "Foo", null));
        assertTrue(AppGuard.blocked("", "x", null));
        assertTrue(AppGuard.blocked("me.pkg", "x", "me.pkg"));
        assertFalse(AppGuard.blocked("com.tencent.mm", "微信", "me.pkg"));
        assertFalse(AppGuard.blocked("com.android.chrome", "Chrome", "me.pkg"));
        assertFalse(AppGuard.blocked("com.spotify.music", "Spotify", "me.pkg"));
    }

    // ================================================================== 长轮询

    VyrseLink link(VyrseFakes.Http h, VyrseFakes.Phone p, VyrseFakes.Sleeper s, VyrseFakes.Listener l) {
        return new VyrseLink(h, BASE, TOKEN, new VyrseDispatcher(p), s, l);
    }

    @Test public void pollExecutesAndPostsResultsWithKeyHeader() throws Exception {
        VyrseFakes.Http h = new VyrseFakes.Http().then(200, VyrseFakes.commands(
                "c1", "flashlight", new JSONObject().put("state", "on"),
                "c2", "tap", new JSONObject().put("n", 3),
                "c3", "battery", null));
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        VyrseFakes.Listener l = new VyrseFakes.Listener();
        VyrseLink k = link(h, p, new VyrseFakes.Sleeper(0), l);
        assertEquals(3, k.pollOnce());
        assertEquals(BASE + "/phone/commands", h.reqs.get(0).url);
        assertEquals("GET", h.reqs.get(0).method);
        for (VyrseFakes.Http.Req q : h.reqs) assertEquals("每个请求都带本机 key", TOKEN, q.token);
        assertEquals(3, h.results().size());
        JSONObject r1 = new JSONObject(h.results().get(0).body);
        assertEquals("c1", r1.getString("id"));
        assertEquals("on", r1.getJSONObject("result").getString("flashlight"));
        JSONObject r2 = new JSONObject(h.results().get(1).body);
        assertTrue(r2.getJSONObject("result").getBoolean("unsupported"));
        assertEquals(Arrays.asList("flashlight:true", "tap:false", "battery:true"), l.cmds);
        assertEquals(VyrseLink.State.ONLINE, k.state());
        assertFalse("监听回调不含参数", l.cmds.toString().contains("state"));
    }

    @Test public void sameCommandIdRunsOnceAndPerPollCap() throws Exception {
        Object[] many = new Object[25 * 3];
        for (int i = 0; i < 25; i++) {
            many[i * 3] = "id" + i;
            many[i * 3 + 1] = "battery";
        }
        VyrseFakes.Http h = new VyrseFakes.Http()
                .then(200, VyrseFakes.commands("dup", "flashlight", new JSONObject().put("state", "toggle")))
                .then(200, VyrseFakes.commands("dup", "flashlight", new JSONObject().put("state", "toggle")))
                .then(200, VyrseFakes.commands(many));
        VyrseFakes.Phone p = new VyrseFakes.Phone();
        VyrseLink k = link(h, p, new VyrseFakes.Sleeper(0), null);
        assertEquals(1, k.pollOnce());
        assertEquals("重复 id 不再执行", 0, k.pollOnce());
        assertEquals(Boolean.TRUE, p.torch);
        assertEquals("每轮最多 20 条", 20, k.pollOnce());
    }

    @Test public void unauthorizedStopsLoopAndReports() throws Exception {
        VyrseFakes.Http h = new VyrseFakes.Http().then(401, "{\"error\":\"This phone isn’t paired (or was removed). Pair it again.\"}");
        VyrseFakes.Listener l = new VyrseFakes.Listener();
        VyrseFakes.Sleeper s = new VyrseFakes.Sleeper(10);
        VyrseLink k = link(h, new VyrseFakes.Phone(), s, l);
        k.run();
        assertEquals(1, l.unauthorized);
        assertEquals(VyrseLink.State.UNAUTHORIZED, k.state());
        assertTrue("401 不重试", s.slept.isEmpty());
        assertEquals(1, h.reqs.size());
    }

    @Test public void backoffDoublesToSixtySecondsAndResets() throws Exception {
        VyrseFakes.Http h = new VyrseFakes.Http();
        for (int i = 0; i < 7; i++) h.thenFail();
        h.then(500, "x");
        h.then(200, "{\"commands\":[]}");
        h.thenFail();
        VyrseFakes.Listener l = new VyrseFakes.Listener();
        VyrseFakes.Sleeper s = new VyrseFakes.Sleeper(10);
        VyrseLink k = link(h, new VyrseFakes.Phone(), s, l);
        k.run();
        assertEquals(Arrays.asList(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 60000L, 500L, 2000L, 4000L), s.slept);
        assertTrue(l.states.contains(VyrseLink.State.OFFLINE));
        assertTrue(l.states.contains(VyrseLink.State.ONLINE));
        assertEquals(VyrseLink.State.STOPPED, k.state());
    }

    @Test public void stopPreventsFurtherCommandsAndBadBaseRejected() throws Exception {
        VyrseFakes.Http h = new VyrseFakes.Http().then(200, VyrseFakes.commands("a", "battery", null, "b", "battery", null));
        VyrseLink k = link(h, new VyrseFakes.Phone(), new VyrseFakes.Sleeper(0), null);
        k.stop();
        assertEquals(0, k.pollOnce());
        try {
            new VyrseLink(h, "http://8.8.8.8:47630", TOKEN, new VyrseDispatcher(new VyrseFakes.Phone()), null, null);
            fail("公网地址不许连");
        } catch (IllegalArgumentException expected) {
        }
        try {
            new VyrseLink(h, BASE, "", new VyrseDispatcher(new VyrseFakes.Phone()), null, null);
            fail("没 key 不连");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test public void garbageFromPcIsAFailureNotACrash() throws Exception {
        VyrseFakes.Http h = new VyrseFakes.Http().then(200, "<html>").then(200, "{\"commands\":[1,null,{\"action\":\"battery\"},{\"id\":\"x\"}]}");
        VyrseLink k = link(h, new VyrseFakes.Phone(), new VyrseFakes.Sleeper(0), null);
        try {
            k.pollOnce();
            fail();
        } catch (java.io.IOException expected) {
        }
        assertEquals("没 id 的跳过；没 action 的当未知动作回失败", 1, k.pollOnce());
        assertTrue(h.results().get(0).body.contains("Unknown phone action"));
    }
}
