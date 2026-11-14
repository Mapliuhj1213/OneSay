package com.onesay.assistant.v2.vyrse;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * compat-vyrse 接线（读源码）：插件登记且默认关、key 进 Keystore 密文登记表、服务不导出、
 * 明文 HTTP 警告在、没开全局 cleartext、用户可见文字没有品牌词、MIT 署名在 NOTICE / THIRD_PARTY_NOTICES。
 */
public class VyrseWiringTest {

    static final String ROOT = System.getProperty("bixby.root", ".");

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(ROOT, rel).toPath()), "UTF-8");
    }

    static final String P = "app/src/com/onesay/assistant/v2/";
    static final String CORE = "app/src/com/onesay/assistant/plug/vyrse/core/";

    @Test public void registeredAndOffByDefault() throws Exception {
        assertTrue(src(P + "kernel/BuiltinPlugins.java").contains("new com.onesay.assistant.v2.kernel.plugins.VyrsePlugin()"));
        String pl = src(P + "kernel/plugins/VyrsePlugin.java");
        assertTrue(pl.contains("public boolean defaultOn() { return false; }"));
        assertTrue(src(P + "vyrse/VyrseSettings.java").contains("new FeatureSwitch(PREF, \"enabled\", false, KILL)"));
        String svc = src(P + "vyrse/VyrseLinkService.java");
        assertTrue("服务只在 开关 + 配对 + 连接开关 时运行",
                svc.contains("return VyrseSettings.enabled(c) && VyrseSettings.paired(c) && VyrseSettings.linkOn(c);"));
    }

    @Test public void keyStoredAsKeystoreCiphertext() throws Exception {
        assertTrue(src(P + "security/SecretPrefs.java").contains("{\"bixby_vyrse\", \"vyrse_token\"}"));
        String s = src(P + "vyrse/VyrseSettings.java");
        assertTrue(s.contains("SecretPrefs.put(e, K_TOKEN, r.token)"));
        assertTrue(s.contains("return SecretPrefs.get(sp(c), K_TOKEN);"));
        assertFalse("key 不明文存", s.contains("putString(K_TOKEN"));
        for (String f : new String[]{P + "vyrse/VyrseLinkService.java", P + "vyrse/VyrseUi.java", P + "vyrse/VyrseSettings.java"}) {
            assertFalse(f + " 日志不打 key", src(f).matches("(?s).*Log\\.[iwde]\\([^;]*(token|K_TOKEN|r\\.token)[^;]*;.*"));
        }
    }

    @Test public void serviceNotExportedAndCleartextStaysOff() throws Exception {
        String man = src("app/AndroidManifest.xml");
        int i = man.indexOf("com.onesay.assistant.v2.vyrse.VyrseLinkService");
        assertTrue(i > 0);
        assertTrue(man.substring(i, man.indexOf("/>", i)).contains("android:exported=\"false\""));
        assertTrue("不为兼容放开全局明文", man.contains("android:usesCleartextTraffic=\"false\""));
        String http = src(CORE + "VyrseHttp.java");
        assertFalse("不走 HttpURLConnection（会被全局策略拦，且不想放开）", http.contains("import java.net.HttpURLConnection") || http.contains("openConnection("));
        assertTrue(http.contains("if (!VyrseProtocol.isPrivateIpv4(host)) throw new IOException"));
    }

    @Test public void fullControlNotImplemented() throws Exception {
        String a = src(P + "vyrse/VyrseAbilities.java") + src(CORE + "PhoneAbilities.java") + src(CORE + "VyrseDispatcher.java");
        for (String w : new String[]{"dispatchGesture", "AccessibilityService", "performAction", "ACTION_SET_TEXT", "takeScreenshot"}) {
            assertFalse(w, a.contains(w));
        }
    }

    @Test public void warningShownToUser() throws Exception {
        String ui = src(P + "vyrse/VyrseUi.java");
        assertTrue(ui.contains("明文 HTTP，没有加密"));
        assertTrue("开总开关前弹警告", ui.contains("new AlertDialog.Builder(act).setTitle(\"连接没有加密\").setMessage(HTTP_WARNING)"));
        assertTrue("细项页常驻警告", ui.contains("host.valueRow(w, \"局域网明文 HTTP\", HTTP_WARNING, \"未加密\", ConsoleHost.TONE_WARN);"));
        assertTrue(src(P + "vyrse/VyrseLinkService.java").contains("局域网明文传输"));
    }

    /** 用户可见文字（字符串字面量）不出现品牌词。prefs 名 / 键名这类全小写内部标识不算（插件 prefs 按内核约定叫 bixby_xxx）。 */
    @Test public void noBrandWordsInUserVisibleText() throws Exception {
        Pattern lit = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        String[] files = {P + "vyrse/VyrseUi.java", P + "vyrse/VyrseLinkService.java", P + "vyrse/VyrseAbilities.java", P + "vyrse/VyrseSettings.java",
                P + "kernel/plugins/VyrsePlugin.java", CORE + "VyrsePairing.java", CORE + "VyrseDispatcher.java"};
        for (String f : files) {
            Matcher m = lit.matcher(src(f));
            while (m.find()) {
                String s = m.group(1);
                if (s.matches("[a-z0-9_.]+")) continue;   // prefs 名 / 键名这类内部标识（沿用 bixby_xxx 约定），不显示给用户
                assertFalse(f + ": " + s, s.toLowerCase(java.util.Locale.ROOT).contains("bixby"));
                assertFalse(f + ": " + s, s.contains("三星") || s.toLowerCase(java.util.Locale.ROOT).contains("samsung"));
            }
        }
    }

    @Test public void mitAttributionPresent() throws Exception {
        assertTrue(src("../../THIRD_PARTY_NOTICES.md").contains("https://github.com/felixthosharon/vyrse"));
        assertTrue(src("../../THIRD_PARTY_NOTICES.md").contains("Copyright (c) 2026 VYRSE contributors"));
        assertTrue(src("../../NOTICE").contains("VYRSE"));
        assertTrue(src("app/assets/NOTICE.txt").contains("VYRSE"));
        for (String f : new File(ROOT, CORE).list()) {
            assertTrue(f, src(CORE + f).contains("Copyright (c) 2026 VYRSE contributors, MIT License"));
        }
        assertTrue(new File(ROOT, "docs/COMPAT_VYRSE.md").isFile());
    }
}
