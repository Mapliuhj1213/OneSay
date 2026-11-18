package com.onesay.assistant.v2.s3;

import static org.junit.Assert.*;
import com.onesay.assistant.v2.ui.chat.ChatWindowPolicy;
import org.junit.Test;

/** ★ fix-1118：控制台自动任务「删除」二次点击 + 输入框占位不截字。 */
public class TaskDeleteTest {
    @Test public void firstTapOnlyArms() {
        assertFalse(TaskDelete.secondTap(0L, 1000L));
    }
    @Test public void secondTapWithinWindowDeletes() {
        assertTrue(TaskDelete.secondTap(1000L, 1000L + 2999L));
        assertTrue(TaskDelete.secondTap(1000L, 1000L + TaskDelete.WINDOW_MS));
    }
    @Test public void lateOrClockBackDoesNotDelete() {
        assertFalse(TaskDelete.secondTap(1000L, 1000L + TaskDelete.WINDOW_MS + 1));
        assertFalse(TaskDelete.secondTap(5000L, 4000L));
    }
    @Test public void idleHintShort() {
        assertEquals("尽管问…", ChatWindowPolicy.HINT_IDLE);
        assertTrue(ChatWindowPolicy.HINT_IDLE.length() <= 5);
    }
}
