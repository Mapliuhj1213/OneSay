package com.onesay.assistant.v2.errand.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.agent.WebTools;
import com.onesay.assistant.v2.errand.ProductQuery;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ fix/shop-research（用户 11-03 14:37 / 14:38）：BrowserReader 离屏 WebView 取价 —— 抽取脚本、解析器、电脑版 URL 构造、
 * scheme 拦截、登录 / 验证跳过、超时与安全设置的接线守卫。
 */
public class BrowserReaderTest {

    static final long NOW = 1793688000000L;   // 2026-11-03 前后的固定时间戳

    static ReaderResult parse(String raw, String url) {
        return ReaderParser.parse(raw, url, 3200, NOW, 0);
    }

    // ------------------------------------------------------------------ 解析：京东搜索

    @Test public void jdSearchParsesPriceShopSelfFlagshipAndDropsOverlay() throws Exception {
        ReaderResult r = parse(ReaderFixtures.jdSearch(), ShopSearchUrls.search("京东", "雷蛇巴塞利斯蛇v3"));
        assertEquals(ReaderResult.Status.OK, r.status);
        assertEquals("浮层里的卡片不要", 6, r.items.size());
        PriceItem a = r.items.get(0);
        assertEquals(439.12, a.price, 0.001);
        assertEquals("京东游戏外设旗舰店", a.shop);
        assertTrue(a.flagship);
        assertFalse(a.selfOperated);
        assertEquals("¥439.12", a.priceLabel());
        assertEquals("https://item.jd.com/100000000001.html", a.link);
        assertTrue(a.cond, a.cond.contains("券后"));
        assertEquals(NOW, a.fetchedAt);
        PriceItem b = r.items.get(1);
        assertEquals(527.12, b.price, 0.001);
        assertTrue("自营旗舰", b.selfOperated && b.flagship);
        assertEquals("自营·旗舰", b.shopKind());
        assertEquals("店铺节点空 → 从卡片文字找", "雷蛇外设专营店", r.items.get(2).shop);
        PriceItem d = r.items.get(3);
        assertEquals("京东 .p-price i 只有数字", 183.05, d.price, 0.001);
        assertEquals("京东自营", d.shop);
        assertTrue(d.selfOperated);
        // 浮层：卡片、正文都剔掉
        assertEquals(1, r.overlays);
        assertFalse(r.text, r.text.contains("打开京东App"));
        assertFalse(r.text, r.text.contains("下载京东App"));
        assertTrue(r.text, r.text.contains("￥439.12"));
        for (PriceItem p : r.items) assertFalse(p.title, p.title.contains("打开京东App"));
    }

    @Test public void relevanceFilterDropsAccessoryAndOtherBrand() throws Exception {
        ReaderResult r = parse(ReaderFixtures.jdSearch(), ShopSearchUrls.search("京东", "雷蛇巴塞利斯蛇v3"));
        List<PriceItem> keep = ReaderParser.relevant(r.items, ProductQuery.parse("雷蛇巴塞利斯蛇v3"), 5);
        assertEquals(keep.toString(), 4, keep.size());
        for (PriceItem p : keep) {
            assertFalse(p.title, p.title.contains("防滑贴"));
            assertFalse(p.title, p.title.contains("罗技"));
        }
        assertEquals("前 N 个", 2, ReaderParser.relevant(r.items, ProductQuery.parse("雷蛇巴塞利斯蛇v3"), 2).size());
        assertTrue(ReaderParser.accessory("适用雷蛇巴塞利斯蛇V3 防滑贴"));
        assertTrue(ReaderParser.accessory("巴塞利斯蛇V3 鼠标脚贴 顺滑"));
        assertFalse(ReaderParser.accessory("雷蛇巴塞利斯蛇V3专业版 无线鼠标"));
        // 与基准对照：四个价格都在
        double[] want = {439.12, 527.12, 245.52, 183.05};
        for (int i = 0; i < 4; i++) assertEquals(want[i], keep.get(i).price, 0.001);
    }

    @Test public void smzdmItemsCarryMallDateAndCoupon() throws Exception {
        ReaderResult r = parse(ReaderFixtures.smzdmSearch(), ShopSearchUrls.search("什么值得买", "雷蛇巴塞利斯蛇v3"));
        assertEquals(2, r.items.size());
        PriceItem a = r.items.get(0);
        assertEquals(439.12, a.price, 0.001);
        assertEquals("京东", a.mall);
        assertEquals("11-03 10:20", a.pageDate);
        assertTrue(a.cond, a.cond.contains("需用券"));
        assertEquals("天猫", r.items.get(1).mall);
        assertEquals("3小时前", r.items.get(1).pageDate);
        String txt = r.asText(2000);
        assertTrue(txt, txt.startsWith("【渲染后页面里的价格（抓取于 "));
        assertTrue(txt, txt.contains("雷蛇 巴塞利斯蛇 V3 专业版 35K 无线鼠标 | ¥439.12 | 京东 | 11-03 10:20 | 需用券 | https://www.smzdm.com/p/100000001/"));
    }

