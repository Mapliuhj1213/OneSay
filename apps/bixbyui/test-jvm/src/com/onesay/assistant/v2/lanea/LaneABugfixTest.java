package com.onesay.assistant.v2.lanea;

import static org.junit.Assert.*;

import android.content.Context;

import com.onesay.assistant.v2.brief.BriefIsland;
import com.onesay.assistant.v2.brief.BriefState;
import com.onesay.assistant.v2.executor.ExecRequestFile;
import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.IslandHost;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** lane-a 第 1 项：B-9 简报清岛、exec 请求文件收尾、debug 唤醒服务通知标题。 */
public class LaneABugfixTest {

    static class Host implements IslandHost {
        final List<String> calls = new ArrayList<String>();
        @Override public Context hostContext() { return null; }
        @Override public boolean islandMode() { return true; }
        @Override public void setListener(String o, Listener l) { }
        @Override public void setScopedListener(String o, Listener l) { }
        @Override public void bgStarted(String o, String id, String t) { calls.add(o + ":start:" + id); }
        @Override public void bgStep(String o, String id, String t) { calls.add(o + ":step:" + id); }
        @Override public void bgConfirm(String o, String id, String t, String d) { }
        @Override public void bgConfirmResolved(String o, String id) { }
        @Override public void bgEnded(String o, String id, boolean ok, String r) { calls.add(o + ":end:" + id); }
        @Override public void remindFired(String o, String id, String t, long at, int late) { }
        @Override public void removeBg(String o, String id) { calls.add(o + ":remove:" + id); }
        @Override public void setTodos(String o, List<IslandModel.TodoRow> rows, int today, boolean chip) { }
        @Override public void setNoteListener(String o, NoteListener l) { }
        @Override public void noteStarted(String o, String id, String t) { }
        @Override public void noteResult(String o, String id, boolean ok, String t, String d, String m, List<String> a) { }
        @Override public boolean addCode(String o, String k, String c, String s, String p, long ttl, long n) { return false; }
        @Override public void removeCode(String o, String k, String c) { }
        @Override public void setNotices(String o, java.util.List<com.onesay.assistant.v2.island.IslandModel.NoticeRow> r, boolean a, boolean h) { }
        @Override public void setNoticeListener(String o, NoticeListener l) { }
        @Override public void setSuggestListener(String o, SuggestListener l) { }
        @Override public boolean suggestShow(String o, String id, String t, String sub, String th, List<String> a, long ttl) { return false; }
        @Override public void suggestUpdate(String o, String id, String sub) { }
        @Override public void suggestWithdraw(String o, String id) { }
    }

    static class Gated extends FeaturePlugin {
        boolean on;
        @Override public String id() { return "g"; }
        @Override public String title() { return "g"; }
        @Override public boolean enabled(Context ctx) { return on; }
    }

    private Host mAttached;

    @After public void detach() {
        if (mAttached != null) FeatureKernel.get().detachIsland(mAttached);
        mAttached = null;
    }

    @Test public void cleanupBgIgnoresSwitchButNormalOpsAreDropped() {
        FeatureKernel k = FeatureKernel.newEmpty();
        Gated g = new Gated();
        k.register(g);
        Host h = new Host();
        k.attachIsland(h);
        g.on = false;
        k.island("g").bgStarted("x", "t");
        k.island("g").removeBg("x");
        assertTrue("关着：普通岛操作被丢弃", h.calls.isEmpty());
        k.island("g").cleanupBg("x");
        assertEquals("撤下不受开关门控", "[g:remove:x]", h.calls.toString());
        k.detachIsland(h);
        k.island("g").cleanupBg("x");   // 没挂岛：空操作不抛
        assertEquals(1, h.calls.size());
    }

    @Test public void briefEventsWhileOffClearStaleIsland() {
        BriefIsland.clear();
        Host h = new Host();
        mAttached = h;
        FeatureKernel.get().attachIsland(h);
        // JVM 里 BriefPlugin.SWITCH.on(null) = false → 插件「关着」：apply 的岛操作被内核丢弃，但 phase 记住了
        BriefState.Event playing = BriefState.parse(BriefState.ACTION, "PLAYING", "durationMs=60000");
        BriefIsland.apply(playing);
        assertEquals(BriefState.Phase.PLAYING, BriefIsland.lastPhase());
        h.calls.clear();
        BriefState.Event done = BriefState.parse(BriefState.ACTION, "DONE", "done");
        assertTrue("kill-file 期间收到 DONE → 撤下岛上的「播放中」", BriefIsland.onEventWhileOff(done));
        assertEquals("[brief:remove:" + BriefState.ISLAND_ID + "]", h.calls.toString());
        assertEquals(BriefState.Phase.IGNORE, BriefIsland.lastPhase());
        assertFalse("岛上已没有简报：不重复撤", BriefIsland.onEventWhileOff(done));
    }

