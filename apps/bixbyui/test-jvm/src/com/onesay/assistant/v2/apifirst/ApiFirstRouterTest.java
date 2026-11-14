package com.onesay.assistant.v2.apifirst;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ★ feat/api-first：路由选择顺序（① 系统接口 → ② 已配置接口 → ③ 深链 / 浏览器 → ④ 点界面）、失败回落、
 * 付款意图只到深链（绝不进点界面）、开源版默认关已配置接口、深链填值与清单。
 */
public class ApiFirstRouterTest {

    /** 可配置的假环境。 */
    static final class FakeEnv implements ApiFirstRouter.Env {
        final Set<String> system = new HashSet<String>();
        boolean keyed = true;
        final Set<String> keys = new HashSet<String>();
        final Set<String> installed = new HashSet<String>();
        boolean gui = true;

        @Override public boolean systemAvailable(String kind) { return system.contains(kind); }
        @Override public boolean keyedEnabled() { return keyed; }
        @Override public boolean hasKey(String api) { return keys.contains(api); }
        @Override public boolean installed(String pkg) { return installed.contains(pkg); }
        @Override public boolean guiAvailable() { return gui; }
    }

    /** 记下每一次尝试；fail 里的路一律失败。 */
    static final class FakeRunner implements ApiFirstRouter.Runner {
        final List<String> calls = new ArrayList<String>();
        final Set<String> fail = new HashSet<String>();
        boolean stopAfterFirst;
        boolean stopped;

        private ApiFirstRouter.Attempt res(String what, String data) {
            calls.add(what);
            if (stopAfterFirst) stopped = true;
            return fail.contains(what) ? ApiFirstRouter.Attempt.fail("假失败 " + what) : ApiFirstRouter.Attempt.ok(data);
        }

        @Override public ApiFirstRouter.Attempt system(ApiIntent in) { return res("system", "ok"); }
        @Override public ApiFirstRouter.Attempt keyed(String api, ApiIntent in) { return res("keyed:" + api, "结果"); }
        @Override public ApiFirstRouter.Attempt open(DeepLinks.Link link, String uri) { return res("open:" + link.id, ""); }
        @Override public ApiFirstRouter.Attempt gui(ApiIntent in) { return res("gui", "点完了"); }
        @Override public boolean stopped() { return stopped; }
    }

    private static FakeEnv personal() {
        FakeEnv e = new FakeEnv();
        e.keys.addAll(Arrays.asList(ApiFirstRouter.API_QWEATHER, ApiFirstRouter.API_TAVILY, ApiFirstRouter.API_HAINA));
        e.installed.addAll(Arrays.asList("com.autonavi.minimap", "com.baidu.BaiduMap", "com.taobao.taobao", "com.jingdong.app.mall",
                "com.MobileTicket", "ctrip.android.view", "com.sankuai.meituan", "me.ele"));
        e.system.addAll(Arrays.asList(ApiIntent.ALARM_SET, ApiIntent.TIMER_SET, ApiIntent.CALENDAR_ADD, ApiIntent.SETTINGS_OPEN, ApiIntent.CONTACT_FIND));
        return e;
    }

    // ------------------------------------------------------------------ 选择顺序

