package com.onesay.assistant.v2.brief;

import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.IslandHost;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** [brief] 晨间播报 v2 BRIEF_STATE → 灵动岛：解析 / 来源校验 / 上岛计划 / 接线。 */
public class BriefStateTest {

    static final String A = BriefState.ACTION;
    static final int UID = 10234;
    static final long NOW = 1_800_000_000_000L;

    static String shell(int uid, String state, String detail, long at) {
        return uid + "\n{\"state\":\"" + state + "\",\"detail\":\"" + detail + "\",\"at\":" + at + "}\n";
    }

    // ---------------------------------------------------------------- 解析

    @Test public void parsesEveryV2State() {
        assertEquals(BriefState.Phase.GENERATING, BriefState.parse(A, "GENERATING", "").phase);
        assertEquals("生成中", BriefState.parse(A, "GENERATING", "").text);
        assertEquals(BriefState.Phase.READY, BriefState.parse(A, "READY", "").phase);
        assertEquals("简报已生成", BriefState.parse(A, "READY", "").title);
        assertEquals(BriefState.Phase.PLAYING, BriefState.parse(A, "PLAYING", "durationMs=61234").phase);
        assertEquals("播放中", BriefState.parse(A, "PLAYING", "durationMs=61234").text);
        assertEquals("播放中 · 闹钟铃声", BriefState.parse(A, "PLAYING", "ringtone").text);
        assertEquals(BriefState.Phase.PLAYED, BriefState.parse(A, "DONE", "done").phase);
        assertEquals(BriefState.Phase.PLAYED, BriefState.parse(A, "DONE", "skipped_to_end").phase);
        assertEquals("简报播完了", BriefState.parse(A, "DONE", "done").title);
    }

    @Test public void failuresAreRecognised() {
        BriefState.Event g = BriefState.parse(A, "IDLE", "error:crash");
        assertEquals(BriefState.Phase.FAILED, g.phase);
        assertEquals("生成失败：程序出错", g.text);
        assertEquals("生成失败", BriefState.parse(A, "IDLE", "error:no_weather").text);
        assertEquals("播放失败：不是这次闹钟的稿子", BriefState.parse(A, "DONE", "invalid:STALE").text);
        assertEquals("播放失败：没有音频", BriefState.parse(A, "DONE", "no_audio").text);
        assertEquals(BriefState.Phase.FAILED, BriefState.parse(A, "DONE", "error:1/-1004").phase);
        assertEquals(BriefState.Phase.FAILED, BriefState.parse(A, "DONE", "error:ringtone").phase);
    }

    @Test public void ignoredAndForeign() {
        assertEquals(BriefState.Phase.IGNORE, BriefState.parse(A, "IDLE", "").phase);
        assertEquals(BriefState.Phase.IGNORE, BriefState.parse(A, "DETAIL", "0").phase);
        assertEquals(BriefState.Phase.IGNORE, BriefState.parse(A, "DUMP", "").phase);
        assertEquals(BriefState.Phase.IGNORE, BriefState.parse(A, "rm -rf /", "").phase);
        assertEquals(BriefState.Phase.IGNORE, BriefState.parse(A, null, null).phase);
        assertNull("action 不对 = 不是我们的广播", BriefState.parse("com.evil.BRIEF_STATE", "READY", ""));
        assertNull(BriefState.parse(null, "READY", ""));
    }

