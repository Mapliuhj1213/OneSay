package com.onesay.plugin.exec.shizuku;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · libs/plugins/exec-shizuku 的命令白名单（Shizuku 以 shell / root 身份执行，默认关）：
 * 只放行注释列出的四类、参数逐个校验；任何 shell 元字符、改别的包、别的 appops、电源 / 睡眠键、工作区外截屏路径都拒。
 * 每行一个命令，期望 allow:<类别> 或 deny。前导零写法的电源键（026）在 AdvShizukuKeyTest（2026-11-14 已修）。
 */
@RunWith(Parameterized.class)
public class AdvShizukuWhitelistTest {

    static final String PKG = "com.onesay.assistant";

    @Parameterized.Parameters(name = "{index}: [{0}] {1}")
    public static Collection<Object[]> data() {
        String[][] rows = {
                {"appops set com.onesay.assistant READ_CLIPBOARD allow", "allow:clipboard"}, {"appops set com.onesay.assistant READ_CLIPBOARD default", "allow:clipboard"},
                {"appops get com.onesay.assistant READ_CLIPBOARD", "allow:clipboard"}, {"  appops   set   com.onesay.assistant   READ_CLIPBOARD   allow  ", "allow:clipboard"},
                {"appops set com.onesay.assistant ACCESS_RESTRICTED_SETTINGS allow", "allow:notification"},
                {"appops get com.onesay.assistant ACCESS_RESTRICTED_SETTINGS", "allow:notification"},
                {"cmd notification allow_listener com.onesay.assistant/.v2.notice.Listener", "allow:notification"},
                {"cmd notification disallow_listener com.onesay.assistant/com.onesay.assistant.L$Inner", "deny"},
                {"settings get secure enabled_notification_listeners", "allow:notification"},
                {"input keyevent KEYCODE_HOME", "allow:keyevent"}, {"input keyevent --longpress KEYCODE_VOLUME_UP", "allow:keyevent"}, {"input keyevent 3", "allow:keyevent"},
                {"input keyevent 224", "allow:keyevent"}, {"screencap -p /data/local/tmp/onesay.png", "allow:screencap"},
                {"screencap -p /data/local/tmp/onesay-1.png", "allow:screencap"}, {"screencap -p /sdcard/Android/data/com.onesay.assistant/cache/s.png", "allow:screencap"},
                {"screencap -p /storage/emulated/0/Android/data/com.onesay.assistant/cache/s.png", "allow:screencap"},
                // ---- deny
                {"", "deny"}, {"   ", "deny"}, {"appops set com.other.app READ_CLIPBOARD allow", "deny"}, {"appops set com.onesay.assistant.debug READ_CLIPBOARD allow", "deny"},
                {"appops set com.onesay.assistant CAMERA allow", "deny"}, {"appops set com.onesay.assistant READ_CLIPBOARD ignore", "deny"},
                {"appops set com.onesay.assistant READ_CLIPBOARD allow extra", "deny"}, {"appops set com.onesay.assistant read_clipboard allow", "deny"},
                {"APPOPS set com.onesay.assistant READ_CLIPBOARD allow", "deny"}, {"appops set com.onesay.assistant READ_CLIPBOARD allow; reboot", "deny"},
                {"appops set com.onesay.assistant READ_CLIPBOARD allow && reboot", "deny"}, {"appops set com.onesay.assistant READ_CLIPBOARD allow | sh", "deny"},
                {"appops set com.onesay.assistant READ_CLIPBOARD $(id)", "deny"}, {"appops set com.onesay.assistant READ_CLIPBOARD `id`", "deny"},
                {"appops set com.onesay.assistant READ_CLIPBOARD allow\nreboot", "deny"}, {"appops set 'com.onesay.assistant' READ_CLIPBOARD allow", "deny"},
                {"appops set com.onesay.assistant READ_CLIPBOARD allow > /x", "deny"}, {"appops reset com.onesay.assistant", "deny"},
                {"cmd notification allow_listener com.evil/.L", "deny"}, {"cmd notification allow_listener com.onesay.assistant", "deny"},
                {"cmd notification allow_listener /.L", "deny"}, {"cmd notification allow_listener com.onesay.assistant/", "deny"},
                {"cmd notification allow_listener com.onesay.assistantx/.L", "deny"}, {"cmd package grant com.onesay.assistant android.permission.X", "deny"},
                {"cmd notification post x y", "deny"}, {"settings put secure enabled_notification_listeners x", "deny"}, {"settings get secure android_id", "deny"},
                {"input keyevent KEYCODE_POWER", "deny"}, {"input keyevent 26", "deny"}, {"input keyevent KEYCODE_SLEEP", "deny"}, {"input keyevent 223", "deny"},
                {"input keyevent --longpress KEYCODE_POWER", "deny"}, {"input keyevent --longpress 26", "deny"}, {"input keyevent keycode_home", "deny"},
                {"input keyevent 1234", "deny"}, {"input keyevent KEYCODE_HOME KEYCODE_BACK", "deny"}, {"input keyevent", "deny"}, {"input tap 100 200", "deny"},
                {"input text hello", "deny"}, {"input keyevent --longpress", "deny"}, {"screencap /data/local/tmp/onesay.png", "deny"},
                {"screencap -p /data/local/tmp/other.png", "deny"}, {"screencap -p /data/local/tmp/onesay.jpg", "deny"}, {"screencap -p /data/local/tmp/../onesay.png", "deny"},
                {"screencap -p /data/local/tmp/./onesay.png", "deny"}, {"screencap -p /data/local/tmp//onesay.png", "deny"}, {"screencap -p /sdcard/onesay.png", "deny"},
                {"screencap -p /sdcard/Android/data/com.other/cache/s.png", "deny"}, {"screencap -p /data/local/tmp/onesay*.png", "deny"},
                {"screencap -p /data/local/tmp/onesay~.png", "deny"}, {"screencap -p /data/local/tmp/onesay中.png", "deny"}, {"screencap -p", "deny"},
                {"sh -c id", "deny"}, {"pm grant com.onesay.assistant android.permission.X", "deny"}, {"reboot", "deny"}, {"/system/bin/appops get com.onesay.assistant READ_CLIPBOARD", "deny"},
                {"appops\u00a0get com.onesay.assistant READ_CLIPBOARD", "deny"}, {"appops get com.onesay.assistant READ_CLIPBOARD #", "deny"},
                {"appops get com.onesay.assistant READ_CLIPBOARD !", "deny"}, {"appops get com.onesay.assistant READ_CLIPBOARD {x}", "deny"},
                {"x" + new String(new char[520]).replace('\0', 'a'), "deny"},
        };
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        for (String[] r : rows) {
            if (!seen.add(r[0])) throw new IllegalStateException("dup " + r[0]);
            out.add(new Object[]{r[0], r[1]});
        }
        out.add(new Object[]{null, "deny"});
        return out;
    }

    private final String cmd, want;

    public AdvShizukuWhitelistTest(String cmd, String want) {
        this.cmd = cmd;
        this.want = want;
    }

    @Test public void check() {
        ShizukuWhitelist w = new ShizukuWhitelist(PKG);
        ShizukuWhitelist.Verdict v = w.check(cmd);
        if (want.startsWith("allow:")) {
            assertTrue("[" + cmd + "] → " + v, v.allowed);
            assertEquals(want.substring(6), v.category);
            assertFalse(v.argv.isEmpty());
            for (String a : v.argv) assertFalse("argv 不含空白", a.matches(".*\\s.*"));
        } else {
            assertFalse("[" + cmd + "] → " + v, v.allowed);
            assertTrue(v.argv.isEmpty());
            assertNotNull(v.reason);
        }
    }
}
