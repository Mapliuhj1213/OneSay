package com.onesay.assistant.v2.errand;

import com.onesay.assistant.v2.errand.web.AgentAction;
import com.onesay.assistant.v2.errand.web.BrowserAgent;
import com.onesay.assistant.v2.errand.web.CheckoutGuard;
import com.onesay.assistant.v2.errand.web.PageScript;
import com.onesay.assistant.v2.errand.web.PageSnapshot;

import org.junit.Test;

import static com.onesay.assistant.v2.errand.BrowserAgentTest.FakeClock;
import static com.onesay.assistant.v2.errand.BrowserAgentTest.Script;
import static com.onesay.assistant.v2.errand.BrowserAgentTest.World;
import static com.onesay.assistant.v2.errand.BrowserAgentTest.page;
import static org.junit.Assert.*;

/**
 * 下单拦截强归一化回归。用例取自 pentest-20261024/poc/logic（Poc.java §3、Poc2.java A/B），
 * 修复前这些全部「放行」，现在必须被拦。
 */
public class SecLogicCheckoutNormTest {

    static PageSnapshot.El btn(int id, String text) {
        return new PageSnapshot.El(id, "button", "button", text, "", "");
    }

    /** 复现 PageScript.txt() 的 JS 归一化（\s+→空格、trim、截 80；JS \s 不含 U+200B/200C/200D/FEFF）。 */
    static String jsNorm(String s) {
        String r = s.replaceAll("[\\s\\u00A0\\u3000\\u2028\\u2029\\u1680\\u2000-\\u200A\\u202F\\u205F]+", " ").trim();
        return r.length() > 80 ? r.substring(0, 80) : r;
    }

    static boolean blocked(String raw) {
        PageSnapshot p = page("https://shop.example.com/item/1", "商品", btn(1, jsNorm(raw)));
        return CheckoutGuard.INSTANCE.block(ErrandIntent.parse("在京东买 牙刷"), p, AgentAction.click(1)) != null;
    }

    static final String[] PREVIOUSLY_BYPASSED = {
            // Poc.java §3
            "提交訂單", "立即購買並付款", "一鍵下單", "立即 支付", "立即\u200b支付", "立即付\u3000款", "Confirm  Order", "下单",
            "结算并付款", "Purchase", "Checkout now", "提交 订单",
            // Poc2.java A：真实电商按钮
            "Buy Now", "Buy now", "Submit order", "Complete order", "Confirm purchase", "Pay", "Pay ¥99.00", "去下单", "确定支付", "确定付款",
            "同意协议并支付", "支付¥99.00", "付款 ¥99", "免拼购买", "立即\n支付", "确认交易", "確認付款", "立即付款",
            // Poc2.java B：攻击者可控文本
            "提交\u200d订单", "一键\ufeff下单", "ＰＡＹ　ＮＯＷ", "\u0420\u0430\u0443 now", "立即\u2060支付", "Place\u00a0your\u00a0order",
            // 用户 10-25 指定补齐
            "buy now", "submit order", "confirm purchase", "purchase", "checkout", "去下单", "确定支付", "确定付款", "免拼购买",
    };

    @Test public void previously_bypassed_pay_buttons_are_now_blocked() {
        for (String s : PREVIOUSLY_BYPASSED) assertTrue("应拦：" + s.replace("\u200b", "<ZWSP>"), blocked(s));
    }

    @Test public void benign_buttons_still_allowed() {
        String[] ok = {"立即购买", "加入购物车", "去结算", "Add to Cart", "查看详情", "收藏", "快捷登录", "领券", "下一页", "Sign in", "Search"};
        for (String s : ok) assertFalse("不该拦：" + s, blocked(s));
    }

