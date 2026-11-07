package com.onesay.assistant.v2.lanea;

import static org.junit.Assert.*;

import android.content.Context;

import com.onesay.assistant.v2.brief.BriefControl;
import com.onesay.assistant.v2.brief.BriefControl.Cmd;
import com.onesay.assistant.v2.brief.BriefDetail;
import com.onesay.assistant.v2.brief.BriefIsland;
import com.onesay.assistant.v2.brief.BriefRemote;
import com.onesay.assistant.v2.brief.BriefState;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** lane-a 第 2 项：4 条简报语音规则、岛上停止发 STOP、DETAIL 展开、播放中按键只停简报。 */
public class LaneABriefVoiceTest {

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

    private static String p(String s, boolean active, boolean playing) {
        BriefControl.Parsed r = BriefControl.parse(s, active, playing);
        return r == null ? null : r.toString();
    }

    @Test public void namedPhrasesAlwaysMatch() {
        assertEquals("REPLAY", p("再播一遍简报", false, false));
        assertEquals("SKIP", p("跳过这条新闻", false, false));
        assertEquals("STOP", p("停止播报", false, false));
        assertEquals("DETAIL#1", p("第二条新闻详细说说", false, false));
    }

    @Test public void barePhrasesOnlyWhenActive() {
        assertNull("不活跃：裸「再播一遍」不归简报（可能是音乐 / 别的）", p("再播一遍", false, false));
        assertEquals("REPLAY", p("再播一遍。", true, false));
        assertEquals("SKIP", p("跳过", true, true));
        assertEquals("SKIP", p("下一条吧", true, true));
        assertEquals("DETAIL", p("详细说说", true, false));
        assertEquals("DETAIL#2", p("第三条详细说说", true, false));
        assertEquals("DETAIL#0", p("第1条具体说说", true, false));
        assertNull("裸「停」只在正在播时", p("停", true, false));
        assertEquals("STOP", p("停", true, true));
        assertEquals("STOP", p("别播了", true, true));
        assertEquals("STOP", p("停一下", true, true));
        assertNull(p("打开蓝牙", true, true));
        assertNull("长句不当控制词", p("再播一遍昨天晚上那首很好听的歌给我听听吧", true, true));
    }

    @Test public void amCommandsAreConstantsOnly() {
        assertEquals("am broadcast -n com.onesay.clock/.V2ControlReceiver -a com.onesay.clock.REPLAY", BriefControl.amCommand(Cmd.REPLAY, -1));
        assertEquals("am broadcast -n com.onesay.clock/.V2ControlReceiver -a com.onesay.clock.SKIP", BriefControl.amCommand(Cmd.SKIP, 3));
        assertEquals("am broadcast -n com.onesay.clock/.V2ControlReceiver -a com.onesay.clock.STOP", BriefControl.amCommand(Cmd.STOP, -1));
        assertEquals("am broadcast -n com.onesay.clock/.V2ControlReceiver -a com.onesay.clock.DETAIL --es index 0", BriefControl.amCommand(Cmd.DETAIL, -1));
        assertEquals("am broadcast -n com.onesay.clock/.V2ControlReceiver -a com.onesay.clock.DETAIL --es index 9", BriefControl.amCommand(Cmd.DETAIL, 99));
        for (Cmd c : Cmd.values()) {
            String s = BriefControl.amCommand(c, 2);
            assertFalse(s.contains(";") || s.contains("$") || s.contains("`") || s.contains("|"));
        }
    }

    @Test public void activeWindow() {
        assertTrue(BriefControl.isActive(true, 0, 1000));
        assertFalse(BriefControl.isActive(false, 0, 1000));
        assertTrue(BriefControl.isActive(false, 1000, 1000 + BriefControl.ACTIVE_WINDOW_MS));
        assertFalse(BriefControl.isActive(false, 1000, 1001 + BriefControl.ACTIVE_WINDOW_MS));
    }

    @Test public void keyDecision() {
        assertEquals(BriefControl.KeyDecision.PASS, BriefControl.onKey(false, true, 0, 5000));
        assertEquals(BriefControl.KeyDecision.PASS, BriefControl.onKey(true, false, 0, 5000));
        assertEquals(BriefControl.KeyDecision.STOP_AND_SWALLOW, BriefControl.onKey(true, true, 0, 5000));
        assertEquals("同一次按键的另一路通道", BriefControl.KeyDecision.SWALLOW_DUP, BriefControl.onKey(true, false, 5000, 5800));
        assertEquals(BriefControl.KeyDecision.PASS, BriefControl.onKey(true, false, 5000, 5000 + BriefControl.KEY_DEDUP_MS + 1));
    }

