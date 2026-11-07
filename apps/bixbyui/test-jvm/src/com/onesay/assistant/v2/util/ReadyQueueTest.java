package com.onesay.assistant.v2.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * round3 #3：冷启动排队（面板服务的指令队列 / WakeService 的唤醒队列共用）——就绪前排队、就绪后按到达顺序补发、
 * 超时丢弃、满了挤掉最旧、fail-open、重置。
 */
public class ReadyQueueTest {

    @Test public void readyRunsNowWithoutQueueing() {
        ReadyQueue<String> q = new ReadyQueue<String>(10000, 4);
        assertTrue(q.markReady(0).isEmpty());
        assertEquals(ReadyQueue.Offer.RUN_NOW, q.offer("打开蓝牙", 5));
        assertEquals(0, q.size());
    }

    @Test public void queuedBeforeReadyAreReplayedInArrivalOrder() {
        ReadyQueue<String> q = new ReadyQueue<String>(10000, 4);
        assertEquals(ReadyQueue.Offer.QUEUED, q.offer("TRI-1 现在几点", 100));
        assertEquals(ReadyQueue.Offer.QUEUED, q.offer("TRI-2 天气怎么样", 900));
        assertEquals(100, q.oldestAt());
        ReadyQueue.Drain<String> d = q.markReady(1500);
        assertEquals(2, d.ready.size());
        assertEquals("TRI-1 现在几点", d.ready.get(0).value);
        assertEquals("TRI-2 天气怎么样", d.ready.get(1).value);
        assertEquals(1400, d.ready.get(0).waitedMs(1500));
        assertTrue(d.expired.isEmpty());
        assertEquals(0, q.size());
        assertEquals(ReadyQueue.Offer.RUN_NOW, q.offer("TRI-3", 1600));
        assertTrue("重复 markReady 不再吐东西", q.markReady(2000).isEmpty());
        assertEquals(1500, q.readyAt());
    }

    @Test public void staleItemsExpireInsteadOfFiringLate() {
        ReadyQueue<String> q = new ReadyQueue<String>(10000, 4);
        q.offer("很早的一句", 0);
        q.offer("刚说的一句", 9000);
        ReadyQueue.Drain<String> d = q.markReady(12000);
        assertEquals(1, d.expired.size());
        assertEquals("很早的一句", d.expired.get(0).value);
        assertEquals(1, d.ready.size());
        assertEquals("刚说的一句", d.ready.get(0).value);
    }

    @Test public void timeoutFailOpenNeverLeavesServiceDeaf() {
        ReadyQueue<String> q = new ReadyQueue<String>(10000, 4);
        q.offer("a", 0);
        q.offer("b", 5000);
        // 初始化卡住：到点检查不放行 → 只丢超时的
        ReadyQueue.Drain<String> d = q.expire(10050, false);
        assertEquals(1, d.expired.size());
        assertTrue(d.ready.isEmpty());
        assertFalse(q.isReady());
        assertEquals(1, q.size());
        // fail-open：放行闸门，剩余未超时的立即补发
        d = q.expire(12000, true);
        assertTrue(q.isReady());
        assertEquals(1, d.ready.size());
        assertEquals("b", d.ready.get(0).value);
        assertEquals(ReadyQueue.Offer.RUN_NOW, q.offer("c", 12001));
    }

    @Test public void capacityDropsOldest() {
        ReadyQueue<String> q = new ReadyQueue<String>(10000, 1);   // WakeService：只留最新一次唤醒
        assertEquals(ReadyQueue.Offer.QUEUED, q.offer("key-activity", 0));
        assertEquals(ReadyQueue.Offer.QUEUED_DROPPED_OLDEST, q.offer("key-file", 30));
        assertEquals("key-activity", q.takeDropped().value);
        assertNull(q.takeDropped());
        ReadyQueue.Drain<String> d = q.markReady(2000);
        assertEquals(1, d.ready.size());
        assertEquals("key-file", d.ready.get(0).value);
    }

    @Test public void resetGoesBackToQueueing() {
        ReadyQueue<String> q = new ReadyQueue<String>(10000, 2);
        q.markReady(0);
        q.reset();
        assertFalse(q.isReady());
        assertEquals(-1, q.readyAt());
        assertEquals(ReadyQueue.Offer.QUEUED, q.offer("服务重建期间的唤醒", 10));
        assertEquals(1, q.markReady(20).ready.size());
    }

    /** 宿主接线守卫：面板服务与 WakeService 都接了队列 + 超时 + [COLD] 日志；startService 冷启动改前台方式。 */
    @Test public void hostWiring() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String fss = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath()), Charset.forName("UTF-8"));
        String wake = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/wake/WakeService.java").toPath()), Charset.forName("UTF-8"));
        assertTrue("CRLF", fss.contains("\r\n"));
        int s = fss.indexOf("public void onSubmitCommand(final String command)");
        String head = fss.substring(s, fss.indexOf("cancelAutoHide();", s));
        assertTrue("onSubmitCommand 第一件事是冷启动闸门", head.contains("mColdQueue.offer(command, now)"));
        assertTrue(fss.contains("[COLD] 面板服务就绪"));
        assertTrue(fss.contains("mColdQueue.expire(now, true)"));
        assertTrue(fss.contains("context.startForegroundService(intent)"));
        assertFalse("show/showListening/hide 不再直接 startService", fss.contains("intent.putExtra(EXTRA_PROMPT, prompt);\r\n        context.startService(intent);"));
        int r = wake.indexOf("private synchronized void requestWakeInternal(");
        assertTrue("所有唤醒来源汇入点先查麦克风就绪", wake.substring(r, r + 400).contains("if (!sMicReady)"));
        assertTrue(wake.contains("queueColdWake(source, conversation, \"服务未创建\")"));
        assertTrue(wake.contains("onMicReady();"));
        assertTrue(wake.contains("sColdWakes.expire(SystemClock.elapsedRealtime(), false)"));
    }
}
