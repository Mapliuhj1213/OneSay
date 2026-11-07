package com.onesay.assistant.v2.errand.web;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ★ fix/shop-research（11-03 14:37）：{@link ReaderScript#EXTRACT} 输出的样例（照京东 / 值得买 PC 页的结构手写；价格取自 RESEARCH-TEST-basilisk §3 基准）。
 * evaluateJavascript 回给 Java 的是「JSON 编码过的字符串」，{@link #encoded} 模拟这一层。
 */
public final class ReaderFixtures {

    private ReaderFixtures() {}

    static JSONObject card(String t, String p, String a, String h, String s) throws Exception {
        JSONObject c = new JSONObject();
        c.put("t", t);
        c.put("p", p);
        c.put("a", a);
        c.put("h", h);
        c.put("s", s);
        return c;
    }

    /** 京东 PC 搜索「雷蛇巴塞利斯蛇v3」：4 个相关款 + 1 个配件 + 1 个别的牌子 + 1 张浮层里的卡片 + 「打开京东 App」浮层。 */
    public static String jdSearch() throws Exception {
        JSONObject o = new JSONObject();
        o.put("u", "https://search.jd.com/Search?enc=utf-8&keyword=%E9%9B%B7%E8%9B%87%E5%B7%B4%E5%A1%9E%E5%88%A9%E6%96%AF%E8%9B%87v3");
        o.put("ti", "雷蛇巴塞利斯蛇v3 - 商品搜索 - 京东");
        JSONArray cards = new JSONArray();
        cards.put(card("￥439.12 雷蛇（Razer）巴塞利斯蛇V3专业版 35K 无线游戏鼠标 黑色 券后 2万+条评价 京东游戏外设旗舰店 关注",
                "￥439.12", "雷蛇（Razer）巴塞利斯蛇V3专业版 35K 无线游戏鼠标 黑色", "https://item.jd.com/100000000001.html", "京东游戏外设旗舰店"));
        cards.put(card("￥527.12 雷蛇 Razer 巴塞利斯蛇V3 Pro 35K 无线三模鼠标 自营 5000+条评价 雷蛇京东自营旗舰店",
                "￥527.12", "雷蛇 Razer 巴塞利斯蛇V3 Pro 35K 无线三模鼠标", "https://item.jd.com/100000000002.html", "雷蛇京东自营旗舰店"));
        cards.put(card("￥245.52 雷蛇 巴塞利斯蛇V3 X 极速版 无线蓝牙双模游戏鼠标 雷蛇外设专营店",
                "￥245.52", "雷蛇 巴塞利斯蛇V3 X 极速版 无线蓝牙双模游戏鼠标", "https://item.jd.com/100000000003.html", ""));
        cards.put(card("183.05 雷蛇巴塞利斯蛇V3 有线游戏鼠标 RGB 11键可编程 京东自营",
                "183.05", "雷蛇巴塞利斯蛇V3 有线游戏鼠标 RGB 11键可编程", "https://item.jd.com/100000000004.html", ""));
        cards.put(card("￥19.90 适用雷蛇巴塞利斯蛇V3 防滑贴 鼠标贴 吸汗 某某数码配件店",
                "￥19.90", "适用雷蛇巴塞利斯蛇V3 防滑贴 鼠标贴 吸汗", "https://item.jd.com/100000000005.html", "某某数码配件店"));
        cards.put(card("￥299.00 罗技（G）G502 HERO 主宰者有线鼠标 罗技京东自营旗舰店",
                "￥299.00", "罗技（G）G502 HERO 主宰者有线鼠标", "https://item.jd.com/100000000006.html", "罗技京东自营旗舰店"));
        cards.put(card("打开京东App 新人专享 ￥10 无门槛券 立即打开",
                "￥10", "打开京东App 新人专享无门槛券", "https://h5.m.jd.com/download", ""));
        o.put("cards", cards);
        JSONArray ov = new JSONArray();
        ov.put("打开京东App 新人专享 ￥10 无门槛券 立即打开");
        o.put("ov", ov);
        o.put("main", new JSONObject().put("t", "").put("p", "￥439.12").put("s", ""));
        o.put("pw", false);
        o.put("tx", "京东\n全部商品分类\n打开京东App 新人专享 ￥10 无门槛券 立即打开\n下载京东App\n雷蛇（Razer）巴塞利斯蛇V3专业版 35K 无线游戏鼠标 黑色\n￥439.12\n"
                + "京东游戏外设旗舰店\n雷蛇 Razer 巴塞利斯蛇V3 Pro 35K 无线三模鼠标\n￥527.12\n自营");
        return encoded(o);
    }

    /** 什么值得买 PC 搜索：条目带商城、爆料时间、券后条件。 */
    public static String smzdmSearch() throws Exception {
        JSONObject o = new JSONObject();
        o.put("u", "https://search.smzdm.com/?c=home&v=b&s=%E9%9B%B7%E8%9B%87%E5%B7%B4%E5%A1%9E%E5%88%A9%E6%96%AF%E8%9B%87v3");
        o.put("ti", "雷蛇巴塞利斯蛇v3_什么值得买");
        JSONArray cards = new JSONArray();
        cards.put(card("雷蛇 巴塞利斯蛇 V3 专业版 35K 无线鼠标 439.12元（需用券） 京东 11-03 10:20 值 32 不值 2",
                "439.12元（需用券）", "雷蛇 巴塞利斯蛇 V3 专业版 35K 无线鼠标", "https://www.smzdm.com/p/100000001/", ""));
        cards.put(card("雷蛇 巴塞利斯蛇V3 X 极速版 245.52元 天猫 3小时前",
                "245.52元", "雷蛇 巴塞利斯蛇V3 X 极速版", "https://www.smzdm.com/p/100000002/", ""));
        o.put("cards", cards);
        o.put("ov", new JSONArray());
        o.put("main", new JSONObject());
        o.put("pw", false);
        o.put("tx", "什么值得买 搜索结果 雷蛇巴塞利斯蛇v3");
        return encoded(o);
    }

    /** 京东商品页（没有列表卡片，只有主信息）。 */
    public static String jdItem() throws Exception {
        JSONObject o = new JSONObject();
        o.put("u", "https://item.jd.com/100000000002.html");
        o.put("ti", "【雷蛇巴塞利斯蛇V3 Pro】雷蛇 Razer 巴塞利斯蛇V3 Pro - 京东");
        o.put("cards", new JSONArray());
        o.put("ov", new JSONArray().put("扫码下载京东App"));
        o.put("main", new JSONObject().put("t", "雷蛇 Razer 巴塞利斯蛇V3 Pro 35K 无线三模鼠标").put("p", "￥527.12").put("s", "雷蛇京东自营旗舰店"));
        o.put("pw", false);
        o.put("tx", "扫码下载京东App\n雷蛇 Razer 巴塞利斯蛇V3 Pro 35K 无线三模鼠标\n京东价 ￥527.12\n京东自营");
        return encoded(o);
    }

    /** 天猫列表跳到淘宝登录。 */
    public static String taobaoLogin() throws Exception {
        JSONObject o = new JSONObject();
        o.put("u", "https://login.taobao.com/member/login.jhtml?redirectURL=https%3A%2F%2Fs.taobao.com%2Fsearch%3Fq%3Dx");
        o.put("ti", "淘宝网 - 淘！我喜欢");
        o.put("cards", new JSONArray());
        o.put("ov", new JSONArray());
        o.put("main", new JSONObject());
        o.put("pw", true);
        o.put("tx", "密码登录 短信登录 账号名/邮箱/手机号 请输入登录密码 登录 免费注册");
        return encoded(o);
    }

    /** 淘宝搜索页没跳走，但页面是登录浮层（可见密码框、没价格）。 */
    public static String taobaoSearchWall() throws Exception {
        JSONObject o = new JSONObject();
        o.put("u", "https://s.taobao.com/search?q=x");
        o.put("ti", "淘宝搜索");
        o.put("cards", new JSONArray());
        o.put("ov", new JSONArray());
        o.put("main", new JSONObject());
        o.put("pw", true);
        o.put("tx", "亲，请登录 扫码登录更安全");
        return encoded(o);
    }

    /** 京东风控验证页。 */
    public static String jdRisk() throws Exception {
        JSONObject o = new JSONObject();
        o.put("u", "https://cfe.m.jd.com/privatedomain/risk_handler/03101900/?returnurl=https%3A%2F%2Fsearch.jd.com%2FSearch");
        o.put("ti", "京东验证");
        o.put("cards", new JSONArray());
        o.put("ov", new JSONArray());
        o.put("main", new JSONObject());
        o.put("pw", false);
        o.put("tx", "安全验证 请完成下方验证 向右滑动完成拼图");
        return encoded(o);
    }

    /** evaluateJavascript 的返回：字符串再 JSON 编码一层。 */
    public static String encoded(JSONObject o) throws Exception {
        String s = new JSONArray().put(o.toString()).toString();
        return s.substring(1, s.length() - 1);
    }
}
