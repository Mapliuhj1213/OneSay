package com.onesay.assistant.v2.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.onesay.assistant.plug.action.core.ActionChain;
import com.onesay.assistant.plug.screen.core.ScreenReader;
import com.onesay.assistant.plug.screen.core.ScreenSnapshot;
import com.onesay.assistant.plug.screen.core.UiElement;
import com.onesay.assistant.plug.screen.core.UiNode;
import com.onesay.assistant.plug.screen.core.UiWindow;
import com.onesay.assistant.v2.a11y.RebindWait;
import com.onesay.assistant.v2.agent.Outcome;
import com.onesay.assistant.v2.agent.PhoneFailure;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.fastscreen.FsProtocol;
import com.onesay.assistant.v2.fastscreen.FsTree;

/** ★ plan-1118（真机 Meizu 21 Pro / Flyme 12.7 / SDK 36 无障碍兜底）：计算器点不动、重绑间隙读屏失败、卡住误标用户停止、Shizuku 助手 NOT_PINNED。 */
public class Plan1118A11yRobustTest {

    // ---------- ② 计算器：按键收进元素表（resource-id）

    static UiNode node(String cls, String rid, int l, int t, int r, int b) {
        UiNode n = new UiNode();
        n.cls = cls;
        n.rid = rid;
        n.l = l;
        n.t = t;
        n.r = r;
        n.b = b;
        return n;
    }

    static ScreenSnapshot read(UiNode root) {
        List<UiWindow> ws = new ArrayList<UiWindow>();
        ws.add(new UiWindow(1, UiWindow.APPLICATION, 1, "com.meizu.flyme.calculator", root));
        return ScreenReader.read("t", ws, 1080, 2400, new ScreenReader.Options());
    }

    static UiElement byRid(ScreenSnapshot s, String rid) {
        for (UiElement e : s.elements) if (rid.equals(e.resourceId)) return e;
        return null;
    }

    @Test public void unlabeledNonClickableKeysAreListedWithResourceId() {
        UiNode root = node("android.widget.FrameLayout", "", 0, 0, 1080, 2400);
        UiNode display = node("android.widget.TextView", "com.meizu.flyme.calculator:id/formula", 0, 300, 1080, 700);
        display.text = "0";
        root.add(display);
        UiNode pad = node("android.view.ViewGroup", "com.meizu.flyme.calculator:id/pad", 0, 1200, 1080, 2400);
        root.add(pad);
        pad.add(node("android.view.View", "com.meizu.flyme.calculator:id/digit_1", 0, 1200, 270, 1500));
        pad.add(node("android.view.View", "com.meizu.flyme.calculator:id/op_mul", 810, 1200, 1080, 1500));
        pad.add(node("android.view.View", "com.meizu.flyme.calculator:id/eq", 810, 2100, 1080, 2400));
        pad.add(node("android.widget.ImageView", "com.meizu.flyme.calculator:id/bg_icon", 270, 1200, 540, 1500));   // 装饰
        pad.add(node("android.view.View", "", 540, 1200, 810, 1500));   // 无 id 的 View：认不出，不收
        ScreenSnapshot s = read(root);
        assertNotNull("digit_1 收进来了", byRid(s, "digit_1"));
        assertNotNull(byRid(s, "op_mul"));
        assertNotNull("短名 eq 也认", byRid(s, "eq"));
        assertNull("装饰图标不收", byRid(s, "bg_icon"));
        assertEquals("名字是空的（让模型看 resourceId）", "", byRid(s, "digit_1").name);
        assertEquals(135, byRid(s, "digit_1").centerX);
    }

    @Test public void keysUnderClickableParentNotDuplicated() {
        UiNode root = node("android.widget.FrameLayout", "", 0, 0, 1080, 2400);
        UiNode btn = node("android.widget.FrameLayout", "com.x:id/key_one", 0, 1200, 270, 1500);
        btn.clickable = true;
        root.add(btn);
        btn.add(node("android.view.View", "com.x:id/digit_1", 20, 1220, 250, 1480));
        ScreenSnapshot s = read(root);
        assertNotNull("可点的父节点本身是元素", byRid(s, "key_one"));
        assertNull("子节点不重复收", byRid(s, "digit_1"));
    }

