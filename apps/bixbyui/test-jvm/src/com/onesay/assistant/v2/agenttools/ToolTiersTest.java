package com.onesay.assistant.v2.agenttools;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.router.CapabilityRetriever;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** ★ arch/tool-tiers（A 路）：工具四档分档 + 「按档位 + 相关度」排序截断。 */
public class ToolTiersTest {

    static Capability cap(String sign, String executor, String cmd) {
        Capability.Builder b = new Capability.Builder(sign, sign).schema(new ParamsSchema()).executor(executor);
        if (cmd != null) b.cmd(cmd);
        return b.build();
    }

    @Test public void tierBySign() {
        assertEquals(ToolTier.GUI, ToolTiers.ofSign("phone.operate"));
        assertEquals(ToolTier.GUI, ToolTiers.ofSign("phone__operate"));
        assertEquals(ToolTier.GUI, ToolTiers.ofSign("gui__unfold"));
        assertEquals("B 路的搜索 / 读网页留在查信息档", ToolTier.INFO, ToolTiers.ofSign("web.search"));
        assertEquals(ToolTier.INFO, ToolTiers.ofSign("web.read"));
        assertEquals(ToolTier.INFO, ToolTiers.ofSign("research.spawn"));
        assertEquals(ToolTier.INFO, ToolTiers.ofSign("memory.search"));
        assertEquals(ToolTier.INFO, ToolTiers.ofSign("conn__tavily__search"));
        assertEquals(ToolTier.INFO, ToolTiers.ofSign("vision.screen.understand"));
        assertEquals(ToolTier.DEEPLINK, ToolTiers.ofSign("app.launch"));
        assertEquals(ToolTier.DEEPLINK, ToolTiers.ofSign("settings.page.wifi"));
        assertEquals(ToolTier.DEEPLINK, ToolTiers.ofSign("store.search.samsung"));
        assertEquals(ToolTier.API, ToolTiers.ofSign("connectivity.wifi.on"));
        assertEquals(ToolTier.API, ToolTiers.ofSign("probe.apps"));
        assertEquals(ToolTier.API, ToolTiers.ofSign(null));
    }

    @Test public void tierByCapability() {
        assertEquals(ToolTier.DEEPLINK, ToolTiers.of(cap("x.open.page", "intent", null)));
        assertEquals(ToolTier.DEEPLINK, ToolTiers.of(cap("mobileqq.chat", "shell", "am start -n com.tencent.mobileqq/.X")));
        assertEquals("面板内闹钟联动不算深链", ToolTier.API, ToolTiers.of(cap("clock.alarm.x", "alarmbridge", "am start -a x")));
        assertEquals(ToolTier.API, ToolTiers.of(cap("settings.foo.set", "shell", "settings put system foo 1")));
        CapabilityRegistry reg = CapabilityRegistry.getInstance();
        assertEquals(ToolTier.API, ToolTiers.of(reg.describe("settings.brightness.set")));
        assertEquals(ToolTier.API, ToolTiers.of(reg.describe("time.now")));
        assertEquals(ToolTier.DEEPLINK, ToolTiers.of(reg.describe("store.search.play")));
        assertEquals(ToolTier.DEEPLINK, ToolTiers.of(reg.describe("app.launch")));
        assertTrue(ToolTier.DEEPLINK.isUpper());
        assertFalse(ToolTier.GUI.isUpper());
    }

    @Test public void bands() {
        assertEquals(3, ToolTiers.band(40));
        assertEquals(3, ToolTiers.band(25));
        assertEquals(2, ToolTiers.band(10));
        assertEquals(1, ToolTiers.band(3));
        assertEquals(0, ToolTiers.band(0));
    }

