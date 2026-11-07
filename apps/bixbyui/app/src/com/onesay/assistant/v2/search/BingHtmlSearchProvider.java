package com.onesay.assistant.v2.search;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 必应网页通道（**免 Key 兜底**，永远排在最后）。
 *
 * <pre>
 *   GET https://cn.bing.com/search?q=&lt;关键词&gt;&amp;setlang=zh-CN
 *   从返回 HTML 的 &lt;li class="b_algo"&gt; 块里抠出标题 / 链接 / 摘要
 * </pre>
 *
 * <h3>为什么要留着这条"土办法"</h3>
 * 因为它是**唯一零配置**的通道。Tavily 和博查都要注册拿 Key，
 * 在用户还没配 Key 的时候，如果这里没有兜底，功能就是"联网问答永远没有资料" ——
 * 那等于什么都没修。有了它，今天就能用；等 Key 配好，它会自动让位给更稳的付费通道。
 *
 * <h3>它的短板（写在这里免得以后踩）</h3>
 * <ul>
 *   <li><b>依赖页面结构</b>：必应改版就得跟着改正则。所以它只能当兜底，不能当主力；</li>
 *   <li><b>延迟高</b>：整页 HTML 通常 100~300KB，比结构化 API 慢一个量级；</li>
 *   <li><b>可能被反爬</b>：命中验证页时正则抠不到东西，会安静返回空列表，
 *       上层随即降级为"如实说不知道"，不会给出错误答案。</li>
 * </ul>
 */
public final class BingHtmlSearchProvider implements SearchProvider {

    private static final String TAG = "Search/Bing";
    /** 数据源名（{@link SearchResult#provider}）。 */
    public static final String NAME = "bing-html";

    private static final String ENDPOINT = "https://cn.bing.com/search?q=";
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 12000;

    /** 结果块：<li class="b_algo"> ... </li> */
    private static final Pattern BLOCK_PATTERN =
            Pattern.compile("<li class=\"b_algo\"[\\s\\S]*?</li>", Pattern.CASE_INSENSITIVE);

    /** 标题链接：<h2 ...><a href="URL" ...>TITLE</a> */
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<h2[^>]*>[\\s\\S]{0,200}?<a[^>]*?href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
            Pattern.CASE_INSENSITIVE);

    /** 摘要：<p ...>TEXT</p> */
    private static final Pattern SNIPPET_PATTERN =
            Pattern.compile("<p[^>]*>([\\s\\S]*?)</p>", Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isReady() {
        return true; // 免 Key，只要网通就能用
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (query == null || query.trim().length() == 0) return out;

        try {
            String url = ENDPOINT + SearchHttp.encode(query.trim()) + "&setlang=zh-CN";
            long t0 = System.currentTimeMillis();
            String html = SearchHttp.get(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            long ms = System.currentTimeMillis() - t0;

            if (html == null || html.length() == 0) {
                Log.w(TAG, "页面获取失败 (" + ms + "ms)");
                return out;
            }

            Matcher bm = BLOCK_PATTERN.matcher(html);
            int got = 0;
            while (bm.find() && got < maxResults) {
                String block = bm.group();

                Matcher tm = TITLE_PATTERN.matcher(block);
                if (!tm.find()) continue;
                String link = tm.group(1);
                String title = SearchHttp.stripHtml(tm.group(2));
                if (title.length() == 0) continue;

                String snippet = "";
                Matcher sm = SNIPPET_PATTERN.matcher(block);
                if (sm.find()) snippet = SearchHttp.stripHtml(sm.group(1));

                SearchResult r = new SearchResult(title, link, snippet, NAME);
                if (!r.isEmpty()) {
                    out.add(r);
                    got++;
                }
            }
            Log.i(TAG, "命中 " + out.size() + " 条 (" + ms + "ms, HTML="
                    + (html.length() / 1024) + "KB)");
        } catch (Throwable t) {
            Log.w(TAG, "解析异常: " + t.getMessage());
        }
        return out;
    }
}
