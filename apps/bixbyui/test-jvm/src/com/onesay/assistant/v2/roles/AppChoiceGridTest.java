package com.onesay.assistant.v2.roles;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.ui.ChoiceGridPalette;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * [r2/bike-chooser] 应用网格数据流：大模型 app_choice 解析（有字段 / 缺字段回退 / 清洗）、2×2 上限与排序、
 * 打开 / 下载动作、「打开应用商店查看更多」的意图构造、深浅两套色的对比度与触控尺寸。
 */
public class AppChoiceGridTest {

    static JSONObject app(String label, String pkg) throws Exception {
        return new JSONObject().put("label", label).put("pkg", pkg);
    }

    static JSONObject opt(String id, String pkg, boolean installed) throws Exception {
        return new JSONObject().put("id", id).put("label", id).put("pkg", pkg).put("installed", installed);
    }

    // ------------------------------------------------------------------ 2×2

    @Test public void gridCapsAtFourInstalledFirstKeepsOrder() throws Exception {
        JSONArray o = new JSONArray()
                .put(opt("a", "com.a.app", false)).put(opt("b", "com.b.app", true)).put(opt("c", "com.c.app", false))
                .put(opt("d", "com.d.app", true)).put(opt("e", "com.e.app", false)).put(opt("f", "com.f.app", true));
        AppGridModel m = AppGridModel.from(new JSONObject().put("options", o).put("store_query", "共享单车"));
        assertEquals(4, AppChoiceSpec.GRID_MAX);
        assertEquals(4, m.cells.size());
        assertEquals(6, m.total);
        assertEquals("b", m.cells.get(0).id);
        assertEquals("d", m.cells.get(1).id);
        assertEquals("f", m.cells.get(2).id);
        assertEquals("a", m.cells.get(3).id);   // 未安装按原顺序补位
        assertEquals(AppGridModel.Action.OPEN, m.cells.get(2).action);
        assertEquals(AppGridModel.Action.DOWNLOAD, m.cells.get(3).action);
        assertEquals(1, m.cells.get(0).optionIndex);
        assertEquals(2, m.rows());
        assertEquals("打开", AppGridModel.Action.OPEN.text);
        assertEquals("下载", AppGridModel.Action.DOWNLOAD.text);
        assertEquals("b，打开", m.cells.get(0).describe());
    }

    @Test public void oddCountLeavesRightSlotEmptyAndBadPkgDropped() throws Exception {
        JSONArray o = new JSONArray().put(opt("a", "com.a.app", true)).put(opt("bad", "not a pkg", true)).put(opt("c", "com.c.app", false));
        AppGridModel m = AppGridModel.from(new JSONObject().put("options", o).put("role_label", "打车"));
        assertEquals(2, m.cells.size());
        assertEquals(1, m.rows());
        assertNotNull(m.at(0, 1));
        o.put(opt("d", "com.d.app", false));
        m = AppGridModel.from(new JSONObject().put("options", o));
        assertEquals(3, m.cells.size());
        assertEquals(2, m.rows());
        assertNull(m.at(1, 1));
        assertNull("没有搜索词就不显示链接", m.storeQuery);
        assertEquals("打车", AppGridModel.from(new JSONObject().put("options", o).put("role_label", "打车")).storeQuery);
        assertTrue(AppGridModel.from(null).isEmpty());
    }

    // ------------------------------------------------------------------ 大模型字段

    @Test public void llmFieldParsed() throws Exception {
        JSONObject c = new JSONObject().put("category", "共享单车").put("store_query", "共享单车")
                .put("apps", new JSONArray().put(app("哈啰", "com.jingyao.easybike")).put(app("美团", "com.sankuai.meituan")));
        AppChoiceSpec s = AppChoiceSpec.parse(c, "我想扫共享单车");
        assertEquals("共享单车", s.category);
        assertEquals("共享单车", s.storeQuery);
        assertEquals(2, s.options.size());
        FakeSnapshot dev = new FakeSnapshot().install("com.sankuai.meituan");
        JSONObject ui = s.toRoleUi(dev);
        assertEquals("chooser", ui.getString("kind"));
        assertEquals("llm", ui.getString("source"));
        assertFalse(ui.getJSONArray("options").getJSONObject(0).getBoolean("installed"));
        assertTrue(ui.getJSONArray("options").getJSONObject(1).getBoolean("installed"));
        AppGridModel m = AppGridModel.from(ui);
        assertEquals("com.sankuai.meituan", m.cells.get(0).pkg);   // 已安装优先
        assertEquals("共享单车", m.storeQuery);
    }

