package com.onesay.assistant.v2.wake;

import static org.junit.Assert.*;

import android.content.Context;

import com.onesay.assistant.v2.brief.BriefControl;
import com.onesay.assistant.v2.brief.BriefControl.KeyDecision;
import com.onesay.assistant.v2.brief.BriefIsland;
import com.onesay.assistant.v2.brief.BriefRemote;
import com.onesay.assistant.v2.brief.BriefState;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 「简报播放中按 Bixby 键 = 只停简报、不唤醒」对所有唤醒 / 手势入口生效。
 *
 * <p>入口与来源（WakeService 里都汇到两个汇合点之一，汇合点第一行过 {@link WakeAdmission}）：
 * <pre>
 *   按键文件 短按 press        key-file          → requestWakeInternal（以前绕过了判断 = BUG-A1）
 *   按键文件 长按 long          key-gesture-long  → dispatchKeyGesture
 *   按键文件 长按后松手 up(hold) key-gesture-hold  → dispatchKeyGesture
 *   随口记收音（插件回调）       key-file-hold     → requestWakeInternal
 *   按键兜底 Activity           key-activity      → requestWake → requestWakeInternal
 *   守护 v7 冷启动 -e gesture long key-gesture-long（seq 0）→ dispatchKeyGesture
 *   开发页模拟按键              key-sim           → requestWakeInternal
 *   唤醒词 KWS/ASR             voice             → requestWakeInternal（放行）
 *   冷启动排队补发              原来源             → requestWakeInternal
 * </pre>
 */
public class BriefKeyGateTest {

    final List<String> sent = new ArrayList<String>();

    @Before public void setUp() {
        BriefIsland.resetForTestPublic();
        BriefRemote.setSinkForTest(new BriefRemote.Sink() {
            @Override public boolean exec(Context ctx, String cmd) {
                sent.add(cmd);
                return true;
            }
        });
    }

    @After public void tearDown() {
        BriefRemote.setSinkForTest(null);
        BriefIsland.resetForTestPublic();
    }

