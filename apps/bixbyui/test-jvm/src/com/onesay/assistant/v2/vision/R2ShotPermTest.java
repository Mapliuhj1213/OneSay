package com.onesay.assistant.v2.vision;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** r2/fixes 第 5 项：识屏截图只放内部 cache；退回外部目录也不 chmod 666，统一 chown 给 App + chmod 600。 */
public class R2ShotPermTest {

    @Test public void captureCmdNever666AlwaysOwner600() {
        String[] paths = {
                "/data/user/0/com.onesay.assistant/cache/scratch/vision/vision_shot.png",
                "/data/user/0/com.onesay.assistant/cache/vision_shot.png",
                "/storage/emulated/0/Android/data/com.onesay.assistant/cache/vision_shot.png"};
        for (String p : paths) {
            // 2026-10-25 exec 守护 v4：截屏是单条命令；文件由 App 先建好（600、App 属主与标签），root 原地写，
            //   不再需要 chown/chmod；App 读不了才退回 fixupFallbackCmd（仍是 chown 给 App + chmod 600，绝不 666）。
            String c = ScreenVisionEngine.captureCmd(p);
            assertFalse(p + " → " + c, c.contains("666"));
            assertEquals("screencap -p '" + p + "'", c);
            String fb = ScreenVisionEngine.fixupFallbackCmd(p);
            assertFalse(fb.contains("666"));
            assertTrue(fb, fb.contains("chmod 600 '" + p + "'"));
            assertTrue(fb, fb.contains("chown $(stat -c %u:%g '" + new File(p).getParent() + "') '" + p + "'"));
        }
    }

    @Test public void internalCacheBeforeExternalAndNo666InSource() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String v = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/vision/ScreenVisionEngine.java").toPath()), StandardCharsets.UTF_8);
        assertFalse("识屏不再 chmod 666", v.contains("chmod 666"));
        assertTrue("截图文件由 App 先建好", v.contains("PrivateScratch.prepare(new File(path))"));
        int priv = v.indexOf("shotPath = shotPathPrivate();");
        int internal = v.indexOf("shotPath = shotPathInternalCache();");
        int external = v.indexOf("shotPath = shotPathExternalCache();");
        assertTrue(priv > 0 && internal > priv && external > internal);
        assertTrue("内部 cache 用 getCacheDir", v.contains("mContext.getCacheDir()"));
    }
}
