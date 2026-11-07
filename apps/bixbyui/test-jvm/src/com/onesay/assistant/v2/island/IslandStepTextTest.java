package com.onesay.assistant.v2.island;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ 11-04 ①：灵动岛逐步显示（工具调用 → 一行短句；失败如实；整词截断不加省略号）。 */
public class IslandStepTextTest {

    static JSONObject o(String k, String v) throws Exception {
        return new JSONObject().put(k, v);
    }

    @Test public void searchReadResearchLines() throws Exception {
        assertEquals("搜索：二手摩托车 价格", IslandStepText.forTool("web.search", o("query", "  二手摩托车   价格 "), null));
        assertEquals("读取：摩托范", IslandStepText.forTool("web.read", o("url", "https://www.58moto.com/garage/123"), null));
        assertEquals("读取：携程", IslandStepText.forTool("web.read", o("url", "https://m.ctrip.com/hotel/hz"), null));
        assertEquals("读取：example.org", IslandStepText.forTool("web.read", o("url", "https://www.example.org/a?b=1"), null));
        assertTrue(IslandStepText.forTool("research.spawn", o("question", "人体工学椅 1000 元以内 代表款"), null).startsWith("调研："));
        assertEquals("查记忆", IslandStepText.forTool("memory.search", new JSONObject(), null));
        assertEquals("等你选择", IslandStepText.forTool("ask_user", null, null));
        assertEquals("打开：微信", IslandStepText.forTool("app.launch", o("app", "微信（com.tencent.mm）"), null));
    }

    @Test public void everyLineFitsAndNeverEllipsis() throws Exception {
        String[] qs = {"2026年近半年笔记本电脑价格趋势 京东 天猫 走势", "Razer Basilisk V3 Pro 35K wireless price", "杭州西湖附近四星级酒店今晚房价对比"};
        for (String q : qs) {
            String t = IslandStepText.forTool("web.search", o("query", q), null);
            assertTrue(t, IslandStepText.units(t) <= IslandStepText.MAX_UNITS);
            assertFalse(t, t.contains("…") || t.contains("..."));
            assertTrue(t, t.startsWith("搜索："));
            assertTrue(t, t.length() > 3);
        }
        // 英文词不切半个
        String en = IslandStepText.forTool("web.search", o("query", "Razer Basilisk V3 Pro 35K wireless price"), null);
        String body = en.substring(3);
        assertTrue(en, ("Razer Basilisk V3 Pro 35K wireless price").startsWith(body));
        assertTrue(en, body.endsWith("Razer") || body.endsWith("Basilisk") || body.endsWith("V3") || body.endsWith("Pro") || body.endsWith("35K"));
    }

    @Test public void failureIsHonest() {
        assertEquals("搜索失败：二手摩托车", IslandStepText.failed("搜索：二手摩托车"));
        assertEquals("读取失败：携程", IslandStepText.failed("读取：携程"));
        assertEquals("查记忆 没成功", IslandStepText.failed("查记忆"));
        assertEquals("这一步没成功", IslandStepText.failed(null));
    }

    @Test public void toolStepVsCannedCaption() {
        assertTrue(IslandStepText.isToolStep("搜索：二手摩托车 价格"));
        assertTrue(IslandStepText.isToolStep("读取：摩托范"));
        assertFalse("套话不是步骤", IslandStepText.isToolStep("收到，我来弄"));
        assertFalse(IslandStepText.isToolStep("还在弄，快好了"));
        assertFalse(IslandStepText.isToolStep(null));
    }
}