    private static void playing() {
        BriefIsland.apply(BriefState.parse(BriefState.ACTION, "PLAYING", "durationMs=60000"));
        assertTrue(BriefIsland.isPlayingNow(System.currentTimeMillis()));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private int stops() {
        int n = 0;
        for (String c : sent) if (c.endsWith("com.onesay.clock.STOP")) n++;
        return n;
    }

    /** 按键文件通道的一次事件（seq 按 WakeService 的取法）。 */
    private static boolean keyFile(String source, int seq) {
        return WakeAdmission.briefSwallows(null, source, WakeAdmission.seqFor(source, seq));
    }

    private static boolean gesture(String g, int seq) {
        return WakeAdmission.briefSwallows(null, WakeAdmission.gestureSource(g), seq);
    }

    // ------------------------------------------------------------------ 简报在播：短按

    @Test public void shortPressV8WhilePlaying_stopsBriefOnly() {
        playing();
        // 守护 v8：down（只预热，不过闸门）→ press 松手才唤醒
        assertTrue("短按 press（按键文件）：只停简报、不唤醒", keyFile(WakeService.SRC_KEY_FILE, 7));
        assertEquals(1, stops());
        assertFalse(BriefIsland.isPlayingNow(now()));
        assertTrue("同一次按键的 Activity 通道：一并吞掉", WakeAdmission.briefSwallows(null, WakeService.SRC_KEY_ACTIVITY, 0));
        assertEquals("不重复发 STOP", 1, stops());
    }

    @Test public void shortPressOldDaemonWhilePlaying_stopsBriefOnly() {
        playing();
        assertTrue("旧守护 v5/v6（没有 seq）", keyFile(WakeService.SRC_KEY_FILE, 0));
        assertEquals(1, stops());
        assertTrue("Activity 通道（时间去抖）", WakeAdmission.briefSwallows(null, WakeService.SRC_KEY_ACTIVITY, 0));
        assertEquals(1, stops());
    }

    @Test public void activityFirstThenFile_sameKeyBothSwallowed() {
        playing();
        assertTrue(WakeAdmission.briefSwallows(null, WakeService.SRC_KEY_ACTIVITY, 0));
        assertTrue("文件通道后到：Activity 那边不知道 seq → 时间去抖吞掉", keyFile(WakeService.SRC_KEY_FILE, 12));
        assertEquals(1, stops());
    }

    @Test public void simulatedKeyWhilePlaying_stopsBriefOnly() {
        playing();
        assertTrue("开发页模拟 Bixby 键（key-sim）同样只停简报", WakeAdmission.briefSwallows(null, WakeService.SRC_KEY_SIM, 0));
        assertEquals(1, stops());
    }

    // ------------------------------------------------------------------ 简报在播：长按

    @Test public void longPressV8WhilePlaying_stopsBriefOnly_noPlugin() {
        playing();
        // 守护 v8：down → long（不交记一记）→ 按住 ≥ 2 s 松手 up（不交随口记）
        assertTrue("长按 long：只停简报，不交插件", gesture("long", 8));
        assertEquals(1, stops());
        assertTrue("同一次长按松手 hold：吞掉", gesture("hold", 8));
        assertTrue("同一次按键的随口记收音（key-file-hold）：吞掉", keyFile(WakeService.SRC_KEY_FILE + "-hold", 8));
        assertEquals("只发一次 STOP", 1, stops());
    }

    @Test public void longPressV7WhilePlaying_pressThenLong() {
        playing();
        // 守护 v7：按下就写 press，满 800 ms 再写 long
        assertTrue(keyFile(WakeService.SRC_KEY_FILE, 9));
        assertTrue("同一次按键的 long：吞掉，不交记一记", gesture("long", 9));
        assertEquals(1, stops());
    }

    @Test public void coldStartLongGestureWhilePlaying() {
        playing();
        assertTrue("守护 v7 冷启动 -e gesture long（没 seq）", gesture("long", 0));
        assertEquals(1, stops());
    }

    @Test public void sameSeqSwallowedBeyondTimeWindow_pure() {
        long t = 100000L;
        // 长按 long 在 ~800 ms 停了简报；按住 3 s 才松手 → up 在停后 ~2.2 s 到，超过 1.5 s 时间去抖，靠 seq 认同一次
        assertEquals(KeyDecision.SWALLOW_DUP, BriefControl.onKey(true, false, t, 8, 8, t + 2200));
        assertEquals(KeyDecision.SWALLOW_DUP, BriefControl.onKey(true, false, t, 8, 8, t + BriefControl.KEY_SAME_PRESS_MS));
        assertEquals("seq 复用上限（守护重启）", KeyDecision.PASS, BriefControl.onKey(true, false, t, 8, 8, t + BriefControl.KEY_SAME_PRESS_MS + 1));
        assertEquals("同 seq 但简报又在播（守护重启复用 seq）超时后：照停", KeyDecision.STOP_AND_SWALLOW,
                BriefControl.onKey(true, true, t, 8, 8, t + BriefControl.KEY_SAME_PRESS_MS + 1));
        // 不同 seq = 新的一次按键：不套时间去抖
        assertEquals(KeyDecision.PASS, BriefControl.onKey(true, false, t, 8, 9, t + 300));
        // 有一边不知道 seq：时间去抖（原行为）
        assertEquals(KeyDecision.SWALLOW_DUP, BriefControl.onKey(true, false, t, 8, 0, t + 300));
        assertEquals(KeyDecision.SWALLOW_DUP, BriefControl.onKey(true, false, t, 0, 9, t + 300));
        assertEquals(KeyDecision.PASS, BriefControl.onKey(true, false, t, 0, 9, t + BriefControl.KEY_DEDUP_MS + 1));
        // 旧 4 参版本 = seq 都不知道
        assertEquals(BriefControl.onKey(true, false, t, t + 300), BriefControl.onKey(true, false, t, 0, 0, t + 300));
        assertEquals(KeyDecision.PASS, BriefControl.onKey(false, true, t, 8, 8, t + 1));
    }

    @Test public void newPressRightAfterStop_wakesNormally() {
        playing();
        assertTrue(keyFile(WakeService.SRC_KEY_FILE, 10));
        assertFalse("停完简报紧接着再按一次（新 seq）= 正常唤醒", keyFile(WakeService.SRC_KEY_FILE, 11));
        assertFalse("长按也一样", gesture("long", 12));
        assertEquals(1, stops());
    }

    // ------------------------------------------------------------------ 简报没在播：照常

    @Test public void notPlaying_allEntriesWakeNormally() {
        assertFalse(keyFile(WakeService.SRC_KEY_FILE, 1));
        assertFalse(keyFile(WakeService.SRC_KEY_FILE, 0));
        assertFalse(keyFile(WakeService.SRC_KEY_FILE + "-hold", 2));
        assertFalse(gesture("long", 3));
        assertFalse(gesture("hold", 3));
        assertFalse(gesture("long", 0));
        assertFalse(WakeAdmission.briefSwallows(null, WakeService.SRC_KEY_ACTIVITY, 0));
        assertFalse(WakeAdmission.briefSwallows(null, WakeService.SRC_KEY_SIM, 0));
        assertFalse(WakeAdmission.briefSwallows(null, WakeService.SRC_VOICE, 0));
        assertTrue("没发任何简报命令", sent.isEmpty());
    }

    @Test public void briefFinished_keyWakesNormally() {
        playing();
        BriefIsland.apply(BriefState.parse(BriefState.ACTION, "DONE", ""));
        assertFalse(BriefIsland.isPlayingNow(now()));
        assertFalse("播完了：按键照常唤醒", keyFile(WakeService.SRC_KEY_FILE, 5));
        assertFalse(gesture("long", 6));
        assertTrue(sent.isEmpty());
    }

    // ------------------------------------------------------------------ 唤醒词： 规则

    @Test public void wakeWordWhilePlaying_wakesThenVoiceRulesApply() {
        playing();
        assertFalse("唤醒词照常唤醒（不停简报）", WakeAdmission.briefSwallows(null, WakeService.SRC_VOICE, 0));
        assertFalse("「✦ 语音」继续聆听也不拦", WakeAdmission.briefSwallows(null, WakeService.SRC_VOICE + "-ui", 0));
        assertTrue(sent.isEmpty());
        assertTrue("简报还在播", BriefIsland.isPlayingNow(now()));
        // 唤醒后说的 4 条裸短句由 BriefPlugin（L0）按 BriefIsland 的活跃 / 在播接管
        long t = now();
        boolean active = BriefIsland.isActive(t), play = BriefIsland.isPlayingNow(t);
        assertEquals("STOP", BriefControl.parse("停", active, play).toString());
        assertEquals("SKIP", BriefControl.parse("跳过", active, play).toString());
        assertEquals("REPLAY", BriefControl.parse("再播一遍", active, play).toString());
        assertEquals("DETAIL#1", BriefControl.parse("第二条详细说说", active, play).toString());
        assertNull("不是简报控制的话照常走路由", BriefControl.parse("今天天气怎么样", active, play));
    }

    @Test public void wakeWordAfterKeyStop_bareStopNotTaken_othersStillActive() {
        playing();
        assertTrue(keyFile(WakeService.SRC_KEY_FILE, 21));
        long t = now();
        boolean active = BriefIsland.isActive(t), play = BriefIsland.isPlayingNow(t);
        assertFalse(play);
        assertTrue("刚有过简报事件 → 仍活跃", active);
        assertNull("裸「停」只在正在播时归简报", BriefControl.parse("停", active, play));
        assertEquals("REPLAY", BriefControl.parse("再播一遍", active, play).toString());
        assertEquals("点名简报的「停」总是接管", "STOP", BriefControl.parse("停止播报", active, play).toString());
    }

    @Test public void wakeWordWhenBriefInactive_bareShortPhrasesNotTaken() {
        long t = now();
        boolean active = BriefIsland.isActive(t), play = BriefIsland.isPlayingNow(t);
        assertFalse(active);
        assertNull(BriefControl.parse("停", active, play));
        assertNull(BriefControl.parse("跳过", active, play));
        assertNull(BriefControl.parse("再播一遍", active, play));
        assertNull(BriefControl.parse("详细说说", active, play));
        assertEquals("点名了新闻 → 总是接管", "SKIP", BriefControl.parse("跳过这条新闻", active, play).toString());
    }

    // ------------------------------------------------------------------ seq 取法

    @Test public void seqOnlyTrustedOnKeyFileChannel() {
        assertEquals(7, WakeAdmission.seqFor(WakeService.SRC_KEY_FILE, 7));
        assertEquals(7, WakeAdmission.seqFor(WakeService.SRC_KEY_FILE + "-hold", 7));
        assertEquals("Activity 通道的 seq 不可信（可能是上一次按键的）", 0, WakeAdmission.seqFor(WakeService.SRC_KEY_ACTIVITY, 7));
        assertEquals(0, WakeAdmission.seqFor(WakeService.SRC_KEY_SIM, 7));
        assertEquals(0, WakeAdmission.seqFor(WakeService.SRC_VOICE, 7));
        assertEquals(0, WakeAdmission.seqFor(null, 7));
        assertEquals(0, WakeAdmission.seqFor(WakeService.SRC_KEY_FILE, -3));
        assertEquals("key-gesture-long", WakeAdmission.gestureSource("long"));
        assertTrue("手势来源仍是按键类", WakeAdmission.gestureSource("hold").startsWith("key-"));
    }

    // ------------------------------------------------------------------ 接线：唯一闸门 + 每个入口都经过汇合点

    private static String body(String src, String sig) {
        int i = src.indexOf(sig);
        assertTrue("找不到 " + sig, i > 0);
        int b = src.indexOf('{', i), depth = 0;
        for (int k = b; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(b, k + 1);
        }
        throw new AssertionError("括号不配对：" + sig);
    }

    private static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test public void wiring_singleGateAtConvergencePoints() {
        String w = KernelWiring.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        // 唤醒汇合点：第一行就是简报闸门，先于暂停 / 冷启动排队 / 声纹互斥 / 打断 TTS / 开窗 / 应答音
        String rwi = body(w, "private synchronized void requestWakeInternal(String source, boolean conversation)");
        int gate = rwi.indexOf("if (briefSwallowsKey(source)) return;");
        assertTrue("闸门在 requestWakeInternal 最前面", gate > 0 && gate < rwi.indexOf("pausedBlocks(source)"));
        for (String later : new String[] {"if (!sMicReady)", "mVpRecording || mEnrollSession", "TtsSpeaker.stopAll()",
                "armCaptureWindow(now, wantWindow)", "playWakeSounds(alt)", "mLastWakeSource = source"}) {
            assertTrue("闸门先于 " + later, rwi.indexOf(later) > gate);
        }
        assertEquals("briefSwallowsKey 只在 requestWakeInternal 调一次", 2, count(w, "briefSwallowsKey("));
        assertTrue(body(w, "private boolean briefSwallowsKey(String source)")
                .contains("WakeAdmission.briefSwallows(this, source, WakeAdmission.seqFor(source, mKeySeq))"));
        // 手势汇合点
        String dkg = body(w, "static void dispatchKeyGesture(final Context app, final String gesture, final int seq)");
        int g = dkg.indexOf("WakeAdmission.briefSwallows(app, WakeAdmission.gestureSource(gesture), seq)");
        assertTrue("手势闸门在交插件之前", g > 0 && g < dkg.indexOf("FeatureKernel.get().onKeyGesture(app, gesture)"));
        assertEquals("WakeService 里闸门只有两处（唤醒汇合点 + 手势汇合点）", 2, count(w, "WakeAdmission.briefSwallows("));
        // 旧位置拿掉了：requestWake 不再单独判断（不能各入口各一份）
        String rw = body(w, "public static void requestWake(String source, boolean conversation)");
        assertFalse(rw.contains("onKeyWake") || rw.contains("briefSwallows"));
        assertTrue("requestWake 仍汇入 requestWakeInternal", rw.contains("s.requestWakeInternal(source, conversation)"));
    }

    @Test public void wiring_everyEntryGoesThroughConvergencePoint() {
        String w = KernelWiring.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        // 唤醒入口都调 requestWakeInternal（短按 / 随口记收音 / 模拟键 / 唤醒词 / 冷启动补发 / requestWake）
        // [ci-1114] 开源版没有 厂商键守护的按键文件短按热路径了：原始按键双击 → Entries.fire(KEY_WAKE) → Entries.doSummon
        //   → WakeService.requestWake → requestWakeInternal，仍过同一个简报闸门。按当前写法更新（调用点 7 → 6，闸门断言不放宽）
        assertTrue("原始按键双击走入口注册表", w.contains("com.onesay.assistant.v2.entry.Entries.fire(WakeService.this, com.onesay.assistant.v2.entry.BuiltinEntries.KEY_WAKE,"));
        assertTrue("入口唤起汇入 requestWake", KernelWiring.src("app/src/com/onesay/assistant/v2/entry/Entries.java").contains("WakeService.requestWake(source, false);"));
        assertTrue("随口记收音", w.contains("w.requestWakeInternal(SRC_KEY_FILE + \"-hold\", false);"));
        assertTrue("模拟按键", w.contains("requestWakeInternal(SRC_KEY_SIM, false);"));
        assertTrue("唤醒词", body(w, "private void wakeOnce()").contains("requestWakeInternal(SRC_VOICE, false);"));
        assertTrue("冷启动补发", w.contains("requestWakeInternal(it.value[0], \"1\".equals(it.value[1]));"));
        assertEquals("requestWakeInternal 的调用点（定义 1 + 5 处）没有新增绕路", 6,
                count(w, "requestWakeInternal(") - count(w, "requestWakeInternal()"));
        assertTrue(body(w, "private void onLongGesture(boolean silent, int seq)").contains("dispatchKeyGesture(getApplicationContext(), \"long\", seq);"));
        assertTrue("冷启动 Activity 的 2 参版本只转发", body(w, "public static void dispatchKeyGesture(final Context app, final String gesture)")
                .contains("dispatchKeyGesture(app, gesture, 0);"));
        // 按键兜底 Activity：短按走 requestWake，冷启动长按走 dispatchKeyGesture
        String act = KernelWiring.src("app/src/com/onesay/assistant/WebViewUiActivity.java");
        assertTrue(act.contains("WakeService.requestWake(WakeService.SRC_KEY_ACTIVITY, conversation);"));
        assertTrue(act.contains("WakeService.dispatchKeyGesture(getApplicationContext(), \"long\");"));
    }

    @Test public void wiring_noOtherCopiesOfTheJudgement() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        List<File> files = new ArrayList<File>();
        collect(new File(root, "app/src"), files);
        assertTrue(files.size() > 100);
        int gestureCalls = 0;
        for (File f : files) {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String name = f.getName();
            if (s.contains("BriefIsland.onKeyWake(")) {
                assertEquals("只有 WakeAdmission 调 BriefIsland.onKeyWake：" + f, "WakeAdmission.java", name);
            }
            if (s.contains("WakeAdmission.briefSwallows(")) {
                assertTrue("只有 WakeService 用闸门：" + f, name.equals("WakeService.java") || name.equals("WakeAdmission.java"));
            }
            // 调试驱动（v2/test，只进 debug 包）直接测插件，不算按键入口
            boolean testDriver = f.getPath().replace('\\', '/').contains("/v2/test/");
            if (!name.equals("FeatureKernel.java") && !testDriver) gestureCalls += count(s, "FeatureKernel.get().onKeyGesture(");
        }
        assertEquals("插件收按键手势只有 dispatchKeyGesture 一个口子", 1, gestureCalls);
        // ACTION_SHOW（面板 + 文字指令注入）不是按键 / 唤醒：不开麦、不过简报闸门；「停」之类由 L0  规则处理
        String fss = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        int a = fss.indexOf("} else if (ACTION_SHOW.equals(action)) {");
        String show = fss.substring(a, fss.indexOf("return START_STICKY;", a));
        assertFalse(show.contains("requestWake") || show.contains("WakeAdmission"));
        // L0 用的活跃 / 在播就是闸门用的同一份状态
        String plug = KernelWiring.plugin("BriefPlugin");
        assertTrue(plug.contains("BriefIsland.isActive(now), com.onesay.assistant.v2.brief.BriefIsland.isPlayingNow(now)"));
    }

    private static void collect(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }
}