    @Test public void readableShortIds() {
        assertEquals("eq", ScreenReader.readableResourceId("com.x:id/eq"));
        assertEquals("del", ScreenReader.readableResourceId("com.x:id/del"));
        assertNull("混淆短名不给", ScreenReader.readableResourceId("com.x:id/a1"));
        assertEquals("digit_1", ScreenReader.readableResourceId("com.x:id/digit_1"));
    }

    // ---------- ② 点了没反应 → 坐标手势补点

    static FsTree tree(String display) {
        FsTree t = new FsTree();
        t.pkg = "com.meizu.flyme.calculator";
        FsTree.Node n = new FsTree.Node();
        n.cls = "android.widget.TextView";
        n.text = display;
        n.l = 0;
        n.t = 300;
        n.r = 1080;
        n.b = 700;
        n.flags = FsTree.F_VISIBLE | FsTree.F_ENABLED;
        t.nodes.add(n);
        return t;
    }

    @Test public void retryOnlyWhenA11yClickLeftScreenUnchanged() {
        long a = TapVerify.sig(tree("0"));
        long same = TapVerify.sig(tree("0"));
        long changed = TapVerify.sig(tree("1"));
        assertEquals(a, same);
        assertFalse(a == changed);
        assertTrue(TapVerify.needGestureRetry(ActionChain.A11Y, a, same));
        assertFalse("界面变了不补", TapVerify.needGestureRetry(ActionChain.A11Y, a, changed));
        assertFalse("已经是手势就不再补", TapVerify.needGestureRetry(ActionChain.GESTURE, a, same));
        assertFalse("读不到树不补（宁可不点两下）", TapVerify.needGestureRetry(ActionChain.A11Y, 0L, same));
        assertFalse(TapVerify.needGestureRetry(ActionChain.A11Y, a, 0L));
        assertEquals(0L, TapVerify.sig(null));
    }

    @Test public void signatureIgnoresTimingButSeesSelection() {
        FsTree x = tree("0"), y = tree("0");
        y.ms = 999;
        assertEquals(TapVerify.sig(x), TapVerify.sig(y));
        y.nodes.get(0).flags |= FsTree.F_SELECTED;
        assertFalse(TapVerify.sig(x) == TapVerify.sig(y));
    }

    @Test public void holeCoversElementAndStaysOnScreen() {
        int[] h = TapVerify.holeFor(0, 1200, 270, 1500, 1080, 2400, 8);
        assertEquals(0, h[0]);
        assertEquals(1192, h[1]);
        assertEquals(278, h[2]);
        assertEquals(1508, h[3]);
        int[] small = TapVerify.holeFor(500, 500, 510, 510, 1080, 2400, 0);
        assertTrue("至少 64×64", small[2] - small[0] >= 64 && small[3] - small[1] >= 64);
    }

    // ---------- ③ 重绑间隙：等新实例 / 做到一半实例换了就重做一次

    static final class FakeClock implements RebindWait.Clock {
        long t;
        final List<Runnable> at = new ArrayList<Runnable>();
        final List<Long> when = new ArrayList<Long>();

        void schedule(long ms, Runnable r) {
            when.add(ms);
            at.add(r);
        }

        @Override public long now() { return t; }

        @Override public void sleep(long ms) {
            t += ms;
            for (int i = 0; i < when.size(); i++) {
                if (when.get(i) >= 0 && t >= when.get(i)) {
                    when.set(i, -1L);
                    at.get(i).run();
                }
            }
        }
    }

    static final class Holder implements RebindWait.Source<Object> {
        Object cur;

        @Override public Object current() { return cur; }
    }

    @Test public void awaitsAcrossRebindGap() {
        final Holder h = new Holder();
        final Object fresh = new Object();
        FakeClock c = new FakeClock();
        c.schedule(1100, new Runnable() { @Override public void run() { h.cur = fresh; } });   // 真机：约 1.1 s 后重新连上
        assertSame(fresh, RebindWait.await(h, RebindWait.MAX_WAIT_MS, c));
        assertTrue(c.t >= 1100 && c.t < 1300);
    }

    @Test public void givesUpAfterMaxWait() {
        Holder h = new Holder();
        FakeClock c = new FakeClock();
        assertNull(RebindWait.await(h, RebindWait.MAX_WAIT_MS, c));
        assertTrue(c.t >= RebindWait.MAX_WAIT_MS && c.t < RebindWait.MAX_WAIT_MS + 100);
    }

