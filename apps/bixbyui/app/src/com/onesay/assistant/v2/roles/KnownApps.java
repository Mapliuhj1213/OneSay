package com.onesay.assistant.v2.roles;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ 11-04 I（白盒审计·未安装软件）：常见 App 名 → 安卓包名（纯函数、零依赖）。
 * 用途：app.launch 只带 {"app":"抖音"}、没带 pkg 时，NOT_FOUND_APP 也能出「未安装 X，点此安装」卡片。
 * 只收确定的包名；查不到返回 null（调用方照旧走文字兜底）。
 */
public final class KnownApps {
    private KnownApps() {}

    private static final Map<String, String> MAP = new LinkedHashMap<String, String>();
    private static void put(String pkg, String... names) { for (String n : names) MAP.put(norm(n), pkg); }

    static {
        put("com.tencent.mm", "微信", "wechat");
        put("com.tencent.mobileqq", "qq");
        put("com.ss.android.ugc.aweme", "抖音", "douyin");
        put("com.smile.gifmaker", "快手");
        put("tv.danmaku.bili", "哔哩哔哩", "b站", "bilibili", "b 站");
        put("com.xingin.xhs", "小红书");
        put("com.sina.weibo", "微博", "新浪微博");
        put("com.zhihu.android", "知乎");
        put("com.ss.android.article.news", "今日头条", "头条");
        put("com.taobao.taobao", "淘宝", "手机淘宝");
        put("com.tmall.wireless", "天猫");
        put("com.jingdong.app.mall", "京东");
        put("com.xunmeng.pinduoduo", "拼多多");
        put("com.taobao.idlefish", "闲鱼");
        put("com.achievo.vipshop", "唯品会");
        put("com.qiyi.video", "爱奇艺");
        put("com.tencent.qqlive", "腾讯视频");
        put("com.youku.phone", "优酷", "优酷视频");
        put("com.lemon.lv", "剪映");
        put("com.alibaba.android.rimet", "钉钉");
        put("com.tencent.wework", "企业微信");
        put("com.ss.android.lark", "飞书");
        put("com.tencent.wemeet.app", "腾讯会议");
        put("cn.wps.moffice_eng", "wps", "wps office");
        put("com.eg.android.AlipayGphone", "支付宝");
        put("com.sankuai.meituan", "美团");
        put("me.ele", "饿了么");
        put("com.sdu.didi.psnger", "滴滴", "滴滴出行");
        put("com.autonavi.minimap", "高德", "高德地图");
        put("com.baidu.BaiduMap", "百度地图");
        put("com.netease.cloudmusic", "网易云", "网易云音乐");
        put("com.tencent.qqmusic", "qq音乐");
        put("com.luna.music", "汽水音乐");
        put("com.ximalaya.ting.android", "喜马拉雅");
        put("com.dianping.v1", "大众点评", "点评");
        put("ctrip.android.view", "携程", "携程旅行");
        put("com.MobileTicket", "12306", "铁路12306");
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s「」\"'“”]", "");
    }

    /** App 名 → 包名；查不到 null。 */
    public static String pkgOf(String appName) {
        String n = norm(appName);
        if (n.length() == 0) return null;
        String p = MAP.get(n);
        if (p != null) return p;
        if (n.endsWith("app")) return MAP.get(n.substring(0, n.length() - 3));
        return null;
    }
}
