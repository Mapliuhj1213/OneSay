package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** ★ 11-04 ②：没明确要浏览器就不开可见浏览器（搜索走搜索 API）。 */
public class SearchRoutingTest {

    @Test public void explicitOnlyWhenAsked() {
        assertTrue(SearchRouting.explicitBrowser("打开浏览器搜一下电脑价格"));
        assertTrue(SearchRouting.explicitBrowser("在浏览器里查一下杭州天气"));
        assertTrue(SearchRouting.explicitBrowser("用百度搜一下周杰伦"));
        assertFalse(SearchRouting.explicitBrowser("帮我找二手摩托车价格"));
        assertFalse(SearchRouting.explicitBrowser("搜索近半年电脑的价格趋势"));
        assertFalse(SearchRouting.explicitBrowser("我想买一张1000以内的人体工学椅，有没有推荐"));
        assertFalse(SearchRouting.explicitBrowser(null));
    }

    @Test public void l0AndFastPathDoNotOpenBrowser() {
        assertTrue(SearchRouting.dropL0("browser.search", "搜索近半年电脑的价格趋势"));
        assertTrue(SearchRouting.dropL0("browser.search.bing", "搜一下二手摩托车价格"));
        assertFalse(SearchRouting.dropL0("browser.search", "打开浏览器搜一下二手摩托车价格"));
        assertFalse("不是浏览器搜索的命中不动", SearchRouting.dropL0("clock.alarm.set", "搜索闹钟"));
        assertNull(FastIntents.route("搜索近半年电脑的价格趋势"));
        assertNull(FastIntents.route("搜一下二手摩托车价格"));
        assertEquals("browser.search", FastIntents.route("打开浏览器搜一下二手摩托车价格").sign);
        assertEquals("二手摩托车价格", FastIntents.route("打开浏览器搜一下二手摩托车价格").args.optString("keyword"));
    }

    @Test public void agentCandidatesDropVisibleSearchWhenApiAvailable() {
        List<Capability> in = new ArrayList<Capability>();
        for (Capability c : CapabilityRegistry.getInstance().listCapabilities()) {
            if (c.sign.startsWith("browser.search") || c.sign.equals("clock.alarm.set")) in.add(c);
        }
        assertTrue("注册表里有 browser.search*", in.size() >= 2);
        List<Capability> out = SearchRouting.filterCandidates(in, "搜索近半年电脑的价格趋势", true);
        for (Capability c : out) assertFalse(c.sign, SearchRouting.isVisibleSearch(c.sign));
        assertTrue(out.size() >= 1);
        assertEquals("没有搜索 API：保留（兜底）", in.size(), SearchRouting.filterCandidates(in, "搜索电脑价格", false).size());
        assertEquals("用户明确要浏览器：保留", in.size(), SearchRouting.filterCandidates(in, "打开浏览器搜电脑价格", true).size());
    }
}
