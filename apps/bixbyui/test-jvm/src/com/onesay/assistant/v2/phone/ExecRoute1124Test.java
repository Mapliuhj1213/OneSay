package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import com.onesay.assistant.plug.action.core.ActionChain;
import com.onesay.assistant.v2.a11y.A11yState;
import com.onesay.assistant.v2.a11y.A11yState.Route;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * [exec-1124 C] 按动作选后端（路线判定 × 各动作顺序）、打开应用后核对前台、接线守卫（root 路径不退步）。
 */
public class ExecRoute1124Test {

    // ------------------------------------------------------------------ 路线

    @Test
    public void routeTable() {
        // helperOk, helperRoot, a11yConnected, killed
        assertEquals(Route.HELPER_ROOT, A11yState.route(true, true, true, false));
        assertEquals(Route.HELPER_ROOT, A11yState.route(true, true, false, false));
        assertEquals(Route.A11Y_FIRST, A11yState.route(true, false, true, false));
        assertEquals("无障碍被开发者关掉不算在", Route.HELPER, A11yState.route(true, false, true, true));
        assertEquals(Route.HELPER, A11yState.route(true, false, false, false));
        assertEquals(Route.A11Y_ONLY, A11yState.route(false, false, true, false));
        assertEquals(Route.A11Y_ONLY, A11yState.route(false, true, true, false));
        assertEquals(Route.NONE, A11yState.route(false, false, true, true));
        assertEquals(Route.NONE, A11yState.route(false, false, false, false));
    }

    @Test
    public void choiceCarriesRouteButBackendUnchanged() {
        A11yState.Choice c = A11yState.choose(true, false, true, true, false, "");
        assertEquals("调用方只看 backend != NONE，助手在时 backend 仍是 HELPER", A11yState.Backend.HELPER, c.backend);
        assertEquals(Route.A11Y_FIRST, c.route);
        c = A11yState.choose(true, true, true, true, false, "");
        assertEquals(Route.HELPER_ROOT, c.route);
        c = A11yState.choose(false, false, true, true, false, "x");
        assertEquals(A11yState.Backend.A11Y, c.backend);
        assertEquals(Route.A11Y_ONLY, c.route);
        c = A11yState.choose(false, false, false, true, false, "x");
        assertEquals(A11yState.Backend.NONE, c.backend);
        assertEquals(Route.NONE, c.route);
        assertTrue(c.offerA11ySettings);
        // 旧的五参数版本照旧
        assertEquals(A11yState.Backend.HELPER, A11yState.choose(true, true, true, false, "").backend);
    }

    // ------------------------------------------------------------------ 点按顺序

    @Test
    public void tapOrders() {
        assertEquals(Arrays.asList(ActionRoute.A11Y_NODE, ActionRoute.HELPER_NODE, ActionRoute.A11Y_GESTURE, ActionRoute.HELPER_INJECT),
                Arrays.asList(ActionRoute.tap(Route.A11Y_FIRST)));
        assertEquals(Arrays.asList(ActionRoute.A11Y_NODE, ActionRoute.A11Y_GESTURE), Arrays.asList(ActionRoute.tap(Route.A11Y_ONLY)));
        assertEquals("root 路径保持原样", Arrays.asList(ActionRoute.HELPER_NODE, ActionRoute.HELPER_INJECT), Arrays.asList(ActionRoute.tap(Route.HELPER_ROOT)));
        assertEquals(Arrays.asList(ActionRoute.HELPER_NODE, ActionRoute.HELPER_INJECT), Arrays.asList(ActionRoute.tap(Route.HELPER)));
        assertEquals(0, ActionRoute.tap(Route.NONE).length);
        assertEquals(0, ActionRoute.tap(null).length);
        assertTrue(ActionRoute.tapUses(Route.A11Y_FIRST, ActionRoute.A11Y_GESTURE));
        assertFalse("root 不往中间插无障碍手势", ActionRoute.tapUses(Route.HELPER_ROOT, ActionRoute.A11Y_GESTURE));
        // 节点点击总在手势前，手势总在注入前
        for (Route r : Route.values()) {
            List<String> o = Arrays.asList(ActionRoute.tap(r));
            int g = o.indexOf(ActionRoute.A11Y_GESTURE), i = o.indexOf(ActionRoute.HELPER_INJECT);
            if (g >= 0 && i >= 0) assertTrue(r.name(), g < i);
            if (o.contains(ActionRoute.A11Y_NODE) && o.contains(ActionRoute.HELPER_NODE))
                assertTrue("两者都在时无障碍先", o.indexOf(ActionRoute.A11Y_NODE) < o.indexOf(ActionRoute.HELPER_NODE));
        }
    }