    @Test public void cleanStripsControlAndBidiAndCaps() {
        assertEquals("ab", BriefState.clean("a\n\u0000b"));
        assertEquals("ab", BriefState.clean("a\u202eb"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append('x');
        assertEquals(BriefState.MAX_TEXT, BriefState.clean(big.toString()).length());
        assertEquals("", BriefState.clean(null));
    }

    // ---------------------------------------------------------------- 来源校验

    @Test public void verifyPassesOnlyWhenFileMatches() {
        BriefState.Event ev = BriefState.parse(A, "PLAYING", "durationMs=60000");
        assertNull(BriefState.verifySource(shell(UID, "PLAYING", "durationMs=60000", NOW - 300), UID, ev, NOW));
    }

    @Test public void verifyRejectsSpoofs() {
        BriefState.Event ev = BriefState.parse(A, "READY", "");
        // 没装 v2 / uid 异常
        assertNotNull(BriefState.verifySource(shell(UID, "READY", "", NOW), -1, ev, NOW));
        assertNotNull(BriefState.verifySource(shell(1000, "READY", "", NOW), 1000, ev, NOW));
        // 读不到（没 root / 守护挂了）→ 拒绝（fail closed）
        assertNotNull(BriefState.verifySource(null, UID, ev, NOW));
        assertNotNull(BriefState.verifySource("", UID, ev, NOW));
        // 状态文件属主不是 v2
        assertNotNull(BriefState.verifySource(shell(10999, "READY", "", NOW), UID, ev, NOW));
        // 文件里的状态和广播不一致（别的 App 冒充发了 READY，v2 实际在 GENERATING）
        assertNotNull(BriefState.verifySource(shell(UID, "GENERATING", "", NOW), UID, ev, NOW));
        // detail 不一致
        BriefState.Event fail = BriefState.parse(A, "IDLE", "error:crash");
        assertNotNull(BriefState.verifySource(shell(UID, "IDLE", "error:other", NOW), UID, fail, NOW));
        // 过期（重放一条旧状态）
        assertNotNull(BriefState.verifySource(shell(UID, "READY", "", NOW - BriefState.FRESH_MS - 1), UID, ev, NOW));
        assertNotNull(BriefState.verifySource(shell(UID, "READY", "", 0), UID, ev, NOW));
        // 输出乱码
        assertNotNull(BriefState.verifySource(UID + "\nnot json", UID, ev, NOW));
        assertNotNull(BriefState.verifySource("abc\n{}", UID, ev, NOW));
        assertNotNull(BriefState.verifySource(null, UID, null, NOW));
    }

    @Test public void verifyCommandIsConstantAndReadOnly() {
        assertEquals("stat -c %u /data/data/com.onesay.clock/files/state.json && cat /data/data/com.onesay.clock/files/state.json",
                BriefState.VERIFY_CMD);
        assertFalse(BriefState.VERIFY_CMD.contains("{"));
        // 接收端只用常量命令，不把广播内容拼进 shell
        String r = KernelWiring.src("app/src/com/onesay/assistant/v2/brief/BriefStateReceiver.java");
        assertTrue(r.contains("execute(BriefState.VERIFY_CMD)"));
        assertEquals("只有一处 shell 调用", 1, r.split("\\.execute\\(").length - 1);
    }

    // ---------------------------------------------------------------- 上岛计划

    static List<String> plan(BriefState.Phase last, String state, String detail) {
        List<BriefState.Step> s = BriefState.plan(last, BriefState.parse(A, state, detail));
        String[] out = new String[s.size()];
        for (int i = 0; i < s.size(); i++) out[i] = s.get(i).toString();
        return Arrays.asList(out);
    }

    @Test public void planTransitions() {
        assertEquals(Arrays.asList("REMOVE", "START:晨间简报", "STEP:生成中"), plan(BriefState.Phase.IGNORE, "GENERATING", ""));
        assertEquals("运行 → 运行只换文字", Arrays.asList("STEP:播放中"), plan(BriefState.Phase.GENERATING, "PLAYING", "durationMs=1"));
        assertEquals(Arrays.asList("REMOVE", "START:晨间简报", "STEP:播放中"), plan(BriefState.Phase.READY, "PLAYING", "durationMs=1"));
        assertEquals(Arrays.asList("REMOVE", "START:简报已生成", "END_OK"), plan(BriefState.Phase.GENERATING, "READY", ""));
        assertEquals(Arrays.asList("REMOVE", "START:简报播完了", "END_OK"), plan(BriefState.Phase.PLAYING, "DONE", "done"));
        assertEquals(Arrays.asList("REMOVE", "START:晨间简报", "END_FAIL:生成失败：程序出错"),
                plan(BriefState.Phase.GENERATING, "IDLE", "error:crash"));
        assertTrue(plan(BriefState.Phase.PLAYING, "IDLE", "").isEmpty());
        assertTrue(plan(BriefState.Phase.PLAYING, "DETAIL", "1").isEmpty());
        assertTrue(BriefState.plan(BriefState.Phase.IGNORE, null).isEmpty());
    }

    /** 计划在真实 IslandModel 上的效果：生成中 / 播放中 = RUN，已生成 / 播完 = DONE，失败 = FAIL + 原因。 */
    @Test public void planOnRealIslandModel() {
        IslandModel m = new IslandModel();
        m.configure(true, true);
        m.setMode(IslandModel.Mode.ISLAND);
        String id = BriefState.ISLAND_ID;
        BriefState.Phase last = BriefState.Phase.IGNORE;
        String[][] seq = { { "GENERATING", "" }, { "READY", "" }, { "PLAYING", "durationMs=5" }, { "DONE", "error:1/2" } };
        IslandModel.Status[] expect = { IslandModel.Status.RUN, IslandModel.Status.DONE, IslandModel.Status.RUN, IslandModel.Status.FAIL };
        for (int i = 0; i < seq.length; i++) {
            BriefState.Event ev = BriefState.parse(A, seq[i][0], seq[i][1]);
            for (BriefState.Step s : BriefState.plan(last, ev)) {
                switch (s.op) {
                    case REMOVE: m.removeBg(id); break;
                    case START: m.bgStarted(id, s.text, 1000L * i); break;
                    case STEP: m.bgStep(id, s.text); break;
                    case END_OK: m.bgEnded(id, true, null, 1000L * i); break;
                    case END_FAIL: m.bgEnded(id, false, s.text, 1000L * i); break;
                    default: break;
                }
            }
            last = ev.phase;
            assertEquals(seq[i][0], expect[i], m.bgStatus(id));
        }
        IslandModel.Snapshot v = m.view();
        assertEquals("失败展开，标出原因", id, v.key);
        assertEquals("播放失败", v.failReason);
        assertEquals(1, m.bgCount());
    }

    // ---------------------------------------------------------------- 接线 / 开关

    @Test public void pluginRegisteredWithSwitchAndBgKind() {
        FeaturePlugin p = FeatureKernel.get().find("brief");
        assertNotNull(p);
        assertEquals("bixby_brief", p.prefsName());
        assertEquals(Arrays.asList("brief.off"), Arrays.asList(p.killFiles()));
        assertTrue(p.defaultOn());
        assertTrue("开关关 = 内核丢弃岛操作", p.kernelGated());
        assertEquals(Arrays.asList(IslandHost.KIND_BG), Arrays.asList(p.islandActivityKinds()));
    }

    @Test public void manifestDeclaresOnlyTheBriefAction() {
        String mf = KernelWiring.src("app/AndroidManifest.xml");
        int at = mf.indexOf("com.onesay.assistant.v2.brief.BriefStateReceiver");
        assertTrue(at > 0);
        String block = mf.substring(at, mf.indexOf("</receiver>", at));
        assertTrue(block.contains("<action android:name=\"com.onesay.clock.BRIEF_STATE\" />"));
        assertEquals("只认一个 action", 1, block.split("<action ").length - 1);
        assertTrue("在 DEBUG-ONLY 段之外（release 也有）", at < mf.indexOf("DEBUG-ONLY-BEGIN"));
    }

    @Test public void receiverChecksSwitchAndVerifiesBeforeIsland() {
        String r = KernelWiring.src("app/src/com/onesay/assistant/v2/brief/BriefStateReceiver.java");
        int sw = r.indexOf("SWITCH.on(app)");
        int ver = r.indexOf("BriefState.verifySource(");
        int isl = r.indexOf("BriefIsland.apply(");
        assertTrue(sw > 0 && ver > sw && isl > ver);
        assertTrue("校验没过直接 return", r.substring(ver, isl).contains("return;"));
        assertTrue("root 读在后台线程", r.contains("goAsync()") && r.contains("Threads.start("));
    }

    @Test public void stopOnIslandGoesToBriefOnlyWithoutReplacingS3() {
        String ic = KernelWiring.src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertTrue(ic.contains("public void setScopedListener("));
        assertTrue("「停止」按活动归属分发", ic.contains("listenerFor(id)"));
        String bi = KernelWiring.src("app/src/com/onesay/assistant/v2/brief/BriefIsland.java");
        assertTrue(bi.contains("setScopedListener(LISTENER)"));
        assertFalse("不能用 setListener（会顶掉 S3 的默认回调）", bi.contains("ch.setListener("));
    }
}