    @Test public void itemPageUsesMainInfo() throws Exception {
        ReaderResult r = parse(ReaderFixtures.jdItem(), "https://item.jd.com/100000000002.html");
        assertEquals(1, r.items.size());
        PriceItem p = r.items.get(0);
        assertEquals(527.12, p.price, 0.001);
        assertEquals("雷蛇 Razer 巴塞利斯蛇V3 Pro 35K 无线三模鼠标", p.title);
        assertTrue(p.selfOperated && p.flagship);
        assertFalse("扫码下载浮层剔掉", r.text.contains("扫码下载"));
    }

    @Test public void priceParsing() {
        assertEquals(439.12, ReaderParser.price("￥439.12"), 0.001);
        assertEquals(439.12, ReaderParser.price("¥ 439.12"), 0.001);
        assertEquals(439.0, ReaderParser.price("439元"), 0.001);
        assertEquals(399.0, ReaderParser.price("券后¥399"), 0.001);
        assertEquals(439.0, ReaderParser.price("439.00"), 0.001);
        assertEquals(1299.0, ReaderParser.price("¥1,299"), 0.001);
        assertEquals(-1, ReaderParser.price("2万+条评价"), 0.001);
        assertEquals(-1, ReaderParser.price(null), 0.001);
        assertEquals(-1, ReaderParser.price("暂无报价"), 0.001);
    }

    // ------------------------------------------------------------------ 登录 / 验证 / 超时 / 脚本错误

    @Test public void loginAndCaptchaAreSkipped() throws Exception {
        ReaderResult a = parse(ReaderFixtures.taobaoLogin(), ShopSearchUrls.search("天猫", "x"));
        assertEquals(ReaderResult.Status.LOGIN, a.status);
        assertTrue(a.skipped());
        assertTrue(a.note, a.note.contains("要登录") && a.note.contains("没有尝试登录"));
        assertTrue(a.items.isEmpty());
        ReaderResult b = parse(ReaderFixtures.taobaoSearchWall(), ShopSearchUrls.search("淘宝", "x"));
        assertEquals("没跳走但有可见密码框、没价格", ReaderResult.Status.LOGIN, b.status);
        ReaderResult c = parse(ReaderFixtures.jdRisk(), ShopSearchUrls.search("京东", "x"));
        assertEquals(ReaderResult.Status.CAPTCHA, c.status);
        assertTrue(c.note, c.note.contains("安全验证"));
        // 有价格的列表页角落里的「请登录」不算墙
        assertEquals(ReaderPolicy.Verdict.OK, ReaderPolicy.classify("https://search.jd.com/Search?keyword=x", "你好，请登录 免费注册 ￥439", false, 3));
        assertEquals(ReaderPolicy.Verdict.LOGIN, ReaderPolicy.classify("https://passport.jd.com/new/login.aspx", "", false, 0));
        assertEquals(ReaderPolicy.Verdict.CAPTCHA, ReaderPolicy.classify("https://s.taobao.com/search?q=x&_____tmd_____=1", "", false, 0));
    }