    @Test
    public void wholeOrders() {
        assertEquals(Arrays.asList(ActionRoute.A11Y, ActionRoute.HELPER), Arrays.asList(ActionRoute.whole(Route.A11Y_FIRST)));
        assertEquals(Arrays.asList(ActionRoute.HELPER), Arrays.asList(ActionRoute.whole(Route.HELPER_ROOT)));
        assertEquals(Arrays.asList(ActionRoute.HELPER), Arrays.asList(ActionRoute.whole(Route.HELPER)));
        assertEquals(Arrays.asList(ActionRoute.A11Y), Arrays.asList(ActionRoute.whole(Route.A11Y_ONLY)));
        assertEquals(0, ActionRoute.whole(Route.NONE).length);
    }

    /** 模拟魅族「两者都开、助手注入坏了」：链条走到无障碍手势就成了，不再「点按 none」。 */
    @Test
    public void shizukuInjectBrokenGestureSaves() {
        final List<String> log = new ArrayList<String>();
        ActionChain.ActionPort p = new ActionChain.ActionPort() {
            @Override public boolean clickNode(int src, int[] b, String cls, int max) {
                log.add("node");
                return false;
            }

            @Override public boolean gestureTap(int x, int y) {
                log.add("gesture");
                return ActionRoute.tapUses(Route.A11Y_FIRST, ActionRoute.A11Y_GESTURE);
            }

            @Override public boolean injectTap(int x, int y) {
                log.add("inject");
                return false;
            }

            @Override public String scrollNode(int src, int[] b, int dir) {
                return null;
            }

            @Override public boolean swipe(int a, int b, int c, int d, int ms) {
                return false;
            }
        };
        assertEquals(ActionChain.GESTURE, ActionChain.tap(p, 1, new int[]{0, 0, 100, 100}, "Button"));
        assertEquals(Arrays.asList("node", "gesture"), log);
    }

    // ------------------------------------------------------------------ 按键顺序

    @Test
    public void keyOrders() {
        assertEquals("两者都在：返回先用全局动作", Arrays.asList(ActionRoute.A11Y_GLOBAL, ActionRoute.HELPER_KEY),
                Arrays.asList(ActionRoute.key(Route.A11Y_FIRST, true, "back")));
        assertEquals("root 也一样：无障碍连着就先全局动作（不再只看 b2Ready）", Arrays.asList(ActionRoute.A11Y_GLOBAL, ActionRoute.HELPER_KEY),
                Arrays.asList(ActionRoute.key(Route.HELPER_ROOT, true, "home")));
        assertEquals("root、无障碍没开：只有助手按键（原样）", Arrays.asList(ActionRoute.HELPER_KEY), Arrays.asList(ActionRoute.key(Route.HELPER_ROOT, false, "back")));
        assertEquals(Arrays.asList(ActionRoute.HELPER_KEY), Arrays.asList(ActionRoute.key(Route.HELPER, false, "home")));
        assertEquals(Arrays.asList(ActionRoute.A11Y_GLOBAL), Arrays.asList(ActionRoute.key(Route.A11Y_ONLY, false, "back")));
        assertEquals(Arrays.asList(ActionRoute.HELPER_KEY, ActionRoute.A11Y_IME), Arrays.asList(ActionRoute.key(Route.A11Y_FIRST, true, "enter")));
        assertEquals(Arrays.asList(ActionRoute.HELPER_KEY, ActionRoute.A11Y_IME), Arrays.asList(ActionRoute.key(Route.HELPER_ROOT, true, "search")));
        assertEquals(Arrays.asList(ActionRoute.A11Y_IME), Arrays.asList(ActionRoute.key(Route.A11Y_ONLY, true, "enter")));
        assertEquals(Arrays.asList(ActionRoute.HELPER_KEY), Arrays.asList(ActionRoute.key(Route.A11Y_FIRST, true, "delete")));
        assertEquals("只有无障碍时删除键做不了", 0, ActionRoute.key(Route.A11Y_ONLY, true, "delete").length);
        assertEquals(0, ActionRoute.key(Route.NONE, true, "back").length);
        assertEquals(0, ActionRoute.key(Route.A11Y_FIRST, true, "volume_up").length);
        assertEquals(0, ActionRoute.key(Route.A11Y_FIRST, true, null).length);
    }

