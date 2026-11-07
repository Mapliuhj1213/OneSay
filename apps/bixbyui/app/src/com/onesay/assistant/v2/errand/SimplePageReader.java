package com.onesay.assistant.v2.errand;

import com.onesay.assistant.v2.search.TavilyExtractProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/research-depth：没配 Tavily 时的网页正文兜底 —— 直接 HTTP GET 原网页、去掉脚本 / 样式 / 标签，留纯文本。
 * 只读、不带 Cookie、不执行脚本；JS 渲染的页面（京东 / 天猫详情）多半读不到价格，比价 / 资讯站（smzdm、zol、ithome…）通常可以。
 * 纯 Java（{@link #toText} JVM 可测）。
 */
public final class SimplePageReader {

    private SimplePageReader() {}

    public static final int TIMEOUT_MS = 7000;
    public static final int MAX_BYTES = 1500 * 1024;
    public static final int MAX_PAGES = 6;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** 读一组链接（并行，最多 {@link #MAX_PAGES} 个）。失败的页给 error。 */
    public static List<TavilyExtractProvider.Page> read(List<String> urls) {
        final List<String> in = new ArrayList<String>();
        if (urls != null) for (String u : urls) if (u != null && u.startsWith("http") && in.size() < MAX_PAGES) in.add(u);
        final TavilyExtractProvider.Page[] out = new TavilyExtractProvider.Page[in.size()];
        List<Thread> ts = new ArrayList<Thread>();
        for (int i = 0; i < in.size(); i++) {
            final int k = i;
            ts.add(com.onesay.assistant.v2.util.Threads.start("page-read-" + i, new Runnable() {
                @Override public void run() {
                    out[k] = fetch(in.get(k));
                }
            }));
        }
        for (Thread t : ts) {
            try {
                t.join(TIMEOUT_MS * 2L);
            } catch (InterruptedException e) {
                break;
            }
        }
        List<TavilyExtractProvider.Page> l = new ArrayList<TavilyExtractProvider.Page>();
        for (int i = 0; i < out.length; i++) l.add(out[i] != null ? out[i] : new TavilyExtractProvider.Page(in.get(i), null, "timeout"));
        return l;
    }

    public static TavilyExtractProvider.Page fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return new TavilyExtractProvider.Page(url, null, "http " + code);
            String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(java.util.Locale.ROOT);
            if (ct.length() > 0 && !ct.contains("html") && !ct.contains("text")) return new TavilyExtractProvider.Page(url, null, "not html");
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0 && bo.size() < MAX_BYTES) bo.write(buf, 0, n);
            is.close();
            byte[] b = bo.toByteArray();
            String cs = charset(ct, b);
            String html = new String(b, cs);
            try {
                com.onesay.assistant.v2.router.ResearchVisuals.collectImage(com.onesay.assistant.v2.router.ResearchVisuals.ogImage(html, url));   // ★ 11-04 ④：页面主图（只记地址，不下载）
            } catch (Throwable ignored) {
            }
            return new TavilyExtractProvider.Page(url, toText(html), null);
        } catch (Throwable t) {
            return new TavilyExtractProvider.Page(url, null, t.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * ★ fix/shop-research（11-03 15:53 比价网取价）：读原始正文（HTML / JSON，不去标签）；只放行 https、桌面 UA、不带 Cookie、
     * 最多 maxBytes 字节；正文超过 maxBytes 回 {@link com.onesay.assistant.v2.errand.compare.CompareSites#OVERSIZE} 标记（不交截断内容）。失败 / 非 2xx 返回 null。
     */
    public static String fetchRaw(String url, int maxBytes) {
        if (url == null || !url.startsWith("https://")) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            if (!c.getURL().getProtocol().equals("https")) return null;   // 跳到 http 的不要
            String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(java.util.Locale.ROOT);
            InputStream is = c.getInputStream();
            long[] got = new long[1];
            byte[] b;
            try {
                b = readCapped(is, maxBytes, got);
            } finally {
                is.close();
            }
            if (b == null) return com.onesay.assistant.v2.errand.compare.CompareSites.OVERSIZE + got[0];   // 超限：不交截断内容
            return new String(b, charset(ct, b));
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 读满流，最多 maxBytes 字节。正文超过 maxBytes → 返回 null（不交截断内容），got[0] = 停下时已读字节数（> maxBytes）；
     * 否则 got[0] = 正文长度。
     */
    public static byte[] readCapped(InputStream is, int maxBytes, long[] got) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = is.read(buf)) >= 0) {
            if (n == 0) continue;
            total += n;
            if (total > maxBytes) {
                if (got != null) got[0] = total;
                return null;
            }
            bo.write(buf, 0, n);
        }
        if (got != null) got[0] = total;
        return bo.toByteArray();
    }

    private static final Pattern CS = Pattern.compile("charset=[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    static String charset(String contentType, byte[] head) {
        Matcher m = CS.matcher(contentType == null ? "" : contentType);
        if (m.find()) return norm(m.group(1));
        String h = new String(head, 0, Math.min(head.length, 4096), java.nio.charset.Charset.forName("ISO-8859-1"));
        m = CS.matcher(h);
        if (m.find()) return norm(m.group(1));
        return "UTF-8";
    }

    private static String norm(String cs) {
        String c = cs.toUpperCase(java.util.Locale.ROOT);
        if (c.equals("GB2312") || c.equals("GBK") || c.equals("GB18030")) return "GB18030";
        try {
            java.nio.charset.Charset.forName(c);
            return c;
        } catch (Throwable t) {
            return "UTF-8";
        }
    }

    private static final Pattern DROP = Pattern.compile("(?is)<(script|style|noscript|svg|head|template)\\b.*?</\\1\\s*>|<!--.*?-->");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(?:p|div|br|li|tr|h[1-6]|section|article|table|ul|ol|dd|dt)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");

    /** HTML → 纯文本（段落换行保留，空白折叠）。 */
    public static String toText(String html) {
        if (html == null) return "";
        String s = DROP.matcher(html).replaceAll(" ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&yen;", "¥").replace("&#165;", "¥");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\\n[ \\n]*", "\n").trim();
        return s;
    }
}
