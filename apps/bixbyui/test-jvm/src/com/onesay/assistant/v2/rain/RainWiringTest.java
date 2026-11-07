package com.onesay.assistant.v2.rain;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** ★ feat/rain-minutely：能力探测、开源构建默认值、接线、私有 Host 不进仓库。 */
public class RainWiringTest {

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(root(), rel).toPath()), "UTF-8");
    }

    @Test public void capabilityProbeReasons() {
        assertNull(RainService.reason(false, true, true));
        assertTrue(RainService.reason(false, false, true).contains("Key"));
        assertTrue(RainService.reason(false, true, false).contains("Host"));
        assertTrue(RainService.reason(false, false, false).contains("Host"));
        assertTrue(RainService.reason(true, true, true).contains("停用"));
    }

    @Test public void cleanHost() {
        assertEquals("abc.re.qweatherapi.com", RainService.cleanHost("https://ABC.re.qweatherapi.com/v7/"));
        assertEquals("abc.re.qweatherapi.com", RainService.cleanHost(" abc.re.qweatherapi.com "));
        assertEquals("", RainService.cleanHost("not a host"));
        assertEquals("", RainService.cleanHost(null));
        assertEquals("", RainService.cleanHost("localhost"));
    }

    /** 开源构建（随仓库的 app/config）：没有 Host、岛提醒默认关 → 置灰；模板同样是空的。 */
    @Test public void openSourceDefaultsOff() throws Exception {
        assertEquals("", com.onesay.assistant.v2.LocalDefaults.QWEATHER_HOST);   // 单测按 app/config 编译
        assertFalse(com.onesay.assistant.v2.LocalDefaults.RAIN_ALERTS_DEFAULT);
        String ex = read("local-config.example/com/onesay/assistant/v2/LocalDefaults.java");
        assertTrue(ex.contains("QWEATHER_HOST = \"\""));
        assertTrue(ex.contains("RAIN_ALERTS_DEFAULT = false"));
        assertFalse(RainService.ALERTS.def);
    }

    /** 本机 local-config 里的私有 Host 不能出现在任何随仓库的源码 / 文档 / 夹具里（local-config 不存在时跳过）。 */
    @Test public void privateHostNeverInTrackedTrees() throws Exception {
        File lc = new File(root(), "local-config/com/onesay/assistant/v2/LocalDefaults.java");
        if (!lc.isFile()) return;
        Matcher m = Pattern.compile("QWEATHER_HOST\\s*=\\s*\"([^\"]*)\"").matcher(new String(Files.readAllBytes(lc.toPath()), "UTF-8"));
        if (!m.find() || m.group(1).length() == 0) return;
        String host = m.group(1);
        String sub = host.split("\\.")[0];
        for (String dir : new String[]{"app", "docs", "test-jvm/src", "test-jvm/fixtures", "local-config.example", "tools"}) {
            scan(new File(root(), dir), sub);
        }
    }

    static void scan(File f, String needle) throws Exception {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) scan(k, needle);
            return;
        }
        String n = f.getName();
        if (!(n.endsWith(".java") || n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".txt") || n.endsWith(".sh") || n.endsWith(".xml"))) return;
        if (f.length() > 2_000_000) return;
        String s = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        assertFalse("私有 Host 出现在 " + f, s.contains(needle));
    }

    @Test public void wiring() throws Exception {
        String plugins = read("app/src/com/onesay/assistant/v2/kernel/BuiltinPlugins.java");
        assertTrue(plugins.contains("new com.onesay.assistant.v2.kernel.plugins.RainPlugin()"));
        String router = read("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertTrue("降水答复进会话历史 + 不挂 AI 免责行", router.contains("\"planner\", \"quicknote\", \"rain\""));
        assertTrue(com.onesay.assistant.v2.security.SecretPrefs.isRegistered("qweather_key"));
        String svc = read("app/src/com/onesay/assistant/v2/rain/RainService.java");
        assertTrue(svc.contains("\"X-QW-Api-Key\""));
        assertFalse("不自己加 Accept-Encoding（交给 HttpURLConnection 透明解 gzip）", svc.contains("Accept-Encoding"));
        // 日志不打 Key / Host / 坐标
        Matcher lm = Pattern.compile("Log\\.[iwde]\\(TAG,([^;]*)\\);").matcher(svc + read("app/src/com/onesay/assistant/v2/rain/RainHooks.java"));
        while (lm.find()) {
            String line = lm.group(1);
            assertFalse(line, line.contains("key(") || line.contains("host(") || line.contains("loc.param") || Pattern.compile("\\.(lat|lon)\\b").matcher(line).find());
        }
        String view = read("app/src/com/onesay/assistant/v2/island/IslandView.java");
        assertTrue(view.contains("rainBars(c, r.bars"));
    }

    @Test public void shortcutWeatherFollowsProbe() {
        com.onesay.assistant.v2.shortcut.ShortcutActions.Probe old = com.onesay.assistant.v2.shortcut.ShortcutActions.weatherProbe;
        try {
            com.onesay.assistant.v2.capability.Capability cap = com.onesay.assistant.v2.shortcut.ShortcutActions.internalCap(com.onesay.assistant.v2.shortcut.ShortcutActions.WEATHER);
            com.onesay.assistant.v2.shortcut.ShortcutActions.weatherProbe = null;
            assertNotNull(com.onesay.assistant.v2.shortcut.ShortcutActions.unavailableReason(com.onesay.assistant.v2.shortcut.ShortcutActions.WEATHER, cap, null, false));
            com.onesay.assistant.v2.shortcut.ShortcutActions.weatherProbe = new com.onesay.assistant.v2.shortcut.ShortcutActions.Probe() {
                @Override public String reason() { return null; }
            };
            assertNull(com.onesay.assistant.v2.shortcut.ShortcutActions.unavailableReason(com.onesay.assistant.v2.shortcut.ShortcutActions.WEATHER, cap, null, false));
            com.onesay.assistant.v2.shortcut.ShortcutActions.weatherProbe = new com.onesay.assistant.v2.shortcut.ShortcutActions.Probe() {
                @Override public String reason() { return "缺和风天气 API Key"; }
            };
            assertTrue(com.onesay.assistant.v2.shortcut.ShortcutActions.unavailableReason(com.onesay.assistant.v2.shortcut.ShortcutActions.WEATHER, cap, null, true).contains("Key"));
        } finally {
            com.onesay.assistant.v2.shortcut.ShortcutActions.weatherProbe = old;
        }
    }
}
