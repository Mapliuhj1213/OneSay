package com.onesay.assistant.v2.island;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * ★ meizu-1118：Android 12+ 的「不可信遮挡」规则会丢掉穿过不透明全宽岛窗的点按（用户手指和无障碍手势都算）→
 * API 31 起不用 R11 固定全宽窗，退回包住岛；且不挂 touchable region（否则包住岛的窗口自己也点不动）。
 */
public class Meizu1118OcclusionTest {

    @Test public void fixedWindowOnlyBeforeAndroid12() {
        for (int sdk = 24; sdk <= 30; sdk++) assertTrue("sdk " + sdk, IslandGeometry.fixedWindowAllowed(sdk));
        for (int sdk = 31; sdk <= 36; sdk++) assertFalse("sdk " + sdk, IslandGeometry.fixedWindowAllowed(sdk));
    }

    @Test public void islandViewGatesTouchRegionOnSdk() throws Exception {
        File f = new File("app/src/com/onesay/assistant/v2/island/IslandView.java");
        if (!f.exists()) f = new File("apps/bixbyui/" + f.getPath());
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("mTouch = IslandGeometry.fixedWindowAllowed(android.os.Build.VERSION.SDK_INT) ? TouchRegion.create(this) : null;"));
        assertTrue(s.contains("mFixedWin = mTouch != null;"));
    }
}
