package com.onesay.assistant.v2.kernel;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/** 审计 R-2：8 个旧 *Settings 的 kill 文件判断统一委托 FeatureSwitch（两个位置都认，分项也认）。 */
public class KillSwitchUnifyTest {

    static final String[] SETTINGS = {
            "s1/S1Settings.java", "s2/S2Settings.java", "s3/S3Settings.java", "diag/DiagSettings.java",
            "summary/SummarySettings.java", "imgsave/ImageSaveSettings.java", "island/IslandSettings.java",
            "vision/screenref/ScreenRefSettings.java"};

    @Test public void pureKillFileCheckBothLocations() throws Exception {
        File ext = Files.createTempDirectory("ext").toFile();
        File tmp = Files.createTempDirectory("tmp").toFile();
        assertFalse(FeatureSwitch.killFilePresent(ext, tmp.getPath(), "capsule.off"));
        assertTrue(new File(tmp, "bixby_capsule.off").createNewFile());
        assertTrue("S1 分项现在也认 /data/local/tmp/bixby_<名>", FeatureSwitch.killFilePresent(ext, tmp.getPath(), "capsule.off"));
        assertFalse("文件名要带 bixby_ 前缀才算", FeatureSwitch.killFilePresent(ext, tmp.getPath(), "pickup.off"));
        assertTrue(new File(ext, "pickup.off").createNewFile());
        assertTrue(FeatureSwitch.killFilePresent(ext, tmp.getPath(), "pickup.off"));
        assertTrue(new File(ext, "pickup.off").delete());
        assertFalse("删掉即恢复", FeatureSwitch.killFilePresent(ext, tmp.getPath(), "pickup.off"));
    }

    @Test public void nullAndEmptyAreSafe() {
        assertFalse(FeatureSwitch.killFilePresent((File) null, null, "s1.off"));
        assertFalse(FeatureSwitch.killFilePresent((File) null, "/nonexistent-dir", ""));
        assertFalse(FeatureSwitch.killFilePresent((File) null, "/nonexistent-dir", null));
    }

    @Test public void allLegacySettingsDelegate() {
        for (String f : SETTINGS) {
            String s = KernelWiring.src("app/src/com/onesay/assistant/v2/" + f);
            assertTrue(f, s.contains("FeatureSwitch.killFilePresent(ctx, "));
            assertFalse(f + " 仍自己拼 kill 文件路径", s.contains("getExternalFilesDir"));
            assertFalse(f + " 仍自己查 /data/local/tmp", s.contains("new File(\"/data/local/tmp"));
        }
    }

    /** kill 文件名（对外口径）一个都没改。 */
    @Test public void killFileNamesUnchanged() {
        String[][] expect = {
                {"s1/S1Settings.java", "\"s1.off\"", "\"capsule.off\"", "\"pickup.off\"", "\"circle.off\"", "\"clipchip.off\""},
                {"s2/S2Settings.java", "\"s2.off\"", "\"memory.off\"", "\"habit.off\"", "\"ledger.off\"", "\"memory.implicit.off\""},
                {"s3/S3Settings.java", "\"s3.off\"", "\"todo.off\""},
                {"diag/DiagSettings.java", "\"diag.off\""},
                {"summary/SummarySettings.java", "\"summary.off\""},
                {"imgsave/ImageSaveSettings.java", "\"imgsave.off\"", "\"imgsave.cache.off\""},
                {"island/IslandSettings.java", "\"island.off\""},
                {"vision/screenref/ScreenRefSettings.java", "\"screenref.off\""}};
        for (String[] e : expect) {
            String s = KernelWiring.src("app/src/com/onesay/assistant/v2/" + e[0]);
            for (int i = 1; i < e.length; i++) assertTrue(e[0] + " " + e[i], s.contains(e[i]));
        }
    }
}