    @Test
    public void keyCodes() {
        assertEquals(4, ActionRoute.helperKeyCode("back"));
        assertEquals(3, ActionRoute.helperKeyCode("home"));
        assertEquals(66, ActionRoute.helperKeyCode("enter"));
        assertEquals(84, ActionRoute.helperKeyCode("search"));
        assertEquals(67, ActionRoute.helperKeyCode("delete"));
        assertEquals(-1, ActionRoute.helperKeyCode("x"));
        assertEquals(com.onesay.assistant.v2.fastscreen.FsProtocol.KEY_BACK, ActionRoute.helperKeyCode("back"));
        assertEquals(com.onesay.assistant.v2.fastscreen.FsProtocol.KEY_HOME, ActionRoute.helperKeyCode("home"));
        assertEquals(com.onesay.assistant.v2.fastscreen.FsProtocol.KEY_ENTER, ActionRoute.helperKeyCode("enter"));
    }

    // ------------------------------------------------------------------ 打开应用核对

    /** 假前台：按时间表变；relaunch 记次数。 */
    static final class FakeProbe implements LaunchCheck.Probe {
        long t;
        final long[] at;
        final String[] fg;
        int relaunches;
        String afterRelaunch;
        long relaunchAt = -1;

        FakeProbe(long[] at, String[] fg) {
            this.at = at;
            this.fg = fg;
        }

        @Override public String foreground() {
            if (relaunchAt >= 0 && afterRelaunch != null && t >= relaunchAt + 500) return afterRelaunch;
            String cur = null;
            for (int i = 0; i < at.length; i++) if (t >= at[i]) cur = fg[i];
            return cur;
        }

        @Override public long now() {
            return t;
        }

        @Override public void sleep(long ms) {
            t += ms;
        }

        @Override public boolean relaunch() {
            relaunches++;
            relaunchAt = t;
            return true;
        }
    }

    @Test
    public void launchAlreadyThere() {
        FakeProbe p = new FakeProbe(new long[]{0}, new String[]{"com.android.calculator2"});
        LaunchCheck.Result r = LaunchCheck.await("com.android.calculator2", "com.android.launcher", p);
        assertEquals(LaunchCheck.Outcome.OK, r.outcome);
        assertEquals(0, r.waitedMs);
        assertEquals(0, p.relaunches);
    }

    @Test
    public void launchArrivesLate() {
        FakeProbe p = new FakeProbe(new long[]{0, 1800}, new String[]{"launcher", "calc"});
        LaunchCheck.Result r = LaunchCheck.await("calc", "launcher", p);
        assertEquals(LaunchCheck.Outcome.OK, r.outcome);
        assertTrue(r.waitedMs >= 1750 && r.waitedMs <= 2000);
        assertEquals("1.8 s 到了，不补拉", 0, p.relaunches);
    }

    @Test
    public void launchBlockedRelaunchOnceThenFail() {
        FakeProbe p = new FakeProbe(new long[]{0}, new String[]{"launcher"});
        LaunchCheck.Result r = LaunchCheck.await("calc", "launcher", p);
        assertEquals(LaunchCheck.Outcome.FAIL, r.outcome);
        assertEquals("只补拉一次", 1, p.relaunches);
        assertTrue(r.relaunched);
        assertEquals("launcher", r.lastForeground);
        assertTrue("最多等 5 秒", r.waitedMs >= LaunchCheck.WAIT_MS && r.waitedMs < LaunchCheck.WAIT_MS + LaunchCheck.POLL_MS + 1);
    }

    @Test
    public void launchRelaunchWorks() {
        FakeProbe p = new FakeProbe(new long[]{0}, new String[]{"launcher"});
        p.afterRelaunch = "calc";
        LaunchCheck.Result r = LaunchCheck.await("calc", "launcher", p);
        assertEquals(LaunchCheck.Outcome.OK, r.outcome);
        assertEquals(1, p.relaunches);
        assertTrue(r.waitedMs < LaunchCheck.WAIT_MS);
    }

    @Test
    public void launchSomethingElsePoppedNoRelaunch() {
        // 前台变成了别的（比如系统的「是否打开」框）：不再补拉（免得再弹一个），到点按失败交给上层（B39 处理确认框）
        FakeProbe p = new FakeProbe(new long[]{0, 300}, new String[]{"launcher", "com.android.systemui"});
        LaunchCheck.Result r = LaunchCheck.await("calc", "launcher", p);
        assertEquals(LaunchCheck.Outcome.FAIL, r.outcome);
        assertEquals(0, p.relaunches);
        assertEquals("com.android.systemui", r.lastForeground);
    }

