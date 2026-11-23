package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B01：L0 文本槽位按原句取回，不丢标点 / 空格 / 大小写（t20k shell.route-slot 110 条）。 */
public class B01L0RawSlotTest {

    static L0RuleRouter L0;

    @BeforeClass public static void init() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        L0 = L0RuleRouter.forRegistry(reg);
    }

    private static String slot(String q, String sign, String key) {
        Invocation i = L0.route(q);
        assertNotNull(q, i);
        assertEquals(q, sign, i.sign);
        return i.args.optString(key);
    }

    @Test public void slotsKeepPunctuation() {
        assertEquals("www.baidu.com", slot("打开网页www.baidu.com", "browser.open", "url"));
        assertEquals("https://a.com/?x=1&y=2", slot("打开网页 https://a.com/?x=1&y=2", "browser.open", "url"));
        assertEquals("买牛奶、鸡蛋和面包", slot("记一下买牛奶、鸡蛋和面包", "notes.new.text", "content"));
        assertEquals("明天 9:30 开会", slot("记一下明天 9:30 开会", "notes.new.text", "content"));
        assertEquals("com.android.settings", slot("删除软件 com.android.settings", "app.uninstall", "app"));
        assertEquals("C++ 入门", slot("卸载C++ 入门", "app.uninstall", "app"));
        assertEquals("+86 138 0013 8000", slot("打电话给+86 138 0013 8000", "phone.call", "number"));
        assertEquals("#话题#", slot("记一下#话题#", "notes.new.text", "content"));
    }

    @Test public void dangerousSchemesNeverLocal() {
        for (String q : new String[]{"打开网页file:///sdcard/a.html", "打开网页javascript:alert(1)", "浏览网站intent://x#Intent;end", "打开网页content://sms/inbox"}) {
            Invocation i = L0.route(q);
            assertTrue(q, i == null || !"browser.open".equals(i.sign));
        }
        assertFalse(com.onesay.assistant.v2.executor.ShellCommand.dangerousUrl("www.a.com"));
        assertFalse(com.onesay.assistant.v2.executor.ShellCommand.dangerousUrl("https://a.com/?x=1"));
        assertFalse(com.onesay.assistant.v2.executor.ShellCommand.dangerousUrl("a.com:8080/x"));
        assertTrue(com.onesay.assistant.v2.executor.ShellCommand.dangerousUrl("JavaScript:alert(1)"));
        try {
            com.onesay.assistant.v2.executor.ShellCommand.slotValues(new JSONObject().put("url", "file:///sdcard/a"), null, null);
            fail("file: 应拒");
        } catch (IllegalArgumentException expected) {
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test public void sentenceEndPunctNotCarried() throws Exception {
        JSONObject a = L0RuleRouter.restoreRawSlots("记一下买牛奶。", new JSONObject().put("content", "买牛奶"));
        assertEquals("买牛奶", a.getString("content"));
        JSONObject b = L0RuleRouter.restoreRawSlots("x", new JSONObject().put("level", 5));
        assertEquals(5, b.getInt("level"));
    }
}
