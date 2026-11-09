package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.fastscreen.FsTree;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ stable-screen：画面稳定再读（{@link StableRead}，可注入时钟 + 假读屏）、编号失效核对（{@link StableRead#checkRef}）、
 * 稳定指纹（{@link UiTree#settleFingerprint}），以及接进 {@link PhoneAgent} 之后的行为（假界面树 + 虚拟时钟）。
 */
public class StableReadTest {

    // ------------------------------------------------------------------ 算法本身

    static final class FakeClock implements StableRead.Clock {
        long t;
        final List<Long> sleeps = new ArrayList<Long>();

        @Override public long now() {
            return t;
        }

        @Override public void sleep(long ms) {
            sleeps.add(ms);
            t += ms;
        }
    }

    /** 按顺序吐指纹（null = 读失败）；用完了一直重复最后一个。每次读屏耗 readCost 毫秒。 */
    static final class Seq implements StableRead.Source<String> {
        final List<String> v;
        final FakeClock c;
        long readCost;
        int i;

        Seq(FakeClock c, String... v) {
            this.c = c;
            this.v = Arrays.asList(v);
        }

        @Override public String read() {
            c.t += readCost;
            String s = v.get(Math.min(i, v.size() - 1));
            i++;
            return s == null ? null : s + "#" + i;   // 值带读序号，指纹只看 # 前面
        }

        @Override public String fingerprint(String value) {
            return value.substring(0, value.indexOf('#'));
        }
    }

    @Test public void constantsMatchTheSpec() {
        assertEquals(150L, StableRead.POLL_MS);
        assertEquals(1500L, StableRead.MAX_WAIT_MS);
        assertEquals(2, StableRead.MATCHES);
        StableRead.Params p = StableRead.Params.defaults();
        assertEquals(150L, p.pollMs);
        assertEquals(1500L, p.maxWaitMs);
        assertEquals(2, p.matches);
        assertTrue(p.recheckRefs);
    }

    @Test public void alreadyStableCostsOneExtraReadAndOneGap() {
        FakeClock c = new FakeClock();
        StableRead.Result<String> r = StableRead.await(c, new Seq(c, "a", "a"), StableRead.Params.defaults());
        assertTrue(r.stable);
        assertEquals(2, r.reads);
        assertEquals(150L, r.waitedMs);
        assertEquals("a#2", r.value);
        assertEquals(Arrays.asList(150L), c.sleeps);
    }

    @Test public void waitsUntilTwoConsecutiveReadsAgree() {
        FakeClock c = new FakeClock();
        StableRead.Result<String> r = StableRead.await(c, new Seq(c, "a", "b", "c", "c", "d"), StableRead.Params.defaults());
        assertTrue(r.stable);
        assertEquals(4, r.reads);
        assertEquals(450L, r.waitedMs);
        assertEquals("c#4", r.value);
    }

    @Test public void aBAPatternIsNotStable() {
        FakeClock c = new FakeClock();
        StableRead.Result<String> r = StableRead.await(c, new Seq(c, "a", "b", "a", "b", "b"), StableRead.Params.defaults());
        assertTrue(r.stable);
        assertEquals("只有相邻两次一致才算", 5, r.reads);
    }

    @Test public void neverStableTimesOutAtMaxWaitAndReturnsTheLastRead() {
        FakeClock c = new FakeClock();
        String[] all = new String[40];
        for (int i = 0; i < all.length; i++) all[i] = "f" + i;
        StableRead.Result<String> r = StableRead.await(c, new Seq(c, all), StableRead.Params.defaults());
        assertFalse(r.stable);
        assertEquals(1500L, r.waitedMs);
        assertEquals(11, r.reads);   // 第一次 + 10 次轮询
        assertEquals("f10#11", r.value);
    }

    @Test public void timeoutCountsReadCostToo() {
        FakeClock c = new FakeClock();
        String[] all = new String[40];
        for (int i = 0; i < all.length; i++) all[i] = "f" + i;
        Seq s = new Seq(c, all);
        s.readCost = 100;
        StableRead.Result<String> r = StableRead.await(c, s, StableRead.Params.defaults());
        assertFalse(r.stable);
        assertTrue("最长约 1.5 s（最后一次读屏可能超出一点）: " + r.waitedMs, r.waitedMs >= 1500 && r.waitedMs <= 1500 + 100);
        assertTrue(r.reads <= 7);
    }

    @Test public void failedReadsNeverCountAsAMatchAndKeepTheLastGoodValue() {
        FakeClock c = new FakeClock();
        StableRead.Result<String> r = StableRead.await(c, new Seq(c, "a", null, "a", "a"), StableRead.Params.defaults());
        assertTrue(r.stable);
        assertEquals(4, r.reads);
        assertEquals("a#4", r.value);
        FakeClock c2 = new FakeClock();
        StableRead.Result<String> r2 = StableRead.await(c2, new Seq(c2, "a", null), StableRead.Params.defaults());
        assertFalse(r2.stable);
        assertEquals("a#1", r2.value);
    }

    @Test public void allReadsFailingReturnsNullUnstable() {
        FakeClock c = new FakeClock();
        StableRead.Result<String> r = StableRead.await(c, new Seq(c, (String) null), StableRead.Params.defaults());
        assertFalse(r.stable);
        assertNull(r.value);
        assertEquals(1500L, r.waitedMs);
    }

    @Test public void seedCountsAsTheFirstRead() {
        FakeClock c = new FakeClock();
        Seq s = new Seq(c, "a");
        StableRead.Result<String> r = StableRead.await(c, s, "a#0", true, StableRead.Params.defaults());
        assertTrue(r.stable);
        assertEquals(1, r.reads);
        assertEquals(1, s.i);
    }

    @Test public void capShortensTheWait() {
        FakeClock c = new FakeClock();
        StableRead.Result<String> r = StableRead.await(c, new Seq(c, "a", "b", "c", "d", "e", "f"), null, false, StableRead.Params.defaults(), 300);
        assertFalse(r.stable);
        assertEquals(300L, r.waitedMs);
    }

    @Test public void paramsAreTunable() {
        StableRead.Params p = StableRead.Params.defaults();
        p.pollMs = 50;
        p.maxWaitMs = 200;
        p.matches = 3;
        FakeClock c = new FakeClock();
        StableRead.Result<String> r = StableRead.await(c, new Seq(c, "a", "a", "a"), p);
        assertTrue(r.stable);
        assertEquals(3, r.reads);
        assertEquals(100L, r.waitedMs);
        p.matches = 1;
        FakeClock c2 = new FakeClock();
        StableRead.Result<String> r1 = StableRead.await(c2, new Seq(c2, "x"), p);
        assertTrue(r1.stable);
        assertEquals(1, r1.reads);
        assertEquals(0L, r1.waitedMs);
        assertTrue(c2.sleeps.isEmpty());
    }

    // ------------------------------------------------------------------ 指纹

    static FakeScreens.B shop() {
        return FakeScreens.screen("com.shop", "x.Detail").text("商品标题").button("立即购买").button("加入购物车").button("查看详情");
    }

    /** 除根节点外所有节点下移 dy（模拟滑入动画 / 顶部插进横幅）。 */
    static FakeScreens.B shifted(FakeScreens.B b, int dy) {
        for (int i = 1; i < b.t.nodes.size(); i++) {
            FsTree.Node n = b.t.nodes.get(i);
            n.t += dy;
            n.b += dy;
        }
        return b;
    }

    @Test public void fingerprintIgnoresIdsButSeesPositionTextAndState() {
        UiTree a = shop().ui(new UiTree.Ids());
        UiTree.Ids other = new UiTree.Ids();
        other.idFor("占位1");
        other.idFor("占位2");
        UiTree b = shop().ui(other);
        assertNotEquals(a.els.get(1).id, b.els.get(1).id);
        assertEquals(a.settleFingerprint(), b.settleFingerprint());
        assertNotEquals(a.settleFingerprint(), shifted(shop(), 40).ui(new UiTree.Ids()).settleFingerprint());
        assertEquals("signature 不看位置：滑动中的画面它分不出来", a.signature(), shifted(shop(), 40).ui(new UiTree.Ids()).signature());
        assertNotEquals(a.settleFingerprint(), FakeScreens.screen("com.shop", "x.Detail").text("商品标题").button("立即购买").button("已加入")
                .ui(new UiTree.Ids()).settleFingerprint());
        assertNotEquals(FakeScreens.screen("p", "a").toggle("WLAN", false).ui(null).settleFingerprint(),
                FakeScreens.screen("p", "a").toggle("WLAN", true).ui(null).settleFingerprint());
    }

    // ------------------------------------------------------------------ 编号核对

    @Test public void refStillOnSameScreenIsValid() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree seen = shop().ui(ids), fresh = shop().ui(ids);
        assertNull(StableRead.checkRef(seen.findText("立即购买").id, seen, fresh));
    }

    @Test public void refGoneIsStale() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree seen = shop().ui(ids);
        UiTree fresh = FakeScreens.screen("com.shop", "x.Popup").text("领取优惠券").button("关闭").ui(ids);
        String m = StableRead.checkRef(seen.findText("立即购买").id, seen, fresh);
        assertNotNull(m);
        assertTrue(m, m.startsWith(StableRead.REF_STALE));
        assertTrue(m, m.contains("立即购买"));
        assertTrue(m, m.contains("已重新读屏"));
    }

    @Test public void refThatMovedButIsUniqueStaysValid() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree seen = shop().ui(ids);
        UiTree fresh = shifted(shop(), 300).ui(ids);
        int id = seen.findText("立即购买").id;
        assertEquals(id, fresh.findText("立即购买").id);
        assertNull(StableRead.checkRef(id, seen, fresh));
    }

    @Test public void refThatMovedAndHasATwinIsStale() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree seen = FakeScreens.screen("com.shop", "x.List").text("标题").button("购买").ui(ids);
        UiTree fresh = FakeScreens.screen("com.shop", "x.List").button("购买").text("标题").button("购买").ui(ids);
        int id = seen.findText("购买").id;
        String m = StableRead.checkRef(id, seen, fresh);
        assertNotNull(m);
        assertTrue(m, m.startsWith(StableRead.REF_STALE));
        assertTrue(m, m.contains("同名"));
    }

    @Test public void twinThatDidNotMoveIsValid() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree seen = FakeScreens.screen("com.shop", "x.List").button("购买").button("购买").ui(ids);
        UiTree fresh = FakeScreens.screen("com.shop", "x.List").button("购买").button("购买").text("加载更多").ui(ids);
        assertNull(StableRead.checkRef(seen.els.get(1).id, seen, fresh));
    }

    @Test public void refNotInSeenOrNoFreshReadIsLeftToValidate() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree seen = shop().ui(ids);
        assertNull(StableRead.checkRef(999, seen, shop().ui(ids)));
        assertNull(StableRead.checkRef(seen.els.get(1).id, seen, null));
        assertNull(StableRead.checkRef(-1, seen, seen));
    }

    // ------------------------------------------------------------------ 接进 PhoneAgent

    /**
     * 假手机：当前「镜头」是一串帧，每读一次屏前进一帧（用完停在最后一帧）；点某个名字 → 换一串帧（模拟转场动画）。
     * 读屏 20 ms、点按 40 ms、waitIdle 加 idleMs、sleep 加 ms（虚拟时钟）。
     */
    static final class Anim implements PhoneAgent.Driver {
        List<FakeScreens.B> frames = new ArrayList<FakeScreens.B>();
        int idx;
        final Map<String, List<FakeScreens.B>> onTap = new HashMap<String, List<FakeScreens.B>>();
        final List<String> log = new ArrayList<String>();
        long clock;
        int observes;

        Anim show(FakeScreens.B... f) {
            frames = new ArrayList<FakeScreens.B>(Arrays.asList(f));
            idx = 0;
            return this;
        }

        Anim on(String label, FakeScreens.B... f) {
            onTap.put(label, new ArrayList<FakeScreens.B>(Arrays.asList(f)));
            return this;
        }

        @Override public UiTree observe(UiTree.Ids ids) {
            clock += 20;
            observes++;
            FakeScreens.B b = frames.get(Math.min(idx, frames.size() - 1));
            idx++;
            return b.ui(ids);
        }

        @Override public boolean tap(UiTree.El e) {
            clock += 40;
            if (e == null) return false;
            log.add("tap " + e.label + " @" + e.cy());
            List<FakeScreens.B> to = onTap.get(e.label);
            if (to != null) {
                frames = to;
                idx = 0;
            }
            return true;
        }

        @Override public boolean longPress(UiTree.El e, int ms) {
            return true;
        }

        @Override public String type(UiTree.El e, String text) {
            log.add("type " + text);
            return text;
        }

        @Override public boolean scroll(UiTree.El e, int dir) {
            return false;
        }

        @Override public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
            return true;
        }

        @Override public boolean key(String key) {
            log.add("key " + key);
            return true;
        }

        @Override public boolean launch(String pkg) {
            log.add("launch " + pkg);
            List<FakeScreens.B> to = onTap.get("launch:" + pkg);
            if (to != null) {
                frames = to;
                idx = 0;
            }
            return true;
        }

        @Override public void waitIdle(int idleMs, int timeoutMs) {
            clock += idleMs;
        }

        @Override public String describeScreen() {
            return null;
        }

        @Override public long now() {
            return clock;
        }

        @Override public void sleep(long ms) {
            clock += ms;
        }
    }

    /** 脚本化 planner，第 hookAt 次调用返回前先跑 hook（模拟模型思考期间界面自己变了）。 */
    static final class Thinking implements PhoneAgent.Planner {
        final FakeScreens.Script s;
        int calls, hookAt = -1;
        Runnable hook;

        Thinking(String... outs) {
            s = new FakeScreens.Script(outs);
        }

        @Override public String next(String system, String user) {
            String o = s.next(system, user);
            calls++;
            if (calls == hookAt && hook != null) hook.run();
            return o;
        }
    }

    static PhoneAgent agent(Anim d, PhoneAgent.Planner p, boolean settle) {
        PhoneAgent a = new PhoneAgent(d, p, new FakeScreens.Host());
        if (settle) a.settle = StableRead.Params.defaults();
        return a;
    }

    static FakeScreens.B paid() {
        return FakeScreens.screen("com.shop", "x.Paid").text("下单成功").button("查看订单");
    }

    @Test public void pureQueryPathIsNotSlowedDown() {
        Anim d = new Anim().show(shop());
        PhoneAgent a = agent(d, new FakeScreens.Script("{\"done\":\"看到了\"}"), true);
        PhoneAgent.Result r = a.run("看看商品", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals("开始的那次读屏是查询，不等稳定", 1, d.observes);
        assertEquals(20L, d.clock);
        assertEquals(0, a.settleReads);
    }

    @Test public void settleOffKeepsTheOldSingleRead() {
        Anim off = new Anim().show(shop()).on("加入购物车", paid());
        PhoneAgent a = agent(off, new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:加入购物车}}]}", "{\"done\":\"好\"}"), false);
        PhoneAgent.Result r = a.run("加购", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals("开始 1 次 + 动作后 1 次", 2, off.observes);
        assertEquals(0, a.settleReads);
        assertFalse(r.trace.toString(), r.trace.toString().contains("settle"));
        Anim on = new Anim().show(shop()).on("加入购物车", paid());
        PhoneAgent b = agent(on, new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:加入购物车}}]}", "{\"done\":\"好\"}"), true);
        b.run("加购", null);
        assertEquals("开始 1 + 执行前核对编号 1 + 动作后两次一致 2", 4, on.observes);
        assertEquals(1, b.settleReads);
    }

    @Test public void afterTapTheModelSeesTheSettledScreenNotTheTransition() {
        Anim d = new Anim().show(shop()).on("立即购买",
                shifted(paid(), 900), shifted(paid(), 500), shifted(paid(), 100), paid(), paid());
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即购买}}]}", "{\"done\":\"买了\"}");
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("买", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        String second = p.users.get(1);
        assertTrue(second, second.contains("「查看订单」"));
        String settledLine = UiTree.line(paid().ui(new UiTree.Ids()).findText("查看订单"));
        assertTrue(second, second.contains(settledLine.substring(settledLine.indexOf('@'))));
        assertFalse(r.trace.toString(), r.trace.toString().contains("unstable"));
        assertEquals(0, a.settleUnstable);
        // 不开 settle：同一个脚本，模型看到的是转场第一帧（位置偏了 900）
        Anim d0 = new Anim().show(shop()).on("立即购买",
                shifted(paid(), 900), shifted(paid(), 500), shifted(paid(), 100), paid(), paid());
        FakeScreens.Script p0 = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即购买}}]}", "{\"done\":\"买了\"}");
        agent(d0, p0, false).run("买", null);
        assertFalse(p0.users.get(1), p0.users.get(1).contains(settledLine.substring(settledLine.indexOf('@'))));
    }

    @Test public void screenThatNeverSettlesIsMarkedUnstableAndCappedAtAboutOnePointFiveSeconds() {
        FakeScreens.B[] ticking = new FakeScreens.B[60];
        for (int i = 0; i < ticking.length; i++) ticking[i] = FakeScreens.screen("com.shop", "x.Live").text("倒计时 " + (60 - i) + " 秒").button("抢购");
        Anim d = new Anim().show(shop()).on("立即购买", ticking);
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即购买}}]}", "{\"done\":\"好\"}");
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("抢", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals(1, a.settleUnstable);
        assertTrue(r.trace.toString(), r.trace.toString().contains("settle unstable tap"));
        assertTrue(p.users.get(1), p.users.get(1).contains("界面仍在变化"));
        // 用的是最后一次读到的那屏
        int readsAfterTap = d.idx;
        assertTrue(p.users.get(1), p.users.get(1).contains("倒计时 " + (60 - (readsAfterTap - 1)) + " 秒"));
        // 动作后最多约 1.5 s 的等待（+ 读屏本身的耗时）
        long settleStart = 20 + 20 + 40 + 250;   // 开始读屏 + 核对读屏 + 点按 + waitIdle
        assertTrue("clock=" + d.clock, d.clock - settleStart <= 1500 + 20 * 2);
    }

    @Test public void launchingAnAppWaitsForTheFirstScreenToSettle() {
        FakeScreens.B splash = FakeScreens.screen("com.shop", "x.Splash").text("加载中");
        Anim d = new Anim().show(FakeScreens.screen("com.launcher", "Home").button("商城"))
                .on("launch:com.shop", splash, shifted(shop(), 600), shop(), shop());
        FakeScreens.Script p = new FakeScreens.Script("{\"done\":\"开了\"}");
        PhoneAgent a = agent(d, p, true);
        a.run("打开商城", "com.shop");
        assertTrue(p.users.get(0), p.users.get(0).contains("「立即购买」"));
        assertFalse(p.users.get(0), p.users.get(0).contains("加载中"));
    }

    @Test public void refThatVanishedWhileTheModelWasThinkingIsNotTappedAndTheFreshScreenGoesBack() {
        final Anim d = new Anim().show(shop());
        Thinking p = new Thinking("{\"steps\":[{\"do\":\"tap\",\"id\":{id:查看详情}}]}", "{\"done\":\"没点\"}");
        p.hookAt = 1;
        p.hook = new Runnable() {
            @Override public void run() {
                d.show(FakeScreens.screen("com.shop", "x.Popup").text("领取优惠券").button("关闭"));
            }
        };
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("买", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertTrue(d.log.toString(), d.log.isEmpty());
        assertEquals(1, a.refStale);
        String second = p.s.users.get(1);
        assertTrue(second, second.contains(StableRead.REF_STALE));
        assertTrue(second, second.contains("「领取优惠券」"));
        assertEquals(0, r.actions);
    }

    @Test public void refThatMovedWhileTheModelWasThinkingIsTappedAtItsNewPlace() {
        final Anim d = new Anim().show(shop());
        Thinking p = new Thinking("{\"steps\":[{\"do\":\"tap\",\"id\":{id:查看详情}}]}", "{\"done\":\"点了\"}");
        p.hookAt = 1;
        p.hook = new Runnable() {
            @Override public void run() {
                d.show(shifted(shop(), 300));
            }
        };
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("买", null);
        int fresh = shifted(shop(), 300).ui(null).findText("查看详情").cy();
        assertEquals(Arrays.asList("tap 查看详情 @" + fresh), d.log);
        assertEquals(1, a.refMoved);
        assertTrue(r.trace.toString(), r.trace.toString().contains("ref moved"));
        // 不核对时点的是模型看到的旧位置
        final Anim d0 = new Anim().show(shop());
        Thinking p0 = new Thinking("{\"steps\":[{\"do\":\"tap\",\"id\":{id:查看详情}}]}", "{\"done\":\"点了\"}");
        p0.hookAt = 1;
        p0.hook = new Runnable() {
            @Override public void run() {
                d0.show(shifted(shop(), 300));
            }
        };
        PhoneAgent b = agent(d0, p0, true);
        b.settle.recheckRefs = false;
        b.run("买", null);
        assertEquals(Arrays.asList("tap 查看详情 @" + shop().ui(null).findText("查看详情").cy()), d0.log);
    }

    @Test public void ambiguousTwinAfterThinkingIsNotTapped() {
        final Anim d = new Anim().show(FakeScreens.screen("com.shop", "x.List").text("标题").button("购买"));
        Thinking p = new Thinking("{\"steps\":[{\"do\":\"tap\",\"id\":{id:购买}}]}", "{\"done\":\"没点\"}");
        p.hookAt = 1;
        p.hook = new Runnable() {
            @Override public void run() {
                d.show(FakeScreens.screen("com.shop", "x.List").button("购买").text("标题").button("购买"));
            }
        };
        PhoneAgent a = agent(d, p, true);
        a.run("买", null);
        assertTrue(d.log.toString(), d.log.isEmpty());
        assertTrue(p.s.users.get(1), p.s.users.get(1).contains("同名"));
    }

    @Test public void refThatBecameAmbiguousWhileTheUserWasConfirmingIsNotTapped() {
        final Anim d = new Anim().show(FakeScreens.screen("com.shop", "x.List").text("标题").button("立即购买"));
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即购买}}]}", "{\"done\":\"没点\"}");
        final FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent.Host changing = new PhoneAgent.Host() {
            @Override public void onStep(String w, int n) {
                h.onStep(w, n);
            }

            @Override public boolean confirm(String title, String detail, String[] out) {
                d.show(FakeScreens.screen("com.shop", "x.List").button("立即购买").text("标题").button("立即购买"));
                return h.confirm(title, detail, out);
            }

            @Override public String takeoverRequested() {
                return h.takeoverRequested();
            }

            @Override public PhoneAgent.Resume awaitResume(String why, String[] sup) {
                return h.awaitResume(why, sup);
            }

            @Override public boolean stopRequested() {
                return h.stopRequested();
            }
        };
        PhoneAgent a = new PhoneAgent(d, p, changing);
        a.settle = StableRead.Params.defaults();
        PhoneAgent.Result r = a.run("买", null);
        assertEquals(1, h.confirms.size());
        assertTrue(d.log.toString(), d.log.isEmpty());
        assertEquals(1, a.refStale);
        assertTrue(p.users.get(1), p.users.get(1).contains(StableRead.REF_STALE));
        assertTrue(r.trace.toString(), r.trace.toString().contains("ref stale after confirm"));
    }

    @Test public void onlyTheFirstTargetedStepOfABatchIsRechecked() {
        Anim d = new Anim().show(shop()).on("加入购物车", shop());
        FakeScreens.Script p = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"back\"},{\"do\":\"tap\",\"id\":{id:加入购物车}},{\"do\":\"tap\",\"id\":{id:加入购物车}}]}", "{\"done\":\"好\"}");
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("加购", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals(3, r.actions);
        // 开始 1 + 三步各自动作后两次一致 2×3；第一步 back 不指名元素，后面两步用的是刚稳定读到的屏，都不再核对
        assertEquals(7, d.observes);
    }

    @Test public void conditionWaitConfirmsTheScreenSettledBeforeSayingMet() {
        Anim d = new Anim().show(shop()).on("立即购买", shifted(paid(), 600), shifted(paid(), 300), paid(), paid());
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即购买},\"expect\":\"下单成功\"}]}", "{\"done\":\"买了\"}");
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("买", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertTrue(p.users.get(1), p.users.get(1).contains("条件已满足"));
        String settledLine = UiTree.line(paid().ui(new UiTree.Ids()).findText("查看订单"));
        assertTrue(p.users.get(1), p.users.get(1).contains(settledLine.substring(settledLine.indexOf('@'))));
    }

    @Test public void conditionNotMetStillReportsNotMet() {
        Anim d = new Anim().show(shop()).on("立即购买", shop());
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即购买},\"expect\":\"下单成功\",\"timeout_s\":1}]}", "{\"done\":\"没成\"}");
        PhoneAgent a = agent(d, p, true);
        a.run("买", null);
        assertTrue(p.users.get(1), p.users.get(1).contains("没等到"));
    }
}