    @Test public void rankByBandThenTierThenScore() {
        List<ToolTiers.Scored> all = new ArrayList<ToolTiers.Scored>();
        all.add(new ToolTiers.Scored(cap("a.weak.api", "shell", "settings put a"), 9, 0));
        all.add(new ToolTiers.Scored(cap("b.strong.link", "intent", null), 45, 1));
        all.add(new ToolTiers.Scored(cap("c.strong.api", "shell", "settings put c"), 30, 2));
        all.add(new ToolTiers.Scored(cap("web.search", "custom", null), 12, 3));
        all.add(new ToolTiers.Scored(cap("d.zero", "shell", "x"), 0, 4));
        List<ToolTiers.Scored> r = ToolTiers.rank(all, 10, 0);
        assertEquals("强相关段里接口排在深链前（即使深链分数更高）", "c.strong.api", r.get(0).cap.sign);
        assertEquals("b.strong.link", r.get(1).cap.sign);
        assertEquals("中相关排在弱相关前", "web.search", r.get(2).cap.sign);
        assertEquals("a.weak.api", r.get(3).cap.sign);
        assertEquals("零分不保留", 4, r.size());

        List<ToolTiers.Scored> top2 = ToolTiers.rank(all, 2, 0);
        assertEquals(2, top2.size());
        assertEquals("截断只砍掉弱相关", "b.strong.link", top2.get(1).cap.sign);

        List<ToolTiers.Scored> keep = ToolTiers.rank(all, 10, 5);
        assertEquals("与旧 getTopK 一样，前 minKeep 名额里零分也保留", 5, keep.size());
    }

    /** 回归：旧字面 Top-40 漏掉 time.now 的那句（2026-10-05 事故句）。 */
    @Test public void realRegistryKeepsDirectInterfaces() {
        List<Capability> c = CapabilityRetriever.getTopKTiered("先看一下现在几点了，如果还没到晚上，就顺便把屏幕亮度调到最低", 40);
        Set<String> signs = new HashSet<String>();
        for (Capability x : c) signs.add(x.sign);
        assertTrue(signs.contains("time.now"));
        assertTrue(signs.contains("settings.brightness.set"));
        assertTrue(c.size() <= 40);
        AgentToolset.end();
    }

    /** 性质：没有任何一个被截掉的候选比留下的候选相关度段更高。 */
    @Test public void truncationNeverDropsHigherBand() {
        String[] qs = { "把亮度调到最低", "下载微信", "帮我打开蓝牙然后把音量调大", "读一下QQ邮箱的邮件", "打开wifi设置页面",
                "40分钟后叫醒我", "让手机安静点", "在小红书发一条笔记" };
        for (String q : qs) {
            List<ToolTiers.Scored> all = CapabilityRetriever.scoreAll(q);
            List<ToolTiers.Scored> top = ToolTiers.rank(all, 12, 3);
            Set<String> kept = new HashSet<String>();
            int minKeptBand = 3;
            for (ToolTiers.Scored s : top) {
                kept.add(s.cap.sign);
                if (s.score > 0) minKeptBand = Math.min(minKeptBand, ToolTiers.band(s.score));
            }
            for (ToolTiers.Scored s : all) {
                if (kept.contains(s.cap.sign)) continue;
                assertTrue(q + " 截掉了更相关的 " + s.cap.sign, ToolTiers.band(s.score) <= minKeptBand);
            }
            // 同一段里档位单调不减
            for (int i = 1; i < top.size(); i++) {
                ToolTiers.Scored a = top.get(i - 1), b = top.get(i);
                if (ToolTiers.band(a.score) == ToolTiers.band(b.score)) {
                    assertTrue(q + " 同段档位乱序 " + a.cap.sign + " / " + b.cap.sign, a.tier.rank <= b.tier.rank);
                }
            }
        }
    }

    @Test public void mountedSignsFromToolsArray() throws Exception {
        org.json.JSONArray tools = CapabilityRegistry.getInstance().exportToolsSchema(
                java.util.Arrays.asList(CapabilityRegistry.getInstance().describe("app.launch"),
                        CapabilityRegistry.getInstance().describe("settings.edge_lighting.on")));
        Set<String> m = ToolTiers.mountedSigns(tools);
        assertTrue(m.contains("app.launch"));
        assertTrue(m.contains("settings.edge_lighting.on"));
    }
}
