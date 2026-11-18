package com.onesay.desktop;

/**
 * [overnight-1119 第 7 条] 中转 / 局域网连接状态的日志降噪：同一条连接反复报同样的离线原因（中转证书 / 加密握手失败、连不上，
 * 每次重连退避都会报一次）时只记第一次，之后同样的原因不再刷屏；原因变了、恢复在线、或者过了 {@link #REPEAT_MS} 才再记一条，
 * 并带上「中间又出现 N 次」。SSL 这类英文异常换成一句中文。纯 Java，可测。
 */
final class LogQuiet {
    static final long REPEAT_MS = 10 * 60_000L;

    private String lastKey = "";
    private long lastAt;
    private int suppressed;

    /** 这条要不要记；要记返回记的文字（可能带「中间又出现 N 次」），不记返回 null。 */
    synchronized String filter(String link, boolean up, String why, long now) {
        String reason = humanize(why);
        String key = link + "|" + up + "|" + normalize(reason);
        if (key.equals(lastKey) && now - lastAt < REPEAT_MS) {
            suppressed++;
            return null;
        }
        String extra = suppressed > 0 ? "（之前同样的情况又出现 " + suppressed + " 次，已省略）" : "";
        lastKey = key;
        lastAt = now;
        suppressed = 0;
        return link + " " + (up ? "在线" : "离线") + "（" + reason + "）" + extra;
    }

    /** 去掉会变的部分（端口、毫秒、地址），同一种错误认成同一条。 */
    static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\d+", "#");
    }

    /** 英文异常 → 中文一句（不认识的原样留着）。 */
    static String humanize(String why) {
        String w = why == null ? "" : why;
        String low = w.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("sslhandshake") || low.contains("ssl") && low.contains("handshake")) return "中转加密握手失败（证书或网络被拦），会自动重试";
        if (low.contains("certificate") || low.contains("pkix")) return "中转证书校验没通过，会自动重试";
        if (low.contains("sslexception") || low.contains("ssl")) return "中转加密连接出错，会自动重试";
        if (low.contains("connectexception") || low.contains("connection refused")) return "中转连不上，会自动重试";
        if (low.contains("unresolvedaddress") || low.contains("unknownhost")) return "找不到中转地址（网络或 DNS），会自动重试";
        if (low.contains("timeout") || low.contains("timed out")) return "连中转超时，会自动重试";
        return w;
    }
}
