package com.onesay.assistant.v2.pclink;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** [rel-1123 合并] 手机确认卡上的「允许本次任务」（只认真人手指；危险项电脑不给这个按钮）；看已装 App 默认关。 */
public class Merge1123PhoneTest {

    static long now = 1_000_000L;
    final List<String> frames = new CopyOnWriteArrayList<String>();
    final PcLanClient.Peer peer = new PcLanClient.Peer("phone-1", "pc-1", new byte[32], "127.0.0.1", 47920, "");

    @Before public void setUp() {
        PcConfirm.reset();
        PcConfirm.clock = new PcConfirm.Clock() {
            @Override public long now() { return now; }
        };
        PcConfirm.ui = new PcConfirm.Ui() {
            @Override public void open(PcConfirm.Card c) { }

            @Override public void close(PcConfirm.Card c) { }
        };
    }

    @After public void tearDown() {
        PcConfirm.reset();
        PcConfirm.ui = null;
        PcConfirm.clock = new PcConfirm.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        };
    }

    PcConfirm.Reply reply() {
        return new PcConfirm.Reply() {
            @Override public void send(PcConfirm.Card c, boolean allow, boolean always, boolean remember, long shownMs) {
                frames.add(PcRx.answerFrame(peer, c, allow, always, remember, shownMs, now));
            }
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(String frame) throws Exception {
        return (Map<String, Object>) PcJson.obj(frame).get("body");
    }

    PcConfirm.Card shownCard(boolean task, long pcId) {
        PcConfirm.Card c = PcConfirm.offer("pc-1", pcId, "tok-0123456789abcdef", "电脑想在桌面建文件夹", "", false, task, 60_000, reply());
        assertNotNull(c);
        PcConfirm.shown(c.id, true);
        now += 800;
        return c;
    }

    @Test public void taskScopeOnlyByHumanTouchAndOnlyWhenOffered() throws Exception {
        PcConfirm.Card c = shownCard(true, 1);
        assertTrue(c.task);
        assertEquals("语音 / 代点不算", PcConfirm.Answer.NOT_HUMAN, PcConfirm.answer(c.id, true, false, false, true, PcConfirm.Input.VOICE));
        assertEquals(PcConfirm.Answer.NOT_HUMAN, PcConfirm.answer(c.id, true, false, false, true, PcConfirm.Input.ACCESSIBILITY));
        PcConfirm.noteRobotInput();
        assertEquals("机器人刚注入过手势：不算", PcConfirm.Answer.NOT_HUMAN, PcConfirm.answer(c.id, true, false, false, true, PcConfirm.Input.TOUCH));
        now += 5_000;
        assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(c.id, true, false, false, true, PcConfirm.Input.TOUCH));
        assertEquals(1, frames.size());
        Map<String, Object> b = body(frames.get(0));
        assertEquals("task", b.get("scope"));
        assertEquals("touch", b.get("input"));
        // 电脑没给「允许本次任务」（危险项）：点了也只是允许一次
        frames.clear();
        PcConfirm.Card d = shownCard(false, 2);
        assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(d.id, true, false, false, true, PcConfirm.Input.TOUCH));
        assertNull(body(frames.get(0)).get("scope"));
        // 普通「允许一次」不带 scope；拒绝也不带
        frames.clear();
        PcConfirm.Card e = shownCard(true, 3);
        assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(e.id, false, false, false, true, PcConfirm.Input.UNKNOWN));
        assertNull(body(frames.get(0)).get("scope"));
        assertEquals(Boolean.FALSE, body(frames.get(0)).get("allow"));
    }

    @Test public void offerFrameCarriesTaskScope() throws Exception {
        // 电脑发来的 confirm_offer 带 task_scope → 手机卡上有「允许本次任务」
        PcConfirm.Card c = PcConfirm.offer("pc-1", 9, "tok-0123456789abcdef", "d", "", false, 60_000, reply());
        assertFalse("老调用口默认没有", c.task);
    }

    @Test public void appListDefaultOff() {
        assertFalse(PcRxPolicy.defaultOn(PcRxPolicy.APP_LIST));
        assertTrue("别的只读项照旧默认开", PcRxPolicy.defaultOn(PcRxPolicy.STATUS));
    }
}
