package com.onesay.assistant.v2.errand.web;

import com.onesay.assistant.v2.errand.ErrandIntent;
import com.onesay.assistant.v2.security.SafetyText;

import java.util.regex.Pattern;

/**
 * ★ lane C3-3：购物守卫（纯 Java）。用户 10-22 23:52 确认：<b>可以搜索、比价、加购物车、进结算页；到付款前停下，交给用户自己付。
 * 付款永远不自动执行。</b>
 *
 * <h3>{@link #block}：这一步不准做（任何跑腿都生效，不只购物）</h3>
 * <ul>
 *   <li>点「提交订单 / 立即支付 / 去付款 / 确认付款 / 支付宝支付 / Place order / Pay now」之类的按钮；</li>
 *   <li>点指向收银台的链接（cashier. / pay. / alipay / tenpay / unionpay / paypal / /pay/ / /payment）；</li>
 *   <li>在结算 / 收银页上输入任何东西（地址、优惠码、支付密码都不碰）；任何页面上的支付密码 / 卡号 / CVV / 身份证框；</li>
 *   <li>打开收银台网址。</li>
 * </ul>
 * 「去结算」「立即购买」「加入购物车」不拦：它们只进结算页，不产生订单、不扣钱。
 * 但「一键购买 / 1-Click / Buy now with 1-Click / 立即购买并支付 / 免密支付 / 快捷支付 / 指纹支付 / 确认支付」这类
 * <b>跳过结算页直接下单或直接扣款</b>的按钮要拦（2026-10-24 r2/fixes，中英文都在 {@link #PAY_TEXT}）。
 *
 * <h3>{@link #stopAt}：到这儿就停（交给用户）</h3>
 * 结算 / 确认订单页（URL 特征，或「收货地址 + 应付总额 + 提交订单」同时出现）、收银台页。购物以外的跑腿走到这些页面也停。
 */
public final class CheckoutGuard implements BrowserAgent.Guard, BrowserAgent.PostActionGuard {

    public static final CheckoutGuard INSTANCE = new CheckoutGuard();

    public static final String STOP_CHECKOUT = "已到结算页（付款前停下），请你自己核对并付款";
    public static final String STOP_CASHIER = "已到付款页，付款只能由你自己完成";

