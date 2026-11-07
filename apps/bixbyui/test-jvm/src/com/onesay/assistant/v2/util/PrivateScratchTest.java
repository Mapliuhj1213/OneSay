package com.onesay.assistant.v2.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 中间产物改放私有目录 + root 写完的属主 / SELinux 修正。 */
public class PrivateScratchTest {

    @Test public void fixupCommandChownsChmodsRestorecons() {
        String c = PrivateScratch.fixupCmd("/data/user/0/com.onesay.assistant/cache/scratch/vision/vision_shot.png",
                "/data/user/0/com.onesay.assistant/cache/scratch/vision");
        assertTrue(c, c.startsWith("chown $(stat -c %u:%g '/data/user/0/com.onesay.assistant/cache/scratch/vision') '/data/user/0/com.onesay.assistant/cache/scratch/vision/vision_shot.png'"));
        assertTrue(c.contains("chmod 600 '/data/user/0/com.onesay.assistant/cache/scratch/vision/vision_shot.png'"));
        assertTrue(c.contains("restorecon '/data/user/0/com.onesay.assistant/cache/scratch/vision/vision_shot.png'"));
        assertTrue(c.endsWith("true"));
        assertEquals("'a'\\''b'", PrivateScratch.quote("a'b"));
    }

    @Test public void privatePathDetection() {
        assertTrue(PrivateScratch.isPrivatePath("/data/user/0/com.onesay.assistant/cache/scratch/imgsave"));
        assertTrue(PrivateScratch.isPrivatePath("/data/data/com.onesay.assistant/cache/scratch/screenref/sr_ui.xml"));
        assertFalse(PrivateScratch.isPrivatePath("/storage/emulated/0/Android/data/com.onesay.assistant/cache/vision_shot.png"));
        assertFalse(PrivateScratch.isPrivatePath(null));
    }

    @Test public void callersUsePrivateScratchAndNoPicturesFallback() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String[] files = {"app/src/com/onesay/assistant/v2/vision/ScreenVisionEngine.java",
                "app/src/com/onesay/assistant/v2/imgsave/ImageSaveController.java",
                "app/src/com/onesay/assistant/v2/vision/screenref/ScreenContextCapture.java"};
        for (String f : files) {
            String src = new String(Files.readAllBytes(new File(root, f).toPath()), StandardCharsets.UTF_8);
            assertTrue(f, src.contains("PrivateScratch.dir("));
            assertTrue(f, src.contains("PrivateScratch.fixupCmd("));
        }
        String v = new String(Files.readAllBytes(new File(root, files[0]).toPath()), StandardCharsets.UTF_8);
        assertFalse("识屏不再退回 /sdcard/Pictures", v.contains("FALLBACK_SHOT_PATH"));
    }
}