    @Test public void retriesOnNewInstanceWhenOldOneDroppedMidRead() {
        final Holder h = new Holder();
        final Object old = new Object(), fresh = new Object();
        h.cur = old;
        final FakeClock c = new FakeClock();
        final List<Object> ran = new ArrayList<Object>();
        String r = RebindWait.call(h, new RebindWait.Op<Object, String>() {
            @Override public String run(Object inst) {
                ran.add(inst);
                if (inst == old) {   // 读到一半被解绑
                    h.cur = null;
                    c.schedule(c.t + 1000, new Runnable() { @Override public void run() { h.cur = fresh; } });
                    return null;
                }
                return "tree";
            }
        }, RebindWait.MAX_WAIT_MS, c);
        assertEquals("tree", r);
        assertEquals(2, ran.size());
        assertSame(fresh, ran.get(1));
    }

    @Test public void genuineFailureOnStableInstanceIsNotRetried() {
        Holder h = new Holder();
        h.cur = new Object();
        FakeClock c = new FakeClock();
        final int[] n = {0};
        Object r = RebindWait.call(h, new RebindWait.Op<Object, Object>() {
            @Override public Object run(Object inst) {
                n[0]++;
                return null;
            }
        }, RebindWait.MAX_WAIT_MS, c);
        assertNull(r);
        assertEquals("实例一直在：真失败，不重做", 1, n[0]);
        assertEquals("也不白等", 0L, c.t);
    }

    // ---------- ② 卡住停下 ≠ 用户拒绝

    @Test public void stallStopIsNotUserRejected() {
        PhoneFailure f = PhoneFailure.classify(true, "连续 3 轮界面没有任何变化", "");
        assertEquals(ErrorCode.EXEC_FAILED, f.code);
        assertEquals(Outcome.NOT_DONE, f.outcome);
        assertEquals(Outcome.Reason.LOOP_GUARD, f.reason);
        assertFalse(f.hint.contains("用户停止"));
        assertEquals("没完成：", PhoneFailure.resultPrefix(true, "连续 3 轮界面没有任何变化"));
    }

    @Test public void realUserStopsStayUserStopped() {
        assertEquals(ErrorCode.USER_REJECTED, PhoneFailure.classify(true, "已停止", "").code);
        assertEquals(ErrorCode.USER_REJECTED, PhoneFailure.classify(true, "用户停止", "").code);
        assertEquals(Outcome.Reason.USER_STOPPED, PhoneFailure.classify(true, "用户没有同意：要发送吗", "").reason);
        assertEquals("已停止：", PhoneFailure.resultPrefix(true, "已停止"));
        assertEquals("没完成：", PhoneFailure.resultPrefix(false, "没找到"));
        assertFalse(PhoneFailure.stallStop(false, "连续 3 轮界面没有任何变化"));
    }

    // ---------- Shizuku 助手 NOT_PINNED：SDK 33+ 的 getPackageInfo(String, long, int)

    public static class PmApi33 {
        public Object getPackageInfo(String p, long flags, int user) { return "long"; }
    }

    public static class PmOld {
        public Object getPackageInfo(String p, int flags, int user) { return "int"; }
    }

    public static class PmNone {
    }

    @Test public void packageInfoReflectionHandlesApi33LongFlags() {
        assertEquals(long.class, FsProtocol.findPackageInfoMethod(PmApi33.class).getParameterTypes()[1]);
        assertEquals(int.class, FsProtocol.findPackageInfoMethod(PmOld.class).getParameterTypes()[1]);
        assertNull(FsProtocol.findPackageInfoMethod(PmNone.class));
    }

    @Test public void notPinnedSaysWhy() {
        String pkg = "com.onesay.assistant.debug";
        assertEquals("launch_pin_failed:NoSuchMethodException", FsProtocol.notPinnedWhy("onesay_fs:" + pkg, 2000, pkg, true, "NoSuchMethodException"));
        assertEquals("uid=0", FsProtocol.notPinnedWhy("onesay_fs:" + pkg, 0, pkg, false, ""));
        assertTrue(FsProtocol.notPinnedWhy("app_process", 2000, pkg, false, "").startsWith("proc="));
        assertTrue(FsProtocol.launchPinAllowed("onesay_fs:" + pkg, 2000, pkg));
    }
}
