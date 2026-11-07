package com.onesay.assistant.v2.errand.web;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（11-03 14:37 / 14:38）：{@link BrowserReader} 的规则（纯 Java，JVM 可测）。
 * <ul>
 *   <li>默认电脑模式：桌面 Chrome（Windows）UA、宽视口 {@link #DESKTOP_W}；可选移动 UA；</li>
 *   <li>单页 {@link #PAGE_TIMEOUT_MS} 超时（加载 + 等渲染 + 抽取合计）；</li>
 *   <li>只放行 https 导航；intent:// / openapp.jdmobile:// / taobao:// / tbopen:// / pinduoduo:// / weixin:// / alipays:// 等唤起 App 的
 *       scheme 一律拦截、不跳转（http 也拦，地址先改成 https 再加载）；</li>
 *   <li>要登录 / 要验证码（滑块）→ 跳过，不尝试登录（{@link #classify}）。</li>
 * </ul>
 */
public final class ReaderPolicy {

    private ReaderPolicy() {}

    /** 单页总超时：加载 + 等渲染 + 抽取。 */
    public static final long PAGE_TIMEOUT_MS = 12000L;
    /** 等渲染：每隔多久探一次价格节点。 */
    public static final long PROBE_EVERY_MS = 700L;
    /** ★ 11-04 午：AgentLoop 调研 / 购物的 web.read 走浏览器渲染时的上限（跑腿比价仍用 {@link #PAGE_TIMEOUT_MS}）。 */
    public static final long AGENT_PAGE_TIMEOUT_MS = 6000L;

    /** ★ 11-04 午：没有价格节点的页面已经稳定（加载完 + 正文 ≥ 200 字且与上次探测一样长）→ 可以抽了。纯函数。 */
    public static boolean settledWithoutPrice(int priceNodes, int len, int lastLen, boolean complete, boolean loading) {
        return priceNodes == 0 && len >= 200 && len == lastLen && complete && !loading;
    }
    /** onPageFinished 之后至少等这么久再抽（SPA 首屏）。 */
    public static final long MIN_SETTLE_MS = 900L;
    /** 拿会话锁最多等多久（串行；拿不到 = BUSY）。 */
    public static final long LOCK_WAIT_MS = 15000L;

    public static final int DESKTOP_W = 1366;
    public static final int DESKTOP_H = 2400;
    public static final int MOBILE_W = 412;
    public static final int MOBILE_H = 2400;

    public static final String DESKTOP_UA = WebViewSetup.PC_UA;
    public static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 正文最多带多少字回来。 */
    public static final int TEXT_MAX = 15000;

    /** 导航放行：只要 https。 */
    public static boolean allowNavigation(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("https://") || u.equals("about:blank");
    }

    private static final Pattern APP_SCHEME = Pattern.compile(
            "^(intent|openapp\\.jdmobile|openjd|jdmobile|taobao|tbopen|tmall|pinduoduo|pddopen|weixin|alipays?|smzdm|snssdk\\d*|"
                    + "market|itms-apps?|mqq\\w*|sinaweibo|vipshop|suning|dianping|imeituan|baiduboxapp|bilibili)://",
            Pattern.CASE_INSENSITIVE);

    /** 唤起 App 的 scheme（日志用来分类；无论是不是这一类，非 https 一律拦）。 */
    public static boolean isAppScheme(String url) {
        return url != null && APP_SCHEME.matcher(url.trim()).find();
    }

    /** 加载前的地址：移动链接改 PC、http 改 https；不是网页 = null。 */
    public static String prepare(String url, boolean mobile) {
        if (url == null) return null;
        String u = url.trim();
        if (!u.regionMatches(true, 0, "http://", 0, 7) && !u.regionMatches(true, 0, "https://", 0, 8)) return null;
        if (u.regionMatches(true, 0, "http://", 0, 7)) u = "https://" + u.substring(7);
        return mobile ? u : ShopSearchUrls.toDesktop(u);
    }

    private static final Pattern CAPTCHA_URL = Pattern.compile(
            "risk_handler|/captcha|captcha\\.|punish|_____tmd_____|nocaptcha|/verify|safe\\.jd\\.com|sec\\.taobao|x5sec|anti-?bot|probe\\.js|redirect_to_search",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOGIN_URL = Pattern.compile(
            "^https?://(?:passport|login|plogin|reg|account)\\.|/login(?:\\.|/|\\?|$)|/member/login|mlogin|login\\.htm",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPTCHA_TEXT = Pattern.compile("验证码|安全验证|滑块|拖动|向右滑动|人机验证|访问异常|网络环境异常|请完成验证|异常流量|访问受限");
    private static final Pattern LOGIN_TEXT = Pattern.compile("请登录|登录后|扫码登录|密码登录|账号登录|短信登录|亲，请登录|登录查看|立即登录");

    public enum Verdict { OK, LOGIN, CAPTCHA }

    /**
     * 渲染后页面的判定：落到验证 / 登录页（地址）→ CAPTCHA / LOGIN；没抽到任何价格时，再看正文是不是登录墙 / 验证页、有没有可见密码框。
     * 有价格就算 OK（列表页角落里的「请登录」不算墙）。
     */
    public static Verdict classify(String finalUrl, String text, boolean passwordBox, int items) {
        String u = finalUrl == null ? "" : finalUrl;
        if (CAPTCHA_URL.matcher(u).find()) return Verdict.CAPTCHA;
        if (LOGIN_URL.matcher(u).find()) return Verdict.LOGIN;
        if (items > 0) return Verdict.OK;
        String t = text == null ? "" : text;
        String head = t.length() > 3000 ? t.substring(0, 3000) : t;
        if (CAPTCHA_TEXT.matcher(head).find() && t.length() < 3000) return Verdict.CAPTCHA;
        if (passwordBox || (LOGIN_TEXT.matcher(head).find() && t.length() < 3000)) return Verdict.LOGIN;
        return Verdict.OK;
    }

    private static final Pattern JS_PLATFORM = Pattern.compile(
            "(?:^|[./])(?:jd\\.com|3\\.cn|tmall\\.com|taobao\\.com|yangkeduo\\.com|smzdm\\.com|manmanbuy\\.com)(?:[/:?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE = Pattern.compile("[¥￥]\\s*\\d|\\d+(?:\\.\\d{1,2})?\\s*元");

    /** 这个站是 JS 渲染为主（京东 / 天猫 / 淘宝 / 拼多多 / 值得买 / 慢慢买）。 */
    public static boolean jsHeavy(String url) {
        if (url == null) return false;
        String h = url.replaceFirst("^https?://", "");
        int k = h.indexOf('/');
        h = k > 0 ? h.substring(0, k) : h;
        return JS_PLATFORM.matcher("." + h + "/").find();
    }

    /**
     * 静态读取之后要不要改用渲染读取：没读到 / 正文太短（&lt;300 字，像 JS 外壳）/ JS 渲染站且正文里没有价格。
     */
    public static boolean needsRender(String url, String staticText) {
        String t = staticText == null ? "" : staticText.trim();
        if (t.length() < 300) return true;
        return jsHeavy(url) && !PRICE.matcher(t).find();
    }

    /** 跳过原因（写进结论）。 */
    public static String skipNote(String site, Verdict v) {
        if (v == Verdict.LOGIN) return site + "电脑版网页要登录才出结果，已跳过（没有尝试登录）";
        if (v == Verdict.CAPTCHA) return site + "网页要安全验证（验证码 / 滑块），已跳过";
        return "";
    }
}
