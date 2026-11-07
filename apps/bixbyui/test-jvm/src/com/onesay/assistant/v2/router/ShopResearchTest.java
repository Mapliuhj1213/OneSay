package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.errand.ErrandIntent;
import com.onesay.assistant.v2.errand.ErrandPrompt;
import com.onesay.assistant.v2.errand.ResearchLadder;
import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.TavilyExtractProvider;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * ★ fix/shop-research：RESEARCH-TEST-basilisk（2026-11-03）的修复回归。
 * 回归句「帮我买雷蛇巴塞利斯蛇v3」：购物路由、多平台、版本推荐、≥2 个带来源与日期的价格、停在付款前、不问预算；
 * 「你决定 / 好」接回同一任务；误触负例（买单 / 买菜谱 / 已买 / 票务）。
 */
public class ShopResearchTest {

    static final String BASILISK = "帮我买雷蛇巴塞利斯蛇v3";

    @After public void tearDown() {
        TaskContinuation.clear();
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(rel).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    // ------------------------------------------------------------------ 1 意图

    @Test public void buyIsShopAndCompareMultiPlatform() {
        ErrandIntent in = ErrandIntent.parse(BASILISK);
        assertEquals(ErrandIntent.Kind.SHOP, in.kind);
        assertTrue("没点名网站 → 比价调研", in.compare);
        assertEquals("雷蛇巴塞利斯蛇v3", in.query);
        assertTrue("至少京东 + 天猫 / 淘宝", in.sites.size() >= 2);
        assertEquals(ErrandIntent.JD, in.sites.get(0));
        assertTrue(in.sites.contains(ErrandIntent.TMALL) || in.sites.contains(ErrandIntent.TAOBAO));
        assertTrue(ErrandIntent.DEFAULT_SHOPS.size() >= 2);
    }

    @Test public void buyVariants() {
        String[][] cases = {
                {"我想入手索尼WH-1000XM5", "索尼WH-1000XM5"},
                {"下单一台戴森吹风机", "戴森吹风机"},
                {"想买个机械键盘", "机械键盘"},
                {"给我买一箱牛奶", "牛奶"},
                {"买个罗技G304", "罗技G304"},
        };
        for (String[] c : cases) {
            ErrandIntent in = ErrandIntent.parse(c[0]);
            assertEquals(c[0], ErrandIntent.Kind.SHOP, in.kind);
            assertTrue(c[0], in.compare);
            assertTrue(c[0] + " → " + in.query, in.query.contains(c[1]));
        }
    }

    @Test public void buyFalseTriggers() {
        String[] neg = {"今天谁买单", "帮我买单", "推荐几本买菜谱", "帮我买菜谱上那个酱", "我买的快递到哪了", "那个键盘你买了吗", "我下单了吗",
                "帮我买张去上海的火车票", "帮我买两张电影票", "帮我充值话费", "买", "帮我买", "我想买点东西", "这个值得买吗", "把亮度调到一半"};
        for (String s : neg) {
            assertFalse("不该算购物：" + s, ErrandIntent.isBuy(s));
            assertFalse("不该走购物跑腿：" + s, ErrandIntent.parse(s).kind == ErrandIntent.Kind.SHOP);
        }
    }

    @Test public void namedSiteAndCartKeepOldFlow() {
        ErrandIntent a = ErrandIntent.parse("在京东买一箱牛奶");
        assertEquals(ErrandIntent.Kind.SHOP, a.kind);
        assertFalse("点名网站 → 进站流程，不是多平台比价", a.compare);
        assertEquals(Arrays.asList(ErrandIntent.JD), a.sites);
        ErrandIntent b = ErrandIntent.parse("帮我把 AirPods Pro 2 加入购物车");
        assertFalse(b.compare);
        // 点名 App 仍不接（不做第三方 App 自动化）
        assertFalse(ErrandIntent.parse("用京东客户端帮我买牙膏").isErrand());
    }

    @Test public void askOnceOnlyWhenShopAppInstalled() throws Exception {
        String s = src("app/src/com/onesay/assistant/v2/askonce/AskOnceHooks.java");
        assertTrue("没装购物 App → 不弹首问芯片", s.contains("isShop(ctx, q) && anyShopApp(ctx)) return AskOnce.askShop(q)"));
        for (String p : new String[]{"com.jingdong.app.mall", "com.taobao.taobao", "com.tmall.wireless", "com.xunmeng.pinduoduo"}) assertTrue(s.contains(p));
    }

    // ------------------------------------------------------------------ 2 AgentLoop 工具

    @Test public void shopKindAndFallbackTools() {
        assertEquals(ResearchGate.Kind.SHOP, ResearchGate.kindOf(BASILISK));
        assertEquals(ResearchGate.Kind.SHOP, ResearchGate.kindOf("iPhone 16 京东多少钱"));
        assertEquals(ResearchGate.Kind.SHOP, ResearchGate.kindOf("帮我比价一下戴森吹风机"));
        assertEquals(ResearchGate.Kind.NONE, ResearchGate.kindOf("把亮度调到一半"));
        assertEquals(ResearchGate.Kind.NONE, ResearchGate.kindOf("茅台股价多少"));
        assertEquals(ResearchGate.Kind.NONE, ResearchGate.kindOf("今天谁买单"));
        assertTrue(Arrays.asList(ResearchGate.SHOP_FALLBACK_SIGNS).contains("browser.open"));
    }

    @Test public void retrieverGivesNoBrightnessToShopping() {
        List<Capability> c = CapabilityRetriever.getTopKTiered(BASILISK, 40);
        for (Capability x : c) {
            assertFalse("购物句不该配 " + x.sign, x.sign.startsWith("settings.brightness") || x.sign.startsWith("volume."));
        }
        // 正例不受影响
        boolean bright = false;
        for (Capability x : CapabilityRetriever.getTopKTiered("把屏幕亮度调到最低", 40)) if (x.sign.startsWith("settings.brightness")) bright = true;
        assertTrue(bright);
        // 纯函数
        List<Capability> l = new ArrayList<Capability>();
        l.add(cap("settings.brightness.set"));
        l.add(cap("volume.media.up"));
        l.add(cap("browser.open"));
        List<Capability> out = CapabilityRetriever.dropIrrelevant(l, "帮我买个机械键盘");
        assertEquals(1, out.size());
        assertEquals("browser.open", out.get(0).sign);
        assertEquals(3, CapabilityRetriever.dropIrrelevant(l, "打开蓝牙").size());
    }

    static Capability cap(String sign) {
        return new Capability.Builder(sign, sign).schema(new com.onesay.assistant.v2.capability.ParamsSchema()).executor("intent").build();
    }

    @Test public void browserOpenIsAViewIntent() throws Exception {
        String s = src("app/src/com/onesay/assistant/v2/capability/caps/AppCaps.java");
        assertTrue(s.contains("am start -a android.intent.action.VIEW -d {url}"));
        String loop = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertTrue(loop.contains("ResearchGate.SHOP_FALLBACK_SIGNS"));
        assertTrue(loop.contains("shopOffer(loopOffer(cb), gateKind)"));
    }

    @Test public void consecutiveIdenticalCallsDeduped() throws Exception {
        List<AgentLoop.Step> steps = new ArrayList<AgentLoop.Step>();
        JSONObject a = new JSONObject().put("filter", "shop");
        steps.add(new AgentLoop.Step("probe.apps", a, "{\"ok\":true,\"apps\":[]}", true, false));
        String o = AgentLoop.repeatObservation(steps, "probe.apps", new JSONObject().put("filter", "shop"));
        assertNotNull(o);
        assertTrue(o.contains("\"deduped\":true"));
        assertNull("参数不同 → 照常执行", AgentLoop.repeatObservation(steps, "probe.apps", new JSONObject().put("filter", "jd")));
        assertNull("有副作用的工具不去重（「再大一点」可以连按）", AgentLoop.repeatObservation(
                Arrays.asList(new AgentLoop.Step("volume.media.up", new JSONObject(), "{}", true, false)), "volume.media.up", new JSONObject()));
        assertNotNull(AgentLoop.repeatObservation(Arrays.asList(new AgentLoop.Step("web.search", new JSONObject().put("query", "x"), "{}", true, false)),
                "web.search", new JSONObject().put("query", "x")));
    }

    // ------------------------------------------------------------------ 3 技能

    @Test public void shopSkillRewritten() throws Exception {
        String s = src("app/assets/skill_shop_pay.md");
        assertFalse("不问预算", s.contains("预算上限"));
        for (String must : new String[]{"不问预算", "默认推荐一款", "你决定", "至少 2 个平台", "web.read", "自营", "官方旗舰", "授权", "专卖", "日期", "券后",
                "规格对不上", "新一代", "大促", "绝不付款", "browser.open"}) {
            assertTrue("skill_shop_pay 缺「" + must + "」", s.contains(must));
        }
        String p = ErrandPrompt.shopSystem();
        for (String must : new String[]{"不问预算", "默认推荐一款", "至少列出 2 个平台", "店铺类型", "数据日期", "券后", "规格存疑", "新一代", "下单付款需要你自己来", "绝不提议替用户付款"}) {
            assertTrue("比价提示词缺「" + must + "」", p.contains(must));
        }
        assertEquals(ErrandPrompt.shopSystem(), ErrandPrompt.answerSystem(ErrandIntent.parse(BASILISK)));
        assertEquals(ErrandPrompt.answerSystem(), ErrandPrompt.answerSystem(ErrandIntent.parse("帮我调研一下扫地机器人")));
    }

    // ------------------------------------------------------------------ 回归句：帮我买雷蛇巴塞利斯蛇v3（跑腿多平台比价，假的搜索 / 读网页）

    static final class Rec implements ResearchLadder.Searcher, ResearchLadder.Extractor, ResearchLadder.Summarizer, ResearchLadder.Browser {
        final List<String> queries = new ArrayList<String>();
        List<String> extracted = new ArrayList<String>();
        String material;
        ErrandIntent summarized;
        boolean browsed;
        final boolean pagesHavePrices;

        Rec(boolean pagesHavePrices) {
            this.pagesHavePrices = pagesHavePrices;
        }

        @Override public List<SearchResult> search(String q) {
            queries.add(q);
            List<SearchResult> r = new ArrayList<SearchResult>();
            if (q.contains("京东")) r.add(new SearchResult("雷蛇巴塞利斯蛇V3专业版35K 京东", "https://item.jd.com/100123.html", "京东 雷蛇巴塞利斯蛇 V3 专业版 35K 无线鼠标"));
            if (q.contains("天猫")) r.add(new SearchResult("雷蛇巴塞利斯蛇V3 天猫雷蛇数码旗舰店", "https://detail.tmall.com/item.htm?id=1", "天猫 雷蛇数码旗舰店 巴塞利斯蛇 V3"));
            if (q.contains("比价")) r.add(new SearchResult("雷蛇V3 Pro 35K 比价 历史价格", "https://compare.fenxianglife.com/p/razer-v3-35k.html", "京东 天猫 各店价格 历史最低"));
            r.add(new SearchResult("雷蛇巴塞利斯蛇V3 评测", "https://www.ithome.com/0/799/836.htm", "巴塞利斯蛇 V3 35K 有线 599 元，V3 专业版 35K 1199 元"));
            return r;
        }

        @Override public boolean ready() {
            return true;
        }

        @Override public List<TavilyExtractProvider.Page> extract(List<String> urls, String q) {
            extracted = new ArrayList<String>(urls);
            List<TavilyExtractProvider.Page> p = new ArrayList<TavilyExtractProvider.Page>();
            for (String u : urls) {
                String body = pagesHavePrices
                        ? "截至 2026-11-03：京东 雷蛇游戏外设旗舰店 V3 专业版 35K 到手 439.12 元（叠券）；京东自营旗舰店 527.12 元；天猫 雷蛇数码旗舰店 849 元。V3 X 极速版 245.52 元。"
                        : "这是一段没有价格的页面文字，只介绍了外观和手感。";
                StringBuilder sb = new StringBuilder(body);
                while (sb.length() < 400) sb.append("巴塞利斯蛇系列介绍。");
                p.add(new TavilyExtractProvider.Page(u, sb.toString(), null));
            }
            return p;
        }

        @Override public String answer(ErrandIntent intent, String mat) {
            summarized = intent;
            material = mat;
            return "推荐 V3 专业版 35K。- 京东 授权旗舰 439.12 元（2026-11-03，叠券）[1]\n- 天猫 雷蛇数码旗舰店 849 元 [2]\n我停在下单前，下单付款需要你自己来。";
        }

        @Override public boolean available() {
            return true;
        }

        @Override public com.onesay.assistant.v2.errand.BrowseResult browse(ErrandIntent intent, String startUrl, ResearchLadder.Progress progress) {
            browsed = true;
            return com.onesay.assistant.v2.errand.BrowseResult.failed("不该进浏览器");
        }
    }

    @Test public void basiliskRegressionMultiPlatformPricesStopBeforePay() {
        Rec r = new Rec(true);
        ResearchLadder.Outcome o = new ResearchLadder(r, r, r, null, r).run(ErrandIntent.parse(BASILISK), null);
        assertTrue(o.toString(), o.ok);
        assertFalse("比价不进 WebView 加购物车", r.browsed);
        assertNull("没有结算页 / 付款交接", o.browseStatus);
        assertNull(o.handoffUrl);
        // 多平台搜索：京东 + 天猫（+ 拼多多 / 淘宝）+ 比价站
        String qs = r.queries.toString();
        assertTrue(qs, qs.contains("京东") && qs.contains("天猫") && qs.contains("比价"));
        assertFalse("不问预算", qs.contains("预算"));
        // 读了原网页（比价站排前面）
        assertFalse(r.extracted.isEmpty());
        assertTrue(r.extracted.toString(), r.extracted.get(0).contains("fenxianglife") || r.extracted.get(0).contains("jd.com"));
        assertTrue("≥2 个站点 + ≥2 个平台 + 读过的原文有价格", ResearchLadder.shopEnough(o.sources));
        assertTrue(ResearchLadder.sitesCovered(o.sources) >= 2);
        assertTrue(ResearchLadder.platformsCovered(o.sources) >= 2);
        assertTrue(o.note, o.note.startsWith("多平台核价"));
        // 资料：原网页标注、带来源链接、带日期的价格
        assertTrue(r.material.contains("（原网页）"));
        assertTrue(r.material.contains("来源：https://"));
        assertTrue(r.material.contains("2026-11-03") && r.material.contains("439.12 元"));
        // 结论：≥2 个价格、停在付款前
        assertTrue(ResearchGate.priceCount(o.answer) >= 2);
        assertTrue(o.answer.contains("下单前"));
        assertEquals(ErrandPrompt.SHOP_ANSWER_MAX_TOKENS, ErrandPrompt.maxTokens(r.summarized));
        assertTrue(ErrandPrompt.answerUser(r.summarized, "x").contains("今天："));
    }

    @Test public void basiliskWithoutPricesIsMarkedIncomplete() {
        Rec r = new Rec(false);
        ResearchLadder.Outcome o = new ResearchLadder(r, r, r, null, r).run(ErrandIntent.parse(BASILISK), null);
        assertFalse(ResearchLadder.shopEnough(o.sources));
        assertTrue(o.note, o.note.startsWith("比价资料不完整"));
        assertFalse(r.browsed);
    }

    // ------------------------------------------------------------------ 4 完成门槛

    @Test public void finishGateNeedsTwoSitesAndOneRead() throws Exception {
        ResearchGate.Evidence ev = new ResearchGate.Evidence();
        ev.onStep("web.search", new JSONObject().put("query", "x"), "{\"results\":[{\"url\":\"https://item.jd.com/1.html\"}]}", true);
        ev.onStep("probe.apps", new JSONObject(), "{}", true);
        String a1 = "雷蛇巴塞利斯蛇 V3 在京东有多个版本，来源是京东商品页（jd.com）。要我帮你打开浏览器去京东搜这款鼠标吗？";
        assertEquals("a1 的实测情形：0 次 web.read → 提醒", ResearchGate.SHOP_EVIDENCE_NUDGE,
                ResearchGate.finishNudge(ResearchGate.Kind.SHOP, ev, a1, false, false));
        assertEquals("证据提醒过一次后，有来源没价格 → 价格提醒", ResearchGate.SHOP_PRICE_NUDGE,
                ResearchGate.finishNudge(ResearchGate.Kind.SHOP, ev, a1, true, false));
        assertNull("各只提醒一次", ResearchGate.finishNudge(ResearchGate.Kind.SHOP, ev, a1, true, true));
        // 读了两站、写了价格 → 放行
        ev.onStep("web.read", new JSONObject().put("url", "https://item.jd.com/100123.html"), "{}", true);
        ev.onStep("web.read", new JSONObject().put("url", "https://detail.tmall.com/item.htm?id=1"), "{}", true);
        String good = "推荐 V3 专业版 35K：京东 439.12 元（jd.com，2026-11-03）；天猫 849 元（tmall.com）。";
        assertEquals(new HashSet<String>(Arrays.asList("jd.com", "tmall.com")), ev.sites(good));
        assertNull(ResearchGate.finishNudge(ResearchGate.Kind.SHOP, ev, good, false, false));
        // 读失败不算
        ResearchGate.Evidence bad = new ResearchGate.Evidence();
        bad.onStep("web.read", new JSONObject().put("url", "https://item.jd.com/1.html"), "{}", false);
        assertEquals(0, bad.reads);
        // 非购物不管
        assertNull(ResearchGate.finishNudge(ResearchGate.Kind.NONE, ev, a1, false, false));
    }

    @Test public void finishGateWiredLikeProbeChainNudge() throws Exception {
        String loop = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        // ★ 11-03 下午：加了「缺例子」（代表款 < 3）第三种提醒
        assertTrue(loop.contains("ResearchGate.finishNudge(gateKind, ev, lastContent, shopEvNudged, shopPriceNudged, shopExNudged)"));
        assertTrue(loop.contains("else if (ResearchGate.isExamplesNudge(gn)) shopExNudged = true;"));
        assertTrue("购物任务不走探针链提醒（a1 的 probe.apps ×4）", loop.contains("!nudged && gateKind == ResearchGate.Kind.NONE"));
    }

    // ------------------------------------------------------------------ 5 多轮接续 / 提议校验

    @Test public void delegateContinuesSameShopTask() {
        long t0 = 1000000L;
        TaskContinuation.record(BASILISK, "V3 有有线、V3 X、V3 专业版几个版本。你想要哪一款？", TaskContinuation.Origin.ERRAND, t0);
        String rw = TaskContinuation.rewrite("你看着办", t0 + 30000);
        assertNotNull(rw);
        assertTrue(rw.startsWith(BASILISK + " " + TaskContinuation.MARKER));
        ErrandIntent in = ErrandIntent.parse(rw);
        assertEquals("接回购物任务，不是问答", ErrandIntent.Kind.SHOP, in.kind);
        assertEquals("雷蛇巴塞利斯蛇v3", in.query);
        assertEquals("你看着办", in.followup);
        assertTrue(in.userDelegated());
        assertTrue(ErrandPrompt.answerUser(in, "x").contains("直接替用户选定一款"));
        assertNull("一次应答只接一次", TaskContinuation.rewrite("你决定", t0 + 40000));
    }

    @Test public void okContinuesAgentTaskNotAsk() {
        long t0 = 5000000L;
        String q = "看看相册有多少张照片再告诉我要不要清理";
        TaskContinuation.record(q, "相册有 3200 张。要我帮你打开相册清理吗？", TaskContinuation.Origin.AGENT, t0);
        String rw = TaskContinuation.rewrite("好", t0 + 10000);
        assertNotNull(rw);
        TriageDecider.Decision d = TriageDecider.decide(rw, TriageDecider.Signals.offline());
        assertEquals("0c-continue", d.rule);
        assertEquals(TriageDecider.Label.DO_ACT, d.label);
        assertTrue("接回 AgentLoop，不重新分诊成 CLARIFY / 问答", d.useAgentLoop);
        assertEquals(q, TaskContinuation.strip(rw));
    }

    @Test public void continuationNeedsOfferAffirmAndTtl() {
        long t0 = 9000000L;
        TaskContinuation.record(BASILISK, "推荐 V3 专业版 35K，京东 439.12 元。", TaskContinuation.Origin.ERRAND, t0);
        assertNull("结尾没提议 / 反问 → 不接", TaskContinuation.rewrite("好", t0 + 1000));
        TaskContinuation.record(BASILISK, "要我打开第一个看看吗？", TaskContinuation.Origin.ERRAND, t0);
        assertNull("超时", TaskContinuation.rewrite("好", t0 + TaskContinuation.TTL_MS + 1));
        TaskContinuation.record(BASILISK, "要我打开第一个看看吗？", TaskContinuation.Origin.ERRAND, t0);
        assertNull("说了别的 → 链断开", TaskContinuation.rewrite("明天天气怎么样", t0 + 1000));
        assertNull(TaskContinuation.rewrite("好", t0 + 2000));
        for (String s : new String[]{"好", "好的", "行", "可以", "你决定", "你看着办", "你决定吧。", "嗯好", "随便"}) assertTrue(s, TaskContinuation.isAffirm(s));
        for (String s : new String[]{"好冷啊", "可以帮我设个闹钟吗", "行车记录仪", "不用了", "明天再说吧"}) assertFalse(s, TaskContinuation.isAffirm(s));
    }

    @Test public void noOfferOfUnavailableActions() {
        String a2y = "这台手机上没装购物 App。我查到京东 439 元。要我帮你打开浏览器去京东搜这款鼠标吗？";
        String cut = ResearchGate.dropUnavailableOffers(a2y, new HashSet<String>(Arrays.asList("web.search", "probe.apps")));
        assertFalse(cut, cut.contains("打开浏览器"));
        assertTrue(cut.contains("439"));
        assertEquals("有 browser.open → 提议成立，保留", a2y,
                ResearchGate.dropUnavailableOffers(a2y, new HashSet<String>(Arrays.asList("web.search", "browser.open"))));
        String pay = "推荐 V3 专业版。要我帮你下单吗？";
        assertFalse("永不提议替用户下单付款", ResearchGate.dropUnavailableOffers(pay, new HashSet<String>(Arrays.asList("browser.open", "phone.operate"))).contains("下单"));
    }

    @Test public void continuationAndOfferWired() throws Exception {
        String h = src("app/src/com/onesay/assistant/v2/errand/ErrandHooks.java");
        assertTrue(h.contains("TaskContinuation.rewrite(command"));
        assertTrue(h.contains("TaskContinuation.Origin.ERRAND"));
        String loop = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertTrue(loop.contains("ResearchGate.dropUnavailableOffers(ensurePlan(lastContent, steps, null, query), toolSigns(tools))"));
        assertTrue(loop.contains("TaskContinuation.Origin.AGENT"));
        assertTrue(src("app/assets/agent_prompt.md").contains("只提议工具列表里真有的动作"));
    }
}
