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

    /** 行已在屏上时（配对成功后 refresh 重建行）attach 回调不会再触发：startPolling 必须当场检查 isAttachedToWindow 并立即开始轮询。 */
    @Test public void pollingStartsImmediatelyWhenRowAlreadyAttached() throws Exception {
        String s = src(P + "pclink/PcLinkUi.java");
        int sp = s.indexOf("static void startPolling(");
        assertTrue(sp > 0);
        int end = s.indexOf("static void pollLoop(", sp);
        String body = s.substring(sp, end);
        int add = body.indexOf("v.addOnAttachStateChangeListener(l);");
        int chk = body.indexOf("if (v.isAttachedToWindow()) l.onViewAttachedToWindow(v);");
        assertTrue("先挂监听", add > 0);
        assertTrue("挂监听后立即检查已 attach", chk > add);
        assertTrue("重复启动有防护", body.contains("if (alive[0]) return;"));
    }

    /** 相机权限弹窗会让扫码页 onPause/onResume：onResume 里只许请求一次，拒绝后不能无限弹窗（oss-device-1108 真机发现）。 */
    @Test public void cameraPermissionRequestedOnlyOnce() throws Exception {
        String s = src(P + "pclink/PcScanActivity.java");
        int r = s.indexOf("protected void onResume()");
        assertTrue(r > 0);
        String body = s.substring(r, s.indexOf("onRequestPermissionsResult", r));
        int g = body.indexOf("if (!mAskedCam)");
        assertTrue("有只问一次的防护", g > 0);
        assertTrue("防护包住 requestPermissions", body.indexOf("requestPermissions(") > g);
    }

    /**
     * 粘贴框不能把配对码交给中文输入法改写：要 ASCII 键盘、不联想；剪贴板里的配对码（规范化后）直接填进去；
     * 扫码页也用同一套规范化判断（oss-device-1108：真机经讯飞输入法输入，两个版本都报「不是电脑端的配对码」）。
     */
    @Test public void pasteAndScanBypassImeRewriting() throws Exception {
        String s = src(P + "pclink/PcLinkUi.java");
        int d = s.indexOf("public static void pasteDialog(");
        assertTrue(d > 0);
        String body = s.substring(d);
        assertTrue(body.contains("EditorInfo.IME_FLAG_FORCE_ASCII"));
        assertTrue(body.contains("InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS"));
        assertTrue(body.contains("InputType.TYPE_TEXT_VARIATION_PASSWORD"));
        assertTrue("剪贴板预填走规范化", body.contains("PcPairing.extract(clipText(act))"));
        assertFalse("不再要求剪贴板原文以前缀开头", body.contains("clip.startsWith(PcPairing.PREFIX)"));
        String scan = src(P + "pclink/PcScanActivity.java");
        assertTrue(scan.contains("if (!PcPairing.looksLike(text)) {"));
        assertFalse(scan.contains("text.trim().startsWith(PcPairing.PREFIX)"));
        // 电脑端 0.4.2 的按钮叫「复制配对串」
        assertFalse(s.contains("复制配对码"));
        assertFalse(scan.contains("复制配对码"));
    }
}
