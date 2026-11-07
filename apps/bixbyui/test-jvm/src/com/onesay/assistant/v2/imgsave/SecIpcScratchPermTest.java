package com.onesay.assistant.v2.imgsave;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 2026-10-25 安全修复 IPC-5：ImageSaveController 外部缓存兜底也一律 chown + chmod 600，不再 666。 */
public class SecIpcScratchPermTest {

    @Test public void externalFallbackIs600() {
        File ext = new File("/storage/emulated/0/Android/data/com.onesay.assistant/cache/imgsave");
        String c = ImageSaveController.scratchFix(new File(ext, "raw"), new File(ext, "ui.xml"), ext);
        assertFalse(c, c.contains("666"));
        assertTrue(c, c.contains("chmod 600 '" + new File(ext, "raw").getAbsolutePath() + "'"));
        assertTrue(c, c.contains("chmod 600 '" + new File(ext, "ui.xml").getAbsolutePath() + "'"));
        assertTrue(c, c.contains("chown $(stat -c %u:%g '" + ext.getAbsolutePath() + "')"));
        File priv = new File("/data/user/0/com.onesay.assistant/cache/scratch/imgsave");
        String p = ImageSaveController.scratchFix(new File(priv, "raw"), new File(priv, "ui.xml"), priv);
        assertFalse(p.contains("666"));
        assertTrue(p.contains("chmod 600"));
    }

    @Test public void noChmod666LeftInCode() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String s = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/imgsave/ImageSaveController.java").toPath()), StandardCharsets.UTF_8);
        assertFalse(s.contains("\"chmod 666"));
    }
}
