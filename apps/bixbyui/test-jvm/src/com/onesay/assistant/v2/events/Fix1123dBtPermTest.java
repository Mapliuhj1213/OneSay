package com.onesay.assistant.v2.events;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** ★ 1123d B35 续：Android 12+ 读已配对蓝牙设备要 BLUETOOTH_CONNECT（清单声明 + 选蓝牙时请求 + 提示）。 */
public class Fix1123dBtPermTest {

    private static String read(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("apps/bixbyui/" + rel);
        if (!f.exists()) f = new File("../" + rel);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    @Test public void needOnlyOnAndroid12Up() {
        assertFalse(EventPickHint.needBtConnect(26, false));
        assertFalse(EventPickHint.needBtConnect(30, false));
        assertTrue(EventPickHint.needBtConnect(31, false));
        assertTrue(EventPickHint.needBtConnect(36, false));
        assertFalse(EventPickHint.needBtConnect(36, true));
        assertEquals("android.permission.BLUETOOTH_CONNECT", EventPickHint.PERM_BT_CONNECT);
    }

    @Test public void hintOrder() {
        // 蓝牙关着：先叫开蓝牙
        assertEquals(EventPickHint.BT_OFF, EventPickHint.emptyHint(false, null, false, true, false));
        // 开着但缺权限：说缺权限
        assertEquals(EventPickHint.BT_NEED_PERMISSION, EventPickHint.emptyHint(false, Collections.<String>emptyList(), true, true, false));
        // 有权限没设备：老提示
        assertEquals(EventPickHint.BT_EMPTY, EventPickHint.emptyHint(false, null, true, true, true));
        // 有设备：不提示
        assertNull(EventPickHint.emptyHint(false, Arrays.asList("耳机"), true, true, false));
        // 旧四参数版本行为不变
        assertEquals(EventPickHint.BT_EMPTY, EventPickHint.emptyHint(false, null, true, true));
        // Wi‑Fi 不看蓝牙权限
        assertEquals(EventPickHint.WIFI_EMPTY, EventPickHint.emptyHint(true, null, true, true, false));
    }

    @Test public void hintTextPlain() {
        String t = EventPickHint.BT_NEED_PERMISSION;
        assertTrue(t.contains(com.onesay.assistant.v2.ui.console.FormChoices.MANUAL));
        for (String bad : new String[] {"Bixby", "三星", "DeX", "小布", "BLUETOOTH", "啥", "咋", "呗"}) assertFalse(bad, t.contains(bad));
    }

    @Test public void manifestDeclaresAndConsoleRequests() throws Exception {
        String m = read("app/AndroidManifest.xml");
        assertTrue(m.contains("<uses-permission android:name=\"android.permission.BLUETOOTH_CONNECT\" />"));
        assertTrue(m.contains("android.permission.BLUETOOTH\" />"));
        String c = read("app/src/com/onesay/assistant/v2/events/EventConsole.java");
        assertTrue(c.contains("EventPickHint.PERM_BT_CONNECT"));
        assertTrue(c.contains("bluetoothConnectOk"));
    }
}