    @Test public void aria_label_title_alt_are_checked_even_when_text_is_innocent() {
        PageSnapshot p = page("https://shop.example.com/item/1", "商品",
                new PageSnapshot.El(1, "button", "button", "去看看", "", "", "立即支付 "),
                new PageSnapshot.El(2, "a", "a", "", "", "", "Buy now with 1-Click"));
        ErrandIntent shop = ErrandIntent.parse("在京东买 牙刷");
        assertNotNull(CheckoutGuard.INSTANCE.block(shop, p, AgentAction.click(1)));
        assertNotNull(CheckoutGuard.INSTANCE.block(shop, p, AgentAction.click(2)));
    }

    @Test public void long_prefix_truncation_is_caught_via_label() {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 14; i++) pad.append("商品详情说明");
        String full = pad + "立即支付";
        PageSnapshot.El e = new PageSnapshot.El(1, "button", "button", jsNorm(full), "", "", full.substring(0, Math.min(160, full.length())));
        assertFalse("可见文字被截断，看不到「立即支付」", CheckoutGuard.isPayText(e.text));
        PageSnapshot p = page("https://shop.example.com/item/1", "商品", e);
        assertNotNull(CheckoutGuard.INSTANCE.block(ErrandIntent.parse("在京东买 牙刷"), p, AgentAction.click(1)));
    }

    @Test public void page_script_extracts_guard_label() {
        assertTrue(PageScript.EXTRACT.contains("aria-label"));
        assertTrue(PageScript.EXTRACT.contains("lab:ls.slice(0,400)"));
        PageSnapshot p = PageSnapshot.parse("{\"url\":\"https://x\",\"els\":[{\"i\":1,\"tag\":\"button\",\"kind\":\"button\",\"text\":\"去看看\",\"lab\":\"立即支付\"}]}");
        assertEquals("立即支付", p.find(1).label);
    }

    @Test public void order_placed_after_click_stops_with_alert() {
        World w = new World();
        String item = "https://shop.example.com/item/1";
        String done = "https://shop.example.com/order/success";
        // 恶意按钮：可见文字无害、标签也无害 —— 事前没拦住，点下去直接下单
        w.add(page(item, "商品 ¥99", btn(1, "领取福利")));
        w.add(page(done, "订单提交成功！订单号 123456，实付 ¥99"));
        w.clicks.put(item + "#1", done);
        Script s = new Script(AgentAction.click(1), AgentAction.done("x"));
        BrowseResult r = new BrowserAgent(w, s, null, CheckoutGuard.INSTANCE, new FakeClock())
                .run(ErrandIntent.parse("在京东买 牙刷"), item, null);
        assertEquals(BrowseResult.Status.ORDER_ALERT, r.status);
        assertEquals(CheckoutGuard.ALERT_ORDER_PLACED, r.reason);
        assertEquals(done, r.url);
    }

    @Test public void order_history_page_does_not_alarm_when_already_there() {
        PageSnapshot before = page("https://x/orders", "我的订单 交易成功 交易成功");
        PageSnapshot after = page("https://x/orders?p=2", "我的订单 交易成功");
        assertNull(CheckoutGuard.INSTANCE.afterAction(null, before, AgentAction.click(1), after));
        assertTrue(CheckoutGuard.looksOrderPlaced(page("https://x", "Thank you for your order! Your order has been placed.")));
        assertTrue(CheckoutGuard.looksOrderPlaced(page("https://x", "付款成功")));
        assertTrue(CheckoutGuard.looksOrderPlaced(page("https://x", "支付\u200b成功")));
        assertFalse(CheckoutGuard.looksOrderPlaced(page("https://x", "确认订单 应付总额 ¥99")));
    }

    @Test public void report_card_for_order_alert_never_claims_no_order() {
        ResearchLadder.Outcome o = TavilyExtractAndReportTest.newOutcomeWithStatus(BrowseResult.Status.ORDER_ALERT, "https://shop.example.com/o", "");
        ErrandReport.Card c = ErrandReport.card(ErrandIntent.parse("在京东买 X"), o, false);
        assertTrue(c.headline, c.headline.contains("订单可能已提交"));
        assertFalse(c.full.contains("没有提交订单"));
    }
}
