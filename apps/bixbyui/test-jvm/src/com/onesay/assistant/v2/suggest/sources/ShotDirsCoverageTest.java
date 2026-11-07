package com.onesay.assistant.v2.suggest.sources;

import com.onesay.assistant.plug.suggest.core.scenes.shot.PathNorm;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotJudge;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ fix/shot-1104 (d)：三星 Note8 截图存 /storage/emulated/0/DCIM/Screenshots/Screenshot_&lt;日期-时间&gt;_&lt;App 名&gt;.jpg（文件名带空格，
 * 2026-11-04 真机实测），A 源（MediaStore 全表 + 路径判定）和 B 源（FileObserver / root inotifyd 的目录表）都要覆盖。
 */
public class ShotDirsCoverageTest {
    static final String SAMSUNG = "Screenshot_20261104-230320_Samsung Experience Home.jpg";

    @Test public void samsungDcimScreenshotsCoveredByAAndB() {
        assertTrue("A 源路径判定（MediaStore _data）", ScreenshotJudge.pathLooksLikeScreenshot("/storage/emulated/0/DCIM/Screenshots/" + SAMSUNG));
        assertTrue("root 源的 lower fs 路径", ScreenshotJudge.pathLooksLikeScreenshot("/data/media/0/DCIM/Screenshots/" + SAMSUNG));
        assertTrue(ScreenshotJudge.pathLooksLikeScreenshot("/sdcard/Pictures/Screenshots/a.png"));
        assertEquals("A、B 两路同一文件规范化成同一个键（去重）", PathNorm.norm("/data/media/0/DCIM/Screenshots/" + SAMSUNG),
                PathNorm.norm("/storage/emulated/0/DCIM/Screenshots/" + SAMSUNG));
        assertEquals("B 源（FileObserver）两个目录都盯，三星的在前", "DCIM/Screenshots", FileObserverScreenshotSource.REL_DIRS[0]);
        assertEquals("Pictures/Screenshots", FileObserverScreenshotSource.REL_DIRS[1]);
        boolean dcim = false;
        for (String[] set : RootInotifyScreenshotSource.DIR_SETS) for (String d : set) dcim |= d.equals("/data/media/0/DCIM/Screenshots");
        assertTrue("root 源也盯 DCIM/Screenshots", dcim);
    }
}
