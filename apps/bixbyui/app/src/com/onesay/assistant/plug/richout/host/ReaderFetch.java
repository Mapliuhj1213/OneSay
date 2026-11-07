/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。阅读模式取正文（后台线程）。
 */
package com.onesay.assistant.plug.richout.host;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.plug.richout.core.LinkPolicy;
import com.onesay.assistant.plug.richout.core.ReaderText;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;

/**
 * 阅读模式取正文的前两档（第三档是原网页 WebView 里跑 PageScript 读 PageSnapshot，在 {@link ReaderSheet} 里做）：
 * <ol>
 *   <li>直接取页：普通 HTTP GET，<b>不带任何 Cookie</b>、不执行脚本；每一跳重定向都过 {@link LinkPolicy}（不许跳到本机 / 非 http(s)）；
 *       最多 5 跳、3 MB、只收 text/html / text/plain；按头 / meta 认编码（GBK 网站也能读）；</li>
 *   <li>Tavily extract（配了 Key 才用；把网址交给 Tavily 抽正文，和跑腿同一个 Key）。</li>
 * </ol>
 */
final class ReaderFetch {

    private ReaderFetch() {}

    private static final String TAG = "RichOut";
    static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";
    static final int MAX_BYTES = 3 * 1024 * 1024;

    static final class Result {
        final ReaderText.Doc doc;
        final String via;
        final String finalUrl;

        Result(ReaderText.Doc doc, String via, String finalUrl) {
            this.doc = doc;
            this.via = via;
            this.finalUrl = finalUrl;
        }
    }

    static Result fetch(Context ctx, String url) {
        String finalUrl = url;
        ReaderText.Doc doc = null;
        try {
            String[] got = direct(url);
            if (got != null) {
                finalUrl = got[0];
                doc = ReaderText.extract(got[1]);
                if (doc.usable()) return new Result(doc, "直接读取", finalUrl);
            }
        } catch (Throwable t) {
            Log.i(TAG, "[RICHOUT] 阅读模式直接取页失败: " + t.getMessage());
        }
        try {
            com.onesay.assistant.v2.search.TavilyExtractProvider tp =
                    new com.onesay.assistant.v2.search.TavilyExtractProvider(new com.onesay.assistant.v2.search.SearchConfig(ctx));
            if (tp.isReady()) {
                List<com.onesay.assistant.v2.search.TavilyExtractProvider.Page> pages = tp.extract(Collections.singletonList(finalUrl), null);
                for (com.onesay.assistant.v2.search.TavilyExtractProvider.Page p : pages) {
                    if (!p.ok()) continue;
                    ReaderText.Doc td = ReaderText.fromText(doc != null ? doc.title : "", p.content);
                    if (td.usable()) return new Result(td, "Tavily 提取", finalUrl);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "[RICHOUT] 阅读模式 Tavily 失败: " + t.getMessage());
        }
        return new Result(doc, null, finalUrl);
    }

    /** @return {最终地址, html}；不是网页 / 被策略拦下返回 null。 */
    static String[] direct(String url) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            LinkPolicy.Decision d = LinkPolicy.decide(cur);
            if (d.action != LinkPolicy.Action.READER) {
                Log.i(TAG, "[RICHOUT] 阅读模式：跳转被拦 " + d);
                return null;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(d.url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");
            c.setRequestProperty("Cookie", "");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    cur = new URL(new URL(d.url), loc).toString();
                    continue;
                }
                if (code != 200) return null;
                String ct = c.getContentType();
                if (ct != null && !ct.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")
                        && !ct.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")
                        && !ct.toLowerCase(java.util.Locale.ROOT).startsWith("application/xhtml")) return null;
                byte[] body = readCapped(c.getInputStream(), MAX_BYTES);
                String cs = ReaderText.charsetOf(ct);
                if (cs == null) cs = ReaderText.metaCharset(body);
                Charset charset;
                try {
                    charset = Charset.forName(cs == null ? "UTF-8" : cs);
                } catch (Throwable t) {
                    charset = Charset.forName("UTF-8");
                }
                String html = new String(body, charset);
                if (ct != null && ct.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")) {
                    html = "<body><article><pre>" + html.replace("&", "&amp;").replace("<", "&lt;") + "</pre></article></body>";
                }
                return new String[] {d.url, html};
            } finally {
                c.disconnect();
            }
        }
        return null;
    }

    private static byte[] readCapped(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, Math.min(n, max - bo.size()));
                if (bo.size() >= max) break;
            }
        } finally {
            in.close();
        }
        return bo.toByteArray();
    }
}
