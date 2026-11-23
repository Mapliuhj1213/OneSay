package com.onesay.plugin.exec.shizuku;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ sec-audit-1118 M4：CALL / ENDCALL / HEADSETHOOK / CAMERA / VOICE_ASSIST 全局键都拒（开源版没有虚拟屏执行面，只测主屏；移植自个人版 3ba70f7），名字 / 数字 / 前导 0 / --longpress 各写法都拒。 */
public class SecM4GlobalKeyTest {

    static final String[] KEYS = {"5", "05", "KEYCODE_CALL", "6", "006", "KEYCODE_ENDCALL", "79", "079", "KEYCODE_HEADSETHOOK",
            "27", "027", "KEYCODE_CAMERA", "231", "0231", "KEYCODE_VOICE_ASSIST"};

    @Test public void globalKeysDeniedOnMainDisplay() {
        ShizukuWhitelist wl = new ShizukuWhitelist("com.onesay.assistant.debug");
        for (String k : KEYS) {
            assertFalse(k, wl.check("input keyevent " + k).allowed);
            assertFalse("--longpress " + k, wl.check("input keyevent --longpress " + k).allowed);
        }
    }

    @Test public void ordinaryKeysStillAllowed() {
        ShizukuWhitelist wl = new ShizukuWhitelist("com.onesay.assistant.debug");
        for (String k : new String[]{"4", "KEYCODE_BACK", "24", "25", "KEYCODE_ENTER", "66", "KEYCODE_MEDIA_PLAY_PAUSE", "85", "7", "KEYCODE_DEL"}) {
            assertTrue(k, wl.check("input keyevent " + k).allowed);
        }
    }
}
