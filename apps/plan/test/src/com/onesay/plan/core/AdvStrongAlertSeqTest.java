package com.onesay.plan.core;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 找茬 · 待办强提醒记录本（StrongAlert.Book）+ 并表同时提醒：对两条待办穷举长度 1–3 的事件序列
 * （1 号响、2 号响、音量键全部稍后、电源键全部停止、整屏页勾掉 1 号、勾掉 2 号、过 5 分钟、1 号发普通通知），
 * 每一步后和按类注释写的模型比对：谁在响、响的顺序（开始响的时刻 → 截止 → id）、合并后的标题 / 正文、没人管到时的那几条、
 * 稍后到点时刻；以及序列化 → 解析来回不丢状态。每个事件之间过 1 分钟。
 */
@RunWith(Parameterized.class)
public class AdvStrongAlertSeqTest {

    static final String[] OPS = {"ring1", "ring2", "snoozeAll", "stopAll", "done1", "done2", "wait5", "notify1"};
    static final long T0 = 1_794_000_000_000L, MIN = 60000L;

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        List<String> seqs = new ArrayList<String>();
        gen("", 0, seqs);
        for (String s : seqs) out.add(new Object[]{s});
        return out;
    }

    static void gen(String p, int len, List<String> out) {
        if (len > 0) out.add(p);
        if (len == 3) return;
        for (String o : OPS) gen(p.length() == 0 ? o : p + "," + o, len + 1, out);
    }

    private final String seq;

    public AdvStrongAlertSeqTest(String seq) {
        this.seq = seq;
    }

    static final class M {
        String state;
        long reminded, first, snz;
        boolean strong;
    }

    @Test public void sequence() {
        StrongAlert.Book b = new StrongAlert.Book();
        Map<Long, M> m = new HashMap<Long, M>();
        long now = T0;
        long[] due = {0, T0 + 30 * MIN, T0 + 10 * MIN};   // 2 号截止更早
        String[] title = {"", "交房租", "回邮件"};
        int step = 0;
        for (String op : seq.split(",")) {
            step++;
            now += MIN;
            String at = seq + " @" + step + " " + op;
            if (op.startsWith("ring")) {
                long id = op.charAt(4) - '0';
                b.ring(id, title[(int) id], due[(int) id], now);
                M x = m.get(id);
                if (x == null) { x = new M(); x.first = now; m.put(id, x); }
                else if (!("ringing".equals(x.state) || "snoozed".equals(x.state))) x.first = now;
                x.state = StrongAlert.RINGING;
                x.reminded = now;
                x.snz = 0;
                x.strong = true;
            } else if (op.equals("snoozeAll")) {
                List<StrongAlert.Entry> l = b.snoozeAll(now, 10, false);
                int n = 0;
                for (M x : m.values()) if (StrongAlert.RINGING.equals(x.state)) { x.state = StrongAlert.SNOOZED; x.snz = now + 10 * MIN; n++; }
                assertEquals(at, n, l.size());
            } else if (op.equals("stopAll")) {
                List<StrongAlert.Entry> l = b.stopAll(now, StrongAlert.STOPPED);
                int n = 0;
                for (M x : m.values()) if (StrongAlert.RINGING.equals(x.state)) { x.state = StrongAlert.STOPPED; x.snz = 0; n++; }
                assertEquals(at, n, l.size());
            } else if (op.startsWith("done")) {
                long id = op.charAt(4) - '0';
                b.complete(id, now);
                M x = m.get(id);
                if (x != null && StrongAlert.RINGING.equals(x.state)) x.state = StrongAlert.DONE;
            } else if (op.equals("wait5")) {
                now += 5 * MIN - MIN;
            } else if (op.equals("notify1")) {
                b.notified(1, title[1], due[1], now);
                M x = m.get(1L);
                if (x == null) { x = new M(); x.first = now; m.put(1L, x); x.state = StrongAlert.NOTIFIED; x.reminded = now; }
                else if (!("ringing".equals(x.state) || "snoozed".equals(x.state))) { x.first = now; x.state = StrongAlert.NOTIFIED; x.reminded = now; x.strong = false; x.snz = 0; }
            }
            // ---- 比对
            List<Long> ringing = new ArrayList<Long>();
            for (long id = 1; id <= 2; id++) {
                M x = m.get(id);
                StrongAlert.Entry e = b.get(id);
                if (x == null) { assertNull(at, e); continue; }
                assertNotNull(at, e);
                assertEquals(at + " #" + id, x.state, e.state);
                assertEquals(at + " #" + id + " reminded", x.reminded, e.remindedAt);
                assertEquals(at + " #" + id + " first", x.first, e.firstAt);
                assertEquals(at + " #" + id + " snz", x.snz, e.snoozeUntil);
                if (StrongAlert.RINGING.equals(x.state)) ringing.add(id);
            }
            // 顺序：开始响早的在前；同时开始按截止（2 号更早）
            if (ringing.size() == 2 && m.get(1L).reminded > m.get(2L).reminded) { ringing.clear(); ringing.add(2L); ringing.add(1L); }
            List<StrongAlert.Entry> all = b.ringingAll();
            assertEquals(at, ringing.size(), all.size());
            for (int i = 0; i < all.size(); i++) assertEquals(at, (long) ringing.get(i), all.get(i).todoId);
            assertEquals(at, !ringing.isEmpty(), b.anyRinging());
            if (ringing.isEmpty()) {
                assertNull(b.ringing());
                assertEquals(-1, b.nextTimeoutAt());
                assertEquals("", StrongAlert.ringTitle(all));
            } else {
                assertEquals((long) ringing.get(0), b.ringing().todoId);
                long first = Long.MAX_VALUE;
                for (long id : ringing) first = Math.min(first, m.get(id).reminded + StrongAlert.RING_TIMEOUT_MS);
                assertEquals(at, first, b.nextTimeoutAt());
                if (ringing.size() == 1) {
                    assertEquals(title[(int) (long) ringing.get(0)], StrongAlert.ringTitle(all));
                    assertEquals("强提醒 · 音量键稍后，电源键停止", StrongAlert.ringText(all));
                } else {
                    assertEquals("2 条待办到点了", StrongAlert.ringTitle(all));
                    assertEquals(title[(int) (long) ringing.get(0)] + "、" + title[(int) (long) ringing.get(1)], StrongAlert.ringText(all));
                }
            }
            int timedOut = 0;
            for (long id : ringing) if (m.get(id).reminded + StrongAlert.RING_TIMEOUT_MS <= now) timedOut++;
            assertEquals(at, timedOut, b.timedOut(now).size());
            long nextSnz = -1;
            for (M x : m.values()) if (StrongAlert.SNOOZED.equals(x.state) && x.snz > now && (nextSnz < 0 || x.snz < nextSnz)) nextSnz = x.snz;
            assertEquals(at, nextSnz, b.nextSnoozeAt(now));
            // 来回
            StrongAlert.Book r = StrongAlert.Book.parse(b.serialize());
            assertEquals(b.entries.size(), r.entries.size());
            for (StrongAlert.Entry e : b.entries) {
                StrongAlert.Entry y = r.get(e.todoId);
                assertEquals(e.state, y.state);
                assertEquals(e.remindedAt, y.remindedAt);
                assertEquals(e.snoozeUntil, y.snoozeUntil);
                assertEquals(e.title, y.title);
                assertEquals(e.strong, y.strong);
            }
        }
    }
}