    @Test
    public void launchCannotReadForegroundIsUnknown() {
        FakeProbe p = new FakeProbe(new long[0], new String[0]);
        LaunchCheck.Result r = LaunchCheck.await("calc", null, p);
        assertEquals("读不到前台：核对不了，不误报失败", LaunchCheck.Outcome.UNKNOWN, r.outcome);
        assertEquals(1, p.relaunches);
    }

    @Test
    public void failNotePlain() {
        String n = LaunchCheck.failNote("计算器", true);
        assertTrue(n, n.contains("「计算器」") && n.contains("5 秒") && n.contains("前面还是别的界面") && n.contains("后台弹出界面"));
        String n2 = LaunchCheck.failNote(null, false);
        assertTrue(n2, n2.contains("这个应用") && !n2.contains("前面还是别的界面"));
        for (String s : new String[]{n, n2}) {
            String l = s.toLowerCase();
            for (String bad : new String[]{"啥", "咋", "呗", "bixby", "三星", "samsung", "dex", "小布", "flyme", "魅族", "mika", "shizuku"})
                assertFalse(bad + " in " + s, l.contains(bad));
        }
    }

    // ------------------------------------------------------------------ 接线守卫

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/" + rel).toPath()), "UTF-8");
    }

    static String body(String s, String head) {
        int k = s.indexOf(head);
        assertTrue("找不到 " + head, k >= 0);
        int depth = 0, i = s.indexOf('{', k);
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(k, j + 1);
        }
        return s.substring(k);
    }

    @Test
    public void driverWiring() throws Exception {
        String d = src("phone/FastScreenDriver.java");
        String cb = body(d, "public com.onesay.assistant.v2.a11y.A11yState.Choice chooseBackend()");
        assertTrue(cb.contains("mFs.rootHelper()") && cb.contains("mRoute = c.route"));
        String key = body(d, "@Override public boolean key(String key)");
        assertTrue("按键按路线表走", key.contains("ActionRoute.key(route(), a != null, key)"));
        assertFalse("返回 / 主页不再只在 b2Ready 时用全局动作", key.contains("b2Ready()"));
        String gt = body(d, "@Override public boolean gestureTap(int x, int y)");
        assertTrue("两者都在时也用无障碍手势", gt.contains("a11yLead() ? lead()"));
        String tap = body(d, "@Override public boolean tap(final UiTree.El e)");
        assertTrue(tap.contains("if (a11yLead() || (mk(1) && mLastB2)) return tapA11yVerified(e);"));
        assertTrue("root / 只有助手的原路径还在", tap.contains("if (mFs.click(e.cx(), e.cy()))") && tap.contains("return injectTap(e.cx(), e.cy());"));
        String inj = body(d, "public boolean injectTap(int x, int y)");
        assertTrue("助手注入仍是最后一级", inj.contains("return mFs.tap(x, y);"));
        String launch = body(d, "@Override public boolean launch(String pkg)");
        assertTrue(launch.contains("StateFirst.skipLaunch(fg, pkg)") && launch.contains("return verifyLaunched(pkg, fg);"));
        assertTrue(d.contains("LaunchCheck.await(pkg, before, new LaunchCheck.Probe()"));
        assertTrue(d.contains("mLaunchNote = LaunchCheck.failNote(label(pkg)"));
        String lead = body(d, "private com.onesay.assistant.v2.a11y.A11yBackend lead()");
        assertTrue("设置里关了就不每步白等", lead.contains("enabledInSettings(mApp)"));
        String fs = src("fastscreen/FastScreen.java");
        assertTrue(fs.contains("public boolean rootHelper()") && fs.contains("mPeerUid = peer;") && fs.contains("mPeerUid = 2000;"));
        String pa = src("phone/PhoneAgent.java");
        assertTrue(pa.contains("String lastLaunchNote();") && pa.contains("\"打不开 \" + s.app + \"：\" + why"));
        assertTrue(src("phone/PhoneHooks.java").contains("return d.lastLaunchNote();"));
        // 审计补：root / 只助手时滚动不额外补滑（原样）；无障碍先读读得可疑时用助手再读一次
        assertTrue(d.contains("if (r == null && a11yLead()) r = swipeDir(e.cx(), dir) ? \"swipe\" : null;"));
        assertTrue(d.contains("if (mLastB2 && a11yFirst() && ObserveFallback.suspect(u.quality(), u.pkg, mApp.getPackageName()))"));
    }
}
