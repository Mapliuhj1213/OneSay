package com.onesay.desktop;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** [preview-1120] 主题插件、图标统一、设置页整理、预览版和正式版分开。 */
public class Preview1120UiTest {

    static String html() throws Exception {
        return new String(UiServer.resource("onesay/ui/index.html"), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 主题包

    @Test public void bundledThemePacksLoadAndCarryLicense() {
        List<Object> l = Themes.list(null);
        assertEquals(5, l.size());
        for (Object o : l) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            assertEquals("MIT", m.get("license"));
            assertTrue(String.valueOf(m.get("source")).startsWith("https://github.com/"));
            @SuppressWarnings("unchecked") Map<String, Object> tk = (Map<String, Object>) m.get("tokens");
            assertTrue(tk.keySet().containsAll(List.of("--bg", "--card", "--text", "--accent", "--line")));
        }
    }

    @Test public void themePackValidationRejectsAnythingButColors() throws Exception {
        String ok = "{\"id\":\"my-theme\",\"name\":\"我的\",\"mode\":\"dark\",\"tokens\":{\"--bg\":\"#101418\",\"card\":\"#182028\",\"--text\":\"rgb(232, 238, 244)\",\"--accent\":\"rgba(79,179,255,0.9)\"}}";
        Map<String, Object> m = Themes.parse(ok);
        assertEquals("my-theme", m.get("id"));
        assertEquals("#182028", ((Map<?, ?>) m.get("tokens")).get("--card"));
        for (String bad : new String[] {
                "{\"id\":\"x\",\"name\":\"x\",\"mode\":\"dark\",\"tokens\":{\"--bg\":\"url(https://evil/x.png)\",\"--card\":\"#fff\",\"--text\":\"#000\",\"--accent\":\"#00f\"}}",
                "{\"id\":\"x\",\"name\":\"x\",\"mode\":\"dark\",\"tokens\":{\"--bg\":\"#fff;}body{display:none\",\"--card\":\"#fff\",\"--text\":\"#000\",\"--accent\":\"#00f\"}}",
                "{\"id\":\"x\",\"name\":\"x\",\"mode\":\"dark\",\"tokens\":{\"--font\":\"Comic Sans\",\"--bg\":\"#fff\",\"--card\":\"#fff\",\"--text\":\"#000\",\"--accent\":\"#00f\"}}",
                "{\"id\":\"../../x\",\"name\":\"x\",\"mode\":\"dark\",\"tokens\":{\"--bg\":\"#fff\",\"--card\":\"#fff\",\"--text\":\"#000\",\"--accent\":\"#00f\"}}",
                "{\"id\":\"x\",\"name\":\"x\",\"mode\":\"sepia\",\"tokens\":{\"--bg\":\"#fff\",\"--card\":\"#fff\",\"--text\":\"#000\",\"--accent\":\"#00f\"}}",
                "{\"id\":\"x\",\"name\":\"x\",\"mode\":\"dark\",\"tokens\":{\"--bg\":\"#fff\"}}",
                "not json"}) {
            try { Themes.parse(bad); fail("应该拒绝：" + bad); } catch (IllegalArgumentException e) { assertFalse(e.getMessage().isEmpty()); }
        }
        // 导入 / 列出 / 删除；不能和自带的重名；作者字段里的尖括号去掉
        Path dir = Files.createTempDirectory("themes");
        Map<String, Object> imp = Themes.importPack(dir, ok.replace("\"name\":\"我的\"", "\"name\":\"我的\",\"author\":\"<script>x</script>\""));
        assertFalse(String.valueOf(imp.get("author")).contains("<"));
        assertEquals(6, Themes.list(dir).size());
        try { Themes.importPack(dir, ok.replace("my-theme", "nord")); fail(); } catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("重名")); }
        assertFalse("自带的删不了", Themes.remove(dir, "nord"));
        assertTrue(Themes.remove(dir, "my-theme"));
        assertEquals(5, Themes.list(dir).size());
        assertEquals("system", Themes.mode("weird"));
        assertEquals("dark", Themes.mode("dark"));
    }

    // ------------------------------------------------------------------ 界面

    @Test public void iconsAreOneLineIconSetAndHanziAreOnlyLabels() throws Exception {
        String h = html();
        assertTrue(h.contains("id=\"i-sprite\""));
        assertTrue("保留 Lucide 的许可说明", h.contains("Lucide（ISC License"));
        // 图标位置（.ic / .big / 输入框的按钮）里不再是汉字或符号
        Matcher m = Pattern.compile("<span class=\"ic\"[^>]*>([^<]+)</span>").matcher(h);
        while (m.find()) fail("图标位置还是文字：" + m.group(1));
        Matcher b = Pattern.compile("<div class=\"big\"[^>]*>([^<]+)</div>").matcher(h);
        while (b.find()) fail("大图标还是文字：" + b.group(1));
        assertFalse(h.contains(">＋</button>"));
        // 每个用到的图标都在 sprite 里
        Matcher u = Pattern.compile("ico\\('([a-z0-9-]+)'\\)|#i-([a-z0-9-]+)\"").matcher(h);
        while (u.find()) {
            String n = u.group(1) != null ? u.group(1) : u.group(2);
            assertTrue("sprite 里缺图标 " + n, h.contains("<symbol id=\"i-" + n + "\""));
        }
    }

    @Test public void noForbiddenBrandWordsOrInternalIdsInUi() throws Exception {
        String h = html();
        for (String w : new String[] {"Bixby", "bixby", "三星", "Samsung", "DeX", "小布"}) assertFalse("界面里出现了「" + w + "」", h.contains(w));
        // 设置页新加的几组
        for (String g : new String[] {"'set-look','外观'", "'set-mcp','MCP 连接器'", "'set-plugins','安全插件'"}) assertTrue(g, h.contains(g));
        // 路径 / 列表都有选择器，不再是一个裸输入框
        for (String k : new String[] {"pathPick('files_dir'", "pathPick('scripts_dir'", "pathPick('adb_path'", "appsEditor(s.apps)", "chipList('trusted_domains'", "chipList('net_allow'", "chipList('ptt_skip'", "numIn('chat_timeout_min'"})
            assertTrue(k, h.contains(k));
        assertFalse(h.contains("inp('files_dir'"));
        assertFalse(h.contains("inp('apps'"));
        // 主题：不再写死跟随系统的 @media，交给 data-theme
        assertFalse(h.contains("@media (prefers-color-scheme: dark){\n  :root{--bg:#101012"));
        assertTrue(h.contains(":root[data-theme=dark]"));
    }

    @Test public void releaseFlavorIsUnchanged() throws Exception {
        assertFalse("测试跑的是正式版", Flavor.PREVIEW);
        assertEquals("OneSay", Flavor.name());
        assertEquals("OneSay", Flavor.dir());
        assertEquals(47820, LanServer.DEFAULT_PORT);
        assertEquals(47830, UiServer.DEFAULT_PORT);
        assertEquals(Main.VERSION, Flavor.version());
        assertEquals("OneSay.Desktop", Host.AUMID);
        assertEquals("OneSay", Flavor.runValue());
        String h = html();
        assertEquals("正式版页面不改", h, UiServer.flavored(h));
    }

    @Test public void folderSettingsAreValidated() throws Exception {
        assertNull(App.dirProblem(""));
        assertNotNull(App.dirProblem("relative/dir"));
        assertNotNull(App.dirProblem("/definitely/not/here/xyz"));
        Path f = Files.createTempFile("x", ".txt");
        assertTrue(App.dirProblem(f.toString()).contains("不是文件夹"));
        assertNull(App.dirProblem(f.getParent().toString()));
    }
}
