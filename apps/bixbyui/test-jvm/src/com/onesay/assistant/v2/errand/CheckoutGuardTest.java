package com.onesay.assistant.v2.errand;

import com.onesay.assistant.v2.errand.web.AgentAction;
import com.onesay.assistant.v2.errand.web.BrowserAgent;
import com.onesay.assistant.v2.errand.web.CheckoutGuard;
import com.onesay.assistant.v2.errand.web.PageSnapshot;
import com.onesay.assistant.v2.errand.web.PlannerPrompt;

import org.junit.Test;

import java.util.Arrays;

import static com.onesay.assistant.v2.errand.BrowserAgentTest.FakeClock;
import static com.onesay.assistant.v2.errand.BrowserAgentTest.Script;
import static com.onesay.assistant.v2.errand.BrowserAgentTest.World;
import static com.onesay.assistant.v2.errand.BrowserAgentTest.input;
import static com.onesay.assistant.v2.errand.BrowserAgentTest.link;
import static com.onesay.assistant.v2.errand.BrowserAgentTest.page;
import static org.junit.Assert.*;

/** ★ lane C3-3：购物到结算页停下，付款永远不自动执行。 */
public class CheckoutGuardTest {

    static PageSnapshot.El btn(int id, String text) {
        return new PageSnapshot.El(id, "button", "button", text, "", "");
    }

    static final String SEARCH = "https://search.jd.com/Search?enc=utf-8&keyword=x";
    static final String ITEM = "https://item.jd.com/100.html";
    static final String CART = "https://cart.jd.com/cart_index";
    static final String TRADE = "https://trade.jd.com/shopping/order/getOrderInfo.action";

    /** 京东式流程：搜索 → 商品 → 加购 → 购物车 → 去结算 → 确认订单页。 */
    static World jd() {
        World w = new World();
        w.add(page(SEARCH, "绿联 USB-C 数据线 ¥19.90 京东自营", link(1, "绿联 USB-C 数据线 1m", ITEM)));
        w.add(page(ITEM, "绿联 USB-C 数据线 ¥19.90", btn(1, "加入购物车"), btn(2, "立即购买")));
        w.add(page(CART, "购物车 绿联 USB-C 数据线 ¥19.90 x1", btn(1, "去结算")));
        w.add(page(TRADE, "收货人 张三 收货地址 …… 应付总额：¥19.90", btn(1, "提交订单"), input(2, "优惠码")));
        w.clicks.put(SEARCH + "#1", ITEM);
        w.clicks.put(ITEM + "#1", CART);
        w.clicks.put(CART + "#1", TRADE);
        w.clicks.put(TRADE + "#1", "https://pay.jd.com/cashier");   // 一旦点了就是下单 —— 测试要证明根本点不到
        return w;
    }

    @Test
    public void buy_flow_stops_at_checkout_without_submitting() {
        World w = jd();
        Script s = new Script(AgentAction.click(1), AgentAction.click(1), AgentAction.click(1),
                AgentAction.click(1) /* 想点「提交订单」 */, AgentAction.done("x"));
        s.wrap = "已加购绿联 USB-C 数据线 ¥19.90 ×1，停在确认订单页";
        BrowseResult r = new BrowserAgent(w, s, null, CheckoutGuard.INSTANCE, new FakeClock())
                .run(ErrandIntent.parse("在京东买 USB-C 数据线"), SEARCH, null);
        assertEquals(BrowseResult.Status.CHECKOUT, r.status);
        assertEquals(TRADE, r.url);
        assertEquals(CheckoutGuard.STOP_CHECKOUT, r.reason);
        assertEquals("已加购绿联 USB-C 数据线 ¥19.90 ×1，停在确认订单页", r.answer);
        assertEquals("到结算页就停：第 4 个动作（提交订单）根本没被请求", 3, w.log.size() - 1);
        assertFalse(w.log.toString(), w.log.toString().contains("pay.jd.com"));
    }

    @Test
    public void pay_buttons_links_and_fields_are_blocked_everywhere() {
        CheckoutGuard g = CheckoutGuard.INSTANCE;
        ErrandIntent shop = ErrandIntent.parse("在京东买 牙刷");
        ErrandIntent research = ErrandIntent.parse("去网上查一下 牙刷");
        PageSnapshot p = page("https://www.example.com/x", "随便一页",
                btn(1, "立即支付"), btn(2, "提交订单"), btn(3, "Place your order"), link(4, "去收银台", "https://cashier.example.com/pay?o=1"),
                new PageSnapshot.El(5, "input", "input:text", "请输入支付密码", "", ""), btn(6, "加入购物车"), btn(7, "去结算"), btn(8, "立即购买"));
        for (int id : new int[]{1, 2, 3, 4}) {
            assertNotNull("购物 #" + id, g.block(shop, p, AgentAction.click(id)));
            assertNotNull("调研 #" + id, g.block(research, p, AgentAction.click(id)));
        }
        assertNotNull(g.block(shop, p, AgentAction.type(5, "123456", false)));
        assertNull("加购不拦", g.block(shop, p, AgentAction.click(6)));
        assertNull("去结算不拦（只进结算页）", g.block(shop, p, AgentAction.click(7)));
        assertNull("立即购买不拦（只进确认订单页）", g.block(shop, p, AgentAction.click(8)));
        assertNotNull(g.block(shop, p, AgentAction.open("https://mclient.alipay.com/cashier/x")));
        // 结算页上不填任何东西
        PageSnapshot trade = page(TRADE, "收货地址 …… 应付总额 ¥1", input(2, "优惠码"));
        assertNotNull(g.block(shop, trade, AgentAction.type(2, "SAVE10", false)));
    }

