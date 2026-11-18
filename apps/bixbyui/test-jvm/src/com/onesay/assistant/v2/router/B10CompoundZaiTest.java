package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ rel-1118 B10：「再 / ，另外 / 。」连接的多意图不丢后半句，笔记正文不被截成宾语（t20k router.multi-intent 7 条复现）。 */
public class B10CompoundZaiTest {

    static L0RuleRouter L0;
    static final CompoundSplitter.Resolver RES = new CompoundSplitter.Resolver() {
        @Override public Invocation resolve(String c) {
            if (c == null || c.trim().isEmpty()) return null;
            Invocation i = L0.route(c);
            return i != null && SearchRouting.dropL0(i.sign, c) ? null : i;
        }
    };

    @BeforeClass public static void init() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        L0 = L0RuleRouter.forRegistry(reg);
    }

    /** 零 LLM 流水线（同 UnifiedRouter.triage）：本地执行返回 Invocation，否则 null。 */
    static Invocation local(String q) {
        FollowupEdit.clearForTest();
        UnifiedRouter.Triage ft = FastPathTriage.triage(q, RES);
        if (ft != null) return ft.l0;
        Invocation l0 = FastPathTriage.l0WithCore(q, RES);
        TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(l0 != null ? l0.sign : null, false, false));
        switch (d.label) {
            case ASK: case CLARIFY: case DIAGNOSE: case AMBIGUOUS: return null;
            default: return !d.useAgentLoop ? l0 : null;
        }
    }

    static List<String> plan(String q) {
        FollowupEdit.clearForTest();
        UnifiedRouter.Triage ft = FastPathTriage.triage(q, RES);
        if (ft == null || ft.plan == null) return null;
        List<String> out = new ArrayList<String>();
        for (Invocation s : ft.plan.steps) out.add(s.sign);
        return out;
    }

    @Test public void bareZaiBeforeDeviceVerbSplits() {
        assertEquals(Arrays.asList("volume.notif.set", "battery.saver.on"), plan("通知音量5再打开省电模式"));
        assertEquals(Arrays.asList("notes.new.text", "settings.edge_lighting.on"), plan("记一下买牛奶再打开侧屏闪光"));
        assertEquals(Arrays.asList("connectivity.bt.on", "connectivity.wifi.off"), plan("打开蓝牙，另外关闭wifi"));
        assertEquals(Arrays.asList("connectivity.bt.on", "connectivity.wifi.off"), plan("打开蓝牙再关闭wifi"));
    }

    @Test public void bareZaiBetweenResolvableNonTextClausesSplits() {
        assertEquals(Arrays.asList("connectivity.gps.state", "connectivity.wifi.state", "browser.search.bing"), plan("定位开着吗再wifi开着吗并且用必应搜索天气"));
        assertEquals(Arrays.asList("volume.notif.set", "settings.screentimeout.get", "browser.search.google"), plan("通知音量5再息屏时间是多少然后用谷歌搜索天气"));
        assertNull(plan("wifi开着吗再说吧"));
        assertNull(plan("打开蓝牙再见"));
    }

    @Test public void planNoteKeepsOnlyItsOwnClause() {
        FollowupEdit.clearForTest();
        UnifiedRouter.Triage ft = FastPathTriage.triage("记一下买牛奶再打开侧屏闪光", RES);
        assertEquals("买牛奶", ft.plan.steps.get(0).args.optString("content"));
    }

    @Test public void zaiInsideNoteOrFollowupDoesNotSplit() {
        assertNull(plan("记一下明天再打电话给妈妈"));
        assertNull(plan("再大一点"));
        assertNull(plan("再见"));
        Invocation n = local("记一下明天再打电话给妈妈");
        assertNotNull(n);
        assertEquals("notes.new.text", n.sign);
        assertEquals("明天再打电话给妈妈", n.args.optString("content"));
    }

    @Test public void noteBodyIsVerbatimNotObject() {
        Invocation n = local("记一下打开冰箱要先拔电");
        assertNotNull(n);
        assertEquals("打开冰箱要先拔电", n.args.optString("content"));
    }

    @Test public void lastClauseNeverLandsInNote() {
        for (String q : new String[]{"记一下买牛奶再打开侧屏闪光", "记一下买牛奶再打开浏览器把引擎换成sogou", "铃声音量5。打开日期和时间设置", "通知音量5再打开省电模式"}) {
            Invocation l = local(q);
            if (l != null && !l.isPlan()) fail(q + " 只执行了一步: " + l.sign + " " + l.args);
        }
    }
}