    @Test public void missingFieldFallsBack() throws Exception {
        JSONArray apps = new JSONArray().put(app("剪映", "com.lemon.lv"));
        // 缺 store_query → category
        assertEquals("视频剪辑", AppChoiceSpec.parse(new JSONObject().put("category", "视频剪辑").put("apps", apps), "x").storeQuery);
        // 都缺 → 用户原话关键词
        assertEquals("共享单车", AppChoiceSpec.parse(new JSONObject().put("apps", apps), "我想扫共享单车").storeQuery);
        assertEquals("剪视频", AppChoiceSpec.keywordsOf("帮我找个剪视频的软件"));
        assertEquals("共享单车", AppChoiceSpec.keywordsOf("我想扫共享单车吧"));
        assertNull(AppChoiceSpec.keywordsOf("！！！"));
        // 没有合法候选 → 不弹网格
        assertNull(AppChoiceSpec.parse(new JSONObject().put("store_query", "共享单车"), "q"));
        assertNull(AppChoiceSpec.parse(new JSONObject().put("apps", new JSONArray().put(app("x", "javascript:alert(1)"))), "q"));
        assertNull(AppChoiceSpec.parse(null, "q"));
    }

    @Test public void sanitize() throws Exception {
        assertEquals("共享单车", AppChoiceSpec.sanitizeQuery("  \"共享单车\"  "));
        assertEquals("共享单车 app", AppChoiceSpec.sanitizeQuery("共享单车<app>"));
        assertEquals("a b", AppChoiceSpec.sanitizeQuery("a&b"));
        assertEquals("http example", AppChoiceSpec.sanitizeQuery("http://example.com"));
        assertEquals("单车", AppChoiceSpec.sanitizeQuery("单车😀\n\t"));
        String longQ = AppChoiceSpec.sanitizeQuery("共享单车共享单车共享单车共享单车");
        assertEquals(AppChoiceSpec.QUERY_MAX, longQ.length());
        assertNull(AppChoiceSpec.sanitizeQuery("···---"));
        assertNull(AppChoiceSpec.sanitizeQuery(""));
        assertNull(AppChoiceSpec.sanitizeQuery(null));
        // 候选：去重、超 8 个截断、超长名称截断
        JSONArray apps = new JSONArray();
        for (int i = 0; i < 12; i++) apps.put(app("应用名字特别特别特别长的一个应用名" + i, "com.x.a" + (i % 10)));
        AppChoiceSpec s = AppChoiceSpec.parse(new JSONObject().put("apps", apps).put("store_query", "\"';DROP"), "q");
        assertEquals(AppChoiceSpec.RAW_MAX, s.options.size());
        assertTrue(s.options.get(0).label.length() <= AppChoiceSpec.LABEL_MAX);
        assertEquals("DROP", s.storeQuery);
    }

    @Test public void markerExtractedFromNaturalLanguage() throws Exception {
        String t = "手机上没有共享单车 App，可以装一个。\n<app_choice>{\"category\":\"共享单车\",\"store_query\":\"共享单车\","
                + "\"apps\":[{\"label\":\"哈啰\",\"pkg\":\"com.jingyao.easybike\"}]}</app_choice>";
        AppChoiceSpec.Extracted ex = AppChoiceSpec.extract(t);
        assertEquals("手机上没有共享单车 App，可以装一个。", ex.text);
        assertEquals("共享单车", ex.json.getString("store_query"));
        assertEquals("手机上没有共享单车 App，可以装一个。", AppChoiceSpec.stripForDisplay(t));
        // 坏 JSON / 没闭合：文字照样剥干净，不弹网格
        AppChoiceSpec.Extracted bad = AppChoiceSpec.extract("好的。<app_choice>{oops</app_choice>");
        assertEquals("好的。", bad.text);
        assertNull(bad.json);
        assertEquals("好的。", AppChoiceSpec.extract("好的。<app_choice>{\"apps\":[").text);
        assertEquals("无尾巴", AppChoiceSpec.extract("无尾巴").text);
        assertNull(AppChoiceSpec.extract("无尾巴").json);
        // reattach ↔ extract 往返
        String back = AppChoiceSpec.reattach("正文", ex.json);
        assertEquals("正文", AppChoiceSpec.extract(back).text);
        assertEquals("共享单车", AppChoiceSpec.extract(back).json.getString("category"));
        assertTrue(AppChoiceSpec.PROMPT_RULE.contains("store_query"));
        assertTrue(AppChoiceSpec.PROMPT_RULE.contains("args.app_choice"));
    }

    // ------------------------------------------------------------------ 意图

