package com.onesay.assistant.v2.fastscreen;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ fast-screen-v2：三星导航栏按键区域推算。 */
public class NavKeysTest {

    @Test public void defaultOrderBackOnRight() {
        int[] r = NavKeys.compute(1080, 2220, 126, 0);
        assertArrayEquals(new int[]{720, 2094, 1080, 2220, 360, 2094, 720, 2220}, r);
    }

    @Test public void swappedOrderBackOnLeft() {
        int[] r = NavKeys.compute(1080, 2220, 126, 1);
        assertEquals(0, r[0]);
        assertEquals(360, r[2]);
        assertEquals(360, r[4]);   // 主页始终在中间
        assertEquals(720, r[6]);
    }

    @Test public void noNavBarGivesEmptyRects() {
        assertArrayEquals(new int[8], NavKeys.compute(1080, 2220, 0, 0));
        assertArrayEquals(new int[8], NavKeys.compute(0, 0, 126, 0));
    }

    @Test public void probeTouchPointFallsInBackKey() {
        // 真机探针记录：返回键触摸 900,2157（默认顺序）
        int[] r = NavKeys.compute(1080, 2220, 126, 0);
        assertTrue(900 >= r[0] && 900 < r[2] && 2157 >= r[1] && 2157 < r[3]);
    }
}
