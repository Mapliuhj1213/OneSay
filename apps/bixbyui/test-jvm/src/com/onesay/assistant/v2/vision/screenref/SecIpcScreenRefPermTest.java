package com.onesay.assistant.v2.vision.screenref;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 2026-10-25 安全修复 IPC-5：识屏界面树兜底到外部缓存时也 chown + chmod 600，不再 666。 */
public class SecIpcScreenRefPermTest {

    @Test public void fixCmdIs600ForAnyDir() {
        String ext = "/storage/emulated/0/Android/data/com.onesay.assistant/cache";
        String c = ScreenContextCapture.fixCmd(ext + "/sr_ui.xml", ext);
        assertFalse(c, c.contains("666"));
        assertTrue(c, c.contains("chmod 600 '" + ext + "/sr_ui.xml'"));
        assertTrue(c, c.contains("chown $(stat -c %u:%g '" + ext + "')"));
        assertTrue(c, c.contains("restorecon"));
        String priv = "/data/user/0/com.onesay.assistant/cache/scratch/screenref";
        assertEquals(com.onesay.assistant.v2.util.PrivateScratch.fixupCmd(priv + "/sr_ui.xml", priv), ScreenContextCapture.fixCmd(priv + "/sr_ui.xml", priv));
    }

    @Test public void captureUsesFixCmd() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String s = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/vision/screenref/ScreenContextCapture.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("String fix = fixCmd(p, dir.getAbsolutePath());"));
        assertFalse(s.contains("\"chmod 666"));
    }
}
