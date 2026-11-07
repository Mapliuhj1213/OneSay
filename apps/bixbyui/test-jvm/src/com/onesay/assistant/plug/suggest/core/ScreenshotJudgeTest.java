package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.suggest.core.scenes.shot.BurstMerger;
import com.onesay.assistant.plug.suggest.core.scenes.shot.OwnWrites;
import com.onesay.assistant.plug.suggest.core.scenes.shot.PathNorm;
import com.onesay.assistant.plug.suggest.core.scenes.shot.PrivateApps;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotCandidate;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotJudge;
import com.onesay.assistant.plug.suggest.core.scenes.shot.WriteSettle;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** §6.8 清单：Judge（路径 / 自产 / 15 s / 尺寸 / 未写完 / 私密 / 锁屏 / 未知包名）、Merger、写完判定、路径规范化。 */
public class ScreenshotJudgeTest {

    static final long NOW = SuggestFakes.T0;
    static final String P = "/storage/emulated/0/DCIM/Screenshots/Screenshot_20261102-150000_WeChat.jpg";

    static ScreenshotCandidate cand(String path, int w, int h, long addedAgoMs, char src, boolean settled) {
        return new ScreenshotCandidate(path, src == 'A' ? 42 : -1, 5000, NOW - addedAgoMs, w, h, src == 'A' ? (NOW - addedAgoMs) / 1000L : 0,
                src, 100, settled);
    }

    static ScreenshotJudge.JudgeEnv env() {
        ScreenshotJudge.JudgeEnv e = new ScreenshotJudge.JudgeEnv();
        e.screenW = 1080;
        e.screenH = 2220;
        e.pluginStartWall = NOW - 60000L;
        e.nowWall = NOW;
        e.nowElapsed = 100;
        e.own = new OwnWrites();
        e.privatePkgs = PrivateApps.defaults();
        return e;
    }

    static String judge(ScreenshotCandidate c, ScreenshotJudge.JudgeEnv e) {
        ScreenshotJudge.Verdict v = ScreenshotJudge.judge(c, e);
        return v.show ? "SHOW" : v.reason;
    }

    @Test public void pathWhitelist() {
        assertEquals("SHOW", judge(cand(P, 1080, 2220, 1000, 'A', true), env()));
        assertEquals("SHOW", judge(cand("/sdcard/Pictures/Screenshots/a.png", 1080, 2220, 1000, 'A', true), env()));
        assertEquals("SHOW", judge(cand("/sdcard/Download/截屏_1.png", 1080, 2220, 1000, 'A', true), env()));
        assertEquals("相机照片", "path", judge(cand("/sdcard/DCIM/Camera/20261102_150000.jpg", 1080, 2220, 1000, 'A', true), env()));
        assertEquals("微信保存", "path", judge(cand("/sdcard/tencent/MicroMsg/WeiXin/mmexport1.jpg", 1080, 2220, 1000, 'A', true), env()));
        assertEquals("path", judge(cand("/sdcard/Download/x.png", 1080, 2220, 1000, 'A', true), env()));
    }

    @Test public void ownWritesExcluded() {
        assertEquals("own", judge(cand("/sdcard/Pictures/OneSay/Screenshots/a.png", 1080, 2220, 1000, 'A', true), env()));
        ScreenshotJudge.JudgeEnv e = env();
        e.own.register("/sdcard/DCIM/Screenshots/Screenshot_self.png", 100);
        assertEquals("登记过的自产图（规范化后匹配）", "own", judge(cand("/storage/emulated/0/DCIM/Screenshots/Screenshot_self.png", 1080, 2220, 1000, 'A', true), e));
        e.nowElapsed = 100 + OwnWrites.TTL_MS + 1;
        assertEquals("60 s 后登记失效", "SHOW", judge(cand("/storage/emulated/0/DCIM/Screenshots/Screenshot_self.png", 1080, 2220, 1000, 'A', true), e));
    }

    @Test public void freshness15s() {
        assertEquals("SHOW", judge(cand(P, 1080, 2220, 14000, 'A', true), env()));
        assertEquals("stale", judge(cand(P, 1080, 2220, 16000, 'A', true), env()));
        ScreenshotJudge.JudgeEnv e = env();
        e.pluginStartWall = NOW;   // 插件刚启动：之前的文件（媒体重扫）不弹
        assertEquals("before_start", judge(cand(P, 1080, 2220, 5000, 'B', true), e));
        assertEquals("B 源按 mtime", "stale", judge(cand(P, 1080, 2220, 20000, 'B', true), env()));
    }

    @Test public void sizeRules() {
        assertEquals("竖屏", "SHOW", judge(cand(P, 1080, 2220, 1000, 'A', true), env()));
        assertEquals("横屏", "SHOW", judge(cand(P, 2220, 1080, 1000, 'A', true), env()));
        assertEquals("滚动长截图", "SHOW", judge(cand(P, 1080, 6400, 1000, 'A', true), env()));
        assertEquals("被缩放", "size", judge(cand(P, 720, 1480, 1000, 'A', true), env()));
        assertEquals("尺寸不明", "size_unknown", judge(cand(P, 0, 0, 1000, 'A', true), env()));
        ScreenshotJudge.Verdict v = ScreenshotJudge.judge(cand(P, 720, 1480, 1000, 'B', true), env());
        assertTrue("B 高置信可放行", v.show);
        assertEquals("size_relaxed", v.reason);
        assertTrue(ScreenshotJudge.sizeMatches(1440, 2960, 2960, 1440));
    }

