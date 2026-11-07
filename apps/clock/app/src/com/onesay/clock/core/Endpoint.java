package com.onesay.clock.core;

/** 用户可配的服务地址校验（纯 Java，JVM 测）：只认 https://，不允许地址里带凭据 / 查询串，长度有上限。 */
public final class Endpoint {
    private Endpoint() { }

    /** 合法则返回去掉首尾空白的地址，否则返回 ""（= 未配置，不发任何请求）。 */
    public static String clean(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.length() == 0 || u.length() > 300) return "";
        if (!u.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) return "";
        String rest = u.substring(8);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        if (host.length() == 0 || host.indexOf('@') >= 0 || host.indexOf(' ') >= 0) return "";
        if (u.indexOf('?') >= 0 || u.indexOf('#') >= 0) return "";
        return u;
    }

    /** 给设置页：为什么不收这个地址；合法返回 null。 */
    public static String why(String url) {
        if (url == null || url.trim().length() == 0) return "地址为空";
        if (!url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) return "只支持 https://（明文 http 会泄露 Key）";
        return clean(url).length() == 0 ? "地址格式不对（不能带账号、?查询串或 #）" : null;
    }

    /** DashScope 兼容：由合成地址推出「列出自定义音色」地址（同主机 /api/v1/services/audio/tts/customization）；推不出返回 ""。 */
    public static String voiceListUrl(String synthUrl) {
        String u = clean(synthUrl);
        int i = u.indexOf("/api/v1/services/audio/tts/");
        return i < 0 ? "" : u.substring(0, i) + "/api/v1/services/audio/tts/customization";
    }
}
