package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B18：「打开网页浏览器嘛」不再被「打开网页{url}」吃成 browser.open url=浏览器嘛；真网址照旧。 */
public class B18BrowserUrlSlotTest {

    static L0RuleRouter L0;

    @BeforeClass public static void init() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        L0 = L0RuleRouter.forRegistry(reg);
    }

    @Test public void nonUrlSlotNotBrowserOpen() {
        for (String q : new String[]{"打开网页浏览器嘛", "打开网页浏览器吧", "打开网页百度", "浏览网站好玩的"}) {
            Invocation i = L0.route(q);
            if (i != null) {
                assertFalse(q + " → " + i.sign + " " + i.args, "browser.open".equals(i.sign));
                assertFalse(q, i.args.has("url") && !L0RuleRouter.plausibleUrl(i.args.optString("url")));
            }
        }
    }

    @Test public void browserLaunchStillLocal() {
        Invocation i = L0.route("打开网页浏览器");
        assertNotNull(i);
        assertFalse(i.sign, "browser.open".equals(i.sign));
    }

    @Test public void realUrlsStillOpen() {
        String[][] ok = {{"打开网页www.baidu.com", "www.baidu.com"}, {"打开网页https://example.com/a?b=1", "https://example.com/a?b=1"},
                {"打开网页bilibili.com", "bilibili.com"}, {"打开网页a.com:8080/x", "a.com:8080/x"}};
        for (String[] c : ok) {
            Invocation i = L0.route(c[0]);
            assertNotNull(c[0], i);
            assertEquals(c[0], "browser.open", i.sign);
            assertEquals(c[0], c[1], i.args.optString("url"));
        }
    }

    @Test public void plausibleUrl() {
        assertTrue(L0RuleRouter.plausibleUrl("中文.中国"));
        assertTrue(L0RuleRouter.plausibleUrl("http://x"));
        assertFalse(L0RuleRouter.plausibleUrl("浏览器嘛"));
        assertFalse(L0RuleRouter.plausibleUrl("百度"));
        assertFalse(L0RuleRouter.plausibleUrl("a .com"));
        assertFalse(L0RuleRouter.plausibleUrl(""));
    }
}
