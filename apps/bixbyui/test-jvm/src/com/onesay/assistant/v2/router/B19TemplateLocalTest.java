package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ fix-1118 B19：t20k router.L0.template 41 条（个人版口径）在开源版逐条钉住：模板自带第二个动作词 / 达成型动词的
 * 一步能力走本地，不再因 TriageDecider 规则先判成多步（AgentLoop）或问答；否定 / 多步 / 联系人名照旧。
 * 开源版口径差异（同样钉住）：智能截屏 / 手掌滑动截屏没有对应能力（三星专有）；「搜索微信」没点名商店按 SearchRouting 交网页搜索；
 * 应用商店详情页的 {n} 是包名，说中文应用名 L0 不接。
 */
public class B19TemplateLocalTest {

    static CapabilityRegistry REG;

    @BeforeClass public static void init() {
        REG = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(REG);
        B11ExplicitTargetTest.L0 = L0RuleRouter.forRegistry(REG);
    }

    static Invocation local(String q) { return B11ExplicitTargetTest.local(q); }

    static void localIs(String q, String sign, String key, String val) {
        Invocation l = local(q);
        assertNotNull(q, l);
        assertEquals(q, sign, l.sign);
        if (key != null) assertEquals(q, val, l.args.optString(key));
    }

    @Test public void namedEngineAndScreenStillLocal() {   // 41 条里 B11 那 11 条
        for (String q : new String[]{"用必应搜索天气", "bing搜索天气", "用bing搜天气"}) localIs(q, "browser.search.bing", "keyword", "天气");
        for (String q : new String[]{"用谷歌搜索天气", "google搜索天气", "用google搜天气"}) localIs(q, "browser.search.google", "keyword", "天气");
        localIs("用搜狗搜索天气", "browser.search.sogou", "keyword", "天气");
        for (String q : new String[]{"识别屏幕这是什么", "看屏幕这是什么", "帮我看看屏幕这是什么", "看看屏幕这是什么"}) {
            localIs(q, "vision.screen.understand", "content", "这是什么");
        }
    }

    @Test public void storeSearchTemplatesLocal() {
        for (String q : new String[]{"打开应用商店下载微信", "应用商店搜索微信", "在应用商店下载微信", "应用商店下载微信",
                "下载微信", "安装微信", "在应用商店搜索微信", "下载安装微信"}) {
            localIs(q, "store.search.market", "keyword", "微信");
        }
        for (String q : new String[]{"在谷歌商店搜索微信", "用谷歌商店下载微信", "play商店下载微信", "google play下载微信",
                "在play商店搜索微信", "谷歌play下载微信"}) {
            localIs(q, "store.search.play", "keyword", "微信");
        }
    }

    @Test public void settingsNotesMailBrowserLocal() {
        localIs("默认浏览器是什么", "browser.default.check", null, null);
        localIs("新建笔记记录买牛奶", "notes.new.text", "content", "买牛奶");
        localIs("打开息屏提醒", "settings.aod.on", null, null);
        localIs("关闭息屏提醒", "settings.aod.off", null, null);
        localIs("管理已安装应用", "settings.page.apps", null, null);
        localIs("写一封草稿", "mail.draft.new", null, null);
    }

    @Test public void smsToNumberLocalAndStillConfirmed() {
        localIs("发短信给10086", "sms.send", "number", "10086");
        localIs("发信息给10086", "sms.send", "number", "10086");
        Capability c = REG.describe("sms.send");
        assertTrue("短信仍是高风险、要确认", c.risk >= 2 && c.confirm);
        // 联系人名不在 L0 当号码填
        Invocation l = local("发短信给张三");
        assertTrue(l == null || !"张三".equals(l.args.optString("number")));
    }

    @Test public void ossDifferencesPinned() {
        for (String q : new String[]{"打开智能截屏", "开启手掌滑动截屏", "关闭智能截屏", "关掉手掌滑动截屏",
                "搜索微信", "查看应用商店详情微信", "打开应用商店详情页微信", "应用详情微信"}) {
            Invocation l = local(q);
            assertTrue(q, l == null || !l.sign.startsWith("browser.search."));
        }
    }

    @Test public void negationAndMultiStepUnchanged() {
        for (String q : new String[]{"别下载微信", "不要打开息屏提醒", "先别发短信给10086", "下载微信，算了不用了"}) {
            assertNull(q, local(q));
        }
        for (String q : new String[]{"下载微信然后打开它", "新建笔记记录买牛奶再发给妈妈", "打开息屏提醒并且调低亮度"}) {
            Invocation l = local(q);
            assertTrue(q, l == null || !(l.sign.startsWith("store.") || l.sign.startsWith("notes.") || l.sign.startsWith("settings.aod")));
        }
    }

    @Test public void howtoStillAsk() {
        for (String q : new String[]{"怎么下载微信", "为什么息屏提醒不亮", "怎么写一封草稿"}) {
            assertNull(q, local(q));
        }
    }
}