    @Test public void storeSearchIntents() {
        List<StoreLinks.Spec> l = StoreLinks.storeSearch("共享单车");
        assertEquals(3, l.size());
        // ④ 真机：market:// 在本机弹「打开方式」（Play 未登录 / Uptodown）→ 三星商店搜索深链排第一、限定包
        assertEquals("android.intent.action.VIEW", l.get(0).action);
        assertEquals("samsungapps://SearchResult/%E5%85%B1%E4%BA%AB%E5%8D%95%E8%BD%A6", l.get(0).uri);
        assertEquals(StoreLinks.GALAXY_PKG, l.get(0).pkg);
        assertEquals("market://search?q=%E5%85%B1%E4%BA%AB%E5%8D%95%E8%BD%A6&c=apps", l.get(1).uri);
        assertNull(l.get(1).pkg);
        assertTrue(l.get(2).uri.startsWith(StoreLinks.WEB_SEARCH));
        assertTrue(l.get(2).uri.contains("%E5%85%B1%E4%BA%AB%E5%8D%95%E8%BD%A6"));
        assertNull("浏览器兜底不限定包", l.get(2).pkg);
        assertTrue(StoreLinks.storeSearch("  ").isEmpty());
        assertTrue(StoreLinks.storeSearch(null).isEmpty());
        for (StoreLinks.Spec sp : StoreLinks.storeSearch("video editor")) assertFalse("空格编码成 %20 不是 +", sp.uri.contains("+"));
        assertTrue(StoreLinks.webDownloadUrl("哈啰", "com.jingyao.easybike").startsWith(StoreLinks.WEB_SEARCH));
    }

    @Test public void openIntentsAndDeepLinkSafety() {
        List<StoreLinks.Spec> a = StoreLinks.openApp("com.eg.android.AlipayGphone", "alipays://platformapi/startapp?saId=10000007");
        assertEquals(2, a.size());
        assertEquals("com.eg.android.AlipayGphone", a.get(0).pkg);
        assertNull("最后一项 = 按包名打开主界面", a.get(1).uri);
        assertEquals(1, StoreLinks.openApp("com.x.y", null).size());
        assertEquals("http 深链不收", 1, StoreLinks.openApp("com.x.y", "https://evil.example").size());
        assertFalse(StoreLinks.safeDeepLink("intent://x#Intent;end"));
        assertFalse(StoreLinks.safeDeepLink("file:///sdcard/a"));
        assertTrue(StoreLinks.safeDeepLink("imeituan://www.meituan.com/x"));
    }

    /** 真机回归（10-30）：Android 的 ICU 正则不认 Java 专有写法，类初始化抛错会让角色目录变成 0 个角色。 */
    @Test public void regexesAreAndroidCompatible() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        for (String f : new String[]{"roles/AppChoiceSpec.java", "roles/StoreLinks.java", "roles/AppGridModel.java", "roles/RoleCatalog.java"}) {
            String src = new String(java.nio.file.Files.readAllBytes(new java.io.File(root, "app/src/com/onesay/assistant/v2/" + f).toPath()), "UTF-8");
            String code = src.replaceAll("(?s)/\\*.*?\\*/", "");   // 注释里提到的不算
            assertFalse(f + " 用了 \\p{IsXxx}", code.contains("\\\\p{Is"));
            assertFalse(f + " 用了 \\p{Cntrl}", code.contains("\\\\p{Cntrl}"));
            assertFalse(f + " 用了 \\p{javaXxx}", code.contains("\\\\p{java"));
        }
    }

    // ------------------------------------------------------------------ 设计 token

    @Test public void paletteContrastAndTouchTargets() {
        for (ChoiceGridPalette p : new ChoiceGridPalette[]{ChoiceGridPalette.DARK, ChoiceGridPalette.LIGHT}) {
            int cell = p.cellOpaque();
            int base = (p.panel >>> 24) == 0 ? p.surface : p.panel;
            assertTrue(p.dark + " name", p.contrast(p.name, cell) >= 7.0);
            assertTrue(p.dark + " open " + p.contrast(p.action, cell), p.contrast(p.action, cell) >= 4.5);
            assertTrue(p.dark + " download", p.contrast(p.actionDownload, cell) >= 4.5);
            assertTrue(p.dark + " link " + p.contrast(p.link, base), p.contrast(p.link, base) >= 4.5);
            assertTrue(p.dark + " link pressed", p.contrast(p.linkPressed, base) >= 4.5);
            assertTrue(p.dark + " placeholder", p.contrast(p.placeholderText, ChoiceGridPalette.over(p.placeholderBg, cell)) >= 4.5);
        }
        assertTrue(ChoiceGridPalette.CELL_MIN_H >= 48);
        assertTrue(ChoiceGridPalette.LINK_MIN_H >= 44);
        assertTrue("图标比文字大", ChoiceGridPalette.ICON > ChoiceGridPalette.SP_NAME * 2);
        assertTrue(ChoiceGridPalette.SP_ACTION < ChoiceGridPalette.SP_NAME);
        assertFalse(ChoiceGridPalette.LIGHT.dark);
        assertTrue((ChoiceGridPalette.LIGHT.panel >>> 24) == 0xFF);
    }
}
