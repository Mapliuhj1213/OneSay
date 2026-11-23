package com.onesay.plugin.exec.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.executor.ExecProtocol;
import com.onesay.assistant.v2.executor.backend.ShellBackend;
import com.onesay.assistant.v2.platform.ShizukuCompat;

import org.junit.Test;

import java.util.Arrays;

/**
 * ★ fix-1118：开源版 Shizuku 白名单补常用开关（真机 c07 电量、c10/c11 蓝牙、c12/c13 Wi-Fi、c18 蓝牙状态、c32 亮度全挂），
 * argv 精确匹配、不放宽元字符；sec-audit-1118 M4：接挂电话 / 耳机 / 相机 / 语音助手键不放。
 */
public class Fix1118ShizukuWhitelistTest {

    static final ShizukuWhitelist W = new ShizukuWhitelist("com.onesay.assistant.debug");

    static void allow(String c) {
        ShizukuWhitelist.Verdict v = W.check(c);
        assertTrue(c + " → " + v, v.allowed);
    }

    static void deny(String c) {
        ShizukuWhitelist.Verdict v = W.check(c);
        assertFalse(c + " → " + v, v.allowed);
    }

    @Test public void newNarrowEntries() {
        for (String c : new String[]{"dumpsys battery", "settings get system screen_brightness", "settings get system screen_brightness_mode",
                "settings get global bluetooth_on", "settings get global wifi_on", "cmd bluetooth_manager enable", "cmd bluetooth_manager disable",
                "cmd wifi status", "svc wifi enable", "settings put system screen_brightness 0", "settings put system screen_brightness 128",
                "settings put system screen_brightness 255",
                "settings put system screen_brightness 256", "settings put system screen_brightness 7253", "settings put system screen_brightness 65535"}) allow(c);   // ★ fix-1118 c32：厂商大刻度
        assertEquals(Arrays.asList("settings", "put", "system", "screen_brightness", "8"), W.check("settings put system screen_brightness 008").argv);
    }

    @Test public void everythingElseStillDenied() {
        for (String c : new String[]{"svc wifi disable", "svc data enable", "svc bluetooth enable", "dumpsys battery set level 5", "dumpsys battery unplug",
                "dumpsys", "dumpsys package com.tencent.mm", "dumpsys battery | grep level", "dumpsys battery; reboot",
                "settings put system screen_brightness 65536", "settings put system screen_brightness 100000", "settings put system screen_brightness -1", "settings put system screen_brightness 1e2",
                "settings put system screen_brightness_mode 0", "settings put global wifi_on 0", "settings put global bluetooth_on 1",
                "settings get secure android_id", "settings get global adb_enabled", "cmd bluetooth_manager enable now",
                "cmd wifi set-wifi-enabled disabled", "cmd wifi status extra", "cmd package uninstall com.tencent.mm",
                "service call bluetooth_manager 6", "sh -c dumpsys battery", "cmd bluetooth_manager $(id)",
                "settings get global wifi_on\nreboot", "svc wifi enable`id`", "ｓｖｃ wifi enable", "svc\u200b wifi enable"}) deny(c);
    }

    @Test public void callCameraAssistKeysDenied() {
        for (String k : new String[]{"KEYCODE_CALL", "5", "05", "KEYCODE_ENDCALL", "6", "006", "KEYCODE_HEADSETHOOK", "79", "KEYCODE_CAMERA", "27",
                "KEYCODE_VOICE_ASSIST", "231", "KEYCODE_POWER", "26"}) {
            deny("input keyevent " + k);
            deny("input keyevent --longpress " + k);
        }
        allow("input keyevent KEYCODE_HOME");
        allow("input keyevent 4");
        allow("input keyevent KEYCODE_VOLUME_DOWN");
    }

    @Test public void templateRewritesStayInsideWhitelist() {
        ShizukuCompat.Rewrite r = ShizukuCompat.rewrite("dumpsys battery | grep level");
        assertEquals("dumpsys battery", r.command);
        assertEquals("level:", r.grep);
        assertEquals("  level: 87", ShizukuCompat.filter("Current Battery Service state:\n  AC powered: false\n  level: 87\n  scale: 100", r.grep));
        assertEquals("cmd bluetooth_manager enable", ShizukuCompat.rewrite("service call bluetooth_manager 6").command);
        assertEquals("cmd bluetooth_manager disable", ShizukuCompat.rewrite("service call bluetooth_manager 9 s16 com.android.shell i32 1").command);
        assertEquals("cmd bluetooth_manager disable", ShizukuCompat.rewrite("svc bluetooth disable").command);
        assertNull(ShizukuCompat.rewrite("service call bluetooth_manager 1"));
        assertNull(ShizukuCompat.rewrite("svc wifi disable"));
        assertNull(ShizukuCompat.rewrite("dumpsys battery | grep level; reboot"));
        for (String c : new String[]{"dumpsys battery | grep level", "service call bluetooth_manager 6", "service call bluetooth_manager 9 s16 com.android.shell i32 1"}) {
            allow(ShizukuCompat.rewrite(c).command);
        }
    }

    @Test public void localBatteryMatchesDumpsysShape() {
        assertTrue(ShizukuCompat.isBatteryRead("dumpsys battery | grep level"));
        assertTrue(ShizukuCompat.isBatteryRead("dumpsys battery | grep -m1 ' level:'"));
        assertFalse(ShizukuCompat.isBatteryRead("dumpsys battery set level 1"));
        assertEquals("  level: 64", ShizukuCompat.localBatteryDump("dumpsys battery | grep level", 64, 312, 2, false, true));
        assertEquals("  temperature: 312", ShizukuCompat.localBatteryDump("dumpsys battery | grep -m1 temperature", 64, 312, 2, false, true));
        assertTrue(ShizukuCompat.localBatteryDump("dumpsys battery", 64, 312, 2, false, true).contains("  level: 64\n"));
    }

    @Test public void denyTextsNameShizukuNotRootAndReadsAreReads() {
        String d = ExecProtocol.describeDeny("SHIZUKU_PROG_NOT_WHITELISTED(pm)");
        assertFalse(d, d.contains("root"));
        assertTrue(d, d.contains("Shizuku"));
        String m = ShellBackend.userDenyMessage(null, "SHIZUKU_SVC_ARGS");
        assertFalse(m, m.contains("守护") || m.contains("root"));
        assertFalse(m, m.contains("Shizuku") || m.contains("SHIZUKU_"));   // ★ 1123d：用户看到的话不带服务商名和内部码
        // 原守护拒绝文本不变
        assertTrue(ExecProtocol.describeDeny("CMD_NOT_ALLOWED").startsWith("root 守护拒绝执行"));
    }
}
