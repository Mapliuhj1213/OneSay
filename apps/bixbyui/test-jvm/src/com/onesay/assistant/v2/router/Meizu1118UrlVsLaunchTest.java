package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ meizu-1118（真机 Meizu 21 Pro）：「打开网页www.baidu.com」被 app.launch 吞成 app="网页www.baidu.com"
 * （品类别名「网页」解析成浏览器 → 只打开浏览器、网址丢了）。像网址的网址候选必须排在 app.launch 前面。
 */
public class Meizu1118UrlVsLaunchTest {

    /** 线上 IntentExecutor 的品类别名：名字里带「网页 / 浏览器」都能解析成浏览器；「微信 / 设置」是真应用。 */
    static final L0RuleRouter.AppResolver ALIAS = new L0RuleRouter.AppResolver() {
        @Override public String resolve(String app) {
            if (app.contains("网页") || app.contains("浏览器")) return "com.android.browser/.Main";
            if (app.equals("微信")) return "com.tencent.mm/.ui.LauncherUI";
            if (app.equals("设置")) return "com.android.settings/.Settings";
            return null;
        }
    };

    static L0RuleRouter l0() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        return L0RuleRouter.forRegistry(reg, ALIAS);
    }

    @Test public void urlBeatsGreedyLaunch() {
        L0RuleRouter l0 = l0();
        String[][] cases = {
                {"打开网页www.baidu.com", "www.baidu.com"},
                {"打开网页 例子.中国", "例子.中国"},
                {"打开网页 https://example.com/a?b=1", "https://example.com/a?b=1"},
                {"打开网页 新华网.中国/news", "新华网.中国/news"},
                {"浏览网站bilibili.com", "bilibili.com"},
        };
        for (String[] c : cases) {
            Invocation i = l0.route(c[0]);
            assertNotNull(c[0], i);
            assertEquals(c[0], "browser.open", i.sign);
            assertEquals(c[0], c[1], i.args.optString("url"));
        }
    }

    @Test public void notUrlStillFallsBackToLaunch() {
        L0RuleRouter l0 = l0();
        Invocation j = l0.route("打开网页浏览器嘛");   // B18：网址槽位不像网址 → 下一个候选（app.launch 打开浏览器）
        assertNotNull(j);
        assertEquals("app.launch", j.sign);
        Invocation k = l0.route("打开微信");
        assertNotNull(k);
        assertEquals("app.launch", k.sign);
        assertEquals("微信", k.args.optString("app"));
    }

    @Test public void preferRule() {
        assertTrue(L0RuleRouter.preferUrlOver("app.launch"));
        assertFalse(L0RuleRouter.preferUrlOver("browser.open"));
    }
}
