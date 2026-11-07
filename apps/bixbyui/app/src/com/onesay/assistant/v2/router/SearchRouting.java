package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ★ 11-04 ②（用户：调研时看到「搜索」就直接打开浏览器）：搜索一律走搜索 API（Tavily 等，免 key 源兜底），
 * 只有用户明确要「打开浏览器 / 打开网页 / 在浏览器里搜」才拉起可见浏览器（纯函数，JVM 可测）。
 *
 * <p>根因（机上日志 02:06:11「搜索近半年电脑的价格趋势。」）：是<b>路由代码</b>，不是提示词——
 * L0 say 模板（browser.search 的「搜索{n} / 搜一下{n} / 查一下{n} / 百度一下{n}」）与 FastIntents.webSearch 把任何
 * 「搜索 X」零 LLM 直接派成 {@code am start VIEW baidu.com/s?wd=X}，根本没进调研；AgentLoop 的 SEARCH 目标族兜底
 * 也会把 browser.search.* 塞进候选，模型可能选它。离屏 BrowserReader（web.read 读页）不算打开浏览器，不受影响。
 */
public final class SearchRouting {

    private SearchRouting() {}

    /** 用户明确要可见浏览器 / 网页。 */
    static final Pattern EXPLICIT = Pattern.compile(
            "打开浏览器|浏览器里|浏览器上|浏览器中|在浏览器|用浏览器|浏览器帮我|浏览器打开|打开网页|网页上搜|网页里搜|网页版|用百度搜|打开百度|百度网页|用必应搜|打开必应|用谷歌搜|打开谷歌|用搜狗搜|打开搜狗|google搜|bing搜");

    public static boolean explicitBrowser(String utterance) {
        return utterance != null && EXPLICIT.matcher(utterance.toLowerCase(java.util.Locale.ROOT)).find();
    }

    /** 拉起可见浏览器的搜索能力（browser.search / .bing / .google / .sogou）。 */
    public static boolean isVisibleSearch(String sign) {
        return sign != null && (sign.equals("browser.search") || sign.startsWith("browser.search."));
    }

    /** L0 / 快路径命中了可见浏览器搜索，但用户没明确要浏览器 → 丢掉这个命中（交给调研 / 问答，走搜索 API）。 */
    public static boolean dropL0(String sign, String utterance) {
        return isVisibleSearch(sign) && !explicitBrowser(utterance);
    }

    /** AgentLoop 候选：有搜索 API（web.search）且用户没明确要浏览器 → 去掉 browser.search.*。 */
    public static List<Capability> filterCandidates(List<Capability> in, String query, boolean webAvailable) {
        if (in == null || !webAvailable || explicitBrowser(query)) return in;
        List<Capability> out = new ArrayList<Capability>(in.size());
        for (Capability c : in) if (c != null && !isVisibleSearch(c.sign)) out.add(c);
        return out;
    }
}
