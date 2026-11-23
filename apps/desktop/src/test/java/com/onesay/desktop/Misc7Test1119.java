package com.onesay.desktop;

import org.junit.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/** [overnight-1119 第 7 条] 中转 SSL 日志降噪、硬编码坐标防护。 */
public class Misc7Test1119 {

    @Test public void sameOfflineReasonIsLoggedOnce() {
        LogQuiet q = new LogQuiet();
        String a = q.filter("中转", false, "connect failed: SSLHandshakeException: Remote host terminated the handshake", 0);
        assertEquals("中转 离线（中转加密握手失败（证书或网络被拦），会自动重试）", a);
        assertNull(q.filter("中转", false, "connect failed: SSLHandshakeException: Remote host terminated the handshake", 2000));
        assertNull("退避里一直重复也不刷", q.filter("中转", false, "connect failed: SSLHandshakeException: Remote host terminated the handshake", 64000));
        String up = q.filter("中转", true, "connected", 70000);
        assertEquals("中转 在线（connected）（之前同样的情况又出现 2 次，已省略）", up);
        assertNotNull("恢复后再断要记", q.filter("中转", false, "error: Connection refused", 71000));
        assertNull(q.filter("中转", false, "error: Connection refused", 72000));
        assertNotNull("10 分钟后再提醒一次", q.filter("中转", false, "error: Connection refused", 72000 + LogQuiet.REPEAT_MS));
        assertTrue(LogQuiet.humanize("java.net.ConnectException").contains("连不上"));
        assertTrue(LogQuiet.humanize("PKIX path building failed").contains("证书"));
        assertEquals("closed 4000 replaced", LogQuiet.humanize("closed 4000 replaced"));
    }

    static Map<String, Object> cmd(String c) {
        Map<String, Object> m = new TreeMap<>();
        m.put("command", c);
        return m;
    }

    @Test public void guessedCoordinatesAreCaught() {
        assertTrue(ChatAgent.guessesCoords("sys_exec", cmd("adb -s 127.0.0.1:16384 shell input tap 540 1650")));
        assertTrue(ChatAgent.guessesCoords("sys_exec", cmd("adb shell \"input swipe 500 1500 500 300 300\"")));
        assertTrue(ChatAgent.guessesCoords("sys_exec", cmd("adb shell input touchscreen tap 10 20")));
        assertFalse(ChatAgent.guessesCoords("sys_exec", cmd("adb shell input text hello")));
        assertFalse(ChatAgent.guessesCoords("sys_exec", cmd("adb shell input keyevent 4")));
        assertFalse(ChatAgent.guessesCoords("file_write", cmd("input tap 1 2")));
    }

    @Test public void phoneDesktopSaysUnsupportedOnOldAndroid() {
        String t = Adb.scrcpyZh("ERROR: Could not create virtual display: not supported", 1, false, true);
        assertTrue(t, t.startsWith("此手机系统版本暂不支持手机桌面"));
        assertFalse("用户可见文字不出现别家的名字", t.contains("DeX") || t.contains("三星"));
    }
}
