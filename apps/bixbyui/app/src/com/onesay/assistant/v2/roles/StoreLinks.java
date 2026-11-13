package com.onesay.assistant.v2.roles;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [r2/bike-chooser] 应用网格用到的跳转链接（纯 Java，JVM 可测；Android 层只负责按顺序 startActivity，第一个能解析的就用）。
 *
 * <ul>
 *   <li>「打开应用商店查看更多」：{@code market://search}（默认应用商店），
 *       再 {@code market://search?q=<搜索词>&c=apps}（交给系统里能处理 market: 的商店）；
 *       没有任何商店能处理 → 浏览器打开搜索页 {@link #WEB_SEARCH}。</li>
 *   <li>已安装 App：先试 open_uri（限定该包，比如支付宝扫一扫），失败 → 按包名启动主界面。</li>
 *   <li>未安装 App 的「下载」：沿用 {@link InstallLinkPlanner} 的商店详情页链（国内三星商店 / 国外 Play / market://details），
 *       连 market: 都解析不了 → 浏览器打开 {@link #webDownloadUrl}。</li>
 * </ul>
 */
public final class StoreLinks {

    private StoreLinks() {}

    /** 浏览器兜底的搜索页（百度：国内可直连，不依赖任何商店）。 */
    public static final String WEB_SEARCH = "https://www.baidu.com/s?wd=";

    public static final class Spec {
        public final String action;
        public final String uri;
        /** 限定由哪个包处理（null = 系统选）。 */
        public final String pkg;
        public final String why;

        Spec(String action, String uri, String pkg, String why) {
            this.action = action;
            this.uri = uri;
            this.pkg = pkg;
            this.why = why;
        }

        @Override public String toString() {
            return why + " " + uri + (pkg != null ? " @" + pkg : "");
        }
    }

    public static final String VIEW = "android.intent.action.VIEW";

    /** 「查看更多」的候选（按顺序试）。query 已清洗；为空返回空表（不显示链接）。 */
    public static List<Spec> storeSearch(String query) {
        String q = AppChoiceSpec.sanitizeQuery(query);
        if (q == null) return Collections.emptyList();
        List<Spec> l = new ArrayList<Spec>();
        // 通用 market:// 搜索（由用户的默认应用商店处理），解析不到再用浏览器
        l.add(new Spec(VIEW, "market://search?q=" + enc(q) + "&c=apps", null, "商店搜索"));
        l.add(new Spec(VIEW, WEB_SEARCH + enc(q + " App 下载"), null, "浏览器搜索"));
        return l;
    }

    /** 已安装 App 的打开候选：open_uri（限定该包）在前；最后一项 uri 为 null = 按包名启动主界面。 */
    public static List<Spec> openApp(String pkg, String openUri) {
        List<Spec> l = new ArrayList<Spec>();
        if (openUri != null && safeDeepLink(openUri)) l.add(new Spec(VIEW, openUri, pkg, "深链"));
        l.add(new Spec(null, null, pkg, "主界面"));
        return l;
    }

    /** 未安装且没有任何商店能处理时的浏览器下载页。 */
    public static String webDownloadUrl(String label, String pkg) {
        String k = AppChoiceSpec.sanitizeQuery(label);
        if (k == null) k = pkg;
        return WEB_SEARCH + enc(k + " 官方下载");
    }

    /** open_uri 只接受自定义 scheme（不接 http/https/file/content/intent/javascript），防 JSON 被改成网页 / 本地文件。 */
    static boolean safeDeepLink(String uri) {
        if (uri == null) return false;
        int i = uri.indexOf("://");
        if (i <= 0) return false;
        String scheme = uri.substring(0, i).toLowerCase(java.util.Locale.ROOT);
        if (!scheme.matches("[a-z][a-z0-9+.\\-]*")) return false;
        return !(scheme.equals("http") || scheme.equals("https") || scheme.equals("file") || scheme.equals("content")
                || scheme.equals("intent") || scheme.equals("javascript") || scheme.equals("data"));
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