    /** ★ 11-03 上机：天猫跳淘宝、值得买腾讯云校验页抽到的是空 body —— 不能算读到了。 */
    @Test public void emptyPageAndSmzdmChallengeNotOk() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("u", "https://s.taobao.com/search?fromTmallRedirect=true&tab=mall&q=x");
        o.put("cards", new org.json.JSONArray());
        o.put("tx", "");
        ReaderResult a = parse(ReaderFixtures.encoded(o), ShopSearchUrls.search("天猫", "x"));
        assertEquals(ReaderResult.Status.BLOCKED, a.status);
        assertTrue(a.note, a.note.contains("没渲染出内容"));
        o.put("u", "https://search.smzdm.com/redirect_to_search.html?ss_ab=ss6&c=home&s=x&mx_v=b");
        ReaderResult b = parse(ReaderFixtures.encoded(o), ShopSearchUrls.search("什么值得买", "x"));
        assertEquals(ReaderResult.Status.CAPTCHA, b.status);
        assertTrue(b.skipped());
    }

    @Test public void scriptErrorsAndProbe() throws Exception {
        assertEquals(ReaderResult.Status.ERROR, parse(null, "https://a.com").status);
        assertEquals(ReaderResult.Status.ERROR, parse("null", "https://a.com").status);
        assertEquals(ReaderResult.Status.ERROR, parse("\"{\\\"err\\\":\\\"TypeError\\\"}\"", "https://a.com").status);
        int[] p = ReaderParser.probe("\"{\\\"u\\\":\\\"https://a.com\\\",\\\"len\\\":900,\\\"n\\\":12,\\\"pw\\\":false,\\\"rs\\\":\\\"complete\\\"}\"");
        assertNotNull(p);
        assertEquals(12, p[0]);
        assertEquals(900, p[1]);
        assertEquals(0, p[2]);
        assertEquals(1, p[3]);
        assertNull(ReaderParser.probe("garbage"));
        ReaderResult t = ReaderResult.fail(ReaderResult.Status.TIMEOUT, "https://search.jd.com/x", 12000, "12 s 内没读完");
        assertFalse(t.ok());
        assertFalse(t.skipped());
        assertTrue(t.toString(), t.toString().startsWith("TIMEOUT 京东"));
    }

    // ------------------------------------------------------------------ 电脑版 URL / scheme

    @Test public void desktopSearchUrlsNoMobileSites() {
        assertEquals("https://search.jd.com/Search?enc=utf-8&keyword=%E9%9B%B7%E8%9B%87%E5%B7%B4%E5%A1%9E%E5%88%A9%E6%96%AF%E8%9B%87v3",
                ShopSearchUrls.search("京东", "雷蛇巴塞利斯蛇v3"));
        assertTrue(ShopSearchUrls.search("天猫", "a b").startsWith("https://list.tmall.com/search_product.htm?q=a%20b"));
        assertTrue(ShopSearchUrls.search("淘宝", "x").startsWith("https://s.taobao.com/search?q="));
        assertTrue(ShopSearchUrls.search("什么值得买", "x").startsWith("https://search.smzdm.com/"));
        assertNull("拼多多没有电脑版搜索", ShopSearchUrls.search("拼多多", "x"));
        assertNull(ShopSearchUrls.search("京东", " "));
        for (String s : new String[]{"京东", "天猫", "淘宝", "什么值得买"}) {
            String u = ShopSearchUrls.search(s, "x");
            assertTrue(u, u.startsWith("https://"));
            assertFalse(u, u.contains("://m.") || u.contains(".m.") || u.contains("mobile.") || u.contains("h5."));
        }
        assertEquals("https://item.jd.com/100038004389.html", ShopSearchUrls.toDesktop("https://item.m.jd.com/product/100038004389.html?x=1"));
        assertEquals("https://detail.tmall.com/item.htm?id=123", ShopSearchUrls.toDesktop("https://detail.m.tmall.com/item.htm?spm=a&id=123"));
        assertEquals("https://item.taobao.com/item.htm?id=77", ShopSearchUrls.toDesktop("https://h5.m.taobao.com/awp/core/detail.htm?id=77"));
        assertEquals("https://www.smzdm.com/p/5678/", ShopSearchUrls.toDesktop("http://m.smzdm.com/p/5678/"));
        assertEquals("https://search.jd.com/Search?enc=utf-8&keyword=abc", ShopSearchUrls.toDesktop("https://so.m.jd.com/ware/search.action?keyword=abc"));
        assertEquals("https://www.ithome.com/0/1.htm", ShopSearchUrls.toDesktop("http://www.ithome.com/0/1.htm"));
        assertEquals("雷蛇巴塞利斯蛇v3", ShopSearchUrls.keywordOf(ShopSearchUrls.search("京东", "雷蛇巴塞利斯蛇v3")));
        assertNull("不是站内搜索页", ShopSearchUrls.keywordOf("https://item.jd.com/1.html?q=abc"));
        assertEquals("天猫", ShopSearchUrls.siteName("https://s.taobao.com/search?fromTmallRedirect=true&tab=mall&q=x"));
        assertEquals("京东", ShopSearchUrls.siteName("https://search.jd.com/Search?keyword=x"));
    }

    @Test public void onlyHttpsNavigationAppSchemesBlocked() {
        assertTrue(ReaderPolicy.allowNavigation("https://search.jd.com/Search?keyword=x"));
        assertTrue(ReaderPolicy.allowNavigation("about:blank"));
        String[] bad = {"http://search.jd.com/", "intent://m.jd.com/#Intent;scheme=openapp.jdmobile;end", "openapp.jdmobile://virtual?params=%7B%7D",
                "taobao://s.taobao.com/search?q=x", "tbopen://m.taobao.com/tbopen/index.html", "pinduoduo://com.xunmeng.pinduoduo/",
                "weixin://dl/business", "alipays://platformapi/startapp", "file:///sdcard/a.html", "javascript:alert(1)", "content://x", null};
        for (String u : bad) assertFalse(String.valueOf(u), ReaderPolicy.allowNavigation(u));
        assertTrue(ReaderPolicy.isAppScheme("openapp.jdmobile://virtual"));
        assertTrue(ReaderPolicy.isAppScheme("intent://x#Intent;end"));
        assertTrue(ReaderPolicy.isAppScheme("tbopen://x"));
        assertFalse(ReaderPolicy.isAppScheme("https://openapp.jd.com/"));
        assertEquals("https://item.jd.com/1.html", ReaderPolicy.prepare("http://item.m.jd.com/product/1.html", false));
        assertEquals("移动模式不改写", "https://item.m.jd.com/product/1.html", ReaderPolicy.prepare("http://item.m.jd.com/product/1.html", true));
        assertNull(ReaderPolicy.prepare("openapp.jdmobile://x", false));
        assertNull(ReaderPolicy.prepare("file:///x", false));
    }

    @Test public void desktopModeDefaultsAndTimeout() {
        assertEquals(12000L, ReaderPolicy.PAGE_TIMEOUT_MS);
        assertTrue(ReaderPolicy.DESKTOP_UA.contains("Windows NT 10.0") && ReaderPolicy.DESKTOP_UA.contains("Chrome/"));
        assertFalse(ReaderPolicy.DESKTOP_UA.contains("Mobile") || ReaderPolicy.DESKTOP_UA.contains("Android"));
        assertTrue(ReaderPolicy.MOBILE_UA.contains("Mobile"));
        assertTrue(ReaderPolicy.DESKTOP_W >= 1366);
        assertTrue(ReaderPolicy.MIN_SETTLE_MS + ReaderPolicy.PROBE_EVERY_MS < ReaderPolicy.PAGE_TIMEOUT_MS);
    }

    @Test public void needsRenderRules() {
        assertTrue("没读到", ReaderPolicy.needsRender("https://www.ithome.com/0/1.htm", null));
        assertTrue("JS 外壳（字太少）", ReaderPolicy.needsRender("https://www.ithome.com/0/1.htm", "<script>"));
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 500) sb.append("雷蛇巴塞利斯蛇V3 商品详情 规格参数 ");
        assertTrue("京东页没价格", ReaderPolicy.needsRender("https://item.jd.com/1.html", sb.toString()));
        assertFalse("京东页静态就有价", ReaderPolicy.needsRender("https://item.jd.com/1.html", sb + "￥439.12"));
        assertFalse("普通站正文够长", ReaderPolicy.needsRender("https://www.ithome.com/0/1.htm", sb.toString()));
        assertTrue(ReaderPolicy.jsHeavy("https://search.smzdm.com/?s=x"));
        assertTrue(ReaderPolicy.jsHeavy("https://s.taobao.com/search?q=x"));
        assertFalse(ReaderPolicy.jsHeavy("https://www.notjd.com.evil.cn/"));
        assertFalse(ReaderPolicy.jsHeavy("https://www.ithome.com/"));
    }

    // ------------------------------------------------------------------ AgentLoop web.read 的页面

    @Test public void toPageForAgentLoop() throws Exception {
        ReaderResult login = parse(ReaderFixtures.taobaoLogin(), "https://s.taobao.com/search?q=x");
        WebTools.Page a = ReaderParser.toPage(login, null);
        assertEquals("needs_sign_in", WebTools.verdict(a));
        ReaderResult cap = parse(ReaderFixtures.jdRisk(), "https://search.jd.com/Search?keyword=x");
        assertEquals("refused", WebTools.verdict(ReaderParser.toPage(cap, null)));
        assertNull("读失败 → 用静态结果", ReaderParser.toPage(ReaderResult.fail(ReaderResult.Status.TIMEOUT, "https://a.com", 12000, ""), "x"));
        String url = ShopSearchUrls.search("京东", "雷蛇巴塞利斯蛇v3");
        WebTools.Page p = ReaderParser.toPage(parse(ReaderFixtures.jdSearch(), url), null);
        assertNotNull(p);
        assertEquals("ok", WebTools.verdict(p));
        assertTrue(p.text, p.text.contains("¥439.12 | 店铺 京东游戏外设旗舰店（旗舰）"));
        assertTrue(p.text, p.text.contains("¥527.12 | 店铺 雷蛇京东自营旗舰店（自营·旗舰）"));
        assertFalse("按 URL 里的搜索词过滤", p.text.contains("防滑贴 鼠标贴 吸汗 |"));
        assertFalse(p.text.contains("G502 HERO 主宰者有线鼠标 |"));
    }

    // ------------------------------------------------------------------ 抽取脚本（静态检查；语法另用 node --check）

    @Test public void extractScriptIsReadOnly() {
        for (String js : new String[]{ReaderScript.PROBE, ReaderScript.EXTRACT}) {
            assertTrue(js.startsWith("(function(){try{") && js.endsWith("})()"));
            assertTrue(js.contains("JSON.stringify("));
            String[] banned = {".click(", "submit(", "document.cookie", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest",
                    "location.href=", "location.assign", "window.open", "dispatchEvent", ".value=", "Android.", "bixby", "eval("};
            for (String b : banned) assertFalse(b, js.contains(b));
        }
        String e = ReaderScript.EXTRACT;
        assertTrue("浮层识别：fixed / sticky + 下载 App 文字", e.contains("position==='fixed'") && e.contains("position==='sticky'") && e.contains("下载"));
        assertTrue(e.contains("inOv(el)"));
        assertTrue("京东 / 值得买节点", e.contains(".p-price") && e.contains("z-highlight") && e.contains(".sku-name"));
        assertTrue("店铺节点", e.contains("[class*=\\\"shop\\\"]") || e.contains("[class*=\"shop\"]"));
        assertTrue("正文上限", e.contains(".slice(0," + ReaderPolicy.TEXT_MAX + ")"));
        assertTrue(ReaderScript.PROBE.contains("input[type=password]"));
    }

    // ------------------------------------------------------------------ 接线守卫：BrowserReader 的安全设置

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, "app/src/" + rel).toPath()), Charset.forName("UTF-8"));
    }

    @Test public void browserReaderHardening() throws Exception {
        String s = src("com/onesay/assistant/v2/errand/web/BrowserReader.java");
        String code = s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertFalse("不挂 JS 接口", code.contains("addJavascriptInterface"));
        for (String must : new String[]{"removeJavascriptInterface(\"searchBoxJavaBridge_\")", "setAllowFileAccess(false)", "setAllowContentAccess(false)",
                "setAllowFileAccessFromFileURLs(false)", "setAllowUniversalAccessFromFileURLs(false)", "MIXED_CONTENT_NEVER_ALLOW",
                "setSavePassword(false)", "setSaveFormData(false)", "setSupportMultipleWindows(false)", "setJavaScriptCanOpenWindowsAutomatically(false)",
                "handler.cancel()", "ReaderPolicy.allowNavigation(url)", "ReaderPolicy.isAppScheme(u)", "Looper.myLooper() == Looper.getMainLooper()",
                "new Semaphore(1, true)", "ReaderPolicy.PAGE_TIMEOUT_MS", "ReaderPolicy.DESKTOP_UA", "setUseWideViewPort(!mobile)",
                "setLoadWithOverviewMode(!mobile)", "clearCache(true)", "deleteOrigin", "expireCookies", "setAcceptThirdPartyCookies(w, false)",
                "evaluateJavascript", "ReaderScript.EXTRACT", "ReaderScript.PROBE"}) {
            assertTrue(must, code.contains(must));
        }
        for (String no : new String[]{"addView(", "WindowManager", "setContentView", "startActivity", "performClick", "ReaderScript.click"}) {
            assertFalse("离屏、不弹界面、不点击：" + no, code.contains(no));
        }
        // JS 弹窗一律取消
        assertTrue(code.contains("onJsAlert") && code.contains("onJsConfirm") && code.contains("onJsPrompt"));
    }

    @Test public void wiredIntoErrandAndAgentWebRead() throws Exception {
        String h = src("com/onesay/assistant/v2/errand/ErrandHooks.java");
        assertTrue(h.contains(".withRendered(rendered(ctx))"));
        assertTrue(h.contains("BrowserReader.get(ctx)"));
        String w = src("com/onesay/assistant/v2/agent/AndroidWebBackend.java");
        assertTrue(w.contains("ReaderPolicy.needsRender(url, stText)"));
        assertTrue(w.contains("br.clear()"));
        assertTrue(w.contains("ReaderParser.toPage(r, stText)"));
        List<String> none = new ArrayList<String>();
        assertTrue(none.isEmpty());
    }
}
