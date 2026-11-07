package com.onesay.assistant.v2.quicknote;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

/**
 * 浏览器地址栏网址不再让记一记整屏拒收（ScreenSensitivity.URL_MORE + BIDI），原密码页判定不退化。
 */
public class QuickNoteBrowserUrlTest {

    @Test
    public void chromeLocalIpWithPortAndPath_notRefused() {
        assertNull(ScreenSensitivity.pageReason(false, Arrays.asList(
                "127.0.0.1:8765/plan/index.html?id=a8F3kQ9z",
                "192.168.1.100:8080/admin/x7Yq2Lp0",
                "localhost:3000/api/v1/items",
                "example.com/a/b7Cd9Ef2Gh",
                "foo.cn:8080",
                "今日计划", "下午 3 点 开会")));
        assertEquals("", ScreenSensitivity.stripAddresses("127.0.0.1:8765/plan/x9Kd2Lm8Qa"));
    }

    @Test
    public void samsungFileAndContentScheme_notRefused() {
        assertNull(ScreenSensitivity.pageReason(false, Arrays.asList(
                "file:///storage/emulated/0/Download/report_2026Q3_v8f2k9.html",
                "content://com.android.providers.downloads.documents/document/msf%3A1000023456",
                "chrome://flags", "about:blank",
                "季度报告")));
    }

    @Test
    public void bidiMarkLocalhost_notRefused_andPasswordPageStillRefused() {
        assertNull(ScreenSensitivity.pageReason(false, Arrays.asList("\u200Elocalhost", "\u200F127.0.0.1:8765/a1B2c3D4e5")));
        assertEquals("", ScreenSensitivity.stripAddresses("\u200Elocalhost"));
        // 原口径不退化
        assertNotNull("密码表单", ScreenSensitivity.pageReason(false, Arrays.asList("127.0.0.1:8765/login", "请输入密码")));
        assertNotNull("密码：", ScreenSensitivity.pageReason(true, Arrays.asList("密码：Abc12345")));
        assertNotNull("验证码", ScreenSensitivity.pageReason(false, Arrays.asList("file:///x.html", "您的验证码是 384920")));
        assertNotNull("一串像密码的", ScreenSensitivity.pageReason(false, Arrays.asList("Icon_recovery_x2_20_20")));
        assertNull(ScreenSensitivity.pageReason(true, Arrays.asList("登录账号 1234509876@qq.com", "您好，附件是本周周报")));
    }
}
