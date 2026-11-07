package com.onesay.assistant.v2.r13;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.net.OfflineSettingsPolicy;
import com.onesay.assistant.v2.net.OfflineSettingsPolicy.Target;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import org.junit.Test;

/** R13b：离线兜底改为「按模式打开设置」（飞行模式 → 飞行模式页；否则 Wi‑Fi 页），带节流。 */
public class R13bOfflineSettingsTest {
    @Test public void airplaneGoesToAirplanePage() { assertEquals(Target.AIRPLANE, OfflineSettingsPolicy.target(true)); }
    @Test public void otherwiseWifiPage() { assertEquals(Target.WIFI, OfflineSettingsPolicy.target(false)); }

    @Test public void throttle() {
        assertTrue(OfflineSettingsPolicy.shouldOpen(5_000L, 0L));
        assertFalse(OfflineSettingsPolicy.shouldOpen(14_999L, 5_000L));
        assertTrue(OfflineSettingsPolicy.shouldOpen(15_000L, 5_000L));
        assertTrue("时钟回绕（重启）不应卡死节流", OfflineSettingsPolicy.shouldOpen(1_000L, 50_000L));
    }

    @Test public void sourceGuard() throws Exception {
        File f = new File("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        if (!f.exists()) f = new File("../app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        String s = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
        assertTrue(s.contains("ACTION_AIRPLANE_MODE_SETTINGS"));
        assertTrue(s.contains("new String[]{android.provider.Settings.ACTION_WIFI_SETTINGS"));
        assertFalse("移动数据设置不管", s.contains("ACTION_DATA_ROAMING_SETTINGS") || s.contains("ACTION_NETWORK_OPERATOR_SETTINGS"));
        assertTrue("打开后要收起面板", s.contains("if (openNetworkSettings()) {"));
    }
}
