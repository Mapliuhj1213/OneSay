package com.onesay.assistant.v2.island;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ fast-screen-v2（真机：三星设置搜索页被判全屏后，执行模式的岛整段消失、用户没法接管 / 停止）。 */
public class IslandExecFullscreenTest {

    @Test public void execTaskStaysVisibleInFullscreen() {
        IslandModel m = new IslandModel();
        m.configure(true, true);
        m.setMode(IslandModel.Mode.ISLAND);
        m.execStarted("exec-1", "替你操作", "com.android.settings", 0);
        assertNotEquals(IslandModel.Kind.HIDDEN, m.view().kind);
        m.setFullscreen(true);
        assertNotEquals("执行模式是用户发起的活动，全屏也要可见", IslandModel.Kind.HIDDEN, m.view().kind);
    }

    @Test public void ordinaryBackgroundTaskStillHidesInFullscreen() {
        IslandModel m = new IslandModel();
        m.configure(true, true);
        m.setMode(IslandModel.Mode.ISLAND);
        m.bgStarted("bg-1", "跑腿", 0);
        m.setFullscreen(true);
        assertEquals(IslandModel.Kind.HIDDEN, m.view().kind);
    }
}
