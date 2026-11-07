package com.onesay.assistant.v2.pclink;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * [pclink] 接线（读源码）：插件登记、提醒中枢旁路监听、密钥登记加密、扫码页声明、ZXing 进构建与 NOTICE、
 * 用户可见文字不出现品牌词、默认开但没配对不联网的约定。
 */
public class PcLinkWiringTest {

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    static final String P = "app/src/com/onesay/assistant/v2/";

    @Test public void pluginRegisteredAndOnByDefault() throws Exception {
        assertTrue(src(P + "kernel/BuiltinPlugins.java").contains("new com.onesay.assistant.v2.kernel.plugins.PcLinkPlugin()"));
        String pl = src(P + "kernel/plugins/PcLinkPlugin.java");
        assertTrue(pl.contains("public boolean defaultOn() { return true; }"));
        assertTrue(pl.contains("NoticeHooks.addSurfaceListener"));
        assertTrue(pl.contains("PcLinkUi.PAGE.equals(page)"));
        assertTrue(pl.contains("ConsoleHost.PAGE_FEATURES.equals(page)"));
        assertTrue(src(P + "pclink/PcLink.java").contains("new FeatureSwitch(PREFS, \"enabled\", true, KILL)"));
    }

    @Test public void noticeHooksFireSurfacedBeforeIsland() throws Exception {
        String s = src(P + "notice/NoticeHooks.java");
        int present = s.indexOf("static void present(Context ctx, NoticeCenter.Tick t) {");
        assertTrue(present > 0);
        int fire = s.indexOf("fireSurfaced(app, t);", present);
        int master = s.indexOf("if (!NoticeSettings.master(app))", present);
        assertTrue("旁路监听在 present 开头", fire > present && fire < master);
    }

    @Test public void forwardingOnlyWhenActiveAndOffLineDrops() throws Exception {
        String s = src(P + "pclink/PcLink.java");
        int f = s.indexOf("public static void onNoticesSurfaced");
        assertTrue(f > 0);
        String body = s.substring(f, s.indexOf("// ====", f));
        assertTrue(body.contains("if (!active(app)) return;"));
        assertTrue(body.contains("fwdOn(app, kind)"));
        assertTrue(body.contains("DEDUP.first"));
        assertFalse("离线不排队：没有重试 / 队列", body.contains("retry") || body.contains("Queue"));
    }

    @Test public void pairKeyIsEncryptedAndRegistered() throws Exception {
        assertTrue(src(P + "security/SecretPrefs.java").contains("{\"bixby_pclink\", \"pair_key\"}"));
        String s = src(P + "pclink/PcLink.java");
        assertTrue(s.contains("SecretPrefs.put(e, K_KEY,"));
        assertTrue(s.contains("SecretPrefs.get(s, K_KEY)"));
        assertFalse("密钥不进日志", s.matches("(?s).*Log\\.[iwde]\\([^;]*K_KEY.*"));
    }

    @Test public void scanActivityDeclaredNotExported() throws Exception {
        String m = src("app/AndroidManifest.xml");
        int i = m.indexOf("com.onesay.assistant.v2.pclink.PcScanActivity");
        assertTrue(i > 0);
        String decl = m.substring(m.lastIndexOf("<activity", i), m.indexOf("/>", i));
        assertTrue(decl.contains("android:exported=\"false\""));
        assertTrue(m.contains("android.permission.CAMERA"));
    }

    @Test public void zxingInBuildAndNotice() throws Exception {
        assertTrue(new File(System.getProperty("bixby.root", "."), "libs/zxing-core-3.5.3.jar").isFile());
        String b = src("build_linux.sh");
        assertTrue(b.contains("ZXING=\"$ROOT/libs/zxing-core-3.5.3.jar\""));
        assertTrue(b.contains("\"$MAIL1\" \"$MAIL2\" \"$ZXING\""));
        assertTrue(src("NOTICE").contains("ZXing"));
        assertEquals(src("NOTICE"), src("app/assets/NOTICE.txt"));
        assertTrue(src("../../THIRD_PARTY_NOTICES.md").contains("zxing-core-3.5.3.jar"));
    }

    @Test public void userVisibleTextHasNoBrandWords() throws Exception {
        for (String f : new String[]{"pclink/PcLinkUi.java", "pclink/PcScanActivity.java", "pclink/PcLink.java", "pclink/PcPairing.java",
                "pclink/PcLanClient.java", "kernel/plugins/PcLinkPlugin.java"}) {
            String s = src(P + f);
            // 只看字符串字面量
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"\\\\]|\\\\.)*\"").matcher(s);
            while (m.find()) {
                String lit = m.group();
                assertFalse(f + " " + lit, lit.contains("Bixby") || lit.contains("三星") || lit.contains("Samsung"));
            }
        }
    }

    @Test public void urlNormalize() {
        assertEquals("https://example.com", PcVoice.normalizeUrl("example.com"));
        assertEquals("http://a.cn/x?y=1", PcVoice.normalizeUrl(" http://a.cn/x?y=1 "));
        assertNull(PcVoice.normalizeUrl("javascript:alert(1)"));
        assertNull(PcVoice.normalizeUrl("file:///etc/passwd"));
        assertNull(PcVoice.normalizeUrl("ftp://x.com"));
        assertNull(PcVoice.normalizeUrl("a b"));
        assertNull(PcVoice.normalizeUrl(""));
    }
}