    /**
     * 付款 / 下单类按钮文字（原始正则，保留给旧调用方 / PoC 抽取；★ 判定请走 {@link #isPayText}，它先强归一化再匹配 {@link #PAY_SQ}）。
     */
    static final Pattern PAY_TEXT = Pattern.compile(
            "提交订单|确认订单并支付|提交并支付|确认下单|立即下单|确认支付|确认付款|立即支付|立即付款|去支付|去付款|马上支付|马上付款|"
                    + "支付宝支付|微信支付|银行卡支付|云闪付|花呗|信用卡支付|快捷支付|余额支付|使用.{0,4}支付|输入支付密码|确认购买并付款|"
                    // r2/fixes 第 3 项：一键 / 免密 / 生物识别类按钮会跳过结算页直接下单或扣款
                    + "一键(?:购买|下单|支付|付款)|立即购买并(?:支付|付款)|免密(?:支付|付款)|小额免密|指纹(?:支付|付款)|面容(?:支付|付款)|刷脸(?:支付|付款)|"
                    + "极速(?:下单|支付)|快速下单|确认并支付|"
                    + "\\bplace (?:your )?order\\b|\\bpay now\\b|\\bpay \\$|\\bcomplete (?:purchase|order)\\b|\\bconfirm (?:and )?pay\\b|\\bproceed to payment\\b|"
                    + "\\b(?:1|one)[- ]?click\\b|\\bbuy now with\\b|\\b(?:apple|google|shop|amazon) pay\\b|\\binstant (?:buy|checkout)\\b|\\bexpress checkout\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * <b>归一化后</b>（{@link SafetyText#squash}：NFKC、去零宽、同形字→拉丁、繁→简、小写、删空白与分隔符）
     * 的付款 / 下单词表。中文同义补齐（去下单 / 确定支付 / 确定付款 / 免拼购买 / 同意协议并支付 …），英文补齐
     * buy now / submit order / confirm purchase / purchase / checkout（用户 10-25 指定：英文站 Buy Now 在开了 1-Click 时直接下单，checkout 一律交用户）。
     */
    static final Pattern PAY_SQ = Pattern.compile(
            "提交订单|确认订单并?支付|提交并支付|确认下单|确定下单|立即下单|去下单|马上下单|快速下单|极速(?:下单|支付)|"
                    + "确[认定](?:支付|付款|购买并付款|交易|并支付)|立即(?:支付|付款)|去(?:支付|付款)|马上(?:支付|付款)|"
                    + "支付宝支付|微信支付|银行卡支付|云闪付|花呗|信用卡支付|快捷支付|余额支付|使用.{0,4}支付|输入支付密码|"
                    + "一键(?:购买|下单|支付|付款)|立即购买并(?:支付|付款)|免密(?:支付|付款)|小额免密|免拼购买|单独购买|"
                    + "指纹(?:支付|付款)|面容(?:支付|付款)|刷脸(?:支付|付款)|同意.{0,6}并(?:支付|付款|下单)|"
                    + "^(?:支付|付款|下单|购买并付款)$|^(?:支付|付款)[¥￥$]?\\d|(?:结算|下单|购买|提交)并(?:支付|付款)|"
                    + "placeyourorder|placeorder|submitorder|submityourorder|confirmorder|confirmpurchase|completepurchase|completeorder|"
                    + "confirmandpay|confirmpay|paynow|^pay$|^pay[¥￥$€£]?\\d|proceedtopayment|buynow|buyitnow|"
                    + "purchase|checkout|(?:1|one)click|(?:apple|google|shop|amazon)pay|instantbuy",
            Pattern.CASE_INSENSITIVE);

    /** ★ LOGIC-3 兜底：页面出现「订单已提交 / 支付成功 / order placed」特征（归一化后匹配）。 */
    static final Pattern ORDER_DONE = Pattern.compile(
            "订单已提交|订单提交成功|下单成功|订单已生成|支付成功|付款成功|交易成功|购买成功|已成功下单|已付款|"
                    + "orderplaced|orderhasbeenplaced|orderisplaced|thankyouforyourorder|thanksforyourorder|thankyouforyourpurchase|"
                    + "orderconfirmed|orderisconfirmed|paymentsuccessful|paymentcomplete|paymentreceived|orderreceived|purchasecomplete",
            Pattern.CASE_INSENSITIVE);

    public static final String ALERT_ORDER_PLACED = "⚠ 页面显示订单可能已提交 / 已付款，已立即停下，请马上核对订单";

    /** ★ LOGIC-3：按钮 / 标签文字是不是付款 / 下单（先强归一化）。 */
    public static boolean isPayText(String raw) {
        if (raw == null || raw.length() == 0) return false;
        if (PAY_TEXT.matcher(raw).find()) return true;
        return PAY_SQ.matcher(SafetyText.squash(raw)).find();
    }

    static boolean isPayEl(PageSnapshot.El e) {
        return e != null && (isPayText(e.text) || isPayText(e.label));
    }

    /** ★ LOGIC-3 兜底：页面有没有「已下单 / 已付款」特征（只看正文前 3000 字 + 标题）。 */
    public static boolean looksOrderPlaced(PageSnapshot p) {
        if (p == null) return false;
        String head = p.text.length() > 3000 ? p.text.substring(0, 3000) : p.text;
        return ORDER_DONE.matcher(SafetyText.squash(p.title + " " + head)).find();
    }

    /** 收银台 / 支付网址。 */
    static final Pattern PAY_URL = Pattern.compile(
            "//cashier\\.|//pay\\.|//paycenter\\.|//mclient\\.alipay|alipay\\.com|tenpay\\.com|unionpay|paypal\\.com|//wx\\.tenpay|"
                    + "/pay/|/payment|/cashier|payorder|topay|gopay",
            Pattern.CASE_INSENSITIVE);

    /** 结算 / 确认订单页网址。 */
    static final Pattern CHECKOUT_URL = Pattern.compile(
            "trade\\.jd\\.com/shopping/order|//trade\\.jd\\.com|buy\\.taobao\\.com/auction/(?:buy_now|confirm_order)|buy\\.tmall\\.com/order/confirm|"
                    + "//buy\\.(?:taobao|tmall)\\.com|/checkout|/confirmorder|/confirm_order|/order/confirm|/orderconfirm|/settle|/settlement|"
                    + "/gp/buy/|/ap/checkout|/buy/spc",
            Pattern.CASE_INSENSITIVE);

    static final Pattern ADDR = Pattern.compile("收货地址|收货人|配送地址|寄送至|送货地址|Shipping address|Deliver to", Pattern.CASE_INSENSITIVE);
    static final Pattern TOTAL = Pattern.compile("应付总额|实付款|应付金额|订单总额|合计[:：]?\\s*[¥￥]|总计[:：]?\\s*[¥￥]|Order total|Total[:：]? *\\$", Pattern.CASE_INSENSITIVE);

    /** 任何页面都不碰的敏感输入框。 */
    static final Pattern SENSITIVE_FIELD = Pattern.compile("支付密码|交易密码|银行卡|卡号|CVV|CVC|安全码|有效期|身份证|card number|security code", Pattern.CASE_INSENSITIVE);

    /** 收银台页？ */
    public static boolean isCashier(PageSnapshot p) {
        return p != null && PAY_URL.matcher(p.url).find();
    }

    /** 结算 / 确认订单页？ */
    public static boolean isCheckout(PageSnapshot p) {
        if (p == null) return false;
        if (CHECKOUT_URL.matcher(p.url).find()) return true;
        String head = p.text.length() > 6000 ? p.text.substring(0, 6000) : p.text;
        if (!ADDR.matcher(head).find() || !TOTAL.matcher(head).find()) return false;
        for (PageSnapshot.El e : p.els) if (isPayEl(e)) return true;
        return PAY_TEXT.matcher(head).find() || PAY_SQ.matcher(SafetyText.squash(head)).find();
    }

    @Override
    public String stopAt(ErrandIntent intent, PageSnapshot page) {
        if (isCashier(page)) return STOP_CASHIER;
        if (isCheckout(page)) return STOP_CHECKOUT;
        return null;
    }

    @Override
    public String block(ErrandIntent intent, PageSnapshot page, AgentAction a) {
        if (a == null) return null;
        switch (a.type) {
            case OPEN:
                if (a.url != null && PAY_URL.matcher(a.url).find()) return "不打开付款页面";
                return null;
            case CLICK: {
                PageSnapshot.El e = page == null ? null : page.find(a.id);
                if (e == null) return null;
                if (isPayEl(e)) return "「" + PageSnapshot.clip(e.text.length() > 0 ? e.text : e.label, 12) + "」是下单 / 付款按钮，付款只能由用户自己点";
                if (e.href.length() > 0 && PAY_URL.matcher(e.href).find()) return "这个链接通往付款页，付款只能由用户自己完成";
                return null;
            }
            case TYPE: {
                PageSnapshot.El e = page == null ? null : page.find(a.id);
                if (e != null && SENSITIVE_FIELD.matcher(e.text).find()) return "不填写支付 / 卡号 / 证件信息";
                if (isCheckout(page) || isCashier(page)) return "结算 / 付款页上不填写任何内容";
                return null;
            }
            default:
                return null;
        }
    }

    /**
     * ★ LOGIC-3 兜底（事后检测补事前漏网）：任何动作执行后，若页面从「没有」变成「有」已下单 / 已付款特征 → 立即停并告警。
     */
    @Override
    public String afterAction(ErrandIntent intent, PageSnapshot before, AgentAction a, PageSnapshot after) {
        if (after == null || a == null) return null;
        if (a.type != AgentAction.Type.CLICK && a.type != AgentAction.Type.TYPE) return null;
        if (looksOrderPlaced(after) && !looksOrderPlaced(before)) return ALERT_ORDER_PLACED;
        return null;
    }

    /** 模型说「到结算页了」：只有守卫也认定是结算 / 收银页才算（否则当作无效动作回灌）。 */
    public static boolean acceptsCheckoutClaim(PageSnapshot page) {
        return isCheckout(page) || isCashier(page);
    }
}
