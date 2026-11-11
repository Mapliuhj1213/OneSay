package com.onesay.assistant.v2.apifirst;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ★ feat/api-first：App 深链 / URL scheme 清单（第 ③ 档）+ 浏览器 https 兜底链接。纯 Java，JVM 可测。
 *
 * <p>原则：只做「打开到对应页面」，不替用户点任何按钮；scheme 只收官方文档写明的或广泛使用的，
 * 拿不准的 {@link Link#verified} = false 并在 {@link Link#source} 里写明。清单就是下面的 {@link #ALL}（每条带用途、来源、是否验证）。
 *
 * <p>模板占位符：{q} {dest} {from} {to} {date} {city}，填值时按 UTF-8 百分号编码（空格 → %20）。
 * {@code launch:} 开头的条目表示「没有公开的页面 scheme，只把 App 打开到首页」（按包名启动）。
 */
public final class DeepLinks {

    private DeepLinks() {}

    /** 来源标识：本应用在地图类 scheme 里报的调用方名字（高德 sourceApplication / 百度 src 要求必填）。 */
    public static final String CALLER = "onesay";
    public static final String BAIDU_SRC = "andr.onesay.assistant";

    public static final String LAUNCH = "launch:";

    /** 一条链接。pkg 为 null = 浏览器链接（https）。 */
    public static final class Link {
        public final String id;
        public final String app;
        public final String pkg;
        public final String template;
        public final String purpose;
        public final String source;
        public final boolean verified;
        /** 必填槽位（缺了就跳过这一条）。 */
        public final List<String> needs;
        public final List<String> kinds;

        Link(String id, String app, String pkg, String template, String purpose, String source, boolean verified,
             String[] needs, String... kinds) {
            this.id = id;
            this.app = app;
            this.pkg = pkg;
            this.template = template;
            this.purpose = purpose;
            this.source = source;
            this.verified = verified;
            this.needs = Collections.unmodifiableList(Arrays.asList(needs));
            this.kinds = Collections.unmodifiableList(Arrays.asList(kinds));
        }

        public boolean browser() {
            return pkg == null;
        }

        public boolean launchOnly() {
            return template.startsWith(LAUNCH);
        }

        public RouteTier tier() {
            return browser() ? RouteTier.BROWSER : RouteTier.DEEPLINK;
        }

        /** 缺哪个必填槽位（都有 → null）。 */
        public String missing(ApiIntent in) {
            for (String n : needs) if (!in.has(n)) return n;
            return null;
        }

        /** 填好的链接（launch: 条目原样返回 launch:包名）。 */
        public String fill(ApiIntent in) {
            if (launchOnly()) return LAUNCH + pkg;
            return DeepLinks.fill(template, in);
        }
    }

    private static final String[] NONE = new String[0];
    private static final String[] Q = {"q"};
    private static final String[] DEST = {"dest"};

    /** 按优先级排好的清单：同一种类里，先 App 深链（按表中顺序），再浏览器链接。 */
    public static final List<Link> ALL = Collections.unmodifiableList(Arrays.asList(
            // ---- 地图：导航 / 路线 ----
            new Link("amap.route", "高德地图", "com.autonavi.minimap",
                    "amapuri://route/plan/?sourceApplication=" + CALLER + "&dname={dest}&dev=0&t=0",
                    "规划到目的地的路线（选好后可开始导航）",
                    "官方文档 lbs.amap.com/api/amap-mobile/guide/android/route", true, DEST, ApiIntent.NAV_TO),
            new Link("baidu.route", "百度地图", "com.baidu.BaiduMap",
                    "baidumap://map/direction?destination={dest}&mode=driving&src=" + BAIDU_SRC,
                    "规划到目的地的驾车路线",
                    "官方文档 lbs.baidu.com 地图调起 API（Android 端 2.3.2 路线规划）", true, DEST, ApiIntent.NAV_TO),
            new Link("web.amap.search.dest", "高德地图网页", null,
                    "https://uri.amap.com/search?keyword={dest}&view=map&src=" + CALLER + "&callnative=1",
                    "浏览器里搜目的地（装了高德会尝试调起）",
                    "官方文档 lbs.amap.com/api/uri-api/guide/search/search", true, DEST, ApiIntent.NAV_TO),
            // ---- 地图：周边 ----
            new Link("amap.poi", "高德地图", "com.autonavi.minimap",
                    "androidamap://poi?sourceApplication=" + CALLER + "&keywords={q}&dev=0",
                    "搜周边（超市、加油站、餐厅…）",
                    "官方文档 lbs.amap.com/api/amap-mobile/guide/android/search", true, Q, ApiIntent.POI_NEARBY),
            new Link("baidu.poi", "百度地图", "com.baidu.BaiduMap",
                    "baidumap://map/place/search?query={q}&src=" + BAIDU_SRC,
                    "搜周边地点",
                    "官方文档 lbs.baidu.com 地图调起 API（Android 端 2.3.1 POI 搜索）", true, Q, ApiIntent.POI_NEARBY),
            new Link("web.amap.search", "高德地图网页", null,
                    "https://uri.amap.com/search?keyword={q}&src=" + CALLER + "&callnative=1",
                    "浏览器里搜周边",
                    "官方文档 lbs.amap.com/api/uri-api/guide/search/search", true, Q, ApiIntent.POI_NEARBY),
            // ---- 火车票（只打开到查询 / 预订页，付款交给用户）----
            new Link("12306.app", "铁路12306", "com.MobileTicket", LAUNCH,
                    "打开 12306（没有公开的页面 scheme，只开到首页）",
                    "包名广泛使用；12306 未公开 scheme（未验证）", false, NONE, ApiIntent.TRAIN_BOOK),
            new Link("ctrip.app", "携程旅行", "ctrip.android.view", LAUNCH,
                    "打开携程（火车票页 scheme 未公开，只开到首页）",
                    "包名广泛使用；火车票页 scheme 未公开（未验证）", false, NONE, ApiIntent.TRAIN_BOOK),
            new Link("ly.app", "同程旅行", "com.tongcheng.android", LAUNCH,
                    "打开同程（火车票页 scheme 未公开，只开到首页）",
                    "包名广泛使用；火车票页 scheme 未公开（未验证）", false, NONE, ApiIntent.TRAIN_BOOK),
            new Link("web.12306.query", "12306 网页", null,
                    "https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc&fs={from}&ts={to}&date={date}&flag=N,N,Y",
                    "12306 官网余票查询页（带出发 / 到达 / 日期）",
                    "广泛使用（官网查询页参数，社区脚本普遍使用）；只带站名、不带电报码时是否自动预填未验证",
                    false, new String[]{"from", "to"}, ApiIntent.TRAIN_BOOK, ApiIntent.TRAIN_QUERY),
            // ---- 外卖 ----
            new Link("meituan.search", "美团", "com.sankuai.meituan",
                    "imeituan://www.meituan.com/search?q={q}",
                    "美团站内搜索（店 / 菜）",
                    "广泛使用（公开 scheme 收集，多为 iOS 实测）；安卓未实测", false, Q, ApiIntent.FOOD_ORDER),
            new Link("meituanwaimai.search", "美团外卖", "com.sankuai.meituan.takeoutnew",
                    "meituanwaimai://waimai.meituan.com/search?query={q}",
                    "美团外卖 App 内搜索",
                    "广泛使用（公开 scheme 收集，多为 iOS 实测）；安卓未实测", false, Q, ApiIntent.FOOD_ORDER),
            new Link("eleme.app", "饿了么", "me.ele", LAUNCH,
                    "打开饿了么（站内搜索 scheme 未公开，只开到首页）",
                    "包名广泛使用；搜索页 scheme 未公开（未验证）", false, NONE, ApiIntent.FOOD_ORDER),
            new Link("web.meituan.waimai", "美团外卖网页", null,
                    "https://h5.waimai.meituan.com/waimai/mindex/home",
                    "美团外卖 H5 首页",
                    "未验证（H5 入口地址可能随版本变）", false, NONE, ApiIntent.FOOD_ORDER),
            // ---- 购物 ----
            new Link("taobao.search", "淘宝", "com.taobao.taobao",
                    "taobao://s.taobao.com/search?q={q}",
                    "淘宝搜索结果页",
                    "广泛使用（公开 scheme 收集）；安卓未实测", false, Q, ApiIntent.GOODS_SEARCH, ApiIntent.SHOP_BUY),
            new Link("jd.search", "京东", "com.jingdong.app.mall",
                    "openapp.jdmobile://virtual?params={jdparams}",
                    "京东搜索结果页",
                    "广泛使用（公开 scheme 收集，params 为 JSON）；安卓未实测", false, Q, ApiIntent.GOODS_SEARCH, ApiIntent.SHOP_BUY),
            new Link("web.taobao.search", "淘宝网页", null,
                    "https://s.taobao.com/search?q={q}",
                    "淘宝网页搜索",
                    "广泛使用（淘宝网页搜索地址）", true, Q, ApiIntent.GOODS_SEARCH, ApiIntent.SHOP_BUY),
            new Link("web.jd.search", "京东网页", null,
                    "https://search.jd.com/Search?keyword={q}&enc=utf-8",
                    "京东网页搜索",
                    "广泛使用（京东网页搜索地址，本仓库读网页已在用）", true, Q, ApiIntent.GOODS_SEARCH, ApiIntent.SHOP_BUY),
            // ---- 查信息兜底 ----
            new Link("web.weather", "必应搜索", null,
                    "https://www.bing.com/search?q={city}%E5%A4%A9%E6%B0%94",
                    "浏览器里查天气",
                    "通用搜索地址", true, NONE, ApiIntent.WEATHER),
            new Link("web.baidu.search", "百度搜索", null,
                    "https://www.baidu.com/s?wd={q}",
                    "浏览器里搜索",
                    "通用搜索地址", true, Q, ApiIntent.WEB_SEARCH)
    ));

    /** 某个种类的全部链接（App 深链在前、浏览器链接在后；表内顺序不变）。 */
    public static List<Link> forKind(String kind) {
        List<Link> apps = new ArrayList<Link>();
        List<Link> web = new ArrayList<Link>();
        for (Link l : ALL) {
            if (!l.kinds.contains(kind)) continue;
            if (l.browser()) web.add(l);
            else apps.add(l);
        }
        apps.addAll(web);
        return apps;
    }

    public static Link byId(String id) {
        for (Link l : ALL) if (l.id.equals(id)) return l;
        return null;
    }

    /** 用户点名的 App 排到最前（只在 App 深链之间调整，浏览器链接永远在后）。 */
    public static List<Link> preferApp(List<Link> links, String app) {
        if (app == null || app.trim().length() == 0) return links;
        String a = app.trim();
        List<Link> first = new ArrayList<Link>();
        List<Link> rest = new ArrayList<Link>();
        for (Link l : links) {
            if (!l.browser() && (l.app.contains(a) || a.contains(l.app) || a.equals(l.pkg))) first.add(l);
            else rest.add(l);
        }
        first.addAll(rest);
        return first;
    }

    // ------------------------------------------------------------------ 填值

    static String fill(String template, ApiIntent in) {
        String out = template;
        out = out.replace("{jdparams}", enc(jdParams(in.slot("q"))));
        for (String k : new String[]{"q", "dest", "from", "to", "date", "city"}) {
            out = out.replace("{" + k + "}", enc(in.slot(k)));
        }
        return out;
    }

    static String jdParams(String q) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            if (c < 0x20) continue;
            sb.append(c);
        }
        return "{\"category\":\"jump\",\"des\":\"productList\",\"keyWord\":\"" + sb + "\",\"from\":\"search\"}";
    }

    /** UTF-8 百分号编码，空格写 %20（不用 +，scheme 里的 + 不一定当空格）。 */
    public static String enc(String s) {
        if (s == null || s.length() == 0) return "";
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
