package com.onesay.assistant.v2.ui.console;

import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.KernelWiring;
import com.onesay.assistant.v2.wake.AssistantPause;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** [console R12] 控制台重设计：状态判定（未知不猜绿）、开发者模式、密钥打码、页面栈、配色对比度、暂停、控件去向对照。 */
public class ConsoleR12Test {

    static String src(String rel) {
        return KernelWiring.src(rel);
    }

    static final String UI = "app/src/com/onesay/assistant/v2/ui/";
    static final String CON = UI + "console/";
    static final String PL = "app/src/com/onesay/assistant/v2/kernel/plugins/";

    // ------------------------------------------------------------------ 状态：探测不到 = 未知，绝不写死绿色

    @Test public void emptySnapshotIsAllUnknown() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        // 控制台整理第二步：还在探（!done）→ 灰点占位、文字和正常时一样短（不写「未知」，免得探完变宽顶动布局）
        for (ConsoleStatus.Pill p : ConsoleStatus.pills(s)) {
            assertEquals(p.toString(), ConsoleStatus.Level.UNKNOWN, p.level);
            assertFalse(p.toString(), p.label.endsWith("未知"));
        }
        assertTrue("未知不出提醒条", ConsoleStatus.banners(s).isEmpty());
        assertEquals("检测中", ConsoleStatus.hero(s).word);
        s.done = true;
        for (ConsoleStatus.Pill p : ConsoleStatus.pills(s)) {
            assertEquals(p.toString(), ConsoleStatus.Level.UNKNOWN, p.level);
            assertTrue("探完还拿不到才写未知 " + p, p.label.endsWith("未知"));
        }
        assertEquals("探测完了还是未知 → 不能说「运行中」", ConsoleStatus.Level.UNKNOWN, ConsoleStatus.hero(s).level);
        assertEquals("状态未知", ConsoleStatus.hero(s).word);
    }

    @Test public void wakePill() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.wakeRunning = false;
        assertEquals(ConsoleStatus.Level.BAD, ConsoleStatus.wake(s).level);
        s.wakeRunning = true;
        assertEquals("服务在但麦克风没开录 = 启动中", ConsoleStatus.Level.WARN, ConsoleStatus.wake(s).level);
        s.micReady = true;
        assertEquals(ConsoleStatus.Level.OK, ConsoleStatus.wake(s).level);
        assertEquals("唤醒", ConsoleStatus.wake(s).label);
        s.paused = true;
        assertEquals(ConsoleStatus.Level.OFF, ConsoleStatus.wake(s).level);
        assertEquals("已暂停", ConsoleStatus.hero(s).word);
    }

    static final String PROBE_ALIVE = "ALIVE\nMOD\n# Bixby key takeover daemon - v8.1 \"silent long press\" (2026-10-11, R10)\nEND\n";

    @Test public void daemonProbeParse() {
        Object[] d = ConsoleStatus.parseDaemon(PROBE_ALIVE);
        assertEquals(Boolean.TRUE, d[0]);
        assertEquals(Boolean.TRUE, d[1]);
        assertEquals("v8.1", d[2]);
        d = ConsoleStatus.parseDaemon("DEAD\nNOMOD\nEND\n");
        assertEquals(Boolean.FALSE, d[0]);
        assertEquals("NOMOD 不能被当成 MOD", Boolean.FALSE, d[1]);
        assertNull(d[2]);
        d = ConsoleStatus.parseDaemon("ALIVE\nMOD\n");
        assertNull("没有 END = 输出被截断 → 全部未知", d[0]);
        assertNull(ConsoleStatus.parseDaemon(null)[0]);
        // v7 头（老守护）也能认
        assertEquals("v7", ConsoleStatus.parseDaemon("ALIVE\nMOD\n# Bixby key takeover daemon - v7 \"K L2\" (2026-10-11)\nEND")[2]);
    }

    @Test public void daemonPillNeedsRoot() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.daemonProbe = PROBE_ALIVE;
        assertEquals("root 没测到 → 守护也是未知", ConsoleStatus.Level.UNKNOWN, ConsoleStatus.daemon(s).level);
        s.rootOk = true;
        assertEquals(ConsoleStatus.Level.OK, ConsoleStatus.daemon(s).level);
        assertEquals("按键 v8.1", ConsoleStatus.daemon(s).label);
        s.daemonProbe = "DEAD\nMOD\nEND";
        assertEquals("按键 未运行", ConsoleStatus.daemon(s).label);
        assertEquals(ConsoleStatus.Level.BAD, ConsoleStatus.hero(s).level);
        s.daemonProbe = "DEAD\nNOMOD\nEND";
        assertEquals("按键 未安装", ConsoleStatus.daemon(s).label);
    }

    @Test public void bannersPriorityAndCap() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.wakeRunning = true;
        s.micReady = true;
        s.rootOk = true;
        s.llmConfigured = false;
        s.overlayOk = false;
        s.batteryWhitelisted = false;
        s.voiceprint = false;
        List<ConsoleStatus.Banner> b = ConsoleStatus.banners(s);
        assertEquals(ConsoleStatus.MAX_BANNERS, b.size());
        assertEquals(ConsoleStatus.Banner.ACT_KEY, b.get(0).actionId);
        assertEquals(ConsoleStatus.Banner.ACT_OVERLAY, b.get(1).actionId);
        assertEquals(4, ConsoleStatus.allProblems(s).size());
        s.islandEnabled = false;
        assertEquals("岛关着就不催悬浮窗", ConsoleStatus.Banner.ACT_BATTERY, ConsoleStatus.banners(s).get(1).actionId);
        s.rootOk = false;
        assertEquals("Root 排在密钥前面", ConsoleStatus.Banner.ACT_STATUS, ConsoleStatus.banners(s).get(0).actionId);
        s.wakeRunning = false;
        assertEquals("唤醒服务最先", ConsoleStatus.Banner.ACT_WAKE, ConsoleStatus.banners(s).get(0).actionId);
    }

    @Test public void heroRunning() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.wakeRunning = true;
        s.micReady = true;
        s.rootOk = true;
        s.daemonProbe = PROBE_ALIVE;
        s.llmConfigured = true;
        s.done = true;
        assertEquals("运行中", ConsoleStatus.hero(s).word);
        assertEquals(ConsoleStatus.Level.OK, ConsoleStatus.hero(s).level);
        assertTrue(ConsoleStatus.heroHint(false, true).contains("长按 0.8 秒记一记"));
        assertFalse(ConsoleStatus.heroHint(false, false).contains("记一记"));
        assertTrue(ConsoleStatus.heroHint(true, true).contains("照常"));
    }

    @Test public void statusSourcesNeverHardcodeGreen() {
        String home = src(CON + "HomePage.java");
        assertFalse("首页不再写死绿色", home.contains("#32D74B") || home.contains("#30D158"));
        assertTrue("胶囊来自 ConsoleStatus.pills(真实快照)", home.contains("ConsoleStatus.pills(s)"));
        String probe = src(CON + "StatusProbe.java");
        assertTrue(probe.contains("WakeService.isServiceRunning()") && probe.contains("echo OK") && probe.contains("ConsoleStatus.DAEMON_PROBE"));
        assertTrue("探测超时保留未知", probe.contains("DEADLINE_MS"));
    }

    // ------------------------------------------------------------------ 开发者模式

    @Test public void devModeGate() {
        assertTrue("debug 包没设过 → 开", DevMode.isOn(true, null));
        assertFalse("release 包没设过 → 关", DevMode.isOn(false, null));
        assertFalse("显式关掉优先", DevMode.isOn(true, Boolean.FALSE));
        assertTrue(DevMode.isOn(false, Boolean.TRUE));
        DevMode g = new DevMode();
        long t = 1000;
        int left = -9;
        for (int i = 1; i <= 7; i++) {
            left = g.tap(t, false);
            t += 300;
            if (i < 7) assertEquals(7 - i, left);
        }
        assertEquals("第 7 下打开", 0, left);
        assertEquals(-1, g.tap(t, true));
        assertNull("前几下不提示", DevMode.hint(6));
        assertEquals("再点 3 次进入开发者模式", DevMode.hint(3));
    }

    @Test public void devModeResetsAfterGap() {
        DevMode g = new DevMode();
        for (int i = 0; i < 5; i++) g.tap(i * 100L, false);
        assertEquals("隔太久重新数", 6, g.tap(10000L, false));
    }

    @Test public void selfTestHiddenInRelease() {
        String dev = src(CON + "DeveloperPage.java");
        assertTrue(dev.contains("if (com.onesay.assistant.v2.test.V2SelfTest.AVAILABLE)"));
        assertTrue(src("app/src-release/com/onesay/assistant/v2/test/V2SelfTest.java").contains("AVAILABLE = false"));
        assertTrue(src("app/src/com/onesay/assistant/v2/test/V2SelfTest.java").contains("AVAILABLE = true"));
        // 控制台整理第二步：「调试工具」（原开发者页入口）搬到首页「开发者」分组，仍只在开发者模式开着时出现
        String home = src(CON + "HomePage.java");
        int gate = home.indexOf("if (mDevBuiltFor)");
        assertTrue("开发者入口只在开发者模式出现", home.contains("mDevBuiltFor = c.devMode();") && gate > 0
                && home.indexOf("new DeveloperPage(c)", gate) > gate);
        assertTrue("连点 7 次", src(CON + "AboutPage.java").contains("devGate.tap("));
    }

    // ------------------------------------------------------------------ 密钥打码（D8）

    @Test public void keyMask() {
        assertEquals("未配置", KeyMask.mask(null));
        assertEquals("未配置", KeyMask.mask("  "));
        assertEquals("已配置", KeyMask.mask("sk-short"));
        assertEquals("已配置 · sk-…3f2a", KeyMask.mask("sk-0123456789abcdef3f2a"));
        assertEquals("已配置 · …wxyz", KeyMask.mask("abcdefghijklmnopqrstuvwxyz"));
        String m = KeyMask.mask("sk-0123456789abcdef3f2a");
        assertFalse("中间部分不露", m.contains("0123"));
        assertFalse(KeyMask.shouldSave(""));
        assertFalse(KeyMask.shouldSave("   "));
        assertTrue(KeyMask.shouldSave("sk-x"));
    }

    @Test public void decryptedKeyNeverSetText() {
        List<String> files = new ArrayList<String>();
        files.add(UI + "MainActivity.java");
        for (File f : new File(System.getProperty("bixby.root", "."), CON).listFiles()) files.add(CON + f.getName());
        for (String f : files) {
            String s = src(f);
            for (String line : s.split("\n")) {
                if (!line.contains("getApiKey()")) continue;
                assertTrue(f + " 里用到解密密钥的行必须经过 KeyMask：" + line.trim(), line.contains("KeyMask.mask("));
            }
        }
        String c = src(CON + "Console.java");
        assertTrue("编辑框只有 hint，不预填", c.contains("et.setHint(") && !c.contains("et.setText("));
    }

    // ------------------------------------------------------------------ 页面栈

    @Test public void pageStack() {
        PageStack<String> st = new PageStack<String>();
        assertNull(st.pop());
        st.push("home");
        assertNull("首页不能被弹出", st.pop());
        assertFalse(st.canPop());
        st.push("features");
        st.push("diag");
        assertEquals("diag", st.top());
        assertEquals("features", st.below());
        assertEquals("diag", st.replaceTop("summary"));
        assertEquals("summary", st.pop());
        st.push("x");
        assertEquals(2, st.popToRoot().size());
        assertEquals("home", st.top());
        assertEquals(1, st.size());
    }

    // ------------------------------------------------------------------ 配色（D2 / D3）

    @Test public void contrast() {
        for (ConsoleTheme t : new ConsoleTheme[]{ConsoleTheme.DARK, ConsoleTheme.LIGHT}) {
            String n = t.dark ? "dark" : "light";
            assertTrue(n + " 标题", ConsoleTheme.contrast(t.text1, t.bg) >= 7);
            assertTrue(n + " 副标题", ConsoleTheme.contrast(t.text2, t.surface) >= 4.5);
            assertTrue(n + " 组标题 / 页脚（≥14sp 中等字重可用 3:1，实际 ≥4）", ConsoleTheme.contrast(t.text3, t.bg) >= 4.0);
            assertTrue(n + " 主色", ConsoleTheme.contrast(t.accent, t.surface) >= 4.5);
            assertTrue(n + " 警告字在警告底上", ConsoleTheme.contrast(t.warn, t.warnContainer) >= 4.5);
            assertTrue(n + " 主色字在主色容器上", ConsoleTheme.contrast(t.accent, t.accentContainer) >= 4.0);
            assertTrue(n + " 危险色", ConsoleTheme.contrast(t.danger, t.surface) >= 4.0);
            assertEquals(n + " 岛永远纯黑", 0xFF000000, t.island);
        }
        assertEquals("深色主色 = 岛的苔绿", 0xFF84AE64, ConsoleTheme.DARK.accent);
        assertEquals("浅色主色压暗", 0xFF4E7A35, ConsoleTheme.LIGHT.accent);
        assertTrue("苔绿原色在白底上不及格（所以浅色要压暗）", ConsoleTheme.contrast(0xFF84AE64, 0xFFFFFFFF) < 3);
        assertEquals(ConsoleTheme.LIGHT, ConsoleTheme.of("light"));
        assertEquals("默认深色", ConsoleTheme.DARK, ConsoleTheme.of(null));
    }

    // ------------------------------------------------------------------ 暂停（D1-A）

    @Test public void pause() {
        assertTrue(AssistantPause.blocks(true, "key-file"));
        assertFalse(AssistantPause.blocks(false, "key-file"));
        assertTrue(AssistantPause.notificationText(true, true).contains("已暂停"));
        assertEquals("语音与 Bixby 按键监听中", AssistantPause.notificationText(false, true));
        String w = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        int gate = w.indexOf("private synchronized void requestWakeInternal(");
        assertTrue("唤醒总闸门先看暂停", w.indexOf("if (pausedBlocks(source)) return;", gate) > gate
                && w.indexOf("if (pausedBlocks(source)) return;", gate) < w.indexOf("syncGestureFlag(this);", gate));
        assertTrue(w.contains("AssistantPause.blocks(AssistantPause.isPaused(this), source)"));
        assertTrue("按键手势也挡", w.contains("[PAUSE] 助手已暂停，忽略按键手势"));
        assertTrue("冷启动兜底也挡", src("app/src/com/onesay/assistant/WebViewUiActivity.java").contains("AssistantPause.isPaused(this)"));
        String m = src(UI + "MainActivity.java");
        assertTrue("am start MainActivity 照样拉起 WakeService（IN-3）", m.contains("ensureWakeServiceStarted();")
                && m.indexOf("ensureWakeServiceStarted();") < m.indexOf("mConsole.start();"));
        assertFalse("暂停不停服务", src(CON + "Console.java").contains("stopService("));
        assertTrue("暂停要二次确认", src(CON + "Console.java").contains("暂停助手？"));
    }

    // ------------------------------------------------------------------ 旧控件一个不丢（每个开关都对得上原来的 prefs 键）

    @Test public void everyOldControlHasAHome() {
        String voice = src(CON + "VoicePage.java");
        for (String k : new String[]{"WakeTuning.K_SHORT_CHIME", "WakeTuning.K_ADAPTIVE_EP", "WakeTuning.K_LIVE_STATE", "TtsSpeaker.setEnabled(",
                "vp.onToggle()", "vp.abort()"}) {
            assertTrue("语音页：" + k, voice.contains(k));
        }
        String mem = src(PL + "MemoryPlugin.java");
        assertTrue(mem.contains("S2Settings.KEY_MEMORY"));
        String roles = src(PL + "RolesPlugin.java");
        assertTrue(roles.contains("S2Settings.KEY_HABIT") && roles.contains("SIGN_HABIT_RESET") && roles.contains("RoleHooks.summary(act)"));
        String diag = src(PL + "DiagPlugin.java");
        assertTrue(diag.contains("DiagSettings.KEY_ENABLED") && diag.contains("DiagSettings.KEY_LLM"));
        String sum = src(PL + "SummaryPlugin.java");
        assertTrue(sum.contains("SummarySettings.KEY_ENABLED") && sum.contains("SummarySettings.KEY_VL"));
        String isl = src(PL + "IslandPlugin.java");
        assertTrue(isl.contains("IslandSettings.KEY_ENABLED") && isl.contains("IslandSettings.KEY_FULLSCREEN_HIDE")
                && isl.contains("IslandSettings.KEY_TUCK") && isl.contains("ACTION_MANAGE_OVERLAY_PERMISSION")
                && isl.contains("S1Hooks.refreshIslandMode(\"console\")"));
        String s3 = src("app/src/com/onesay/assistant/v2/s3/S3Hooks.java");
        assertTrue(s3.contains("S3Settings.PREF_S3, S3Settings.KEY_ENABLED") && s3.contains("S3Settings.PREF_TODO, S3Settings.KEY_ENABLED")
                && s3.contains("S3Settings.KEY_CHIP") && s3.contains("whitelistByRoot(act)") && s3.contains("listText(now)")
                && s3.contains("todoListText(now)"));
        String qn = src(PL + "QuickNotePlugin.java");
        for (String k : new String[]{"SWITCH.set(act, on)", "syncGestureFlag(act)", "KEY_HOLD", "KEY_VL", "KEY_LEDGER", "KEY_THUMBS"}) {
            assertTrue("记一记设置：" + k, qn.contains(k));
        }
        assertTrue("记一记列表有「设置」入口", src("app/src/com/onesay/assistant/v2/quicknote/ui/QuickNotePage.java").contains("mHost.openQuickNoteSettings()"));
        String con = src(CON + "Console.java");
        assertTrue("API Key 保存", con.contains("compiler.setApiKey(k.trim())"));
        assertTrue("邮箱入口", src(CON + "AdvancedPage.java").contains("MailSettingsActivity.class"));
        String dev = src(CON + "DeveloperPage.java");
        for (String k : new String[]{"FloatingSheetService.show(", "simulateKeyTrigger()", "\"play_test\", \"test_wake.wav\"", "V2SelfTest.run(",
                "CapabilityRegistry.getInstance()", "minedCount()"}) {
            assertTrue("开发者页：" + k, dev.contains(k));
        }
        // D6：以前只能 adb 改的开关
        assertTrue(src(PL + "S1Plugin.java").contains("S1Settings.KEY_CIRCLE") && src(PL + "S1Plugin.java").contains("S1Settings.KEY_PICKUP")
                && src(PL + "S1Plugin.java").contains("S1Settings.KEY_CLIP_CHIP") && src(PL + "S1Plugin.java").contains("S1Settings.KEY_CLIP_DEIXIS"));
        assertTrue(src(PL + "ScreenRefPlugin.java").contains("ScreenRefSettings.KEY_CHIPS") && src(PL + "ScreenRefPlugin.java").contains("ScreenRefSettings.KEY_VL"));
        assertTrue(src(PL + "ImageSavePlugin.java").contains("ImageSaveSettings.KEY_CACHE"));
    }

    @Test public void builtinPluginsAreV2() {
        List<String> legacy = new ArrayList<String>();
        for (FeaturePlugin p : FeatureKernel.get().plugins()) if (!p.consoleV2()) legacy.add(p.id());
        assertEquals("只有示例插件 hello 还走 v1 卡片（画在「开发者 › 诊断与摘要 › 其他」）", java.util.Arrays.asList("hello"), legacy);
        String feats = src(CON + "GroupPages.java");
        assertTrue("v1 插件仍然有地方画", feats.contains("addConsoleCards(c, legacy)"));
    }

    @Test public void killSwitchListComplete() {
        String dev = src(CON + "DeveloperPage.java");
        int n = 0;
        for (String f : DeveloperPageNames.FILES) {
            assertTrue("开发者页列出 " + f, dev.contains("\"" + f + "\""));
            n++;
        }
        assertEquals(20, n);
    }

    /** 方案 §5 的 20 个功能级 *.off（和各 *Settings 里的名字一致）。 */
    static final class DeveloperPageNames {
        static final String[] FILES = {"s1.off", "capsule.off", "pickup.off", "circle.off", "clipchip.off", "s2.off", "memory.off",
                "habit.off", "ledger.off", "memory.implicit.off", "s3.off", "todo.off", "diag.off", "summary.off", "island.off", "screenref.off",
                "imgsave.off", "imgsave.cache.off", "quicknote.off", "hello.off"};
    }

    @Test public void noNewActivityNoFragment() {
        for (File f : new File(System.getProperty("bixby.root", "."), CON).listFiles()) {
            String s = src(CON + f.getName());
            assertFalse(f.getName() + " 不能是 Activity", s.contains("extends Activity"));
            assertFalse(f.getName() + " 不用 Fragment", s.contains("extends Fragment") || s.contains("FragmentManager") || s.contains("import android.app.Fragment"));
        }
        assertTrue("切页弹簧 = 岛的弹簧", src(CON + "ConsoleNav.java").contains("new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA)"));
    }

    @Test public void acceptanceScriptUsesNewPaths() {
        String a = src("acceptance/device_acceptance.sh");
        assertFalse("旧小节名不再出现", a.contains("体验实验开关（需真机验证）") || a.contains("「默认应用」卡片") || a.contains("用 root 加入」按钮"));
        assertTrue(a.contains("「语音 › 唤醒手感」") && a.contains("「语音 › 回应」") && a.contains("「记忆 › 默认应用」")
                && a.contains("「功能与权限 › 电池优化白名单」"));
        assertTrue("IN-3 仍靠 am start MainActivity 拉起服务", a.contains("WakeService 运行（经控制台拉起）"));
        assertTrue(com.onesay.assistant.v2.s3.S3WhitelistGuide.TEXT.contains("「功能与权限」→「电池优化白名单」点「加入」"));
    }
}
