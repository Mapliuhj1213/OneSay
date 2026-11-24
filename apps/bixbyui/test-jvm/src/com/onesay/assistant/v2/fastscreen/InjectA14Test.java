package com.onesay.assistant.v2.fastscreen;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ★ fix/inject-a14：入口选择（只有 Global / 只有旧类 / 都没有）、按下失败才走 input 命令且绝不点两下、截图分支、接线守卫。
 */
public class InjectA14Test {

    /** 假事件类型（代替 InputEvent）。 */
    public static final class Ev {}

    public static final class FakeGlobal {
        static final FakeGlobal I = new FakeGlobal();

        public static FakeGlobal getInstance() {
            return I;
        }

        public boolean injectInputEvent(Ev e, int mode) {
            return true;
        }
    }

    /** 安卓 14 上的旧 InputManager：没有 getInstance。 */
    public static final class FakeLegacyNoInstance {
        public boolean injectInputEvent(Ev e, int mode) {
            return true;
        }
    }

    public static final class FakeLegacy {
        static final FakeLegacy I = new FakeLegacy();

        public static FakeLegacy getInstance() {
            return I;
        }

        public boolean injectInputEvent(Ev e, int mode) {
            return true;
        }
    }

    static InjectPlan.Finder finder(final Class<?> global, final Class<?> legacy) {
        return new InjectPlan.Finder() {
            @Override
            public Class<?> find(String name) throws ClassNotFoundException {
                if (name.equals(InjectPlan.GLOBAL) && global != null) return global;
                if (name.equals(InjectPlan.LEGACY) && legacy != null) return legacy;
                throw new ClassNotFoundException(name);
            }
        };
    }

    @Test
    public void prefersGlobal() {
        InjectPlan.Injector j = InjectPlan.resolve(finder(FakeGlobal.class, FakeLegacy.class), Ev.class);
        assertNotNull(j);
        assertEquals(InjectPlan.GLOBAL, j.from);
        assertSame(FakeGlobal.I, j.target);
    }

    @Test
    public void android14LegacyWithoutGetInstanceUsesGlobal() {
        InjectPlan.Injector j = InjectPlan.resolve(finder(FakeGlobal.class, FakeLegacyNoInstance.class), Ev.class);
        assertEquals(InjectPlan.GLOBAL, j.from);
    }

    @Test
    public void android8OnlyLegacy() {
        InjectPlan.Injector j = InjectPlan.resolve(finder(null, FakeLegacy.class), Ev.class);
        assertNotNull(j);
        assertEquals(InjectPlan.LEGACY, j.from);
        assertSame(FakeLegacy.I, j.target);
    }

    @Test
    public void noneGivesNull() {
        assertNull(InjectPlan.resolve(finder(null, null), Ev.class));
        assertNull(InjectPlan.resolve(finder(null, FakeLegacyNoInstance.class), Ev.class));
    }

    /** 记下调用顺序的假手势。 */
    static final class Rec implements InjectPlan.Gesture {
        final List<String> log = new ArrayList<String>();
        final Boolean downOk;   // null = 抛异常
        final boolean[] ups;
        int upIdx;
        final boolean shellOk;

        Rec(Boolean downOk, boolean shellOk, boolean... ups) {
            this.downOk = downOk;
            this.shellOk = shellOk;
            this.ups = ups;
        }

        @Override
        public boolean down() throws Exception {
            log.add("down");
            if (downOk == null) throw new IllegalStateException("NO_INJECTOR");
            return downOk;
        }

        @Override
        public boolean moves() {
            log.add("moves");
            return true;
        }

        @Override
        public boolean up() {
            log.add("up");
            return upIdx < ups.length && ups[upIdx++];
        }

        @Override
        public boolean shell() {
            log.add("shell");
            return shellOk;
        }
    }

    @Test
    public void downOkNeverShell() {
        Rec r = new Rec(true, true, true);
        assertEquals(InjectPlan.VIA_INJECT, InjectPlan.run(r));
        assertEquals(Arrays.asList("down", "moves", "up"), r.log);
    }

    @Test
    public void downOkUpFailsRetriesUpOnlyNoShell() {
        Rec r = new Rec(true, true, false, false);
        assertNull(InjectPlan.run(r));
        assertEquals(Arrays.asList("down", "moves", "up", "up"), r.log);
        assertFalse(r.log.contains("shell"));

        Rec r2 = new Rec(true, true, false, true);
        assertEquals(InjectPlan.VIA_INJECT, InjectPlan.run(r2));
        assertFalse(r2.log.contains("shell"));
    }

    @Test
    public void downFailsGoesShellOnce() {
        Rec r = new Rec(false, true);
        assertEquals(InjectPlan.VIA_SHELL, InjectPlan.run(r));
        assertEquals(Arrays.asList("down", "shell"), r.log);
    }

    @Test
    public void noInjectorThrowsGoesShell() {
        Rec r = new Rec(null, true);
        assertEquals(InjectPlan.VIA_SHELL, InjectPlan.run(r));
        assertEquals(Arrays.asList("down", "shell"), r.log);
        assertNull(InjectPlan.run(new Rec(null, false)));
    }

    @Test
    public void argv() {
        assertEquals(Arrays.asList("/system/bin/input", "tap", "10", "20"), Arrays.asList(InjectPlan.tapArgv(10, 20, 40)));
        assertEquals(Arrays.asList("/system/bin/input", "swipe", "10", "20", "10", "20", "800"),
                Arrays.asList(InjectPlan.tapArgv(10, 20, 800)));
        assertEquals(Arrays.asList("/system/bin/input", "swipe", "1", "2", "3", "4", "300"),
                Arrays.asList(InjectPlan.swipeArgv(1, 2, 3, 4, 300)));
        assertEquals(Arrays.asList("/system/bin/input", "keyevent", "4"), Arrays.asList(InjectPlan.keyArgv(4)));
        assertEquals(Arrays.asList("/system/bin/screencap", "-p", "/x.png"), Arrays.asList(InjectPlan.screencapArgv("/x.png")));
    }

    @Test
    public void screencapFromApi31() {
        assertFalse(InjectPlan.useScreencap(26));
        assertFalse(InjectPlan.useScreencap(30));
        assertTrue(InjectPlan.useScreencap(31));
        assertTrue(InjectPlan.useScreencap(34));
    }

    @Test
    public void helperWiredUp() throws Exception {
        String s = new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/fastscreen/HelperMain.java").toPath()), "UTF-8");
        assertTrue(s.contains("InjectPlan.resolve(InjectPlan.SYSTEM, InputEvent.class)"));
        assertFalse("旧的只取 InputManager 的写法应已去掉", s.contains("Class.forName(\"android.hardware.input.InputManager\")"));
        assertEquals(3, count(s, "InjectPlan.run(new InjectPlan.Gesture()"));
        assertTrue(s.contains("InjectPlan.useScreencap(android.os.Build.VERSION.SDK_INT)"));
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }
}
