package com.onesay.assistant.v2.search;

/**
 * 一条联网搜索结果（不可变）。
 *
 * <p>刻意只保留三个字段：标题、链接、摘要。
 * 大模型回答实时问题时真正用得上的是**摘要里的数字与事实**，
 * 正文抓取（Tavily 的 include_raw_content）会让延迟翻倍、token 涨十倍，
 * 对"比特币多少钱"这种问题收益为零。
 */
public final class SearchResult {

    public final String title;
    public final String url;
    public final String snippet;
    /**
     * ★ fix/shop-research（11-03 下午）：哪个数据源给的（{@link BingHtmlSearchProvider#NAME} = 免 key 的必应网页通道，
     * 结果质量差，比价跑腿要做相关性过滤）；不知道为空串。
     */
    public final String provider;

    public SearchResult(String title, String url, String snippet) {
        this(title, url, snippet, "");
    }

    public SearchResult(String title, String url, String snippet, String provider) {
        this.title = clean(title);
        this.url = clean(url);
        this.snippet = clean(snippet);
        this.provider = provider == null ? "" : provider;
    }

    /** 免 key 的必应网页通道给的结果（要做相关性过滤）。 */
    public boolean keyless() {
        return BingHtmlSearchProvider.NAME.equals(provider);
    }

    public boolean isEmpty() {
        return title.length() == 0 && snippet.length() == 0;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
    }

    @Override
    public String toString() {
        return title + " | " + url + " | " + snippet;
    }
}
