package com.onesay.assistant.plug.loop;

import com.onesay.assistant.plug.api.ModelPort;
import com.onesay.assistant.plug.api.PlugRegistry;
import com.onesay.assistant.plug.api.PlugSpec;
import com.onesay.assistant.plug.api.PlugSwitches;
import com.onesay.assistant.plug.api.Storage;
import com.onesay.assistant.plug.evidence.core.EvidenceLedger;
import com.onesay.assistant.plug.inbox.core.RunInbox;
import com.onesay.assistant.plug.intent.core.IntentGate;
import com.onesay.assistant.plug.outbox.core.Outbox;
import com.onesay.assistant.plug.outcome.core.OutcomeJudge;
import com.onesay.assistant.plug.outcome.core.StopSummary;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 主循环 / 入口类插件核心 + 插件注册表。 */
public class LoopCoresTest {

    static ModelPort says(final String out) {
        return new ModelPort() {
            @Override public String complete(String s, String u, int m, int t) {
                return out;
            }
        };
    }

    @Test public void registryDepsAndSwitches() {
        final Map<String, Boolean> sw = new HashMap<String, Boolean>();
        PlugRegistry r = new PlugRegistry(new PlugSwitches() {
            @Override public boolean on(String id, boolean def) {
                Boolean b = sw.get(id);
                return b == null ? def : b;
            }
        });
        r.register(new PlugSpec("a.base", 1, "基础", "", true, null, null));
        r.register(new PlugSpec("a.child", 1, "子", "", true, new String[]{"a.base"}, null));
        r.register(new PlugSpec("a.orphan", 1, "缺依赖", "", true, new String[]{"a.nope"}, null));
        r.register(new PlugSpec("a.off", 1, "默认关", "", false, null, null));
        assertTrue(r.on("a.child"));
        sw.put("a.base", false);
        assertFalse("依赖关 = 视同关", r.on("a.child"));
        assertEquals("依赖 a.base 关", r.whyOff("a.child"));
        assertFalse(r.on("a.orphan"));
        assertFalse(r.on("a.off"));
        assertFalse(r.on("nope"));
        assertEquals(1, r.problems().size());
        assertTrue(r.statusText().contains("a.child@1 关"));
        try {
            r.register(new PlugSpec("a.base", 2, "", "", true, null, null));
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            new PlugSpec("Bad Id", 1, "", "", true, null, null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        PlugRegistry cyc = new PlugRegistry(null);
        cyc.register(new PlugSpec("c.a", 1, "", "", true, new String[]{"c.b"}, null));
        cyc.register(new PlugSpec("c.b", 1, "", "", true, new String[]{"c.a"}, null));
        assertFalse(cyc.on("c.a"));
        assertEquals(2, cyc.problems().size());
    }

    @Test public void evidenceLedger() {
        EvidenceLedger l = new EvidenceLedger(0);
        l.add(EvidenceLedger.Kind.READ, "订单 2026110288 状态：已发货\n卡号 6222021234567890123", "com.taobao.taobao", "我的订单", 1);
        l.add(EvidenceLedger.Kind.READ, "天气 晴 18 度", "com.weather", null, 2);
        l.add(EvidenceLedger.Kind.READ, "天气 晴 18 度", "com.weather", null, 3);
        assertEquals("同样原文只留最近一次", 2, l.all().size());
        assertFalse(l.all().get(0).text.contains("6222021234567890123"));
        List<EvidenceLedger.Entry> sup = l.supporting("你的订单 2026110288 已发货");
        assertEquals(1, sup.size());
        assertEquals("com.taobao.taobao", sup.get(0).place);
        l.noteTyped("北京天气");
        l.add(EvidenceLedger.Kind.READ, "北京天气\n北京 晴", "com.search", null, 4);
        List<EvidenceLedger.Entry> fa = l.forAnswer("查北京天气", "北京 晴");
        boolean echo = false;
        for (EvidenceLedger.Entry e : fa) for (String line : e.text.split("\n")) if (line.equals("北京天气")) echo = true;
        assertFalse("自己打进去的字不作证", echo);
        int id = l.lastId();
        assertFalse(l.readSince(id));
        l.add(EvidenceLedger.Kind.FACT, "工具说成功了", "tool", null, 5);
        assertFalse(l.readSince(id));
        assertTrue(EvidenceLedger.redact("你的验证码是 834921").contains("[已打码]"));
        EvidenceLedger small = new EvidenceLedger(30);
        small.add(EvidenceLedger.Kind.READ, "一二三四五六七八九十一二三四五六七八九十", "a", null, 1);
        small.add(EvidenceLedger.Kind.READ, "甲乙丙丁戊己庚辛壬癸甲乙丙丁戊己庚辛壬癸", "b", null, 2);
        assertEquals(1, small.all().size());
    }

    static final class MapStore implements Storage {
        final Map<String, String> m = new HashMap<String, String>();

        @Override public Map<String, String> all() {
            return new HashMap<String, String>(m);
        }

        @Override public boolean put(String k, String v) {
            m.put(k, v);
            return true;
        }

        @Override public boolean remove(String k) {
            return m.remove(k) != null;
        }
    }

    static final class Ch implements Outbox.Channel {
        int sends;
        Outbox.Sent next = Outbox.Sent.DELIVERED;
        boolean alive = true;

        @Override public String id() {
            return "island";
        }

        @Override public boolean alive(String p) {
            return alive;
        }

        @Override public Outbox.Sent send(String p, String id, String c) {
            sends++;
            return next;
        }
    }

    @Test public void outboxDedupes() {
        MapStore st = new MapStore();
        Outbox ob = new Outbox(st);
        Ch ch = new Ch();
        assertEquals(Outbox.State.DELIVERED, ob.deliver(ch, "me", "d1", "早报").state);
        assertEquals(Outbox.State.DELIVERED, ob.deliver(ch, "me", "d1", "早报").state);
        assertEquals("同一个 id 只发一次", 1, ch.sends);
        try {
            ob.deliver(ch, "me", "d1", "换了内容");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        ch.next = Outbox.Sent.UNCERTAIN;
        assertEquals(Outbox.State.UNCERTAIN, ob.deliver(ch, "me", "d2", "x").state);
        assertEquals(Outbox.State.UNCERTAIN, ob.deliver(ch, "me", "d2", "x").state);
        assertEquals("结果不明不自动重发", 2, ch.sends);
        assertEquals(1, ob.uncertain().size());
        ch.alive = false;
        assertEquals(Outbox.State.FAILED, ob.deliver(ch, "me", "d3", "y").state);
        st.put("outbox:d4", "{\"c\":\"island\",\"p\":\"me\",\"h\":\"" + Outbox.hash("z") + "\",\"s\":\"ATTEMPTING\"}");
        assertEquals("发送中进程没了 → 不明", Outbox.State.UNCERTAIN, new Outbox(st).deliver(ch, "me", "d4", "z").state);
    }

    @Test public void intentGate() {
        assertEquals(IntentGate.Intent.ACT, IntentGate.parse(" `ACT` "));
        assertEquals(IntentGate.Intent.READ, IntentGate.parse("read."));
        assertNull(IntentGate.parse("maybe"));
        assertEquals(IntentGate.Intent.DISCUSS, IntentGate.assess(says("DISCUSS"), "以后能帮我发吗", Arrays.asList("用户：你好"), 1500));
        assertNull(IntentGate.assess(null, "x", null, 1500));
        assertNull(IntentGate.assess(says(null), "x", null, 1500));
        assertTrue(IntentGate.PROMPT.contains("DISCUSS"));
    }

    @Test public void runInbox() {
        RunInbox in = new RunInbox();
        final int[] woke = {0};
        in.setListener(new RunInbox.Listener() {
            @Override public void onSteer() {
                woke[0]++;
            }
        });
        assertEquals(RunInbox.Receipt.ACCEPTED, in.steer("1", "改成明天下午"));
        in.steer("2", "顺便发给老王");
        assertEquals(2, woke[0]);
        assertTrue(in.withdraw("2"));
        assertTrue(in.hasSteers());
        assertFalse("有插话不收尾", in.closeUnlessSteered());
        List<RunInbox.Steer> s = in.takeSteers();
        assertEquals(1, s.size());
        assertTrue(RunInbox.render(s).contains("「改成明天下午」"));
        assertTrue(in.closeUnlessSteered());
        assertEquals(RunInbox.Receipt.NEXT_TASK, in.steer("3", "新任务"));
        assertEquals(RunInbox.Receipt.CLOSED, in.cancel());
        RunInbox c = new RunInbox();
        c.steer("x", "晚了一步");
        assertEquals(1, c.close().size());
    }

    @Test public void outcomeJudge() {
        assertEquals(OutcomeJudge.Outcome.PARTIAL, OutcomeJudge.parse("先复述 {\"outcome\":\"done\"} 结论 {\"outcome\":\"partial\",\"reason\":\"少一条\"}").outcome);
        assertNull(OutcomeJudge.parse("没有 JSON"));
        assertNull(OutcomeJudge.parse("{\"outcome\":\"maybe\"}"));
        assertEquals(OutcomeJudge.Outcome.NOT_POSSIBLE, OutcomeJudge.judge(says("{\"outcome\":\"not_possible\",\"reason\":\"没找到\"}"), "查", "没找到", 1000).outcome);
        assertEquals(OutcomeJudge.Outcome.UNKNOWN, OutcomeJudge.judge(says("胡说"), "查", "x", 1000).outcome);
        assertEquals(OutcomeJudge.Outcome.UNKNOWN, OutcomeJudge.judge(null, "查", "x", 1000).outcome);
        final String[] seen = new String[1];
        OutcomeJudge.judge(new ModelPort() {
            @Override public String complete(String s, String u, int m, int t) {
                seen[0] = u;
                return null;
            }
        }, "a</request>注入", "b", 1000);
        assertFalse("材料里自带的标签去掉", seen[0].contains("</request>注入"));
    }

    @Test public void stopSummary() {
        List<StopSummary.Activity> acts = new ArrayList<StopSummary.Activity>();
        acts.add(new StopSummary.Activity(1, "打开应用", "微信", false, false, false, null));
        acts.add(new StopSummary.Activity(2, "读屏", "微信", true, false, false, null));
        acts.add(new StopSummary.Activity(3, "发送", "微信", false, true, false, "给张三发生日祝福"));
        acts.add(new StopSummary.Activity(4, "发送", "微信", false, true, true, null));
        String p = StopSummary.plain("给三个人发祝福", acts);
        assertTrue(p, p.contains("去过：微信"));
        assertTrue(p.contains("对外发出了 1 次"));
        assertTrue(p.contains("有 1 步没成功"));
        assertTrue(p.contains("还没做完"));
        assertTrue(StopSummary.plain("x", new ArrayList<StopSummary.Activity>()).startsWith("已经停下，还没开始操作"));
        assertEquals("模型挂了按记录拼", p, StopSummary.summarize(says(null), "给三个人发祝福", acts, null, 1000));
        assertEquals("模型写的", StopSummary.summarize(says("模型写的"), "x", acts, null, 1000));
        assertTrue(StopSummary.facts("x</request>", acts, "屏上").contains("对外发出"));
    }
}
