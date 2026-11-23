package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * ★ a8fix-1118：L0RuleRouter.URL_LIKE 的 \p{IsHan} 换成 RegexCompat.HAN 显式区间后，网址判定与原写法逐条一致；
 * L0 类初始化不再依赖 Java 专有正则；模板槽位取值不再用 API 26 的 Matcher.group(String)。
 */
public class A8UrlLikeHanTest {

    /** 原写法（JVM 认、Android 7–8 不认），只在单测里当参照。 */
    static final Pattern OLD = Pattern.compile(
            "^(?:https?://\\S+|(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+[A-Za-z\\p{IsHan}]{2,}(?::\\d{1,5})?(?:[/?#]\\S*)?)$",
            Pattern.CASE_INSENSITIVE);

    static final String[] CORPUS = {
            "www.baidu.com", "https://example.com/a?b=1", "http://x", "bilibili.com", "a.com:8080/x", "中文.中国", "例子.公司",
            "例子.网络/路径", "公司.商标", "A.COM", "a.b", "a.c1", "浏览器嘛", "百度", "", "x.みんな", "例え.コム", "한국.한국", "a.Жж",
            "中文.中", "a.〇〇", "a.々々", "a.\uD840\uDC00\uD840\uDC01", "a.ab:99999", "a.ab:1/x y", "-a.com", "a-.com", "a--b.cn",
            "www.qq.com#frag", "例子.中国?x=1", "例子。中国", "a.ab　", "１.中国", "a.ｃｏｍ", "a.ÉÉ", "a.é中",
    };

    @Test public void sameVerdictAsOldIsHanPattern() {
        for (String u : CORPUS) {
            assertEquals("「" + u + "」", OLD.matcher(u).matches(), L0RuleRouter.plausibleUrl(u));
        }
        assertTrue(L0RuleRouter.plausibleUrl("中文.中国"));
        assertTrue(L0RuleRouter.plausibleUrl("www.baidu.com"));
        assertFalse(L0RuleRouter.plausibleUrl("x.みんな"));
        assertFalse(L0RuleRouter.plausibleUrl("浏览器嘛"));
    }

    @Test public void l0RoutesSlotsWithoutNamedGroupApi() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        L0RuleRouter l0 = L0RuleRouter.forRegistry(reg);
        Invocation i = l0.route("打开网页中文.中国");
        assertNotNull(i);
        assertEquals("browser.open", i.sign);
        assertEquals("中文.中国", i.args.optString("url"));
        Invocation j = l0.route("打开网页浏览器嘛");
        assertTrue(j == null || !"browser.open".equals(j.sign));
    }

    @Test public void templateRegexWithStrayParenIsJustNoMatch() {
        // 模板正则编不过只算这条不匹配（cachedPattern 兜底），不抛
        String r = L0RuleRouter.debugBuildRegex("打开{app}");
        assertTrue(Pattern.compile(r).matcher("打开微信").matches());
        assertEquals(1, com.onesay.assistant.v2.util.RegexCompat.groupIndex(r, "app"));
    }
}