    @Test public void systemApiFirstThenGui() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.ALARM_SET, "time", "7:30"), personal());
        assertEquals(Arrays.asList(RouteTier.SYSTEM, RouteTier.GUI), p.tiers());
        FakeRunner r = new FakeRunner();
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, r);
        assertTrue(o.ok);
        assertEquals(RouteTier.SYSTEM, o.tier);
        assertEquals(Arrays.asList("system"), r.calls);   // 系统接口成功就不再点界面
    }

    @Test public void keyedBeforeDeeplinkBeforeBrowserBeforeGui() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.GOODS_SEARCH, "q", "降噪耳机"), personal());
        assertEquals(Arrays.asList(RouteTier.KEYED_API, RouteTier.DEEPLINK, RouteTier.DEEPLINK, RouteTier.BROWSER, RouteTier.BROWSER, RouteTier.GUI), p.tiers());
        // 档位序号单调不减
        int last = 0;
        for (RouteTier t : p.tiers()) {
            assertTrue(t.rank >= last);
            last = t.rank;
        }
        FakeRunner r = new FakeRunner();
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, r);
        assertEquals(RouteTier.KEYED_API, o.tier);
        assertEquals("海纳商品", o.via);
        assertEquals(Arrays.asList("keyed:haina"), r.calls);
    }

    @Test public void fallbackWalksDownInOrderAndTracesWhy() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.GOODS_SEARCH, "q", "降噪耳机"), personal());
        FakeRunner r = new FakeRunner();
        r.fail.addAll(Arrays.asList("keyed:haina", "open:taobao.search", "open:jd.search"));
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, r);
        assertTrue(o.ok);
        assertEquals(RouteTier.BROWSER, o.tier);
        assertEquals(Arrays.asList("keyed:haina", "open:taobao.search", "open:jd.search", "open:web.taobao.search"), r.calls);
        assertTrue(o.uri.startsWith("https://s.taobao.com/search?q="));
        String trace = o.trace.toString();
        assertTrue(trace, trace.contains("②已配置接口 海纳商品：失败（假失败 keyed:haina），换下一条"));
        assertTrue(trace, trace.contains("③App 深链 淘宝：失败"));
        assertTrue(trace, trace.contains("③浏览器链接 淘宝网页：已打开"));
        assertTrue(trace, trace.contains("①系统接口：这类事没有系统接口，跳过"));
        assertFalse("日志不带密钥", ApiFirstRouter.logLine(o).toLowerCase().contains("key="));
    }

    @Test public void allFailFallsToGuiLast() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.NAV_TO, "dest", "广州南站"), personal());
        assertEquals(Arrays.asList(RouteTier.DEEPLINK, RouteTier.DEEPLINK, RouteTier.BROWSER, RouteTier.GUI), p.tiers());
        FakeRunner r = new FakeRunner();
        r.fail.addAll(Arrays.asList("open:amap.route", "open:baidu.route", "open:web.amap.search.dest"));
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, r);
        assertTrue(o.ok);
        assertEquals(RouteTier.GUI, o.tier);
        assertEquals(4, r.calls.size());
        assertEquals("gui", r.calls.get(3));
    }

    @Test public void notInstalledAndMissingSlotSkippedWithReason() {
        FakeEnv e = personal();
        e.installed.remove("com.autonavi.minimap");
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.NAV_TO, "dest", "广州南站"), e);
        assertEquals(RouteTier.DEEPLINK, p.steps.get(0).tier);
        assertEquals("百度地图", p.steps.get(0).link.app);
        assertTrue(p.notes.toString(), p.notes.contains("③App 深链 高德地图：没装，跳过"));
        ApiFirstRouter.Plan p2 = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.NAV_TO), e);
        assertTrue(p2.notes.toString(), p2.notes.toString().contains("缺少「目的地」"));
        assertEquals(Arrays.asList(RouteTier.GUI), p2.tiers());
    }

    @Test public void userNamedAppGoesFirst() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.NAV_TO, "dest", "广州南站", "app", "百度地图"), personal());
        assertEquals("百度地图", p.steps.get(0).link.app);
        assertEquals("高德地图", p.steps.get(1).link.app);
        assertEquals(RouteTier.BROWSER, p.steps.get(2).tier);   // 浏览器链接永远在 App 深链后面
    }

    @Test public void noKeyOrOssSkipsKeyedTier() {
        FakeEnv e = personal();
        e.keys.clear();
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.WEATHER, "city", "江门"), e);
        assertFalse(p.tiers().contains(RouteTier.KEYED_API));
        assertTrue(p.notes.toString(), p.notes.contains("②已配置接口：和风天气 没配密钥，跳过"));
        assertEquals(RouteTier.BROWSER, p.steps.get(0).tier);

        FakeEnv oss = personal();
        oss.keyed = false;   // 开源版：已配置接口这一档默认关，有密钥也跳过
        ApiFirstRouter.Plan p2 = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.WEB_SEARCH, "q", "OneSay"), oss);
        assertFalse(p2.tiers().contains(RouteTier.KEYED_API));
        assertTrue(p2.notes.toString(), p2.notes.contains("②已配置接口：Tavily 搜索 这一档没打开，跳过"));
    }

    @Test public void systemUnavailableFallsBack() {
        FakeEnv e = personal();
        e.system.remove(ApiIntent.CALENDAR_ADD);
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.CALENDAR_ADD, "title", "体检", "time", "9:00"), e);
        assertEquals(Arrays.asList(RouteTier.GUI), p.tiers());
        assertTrue(p.notes.contains("①系统接口：这台机器上没有能处理的应用，跳过"));
    }

    @Test public void stopHaltsBeforeNextAttempt() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.NAV_TO, "dest", "广州南站"), personal());
        FakeRunner r = new FakeRunner();
        r.fail.add("open:amap.route");
        r.stopAfterFirst = true;
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, r);
        assertFalse(o.ok);
        assertTrue(o.stopped);
        assertEquals(1, r.calls.size());
        assertEquals("已停止", o.trace.get(o.trace.size() - 1));
    }

    // ------------------------------------------------------------------ 付款意图：只到深链

    @Test public void paymentNeverReachesGui() {
        for (String kind : ApiIntent.PAYMENT_KINDS) {
            ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(kind, "q", "牛肉面", "from", "江门", "to", "上海", "date", "2026-11-12"), personal());
            assertFalse(kind + " 计划里不能有点界面", p.tiers().contains(RouteTier.GUI));
            assertTrue(kind, p.notes.contains("④界面操作：涉及付款，不进界面自动化（最后一下得你亲手点）"));
            FakeRunner r = new FakeRunner();
            for (ApiFirstRouter.Step s : p.steps) if (s.link != null) r.fail.add("open:" + s.link.id);
            ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, r);
            assertFalse(kind, o.ok);
            assertFalse(kind + " 全失败也不点界面", r.calls.contains("gui"));
            assertEquals(ApiFirstRouter.CODE_PAY_USER_TAP, o.code);
            assertTrue(kind + " " + o.handoff, o.handoff.contains("确认并付款"));
        }
    }

    @Test public void trainBookOpensAppAndHandsOff() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.TRAIN_BOOK, "from", "江门", "to", "上海", "date", "2026-11-12"), personal());
        FakeRunner r = new FakeRunner();
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, r);
        assertTrue(o.ok);
        assertEquals(RouteTier.DEEPLINK, o.tier);
        assertEquals("铁路12306", o.via);
        assertEquals("launch:com.MobileTicket", o.uri);
        assertEquals("需要你在铁路12306里确认车次并付款", o.handoff);
        assertEquals(ApiFirstRouter.CODE_PAY_USER_TAP, o.code);
        JSONObject j = o.toJson();
        assertFalse("launch: 不当网址报给模型", j.has("opened"));
        assertEquals("需要你在铁路12306里确认车次并付款", j.optString("handoff"));
    }

    @Test public void foodOrderStopsAtDeeplink() {
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(
                ApiFirstRouter.plan(ApiIntent.of(ApiIntent.FOOD_ORDER, "q", "牛肉面"), personal()), new FakeRunner());
        assertEquals("美团", o.via);
        assertTrue(o.uri.startsWith("imeituan://www.meituan.com/search?q="));
        assertEquals("需要你在美团里选好菜、确认订单并付款", o.handoff);
    }

    @Test public void trainQueryUsesSearchAndOnlyGivesBookingLinks() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of(ApiIntent.TRAIN_QUERY, "from", "江门", "to", "上海", "date", "2026-11-12"), personal());
        assertEquals(Arrays.asList(RouteTier.KEYED_API, RouteTier.BROWSER), p.tiers());   // 查询不点 App，也不开 App 深链
        FakeRunner r = new FakeRunner();
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, r);
        assertEquals(RouteTier.KEYED_API, o.tier);
        assertEquals(Arrays.asList("keyed:tavily"), r.calls);
        assertEquals("订票需要你在 12306 / 携程 / 同程里确认车次并付款", o.handoff);
        boolean web = false;
        for (String[] l : o.links) if (l[1].startsWith("https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc&fs=%E6%B1%9F%E9%97%A8")) web = true;
        assertTrue(web);
        JSONArray bl = o.toJson().optJSONArray("booking_links");
        assertNotNull(bl);
        assertTrue(bl.length() >= 3);
    }

    @Test public void unknownIntentIsNotRouted() {
        ApiFirstRouter.Plan p = ApiFirstRouter.plan(ApiIntent.of("rocket.launch"), personal());
        assertTrue(p.steps.isEmpty());
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(p, new FakeRunner());
        assertFalse(o.ok);
        assertEquals("unknown_intent", o.code);
    }

    // ------------------------------------------------------------------ 深链清单 / 填值

    @Test public void deeplinkFillEncodes() {
        DeepLinks.Link amap = DeepLinks.byId("amap.route");
        assertEquals("amapuri://route/plan/?sourceApplication=onesay&dname=%E5%B9%BF%E5%B7%9E%E5%8D%97%E7%AB%99&dev=0&t=0",
                amap.fill(ApiIntent.of(ApiIntent.NAV_TO, "dest", "广州南站")));
        DeepLinks.Link jd = DeepLinks.byId("jd.search");
        String u = jd.fill(ApiIntent.of(ApiIntent.GOODS_SEARCH, "q", "a\"b c"));
        assertTrue(u, u.startsWith("openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22"));
        assertTrue("引号转义 + 空格 %20", u.contains("a%5C%22b%20c"));
        assertFalse(u.contains(" "));
        assertEquals("launch:me.ele", DeepLinks.byId("eleme.app").fill(ApiIntent.of(ApiIntent.FOOD_ORDER)));
    }

    @Test public void deeplinkTableIsSane() {
        Set<String> ids = new HashSet<String>();
        for (DeepLinks.Link l : DeepLinks.ALL) {
            assertTrue("id 唯一 " + l.id, ids.add(l.id));
            assertFalse(l.id, l.kinds.isEmpty());
            for (String k : l.kinds) assertTrue(l.id + " 种类认识 " + k, ApiIntent.KINDS.contains(k));
            assertTrue(l.id + " 写明来源", l.source.length() > 0);
            if (l.browser()) assertTrue(l.id + " 浏览器兜底只用 https", l.template.startsWith("https://"));
            else assertFalse(l.id + " App 深链不是 http", l.template.startsWith("http"));
            if (!l.verified) assertTrue(l.id + " 拿不准的要写未验证 / 未实测 / 推断", l.source.contains("未验证") || l.source.contains("未实测"));
            for (String bad : new String[]{"Bixby", "bixby", "三星", "Samsung"}) {
                assertFalse(l.id, l.app.contains(bad) || l.purpose.contains(bad) || l.template.contains(bad));
            }
        }
        // 每个深链种类都有浏览器兜底（火车只开 App 首页时有 12306 网页兜底）
        for (String k : new String[]{ApiIntent.NAV_TO, ApiIntent.POI_NEARBY, ApiIntent.GOODS_SEARCH, ApiIntent.SHOP_BUY, ApiIntent.FOOD_ORDER, ApiIntent.TRAIN_BOOK}) {
            List<DeepLinks.Link> ls = DeepLinks.forKind(k);
            assertTrue(k, ls.get(ls.size() - 1).browser());
        }
    }

    // ------------------------------------------------------------------ 工具 JSON / 参数

    @Test public void toolJsonAndParse() throws Exception {
        JSONArray tools = new JSONArray();
        RouteTool.append(tools);
        RouteTool.append(tools);   // 同名不重复
        assertEquals(1, tools.length());
        JSONObject fn = tools.getJSONObject(0).getJSONObject("function");
        assertEquals("route__open", fn.getString("name"));
        assertTrue(fn.getString("description").contains("付款必须用户自己点"));
        assertEquals(ApiIntent.KINDS.size(), fn.getJSONObject("parameters").getJSONObject("properties").getJSONObject("intent").getJSONArray("enum").length());
        ApiIntent in = RouteTool.parse(new JSONObject("{\"intent\":\"NAV.TO\",\"dest\":\" 广州南站 \",\"app\":\"\"}"));
        assertEquals(ApiIntent.NAV_TO, in.kind);
        assertEquals("广州南站", in.slot("dest"));
        assertFalse(in.has("app"));
        assertTrue(RouteTool.isSign("route__open"));
        assertTrue(RouteTool.isSign("route.open"));
        assertEquals(0, RouteTool.capability().risk);
    }

    @Test public void systemParamHelpers() {
        assertEquals("android.settings.WIFI_SETTINGS", RouteTool.settingsAction("WiFi"));
        assertEquals("android.settings.BLUETOOTH_SETTINGS", RouteTool.settingsAction("蓝牙"));
        assertEquals("android.settings.SETTINGS", RouteTool.settingsAction(""));
        assertEquals(7, RouteTool.parseTime("7:30")[0]);
        assertEquals(30, RouteTool.parseTime("7点半")[1]);
        assertEquals(19, RouteTool.parseTime("19点")[0]);
        assertNull(RouteTool.parseTime("25:00"));
        assertEquals(300, RouteTool.timerSeconds("5分钟"));
        assertEquals(-1, RouteTool.timerSeconds("abc"));
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Asia/Shanghai");
        long t = RouteTool.epochMillis("2026-11-12", "9:00", tz, 0L);
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        assertEquals(2026, c.get(java.util.Calendar.YEAR));
        assertEquals(9, c.get(java.util.Calendar.HOUR_OF_DAY));
        assertEquals(-1, RouteTool.epochMillis("2026-11-12", null, tz, 0L));
    }
}
