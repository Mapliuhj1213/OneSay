package com.onesay.assistant.v2.whitelist;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B13：厂商安装器 / 安全中心 / 权限管理、系统设置、root 管理器和 Windows 高危系统工具、8.3 短名不能加白名单（t20k whitelist.normid 19 条复现）。 */
public class B13NeverListTest {

    static final String A = AppWhitelist.ANDROID, W = AppWhitelist.WINDOWS;

    @Test public void vendorSecurityAndSettingsRefused() {
        for (String p : new String[]{"com.meizu.safe", "com.miui.packageinstaller", "com.miui.securitycenter", "com.coloros.safecenter",
                "com.oplus.safecenter", "com.samsung.android.packageinstaller", "com.huawei.systemmanager", "com.vivo.permissionmanager",
                "com.android.settings", "me.weishu.kernelsu", "io.github.huskydg.magisk", "COM.MEIZU.SAFE"}) {
            assertNotNull(p, AppWhitelist.refuseReason(A, p));
        }
    }

    @Test public void windowsAdminToolsRefused() {
        for (String p : new String[]{"vssadmin.exe", "bcdedit.exe", "wevtutil.exe", "diskpart.exe", "takeown.exe", "icacls.exe", "shutdown.exe",
                "VSSADMIN", "C:\\Windows\\System32\\bcdedit.exe", "\"C:\\Windows\\System32\\diskpart.exe\"", "netsh", "fsutil.exe", "cipher.exe"}) {
            assertNotNull(p, AppWhitelist.refuseReason(W, p));
        }
    }

    @Test public void shortNamesRefused() {
        for (String p : new String[]{"POWERS~1.EXE", "powers~1.exe", "C:\\PROGRA~1\\NOTEPA~1.EXE", "cmd~2"}) {
            assertNotNull(p, AppWhitelist.refuseReason(W, p));
        }
    }

    @Test public void ordinaryAppsStillAllowed() {
        for (String p : new String[]{"com.tencent.mm", "com.eg.android.AlipayGphone", "com.meizu.notepaper", "com.android.settingsx.demo"}) {
            assertNull(p, AppWhitelist.refuseReason(A, p));
        }
        for (String p : new String[]{"notepad++.exe", "WINWORD", "C:\\Program Files\\Tencent\\WeChat\\WeChat.exe", "my~app.exe"}) {
            assertNull(p, AppWhitelist.refuseReason(W, p));
        }
    }

    @Test public void syncedEntryForRefusedAppNeverCounts() {
        AppWhitelist wl = new AppWhitelist();
        assertFalse(wl.contains(A, "com.meizu.safe"));
        assertFalse(wl.contains(W, "vssadmin.exe"));
    }
}
