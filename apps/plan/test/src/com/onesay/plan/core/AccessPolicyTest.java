package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/** 后门门卫 + Manifest 声明（signature 权限、Provider 导出但代码把门、接收器不乱导出）。 */
public class AccessPolicyTest {

    static final int OWN = 10300, BIXBY = 10222, OTHER = 10400;

    @Test public void selfAllowed() {
        assertEquals(AccessPolicy.Who.SELF, AccessPolicy.decide(OWN, OWN, false, false));
    }

    @Test public void signaturePermissionAllowed() {
        assertEquals(AccessPolicy.Who.PERMISSION, AccessPolicy.decide(BIXBY, OWN, true, false));
    }

    @Test public void sameSignerAllowedEvenWithoutPermission() {
        // 先装调用方、后装 Plan 时系统可能不补授 signature 权限 → 靠运行期同签名校验
        assertEquals(AccessPolicy.Who.SAME_SIGNER, AccessPolicy.decide(BIXBY, OWN, false, true));
    }

    @Test public void strangerDenied() {
        assertEquals(AccessPolicy.Who.DENIED, AccessPolicy.decide(OTHER, OWN, false, false));
        assertFalse(AccessPolicy.allowed(AccessPolicy.Who.DENIED));
    }

    @Test public void noRootOrShellChannel() {
        assertEquals(AccessPolicy.Who.DENIED, AccessPolicy.decide(0, OWN, false, false));
        assertEquals("root 持有所有权限也拒绝", AccessPolicy.Who.DENIED, AccessPolicy.decide(0, OWN, true, true));
        assertEquals(AccessPolicy.Who.DENIED, AccessPolicy.decide(2000, OWN, false, false));
        assertEquals(AccessPolicy.Who.DENIED, AccessPolicy.decide(2000, OWN, true, true));
    }

    @Test public void noHardcodedCertificates() throws Exception {
        String src = new String(Files.readAllBytes(new File(System.getProperty("plan.root", "."),
                "app/src/com/onesay/plan/core/AccessPolicy.java").toPath()), Charset.forName("UTF-8"));
        assertFalse("不内置证书指纹", src.matches("(?s).*[0-9a-f]{64}.*"));
    }

    static String manifest() throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("plan.root", "."), "app/AndroidManifest.xml").toPath()), Charset.forName("UTF-8"));
    }

    @Test public void manifestDeclaresSignaturePermission() throws Exception {
        String m = manifest();
        assertTrue(m.contains("android:name=\"" + Contract.PERMISSION + "\""));
        assertTrue(m.contains("android:protectionLevel=\"signature\""));
        assertFalse(m.contains("signatureOrSystem"));
    }

    @Test public void manifestProviderAuthorityMatchesContract() throws Exception {
        String m = manifest();
        assertTrue(m.contains("android:authorities=\"" + Contract.AUTHORITY + "\""));
        assertTrue(m.contains("android:grantUriPermissions=\"false\""));
    }

    @Test public void remindReceiverNotExported() throws Exception {
        String m = manifest();
        int i = m.indexOf(".remind.RemindReceiver");
        assertTrue(i > 0);
        assertTrue(m.substring(i, m.indexOf("/>", i)).contains("android:exported=\"false\""));
        int t = m.indexOf(".ui.TicketActivity");
        assertTrue(m.substring(t, m.indexOf("/>", t)).contains("android:exported=\"false\""));
    }

    @Test public void noDangerousPermissions() throws Exception {
        String m = manifest();
        for (String p : new String[]{"INTERNET", "READ_SMS", "READ_CONTACTS", "READ_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW"})
            assertFalse(p, m.contains("android.permission." + p));
    }

    @Test public void sourceLabels() {
        assertEquals("ui", AccessPolicy.source(AccessPolicy.Who.SELF, null));
        assertEquals("bixbyui", AccessPolicy.source(AccessPolicy.Who.PERMISSION, "com.onesay.assistant"));
        assertEquals("bixbyui", AccessPolicy.source(AccessPolicy.Who.SAME_SIGNER, "com.onesay.assistant.debug"));
        assertEquals("other", AccessPolicy.source(AccessPolicy.Who.SAME_SIGNER, "com.example"));
        assertEquals("other", AccessPolicy.source(AccessPolicy.Who.PERMISSION, null));
    }

    @Test public void runSourceLabels() {
        assertEquals("bixbyui", AccessPolicy.runSource("com.onesay.assistant"));
        assertEquals("root", AccessPolicy.runSource(null));
        assertEquals("root", AccessPolicy.runSource("com.example"));
    }
}
