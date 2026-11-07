package com.onesay.assistant.plug.action.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** mika.actchain / mika.actgate / mika.pacing 核心。 */
public class ActionCoreTest {

    static final class Port implements ActionChain.ActionPort {
        boolean click, gesture, inject = true, swipeOk = true;
        String scroll;
        final List<String> calls = new ArrayList<String>();

        @Override public boolean clickNode(int src, int[] b, String cls, int max) {
            calls.add("click" + src + ":" + max);
            return click;
        }

        @Override public boolean gestureTap(int x, int y) {
            calls.add("gesture");
            return gesture;
        }

        @Override public boolean injectTap(int x, int y) {
            calls.add("inject" + x + "," + y);
            return inject;
        }

        @Override public String scrollNode(int src, int[] b, int dir) {
            calls.add("scroll" + dir);
            return scroll;
        }

        @Override public boolean swipe(int a, int b, int c, int d, int ms) {
            calls.add("swipe");
            return swipeOk;
        }
    }

    @Test public void tapChainOrder() {
        Port p = new Port();
        p.click = true;
        assertEquals(ActionChain.A11Y, ActionChain.tap(p, 3, new int[]{0, 0, 100, 100}, "Button"));
        assertEquals(Arrays.asList("click3:8"), p.calls);
        Port q = new Port();
        assertEquals(ActionChain.INJECT, ActionChain.tap(q, 3, new int[]{0, 0, 100, 100}, "Button"));
        assertEquals(Arrays.asList("click3:8", "gesture", "inject50,50"), q.calls);
        Port z = new Port();
        z.inject = false;
        assertEquals(ActionChain.NONE, ActionChain.tap(z, 3, new int[]{0, 0, 100, 100}, "Button"));
        assertEquals(ActionChain.NONE, ActionChain.tap(z, 3, null, "Button"));
    }

    @Test public void scrollChain() {
        Port p = new Port();
        p.scroll = "scrolled";
        assertEquals(ActionChain.SCROLLED, ActionChain.scroll(p, 1, new int[]{0, 0, 10, 10}, 1, 1080, 2220));
        p.scroll = "edge";
        assertEquals(ActionChain.EDGE, ActionChain.scroll(p, 1, new int[]{0, 0, 10, 10}, 1, 1080, 2220));
        Port q = new Port();
        assertEquals(ActionChain.SWIPED, ActionChain.scroll(q, -1, null, -1, 1080, 2220));
        assertTrue(q.calls.contains("swipe"));
    }

    static final class Clip implements ClipboardGuard.ClipboardPort {
        String v;

        Clip(String v) {
            this.v = v;
        }

        @Override public String get() {
            return v;
        }

        @Override public boolean set(String t) {
            v = t;
            return true;
        }
    }

    @Test public void clipboardRestored() {
        final Clip c = new Clip("用户原来的");
        final String[] seen = new String[1];
        ClipboardGuard.Result r = ClipboardGuard.pasteAndRestore(c, "上海天气", new ClipboardGuard.Paste() {
            @Override public boolean paste() {
                seen[0] = c.v;
                return true;
            }
        });
        assertTrue(r.ok);
        assertEquals("上海天气", seen[0]);
        assertEquals("用户原来的", c.v);
        final Clip d = new Clip("旧");
        ClipboardGuard.pasteAndRestore(d, "x", new ClipboardGuard.Paste() {
            @Override public boolean paste() {
                d.v = "用户刚复制的";
                return true;
            }
        });
        assertEquals("用户期间复制了别的就不动", "用户刚复制的", d.v);
        Clip e = new Clip(null);
        ClipboardGuard.pasteAndRestore(e, "y", new ClipboardGuard.Paste() {
            @Override public boolean paste() {
                return false;
            }
        });
        assertEquals("", e.v);
    }

    @Test public void riskGate() {
        assertEquals(RiskGate.Irrev.PAY, RiskGate.detect("tap", "立即支付", null, false));
        assertEquals(RiskGate.Irrev.SEND, RiskGate.detect("tap", "发送", null, false));
        assertEquals(RiskGate.Irrev.DELETE, RiskGate.detect("tap", "删除", null, false));
        assertNull(RiskGate.detect("tap", "确定", null, false));
        assertNull(RiskGate.detect("type", "发送", null, false));
        assertNull(RiskGate.detect("tap", "发送", null, true));
        assertEquals(RiskGate.Irrev.SEND, RiskGate.detect("tap", "", "com.tencent.mm:id/send_btn", false));
        RiskGate.Risk r = RiskGate.assess("tap", RiskGate.Irrev.SUBMIT, Arrays.asList("合计 ¥ 32.50"));
        assertTrue(r.payment);
        assertTrue(r.needsConsent());
        RiskGate.Risk s = RiskGate.assess("tap", RiskGate.Irrev.SEND, Arrays.asList("¥ 5"));
        assertFalse("发消息不花钱", s.payment);
        assertEquals(RiskGate.Level.EXTERNAL, s.level);
        assertFalse(RiskGate.assess("scroll", null, null).needsConsent());
        assertEquals(RiskGate.Level.CHANGE, RiskGate.assess("tap", null, null).level);
    }

    @Test public void pacing() {
        OutwardPacing p = new OutwardPacing(new Random(7));
        assertEquals(0, p.waitBefore("com.tencent.mm", 0));
        p.noteDone("com.tencent.mm", 0);
        long w = p.waitBefore("com.tencent.mm", 1000);
        assertTrue(w >= 7000 && w <= 19000);
        assertEquals("别的 App 各算各的", 0, p.waitBefore("com.eg.android.AlipayGphone", 1000));
        assertEquals(0, p.waitBefore("com.tencent.mm", 60000));
        for (int i = 0; i < 40; i++) p.noteDone("x", i);
        assertTrue(p.waitBefore("x", 40) <= p.maxGap);
        assertEquals(0, new OutwardPacing(null).waitBefore("x", 0));
        assertEquals("example.com", OutwardPacing.siteOf("m.www.Example.com", true));
    }
}