    @Test public void killWatchDecisionAndPlayingWindow() {
        assertTrue(BriefIsland.shouldClear(BriefState.Phase.PLAYING, false));
        assertTrue(BriefIsland.shouldClear(BriefState.Phase.GENERATING, false));
        assertFalse(BriefIsland.shouldClear(BriefState.Phase.PLAYING, true));
        assertFalse(BriefIsland.shouldClear(BriefState.Phase.IGNORE, false));
        assertEquals(60000L, BriefIsland.durationMs("durationMs=60000"));
        assertEquals(0L, BriefIsland.durationMs("ringtone"));
        assertEquals(0L, BriefIsland.durationMs("durationMs=abc"));
        assertEquals(1000L + 60000L + BriefIsland.PLAYING_GRACE_MS, BriefIsland.playingUntil(1000L, "durationMs=60000"));
        assertEquals(1000L + BriefIsland.PLAYING_FALLBACK_MS, BriefIsland.playingUntil(1000L, "ringtone"));
        assertTrue(BriefIsland.KILL_POLL_MS <= 1000L);   // 上机报告：touch 后 3 s 内必须撤下
    }

    @Test public void wiringPresence() {
        String plugin = KernelWiring.plugin("BriefPlugin");
        assertTrue("内核比对到关 → onDisable 撤岛", plugin.contains("onDisable(Context ctx)") && plugin.contains("BriefIsland.clear()"));
        String rcv = KernelWiring.src("app/src/com/onesay/assistant/v2/brief/BriefStateReceiver.java");
        int off = rcv.indexOf("BriefPlugin.SWITCH.on(app)");
        assertTrue(off > 0 && rcv.indexOf("BriefIsland.onEventWhileOff(ev)") > off);
        assertTrue("先解析再判开关（关着也要认出事件）", rcv.indexOf("BriefState.parse(") < off);
        String isl = KernelWiring.src("app/src/com/onesay/assistant/v2/brief/BriefIsland.java");
        assertTrue(isl.contains("channel().cleanupBg(BriefState.ISLAND_ID)"));
    }

    // ---- exec 请求文件 ----

    @Test public void abandonClearsOnlyOurOwnRequest() throws Exception {
        File d = Files.createTempDirectory("lanea-exec").toFile();
        File req = new File(d, ".bixby_req");
        write(req, "settings put global airplane_mode_on 1");
        assertEquals(ExecRequestFile.Abandon.CLEARED, ExecRequestFile.abandon(req, "settings put global airplane_mode_on 1"));
        assertEquals("清空后守护恢复也不会补跑", 0L, req.length());
        assertEquals(ExecRequestFile.Abandon.ALREADY_TAKEN, ExecRequestFile.abandon(req, "x"));
        write(req, "echo other");
        assertEquals("别的请求已覆盖：不动", ExecRequestFile.Abandon.REPLACED, ExecRequestFile.abandon(req, "echo mine"));
        assertEquals("echo other", new String(Files.readAllBytes(req.toPath()), "UTF-8"));
        assertEquals(ExecRequestFile.Abandon.ALREADY_TAKEN, ExecRequestFile.abandon(new File(d, "nope"), "x"));
        assertEquals(ExecRequestFile.Abandon.ERROR, ExecRequestFile.abandon(null, "x"));
    }

    @Test public void shellExecutorCallsAbandonInBothFailureBranches() {
        String s = KernelWiring.src("app/src/com/onesay/assistant/v2/executor/ShellExecutor.java");
        int pick = s.indexOf("特权执行守护未就绪\", false, true)");
        // 2026-10-25 exec 守护 v4：比对的是本次写入的请求体（签名请求时不等于命令原文）
        int a1 = s.indexOf("abandonRequest(req, res, body)");
        assertTrue("1.5 s 取件超时分支先收尾再报失败", a1 > 0 && a1 < pick);
        int to = s.indexOf("命令超时未返回");
        assertTrue("12 s 超时（没取走）分支同样收尾", s.indexOf("abandonRequest(req, res, body)", a1 + 1) < to);
        String d = KernelWiring.src("tools/kdaemon/exec_daemon.v3.sh");
        assertTrue(d.contains("stale \"$REQ\"") && d.contains("stale \"$sreq\"") && d.contains("STALE_S=${BIXBY_EXEC_STALE_S:-3}"));
        assertTrue("v2 仓库副本保持原样", KernelWiring.src("tools/kdaemon/exec_daemon.v2.sh").contains("exec-daemon v2 start"));
    }

    @Test public void wakeServiceNotificationTitleCarriesVariant() {
        String w = KernelWiring.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue(w.contains("b.setContentTitle(com.onesay.assistant.v2.BuildVariant.APP_TITLE_V2 + \" 语音与按键服务\")"));
        assertFalse(w.contains("\"OneSay 语音与按键服务\""));
        String dbg = KernelWiring.src("app/variant/debug/com/onesay/assistant/v2/BuildVariant.java");
        assertTrue("debug 包标题 = OneSay Debug 语音与按键服务", dbg.contains("APP_TITLE_V2 = \"OneSay Debug\""));
    }

    private static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f, false);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }
}
