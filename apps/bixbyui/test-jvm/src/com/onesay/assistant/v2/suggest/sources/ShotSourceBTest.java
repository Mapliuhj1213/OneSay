package com.onesay.assistant.v2.suggest.sources;

import com.onesay.assistant.plug.suggest.core.SuggestFakes;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ShotEventGate;
import com.onesay.assistant.plug.suggest.core.scenes.shot.WriteSettle;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * ★ fix/shot-1104 (c)：B 源——认「新建 n」+ 150 ms × 2 次大小不变且 &gt; 0 再处理；su 被 Magisk 拒时不再连试 5 次；
 * 默认 B 源换成免 root FileObserver。
 */
public class ShotSourceBTest {
    static final String SAMSUNG = "Screenshot_20261104-230320_Samsung Experience Home.jpg";

    private static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    @Test public void inotifydCreateEventAccepted() {
        String[] r = InotifydLine.parse("n\t/data/media/0/DCIM/Screenshots\t" + SAMSUNG);
        assertNotNull("三星只报 n（新建）", r);
        assertEquals("n", r[0]);
        assertEquals("/data/media/0/DCIM/Screenshots/" + SAMSUNG, r[1]);
        assertNotNull(InotifydLine.parse("w\t/sdcard/DCIM/Screenshots\tx.png"));
        assertNotNull(InotifydLine.parse("y\t/sdcard/DCIM/Screenshots\tx.png"));
        assertNull("删除不认", InotifydLine.parse("d\t/sdcard/DCIM/Screenshots\tx.png"));
        assertNull("修改不认", InotifydLine.parse("c\t/sdcard/DCIM/Screenshots\tx.png"));
        assertEquals("inotifyd - /d:nwy", RootInotifyScreenshotSource.cmd(new String[]{"/d"}));
    }

    @Test public void fastSettleNeedsTwoStableReads() {
        SuggestFakes.FakeClock clock = new SuggestFakes.FakeClock();
        SuggestFakes.FakeSched sched = new SuggestFakes.FakeSched(clock);
        SuggestFakes.FakeFiles files = new SuggestFakes.FakeFiles();
        final long[] got = {-2};
        final String[] why = {null};
        WriteSettle.Callback cb = new WriteSettle.Callback() {
            @Override public void settled(long size) { got[0] = size; }
            @Override public void gaveUp(String reason) { why[0] = reason; }
        };
        files.files.put("/a", 0L);   // n 事件到的时候文件还是空的
        WriteSettle.awaitFast("/a", files, sched, cb);
        files.files.put("/a", 120000L);
        sched.advance(150);
        files.files.put("/a", 333683L);
        sched.advance(150);
        assertEquals(-2, got[0]);
        sched.advance(150);
        assertEquals("只稳定 1 次还不够", -2, got[0]);
        sched.advance(150);
        assertEquals("连续 2 次（150 ms 间隔）不变且 > 0 = 写完", 333683L, got[0]);
        files.files.put("/e", 0L);
        WriteSettle.awaitFast("/e", files, sched, cb);
        sched.advance(3300);
        assertEquals("一直是 0 字节不算写完", "empty", why[0]);
        assertEquals(150L, WriteSettle.B_STEP_MS);
        assertEquals(2, WriteSettle.B_STABLE);
    }

    @Test public void slowSettleUnchangedForMediaStore() {
        SuggestFakes.FakeClock clock = new SuggestFakes.FakeClock();
        SuggestFakes.FakeSched sched = new SuggestFakes.FakeSched(clock);
        SuggestFakes.FakeFiles files = new SuggestFakes.FakeFiles();
        final long[] got = {-2};
        files.files.put("/m", 10L);
        WriteSettle.await("/m", files, sched, new WriteSettle.Callback() {
            @Override public void settled(long size) { got[0] = size; }
            @Override public void gaveUp(String reason) { }
        });
        sched.advance(250);
        assertEquals("A 源仍是 250 ms × 1", 10L, got[0]);
    }

    @Test public void gateMergesEventsOfSameFile() {
        ShotEventGate g = new ShotEventGate();
        String p = "/data/media/0/DCIM/Screenshots/" + SAMSUNG;
        assertTrue(g.begin(p, 1000));
        assertFalse("n 之后又来 w：同一文件正在等，不再起", g.begin("/storage/emulated/0/DCIM/Screenshots/" + SAMSUNG, 1100));
        g.end(p, 1400, true);
        assertFalse("刚报过（5 s 内）：滚动截图改写不重复报", g.begin("/sdcard/DCIM/Screenshots/" + SAMSUNG, 2000));
        assertTrue("5 s 后同名新文件照常", g.begin(p, 1400 + ShotEventGate.HOLD_MS));
        g.end(p, 7000, false);
        assertTrue("没报成（放弃）不压后续事件", g.begin(p, 7001));
        assertTrue("不同文件互不影响", g.begin("/sdcard/DCIM/Screenshots/b.jpg", 7002));
        assertEquals(2, g.inflight());
    }

    @Test public void suRejectedFastStopsRetrying() throws Exception {
        assertTrue("第一次就几秒内退出、一行没有 = su 被拒", RootInotifyScreenshotSource.suRejectedFast(0, 120, 0));
        assertFalse("跑过一阵的不算", RootInotifyScreenshotSource.suRejectedFast(0, 60000, 0));
        assertFalse("有输出的不算", RootInotifyScreenshotSource.suRejectedFast(0, 100, 3));
        assertFalse("只看第一次", RootInotifyScreenshotSource.suRejectedFast(2, 100, 0));
        String host = src("app/src/com/onesay/assistant/v2/suggest/SuggestHost.java");
        assertTrue("root inotifyd 默认关（App uid 的 su 被 Magisk 拒）", host.contains("ROOT_INOTIFY_DEFAULT = false"));
        assertTrue("默认 B 源 = 免 root FileObserver", host.contains("d.sources.add(sFo)"));
        String fo = src("app/src/com/onesay/assistant/v2/suggest/sources/FileObserverScreenshotSource.java");
        assertTrue("认 CREATE（这台机只报新建）", fo.contains("FileObserver.CREATE"));
        assertTrue(fo.contains("WriteSettle.awaitFast("));
        assertTrue(fo.contains("mGate.begin("));
        String rt = src("app/src/com/onesay/assistant/v2/suggest/sources/RootInotifyScreenshotSource.java");
        assertTrue(rt.contains("WriteSettle.awaitFast("));
    }
}