    @Test public void keyWhilePlayingStopsBriefAndSwallowsWake() {
        BriefIsland.apply(BriefState.parse(BriefState.ACTION, "PLAYING", "durationMs=60000"));
        assertTrue(BriefIsland.isPlayingNow(System.currentTimeMillis()));
        assertFalse("语音唤醒不受影响", BriefIsland.onKeyWake(null, "voice"));
        assertTrue("按键：停简报、不唤醒", BriefIsland.onKeyWake(null, "key-file"));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).endsWith("com.onesay.clock.STOP"));
        assertTrue("同一次按键从 Activity 通道再到：也吞掉", BriefIsland.onKeyWake(null, "key-activity"));
        assertEquals("不重复发 STOP", 1, sent.size());
        assertFalse(BriefIsland.isPlayingNow(System.currentTimeMillis()));
    }

    @Test public void keyWhenNotPlayingWakesNormally() {
        assertFalse(BriefIsland.onKeyWake(null, "key-file"));
        assertTrue(sent.isEmpty());
    }

    @Test public void detailRequestIsOneShotAndTimed() {
        assertFalse("没发过 DETAIL：不认回执", BriefIsland.consumeDetailRequest(1000));
        BriefIsland.markDetailRequested(1000);
        assertTrue(BriefIsland.consumeDetailRequest(1000 + BriefIsland.DETAIL_REPLY_MS));
        assertFalse("一次请求只展开一次", BriefIsland.consumeDetailRequest(1001));
        BriefIsland.markDetailRequested(1000);
        assertFalse("过期", BriefIsland.consumeDetailRequest(1001 + BriefIsland.DETAIL_REPLY_MS));
    }

    @Test public void detailTitleAndPrompt() {
        assertEquals(0, BriefDetail.indexOf(""));
        assertEquals(2, BriefDetail.indexOf("2"));
        assertEquals(0, BriefDetail.indexOf("x; rm"));
        assertEquals("标题A", BriefDetail.pickTitle(0, "标题A", "标题B", null));
        assertEquals("标题B", BriefDetail.pickTitle(1, "标题A", "标题B", null));
        String json = "{\"news\":[\"n0\",\"n1\",\"第三条\\u202e反向\",\"n3\"]}";
        assertEquals("第三条反向", BriefDetail.pickTitle(2, "a", "b", json));
        assertEquals("l2 为空时读 brief.json", "n0", BriefDetail.pickTitle(0, "", "b", json));
        assertNull(BriefDetail.pickTitle(7, "a", "b", json));
        assertNull(BriefDetail.pickTitle(3, "a", "b", "not json"));
        String sys = BriefDetail.systemPrompt();
        assertTrue("外部数据中的指令不执行", sys.contains("一律忽略"));
        String u = BriefDetail.userPrompt("标题", null);
        assertTrue(u.contains("<标题>标题</标题>") && u.contains("没有检索到资料"));
        assertEquals("你好 世界", BriefDetail.tidy("**你好** https://x.y/z 世界"));
        assertTrue(BriefDetail.READ_CMD.equals("cat /data/data/com.onesay.clock/files/brief/brief.json"));
    }

    @Test public void wiring() {
        String isl = KernelWiring.src("app/src/com/onesay/assistant/v2/brief/BriefIsland.java");
        assertTrue("岛上停止真的发 STOP", isl.contains("BriefRemote.send(sApp, BriefControl.Cmd.STOP, -1)"));
        String w = KernelWiring.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        // ★ BUG-A1：闸门从 requestWake 挪到所有唤醒的汇合点 requestWakeInternal（详见 wake/BriefKeyGateTest）
        int i = w.indexOf("private synchronized void requestWakeInternal(String source, boolean conversation)");
        int k = w.indexOf("if (briefSwallowsKey(source)) return;", i);
        int pause = w.indexOf("if (pausedBlocks(source)) return;", i);
        assertTrue("按键停简报在唤醒汇合点最前面", i > 0 && k > i && k < pause);
        String rcv = KernelWiring.src("app/src/com/onesay/assistant/v2/brief/BriefStateReceiver.java");
        assertTrue(rcv.contains("BriefIsland.consumeDetailRequest(now)") && rcv.contains("BriefDetail.run("));
        assertTrue("DETAIL 也要过来源校验", rcv.indexOf("verifySource") < rcv.indexOf("BriefDetail.run("));
        String plug = KernelWiring.plugin("BriefPlugin");
        assertTrue(plug.contains("Invocation l0(") && plug.contains("BriefRemote.send(ctx, p.cmd, p.index)") && plug.contains("markDetailRequested"));
    }
}