    @Test public void unsettledAndDup() {
        assertEquals("unsettled", judge(cand(P, 1080, 2220, 1000, 'A', false), env()));
        ScreenshotJudge.JudgeEnv e = env();
        ScreenshotCandidate c = cand(P, 1080, 2220, 1000, 'A', true);
        e.recentKeys = new HashSet<String>(Collections.singleton(ScreenshotJudge.dedupKey(c)));
        ScreenshotCandidate b = cand("/data/media/0/DCIM/Screenshots/Screenshot_20261102-150000_WeChat.jpg", 1080, 2220, 1000, 'B', true);
        assertEquals("A、B 同一文件只认先到的", "dup", judge(b, e));
    }

    @Test public void privateAndLocked() {
        ScreenshotJudge.JudgeEnv e = env();
        e.fgPkg = "com.eg.android.AlipayGphone";
        assertEquals("private", judge(cand(P, 1080, 2220, 1000, 'A', true), e));
        e.fgPkg = PrivateApps.WECHAT;
        assertEquals("D3：微信不在默认名单", "SHOW", judge(cand(P, 1080, 2220, 1000, 'A', true), e));
        e.fgPkg = null;
        assertEquals("包名未知照常弹", "SHOW", judge(cand(P, 1080, 2220, 1000, 'A', true), e));
        e.locked = true;
        assertEquals("locked", judge(cand(P, 1080, 2220, 1000, 'A', true), e));
        assertTrue(PrivateApps.defaults().contains("com.samsung.knox.securefolder"));
    }

    @Test public void pathNorm() {
        assertEquals("/sdcard/DCIM/Screenshots/a.png", PathNorm.norm("/storage/emulated/0/DCIM/Screenshots/a.png"));
        assertEquals("/sdcard/DCIM/Screenshots/a.png", PathNorm.norm("/data/media/0/DCIM//Screenshots/a.png"));
        assertEquals("Screenshots", PathNorm.parentName("/mnt/sdcard/DCIM/Screenshots/a.png"));
    }

    @Test public void burstMerger() {
        BurstMerger m = new BurstMerger();
        BurstMerger.MergeResult r1 = m.add("/sdcard/DCIM/Screenshots/1.png", 0);
        assertEquals(BurstMerger.NEW, r1.kind);
        BurstMerger.MergeResult r2 = m.add("/sdcard/DCIM/Screenshots/2.png", 1500);
        assertEquals(BurstMerger.MERGED, r2.kind);
        assertEquals(2, r2.count);
        assertEquals(r1.burstId, r2.burstId);
        BurstMerger.MergeResult r = null;
        for (int i = 3; i <= 5; i++) r = m.add("/sdcard/DCIM/Screenshots/" + i + ".png", 1500 + i * 1000);
        assertEquals(5, r.count);
        assertEquals("最多带 4 张", 4, r.paths.size());
        assertEquals("/sdcard/DCIM/Screenshots/5.png", r.latest());
        BurstMerger.MergeResult same = m.add("/storage/emulated/0/DCIM/Screenshots/5.png", 7000);
        assertEquals("同一文件重复上报只算一张", 5, same.count);
        BurstMerger.MergeResult n = m.add("/sdcard/DCIM/Screenshots/6.png", 7000 + 3001);
        assertEquals("跨窗口 = 新一组", BurstMerger.NEW, n.kind);
        assertEquals(1, n.count);
        assertTrue(n.burstId > r1.burstId);
    }

    @Test public void writeSettle() {
        final SuggestFakes.FakeClock clock = new SuggestFakes.FakeClock();
        SuggestFakes.FakeSched sched = new SuggestFakes.FakeSched(clock);
        final SuggestFakes.FakeFiles files = new SuggestFakes.FakeFiles();
        final long[] got = {-2};
        final String[] why = {null};
        WriteSettle.Callback cb = new WriteSettle.Callback() {
            @Override public void settled(long size) { got[0] = size; }
            @Override public void gaveUp(String reason) { why[0] = reason; }
        };
        files.files.put("/a", 100L);
        WriteSettle.await("/a", files, sched, cb);
        files.files.put("/a", 900L);   // 还在写
        sched.advance(250);
        assertEquals(-2, got[0]);
        sched.advance(250);
        assertEquals("250 ms 不变 = 写完", 900L, got[0]);
        files.files.put("/b", 0L);
        WriteSettle.await("/b", files, sched, cb);
        sched.advance(3500);
        assertEquals("一直是空文件 → 3 s 放弃", "empty", why[0]);
        files.files.put("/c", 10L);
        WriteSettle.await("/c", files, sched, cb);
        files.files.remove("/c");
        sched.advance(250);
        assertEquals("gone", why[0]);
        assertNull(null);
        assertFalse(ScreenshotJudge.isOwnPath(P));
    }
}
