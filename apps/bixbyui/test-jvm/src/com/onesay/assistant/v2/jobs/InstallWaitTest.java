package com.onesay.assistant.v2.jobs;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

/** ★ install-wait（PLAN-install-wait-resume）：状态机 / 包名过滤 / REPLACING / 超时过期 / 重建恢复 / 多任务 / 定时任务列表与删除。 */
public class InstallWaitTest {
    static final long T0 = 1_800_000_000_000L;
    static final List<String> P = Arrays.asList("com.sankuai.meituan", "com.sdu.didi.psnger", "com.autonavi.minimap", "a.b", "c.d");
    static final List<String> L = Arrays.asList("美团", "滴滴", "高德", "x", "y");

    @Test public void registerKeepsAtMostFourCandidatesAndTwoHourDeadline() {
        InstallWait w = new InstallWait();
        InstallWait.Job j = w.register("叫个车去机场", "叫车", "已选 美团", P, L, T0);
        assertEquals(4, j.pkgs.size());
        assertEquals(InstallWait.WAITING, j.state);
        assertEquals(T0 + 2 * 3600_000L, j.deadline);
        assertEquals("等你装好 美团 · 点我回来", j.waitingText());
        assertNull(w.register("x", "x", "", Arrays.<String>asList(), Arrays.<String>asList(), T0));
    }

    @Test public void packageAddedFiltersAndIgnoresReplacing() {
        InstallWait w = new InstallWait();
        InstallWait.Job j = w.register("叫个车去机场", "叫车", "", P, L, T0);
        assertTrue(w.onPackageAdded("com.other", false, T0 + 1000).isEmpty());
        assertTrue(w.onPackageAdded("com.sdu.didi.psnger", true, T0 + 1000).isEmpty());
        assertEquals(InstallWait.WAITING, j.state);
        List<InstallWait.Job> hit = w.onPackageAdded("com.sdu.didi.psnger", false, T0 + 1000);
        assertEquals(1, hit.size());
        assertEquals(InstallWait.RESUMING, j.state);
        assertEquals("滴滴装好了，接着帮你叫车", j.resumeText());
        assertTrue(j.resumeQuery().startsWith("叫个车去机场（滴滴刚装好、首次启动"));
        assertTrue(j.resumeQuery().contains("付款停下"));
        w.done(j.id);
        w.prune();
        assertTrue(w.active().isEmpty());
    }

    @Test public void cancelDuringGrace() {
        InstallWait w = new InstallWait();
        InstallWait.Job j = w.register("叫车", "叫车", "", P, L, T0);
        w.onPackageAdded("com.sankuai.meituan", false, T0 + 5);
        assertTrue(w.cancel(j.id));
        assertEquals(InstallWait.CANCELLED, j.state);
    }

    @Test public void remindOnceAt15MinThenExpireAt2h() {
        InstallWait w = new InstallWait();
        InstallWait.Job j = w.register("叫车", "叫车", "", P, L, T0);
        assertEquals(InstallWait.Tick.NONE, w.tick(j, T0 + 14 * 60_000L));
        assertEquals(InstallWait.Tick.REMIND, w.tick(j, T0 + 15 * 60_000L));
        assertEquals(InstallWait.Tick.NONE, w.tick(j, T0 + 16 * 60_000L));
        assertEquals(InstallWait.Tick.EXPIRE, w.tick(j, T0 + 2 * 3600_000L));
        assertEquals(InstallWait.EXPIRED, j.state);
        assertTrue(w.onPackageAdded("com.sankuai.meituan", false, T0 + 2 * 3600_000L + 1).isEmpty());
    }

    @Test public void lateInstallAfterDeadlineIgnored() {
        InstallWait w = new InstallWait();
        w.register("叫车", "叫车", "", P, L, T0);
        assertTrue(w.onPackageAdded("com.sankuai.meituan", false, T0 + 3 * 3600_000L).isEmpty());
    }

    @Test public void rebuildRestoresAndPollsInstalled() {
        InstallWait w = new InstallWait();
        InstallWait.Job j = w.register("叫个车去机场", "叫车", "已选 美团", P, L, T0);
        w.tick(j, T0 + 15 * 60_000L);
        InstallWait w2 = InstallWait.fromJson(w.toJson());
        assertEquals(1, w2.active().size());
        InstallWait.Job r = w2.active().get(0);
        assertEquals(j.id, r.id);
        assertEquals(j.deadline, r.deadline);
        assertTrue(r.reminded);
        assertEquals("已选 美团", r.checkpoint);
        List<InstallWait.Job> hit = w2.poll(new InstallWait.Installed() {
            @Override public boolean isInstalled(String p) { return "com.autonavi.minimap".equals(p); }
        }, T0 + 20 * 60_000L);
        assertEquals(1, hit.size());
        assertEquals("com.autonavi.minimap", hit.get(0).installedPkg);
        // RESUMING 中被杀 → 重建后回到 WAITING，再查一次
        InstallWait w3 = InstallWait.fromJson(w2.toJson());
        assertEquals(1, w3.active().size());
    }

    @Test public void multipleJobsMatchIndependently() {
        InstallWait w = new InstallWait();
        InstallWait.Job a = w.register("叫车", "叫车", "", Arrays.asList("com.sdu.didi.psnger"), Arrays.asList("滴滴"), T0);
        InstallWait.Job b = w.register("点外卖", "点外卖", "", Arrays.asList("com.sankuai.meituan", "me.ele"), Arrays.asList("美团", "饿了么"), T0);
        List<InstallWait.Job> hit = w.onPackageAdded("me.ele", false, T0 + 1);
        assertEquals(1, hit.size());
        assertSame(b, hit.get(0));
        assertTrue(a.waiting());
    }

    @Test public void listedAndDeletableInScheduledTasks() throws Exception {
        InstallWait w = new InstallWait();
        InstallWait.setLive(w);
        try {
            w.register("叫个车去机场", "叫车", "", P, L, T0);
            com.onesay.assistant.v2.s3.S3Engine e = new com.onesay.assistant.v2.s3.S3Engine(new com.onesay.assistant.v2.s3.S3Store.Mem(), TimeZone.getTimeZone("Asia/Shanghai"));
            String list = e.listText(T0 + 60_000L);
            assertTrue(list, list.contains("等装好 美团"));
            assertTrue(list, list.contains("还剩 119 分钟"));
            String q = "删掉等装美团的那个任务";
            assertTrue(InstallWait.CANCEL_Q.matcher(q).find());
            com.onesay.assistant.v2.s3.S3Engine.Result r = e.handle(q, T0, new com.onesay.assistant.v2.s3.S3Engine.Flags(true, true));
            assertNotNull(r);
            assertTrue(r.reply.contains("已删掉等装美团"));
            // claims → route 同一句再调一次不落空
            assertNotNull(e.handle(q, T0, new com.onesay.assistant.v2.s3.S3Engine.Flags(true, true)));
            assertTrue(w.active().isEmpty());
            assertFalse(e.listText(T0).contains("等装"));
        } finally {
            InstallWait.setLive(new InstallWait());
        }
    }
}
