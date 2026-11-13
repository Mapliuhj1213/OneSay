package com.onesay.assistant.v2.ui.console;

import com.onesay.assistant.v2.exec.ControlColor;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** ★ console-align 2026-11-12：手机设置区与电脑端设置页同名同序、危险项 / 状态徽标同一写法、共享清单 docs/CONSOLE.md。 */
public class ConsoleAlignTest {

    static final String CON = "app/src/com/onesay/assistant/v2/ui/console/";

    static String src(String rel) {
        return KernelWiring.src(rel);
    }

    static String doc() throws Exception {
        File root = new File(System.getProperty("bixby.root", "."));
        File f = new File(root, "docs/CONSOLE.md");
        if (!f.isFile()) f = new File(root, "../../docs/CONSOLE.md");   // 开源版：仓库根目录
        assertTrue("docs/CONSOLE.md 存在", f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void sharedOrderMatchesDocAndHome() throws Exception {
        String d = doc();
        String h = src(CON + "HomePage.java");
        int set = h.indexOf("k.group(col, \"设置\")");
        int prevDoc = -1, prevHome = set;
        for (String[] g : ConsoleGroups.GROUPS) {
            int i = d.indexOf("| " + g[1] + " |", prevDoc + 1);
            assertTrue("清单里有「" + g[1] + "」且顺序对", i > prevDoc);
            prevDoc = i;
            int j = h.indexOf("k.linkRow(g1, \"" + g[1] + "\"", prevHome);
            assertTrue("首页有「" + g[1] + "」且顺序对", j > prevHome);
            prevHome = j;
        }
        assertTrue("关于在最后", d.indexOf("| 关于 |", prevDoc) > prevDoc);
        for (String w : new String[]{"Bixby", "三星"}) assertFalse(w, d.contains(w));
    }

    @Test public void dangerVerbsAndConfirm() {
        for (String v : new String[]{"清空", "清除", "删除", "撤销", "解除配对", "卸载", "关机", "换一把钥匙", "忘掉手机证书"}) {
            assertTrue(v, ConsoleStyle.isDanger(v));
        }
        for (String v : new String[]{"下载", "保存", "录制", "加入", "去授权", "扫码", null, ""}) assertFalse(String.valueOf(v), ConsoleStyle.isDanger(v));
        String kit = src(CON + "ConsoleKit.java");
        assertTrue("危险按钮红字红底", kit.contains("if (ConsoleStyle.isDanger(action))") && kit.contains("t.dangerContainer, 20"));
        assertTrue("确认框危险动词写红", kit.contains("if (ConsoleStyle.isDanger(ok))") && kit.contains("BUTTON_POSITIVE).setTextColor(t.danger)"));
    }

    @Test public void badgesUseControlColors() {
        assertEquals(ControlColor.AGENT, ConsoleStyle.badgeRgb(ConsoleStyle.RUN));
        assertEquals(ControlColor.WAIT_USER, ConsoleStyle.badgeRgb(ConsoleStyle.WAIT));
        assertEquals(ControlColor.USER, ConsoleStyle.badgeRgb(ConsoleStyle.USER));
        assertEquals(ControlColor.STOPPED, ConsoleStyle.badgeRgb(ConsoleStyle.STOP));
        assertEquals("#7C7CFF", ConsoleStyle.hex(ControlColor.AGENT));
        assertEquals("#FFB020", ConsoleStyle.hex(ControlColor.WAIT_USER));
        assertEquals("#34C759", ConsoleStyle.hex(ControlColor.USER));
        assertEquals("#FF453A", ConsoleStyle.hex(ControlColor.STOPPED));
        assertTrue(src(CON + "GroupPages.java").contains("k.badgeRow(b, st, ConsoleStyle.badgeSub(st), st, ConsoleStyle.badgeRgb(st))"));
    }

    @Test public void docListsSameBadgeHexAndDangerVerbs() throws Exception {
        String d = doc();
        for (String st : ConsoleStyle.BADGES) {
            assertTrue("清单里有徽标 " + st, d.contains(st) && d.contains(ConsoleStyle.hex(ConsoleStyle.badgeRgb(st))));
        }
        for (String v : ConsoleStyle.DANGER_VERBS) assertTrue("清单里有危险动词 " + v, d.contains(v));
    }

    @Test public void modelPageHasEndpointAndModelName() {
        String a = src(CON + "AdvancedPage.java");
        int g = a.indexOf("k.group(col, \"主模型\")");
        assertTrue(g > 0);
        int url = a.indexOf("k.linkRow(g, \"接口地址\"", g), model = a.indexOf("k.linkRow(g, \"模型名\"", g), key = a.indexOf("k.linkRow(g, \"API Key\"", g);
        assertTrue("主模型：接口地址 → 模型名 → API Key", url > g && model > url && key > model);
        assertTrue("保存走校验", a.contains("MainModelEndpoint.save(c.act, url.getText().toString())") && a.contains("if (!r.ok)"));
        assertTrue("模型名走既有 chat_model", a.contains("ContextPrefs.setChatModel(c.act, m)") && a.contains("MainModelEndpoint.checkModel(m)"));
        assertTrue("日志只记主机名", a.contains("MainModelEndpoint.logLine()"));
        String l2 = src("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java");
        assertTrue("启动时读设置", l2.contains("MainModelEndpoint.load(this.mContext)"));
        assertTrue("仍用 DEEPSEEK_URL 建主客户端（请求时现取）", l2.contains("net.LlmClient.DEEPSEEK_URL"));
        assertFalse("不在构造时把地址写死", l2.contains("LlmEndpoints.deepseekChatUrl()"));
        assertTrue("debug 才认 http", src("app/src/com/onesay/assistant/v2/net/MainModelEndpoint.java").contains("FLAG_DEBUGGABLE"));
    }

    @Test public void mergedPagesKeepEverything() {
        String gp = src(CON + "GroupPages.java");
        assertTrue("权限与审批 = 系统权限 + 屏幕与剪贴板 + 以后这类不用问",
                gp.contains("k.group(col, \"系统权限\")") && gp.contains("c.kit.group(col, \"屏幕与剪贴板\")") && gp.contains("k.group(col, \"以后这类不用问\")"));
        assertTrue("撤销先确认", gp.contains("k.confirm(\"撤销「\"") && gp.contains("ConsentPolicy.revoke(sign)"));
        assertFalse("隐私页并进权限与审批", gp.contains("class PrivacyPage"));
        String v = src(CON + "VoicePage.java");
        assertTrue("语音一页 = 唤醒 + 朗读", v.contains("if (mode == MODE_ALL) buildRead(col);"));
        String f = src(CON + "FeaturesPage.java");
        assertTrue("灵动岛 / 主动建议两行在功能页最上面", f.indexOf("\"灵动岛\"") < f.indexOf("k.group(col, \"功能\")")
                && f.indexOf("new GroupPages.SuggestPage(c)") < f.indexOf("k.group(col, \"功能\")"));
        String about = src(CON + "AboutPage.java");
        assertTrue("外观在关于", about.contains("k.group(col, \"外观\")") && about.contains("c.setThemeMode("));
        String pc = src("app/src/com/onesay/assistant/v2/pclink/PcLinkUi.java");
        int dl = pc.indexOf("public static void addDownloadRows(");
        assertTrue(dl > 0 && pc.indexOf("\"把链接下到电脑\"", dl) > dl);
        int end = pc.indexOf("// ================================================================== 细项页", dl);
        for (int i = pc.indexOf("\"把链接下到电脑\""); i >= 0; i = pc.indexOf("\"把链接下到电脑\"", i + 1)) {
            assertTrue("「把链接下到电脑」只在「设置 › 下载」里（电脑联动细项页不再重复）", i > dl && i < end);
        }
    }

    @Test public void noVendorWordsInConsoleUi() throws Exception {
        for (String f : new String[]{"HomePage.java", "GroupPages.java", "AdvancedPage.java", "FeaturesPage.java", "AboutPage.java", "ConsoleGroups.java", "ConsoleStyle.java"}) {
            for (String line : src(CON + f).split("\n")) {
                String t = line.trim();
                if (t.startsWith("package ") || t.startsWith("import ") || t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
                String noPkg = line.replace("com.bixbyui", "").replace("bixby_", "");
                assertFalse(f + "：" + line, noPkg.contains("Bixby") || noPkg.contains("bixby"));
                assertFalse(f + "：" + line, line.contains("三星"));
            }
        }
    }
}