    @Test
    public void checkout_detection_by_url_or_content() {
        assertTrue(CheckoutGuard.isCheckout(page(TRADE, "")));
        assertTrue(CheckoutGuard.isCheckout(page("https://buy.taobao.com/auction/confirm_order.htm", "")));
        assertTrue(CheckoutGuard.isCheckout(page("https://shop.example.com/a", "收货地址：北京 …… 实付款 ¥99", btn(1, "提交订单"))));
        assertFalse("购物车不是结算页", CheckoutGuard.isCheckout(page(CART, "购物车 合计 ¥19.90", btn(1, "去结算"))));
        assertFalse(CheckoutGuard.isCheckout(page(ITEM, "¥19.90", btn(1, "加入购物车"))));
        assertTrue(CheckoutGuard.isCashier(page("https://pay.jd.com/cashier?o=1", "")));
        assertEquals(CheckoutGuard.STOP_CASHIER, CheckoutGuard.INSTANCE.stopAt(ErrandIntent.parse("去网上查一下 X"), page("https://cashier.example.com/x", "")));
    }

    @Test
    public void model_cannot_claim_checkout_on_a_normal_page() {
        World w = jd();
        Script s = new Script(AgentAction.click(1), AgentAction.checkout("到结算页了"), AgentAction.done("其实没买"));
        BrowseResult r = new BrowserAgent(w, s, null, CheckoutGuard.INSTANCE, new FakeClock())
                .run(ErrandIntent.parse("在京东买 USB-C 数据线"), SEARCH, null);
        assertEquals(BrowseResult.Status.DONE, r.status);
        assertTrue(s.seenHistory.get(2).toString(), s.seenHistory.get(2).toString().contains("还不是结算"));
    }

    @Test
    public void shop_prompt_forbids_paying_and_research_prompt_has_no_shop_rules() {
        String sp = PlannerPrompt.system(ErrandIntent.parse("在京东买 USB-C 数据线"));
        assertTrue(sp.contains("checkout"));
        assertTrue(sp.contains("绝对不要点「提交订单」"));
        assertFalse(PlannerPrompt.system(ErrandIntent.parse("去网上查一下 X")).contains("checkout"));
    }

    @Test
    public void report_for_checkout() {
        ResearchLadder.Outcome o = TavilyExtractAndReportTest.newOutcomeWithStatus(BrowseResult.Status.CHECKOUT, TRADE, "已加购 X ¥19.90\n共 1 件");
        ErrandReport.Card c = ErrandReport.card(ErrandIntent.parse("在京东买 X"), o, false);
        assertEquals("已到结算页，付款请你来", c.headline);
        assertEquals("已加购 X ¥19.90", c.detail);
        assertTrue(c.full.contains("没有提交订单、没有付款"));
        assertEquals("已到结算页：已加购 X ¥19.90", ErrandReport.checkoutPrompt(o));
        assertTrue(Arrays.asList(BrowseResult.Status.values()).contains(BrowseResult.Status.CHECKOUT));
    }

    /** r2/fixes 第 3 项：一键下单 / 免密 / 生物识别支付类按钮（中英文）一律拦，「立即购买 / 去结算 / 加购」照旧放行。 */
    @Test
    public void one_click_and_passwordless_pay_buttons_are_blocked() {
        CheckoutGuard g = CheckoutGuard.INSTANCE;
        ErrandIntent shop = ErrandIntent.parse("在京东买 牙刷");
        String[] blocked = {"一键购买", "一键下单", "立即购买并支付", "立即购买并付款", "免密支付", "小额免密支付", "快捷支付",
                "指纹支付", "面容支付", "刷脸支付", "确认支付", "极速下单",
                "1-Click", "Buy Now with 1-Click", "Buy now with 1-Click®", "One-Click Buy", "1-click ordering", "Buy with Apple Pay", "Express Checkout"};
        for (int i = 0; i < blocked.length; i++) {
            PageSnapshot p = page("https://www.amazon.com/dp/B0X", "商品页", btn(1, blocked[i]));
            assertNotNull("应拦：" + blocked[i], g.block(shop, p, AgentAction.click(1)));
        }
        // 2026-10-25 r2/sec-logic LOGIC-3：用户指定英文站 Buy Now / checkout 也拦（开了 1-Click 时 Buy Now 直接下单）→ 移到 SecLogicCheckoutNormTest
        String[] allowed = {"立即购买", "Add to Cart", "加入购物车", "去结算", "快捷登录", "clickable"};
        for (int i = 0; i < allowed.length; i++) {
            PageSnapshot p = page("https://www.amazon.com/dp/B0X", "商品页", btn(1, allowed[i]));
            assertNull("不该拦：" + allowed[i], g.block(shop, p, AgentAction.click(1)));
        }
    }
}
