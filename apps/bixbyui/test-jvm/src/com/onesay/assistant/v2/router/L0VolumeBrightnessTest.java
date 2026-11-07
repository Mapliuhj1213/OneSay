package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ShellCommand;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.*;

/**
 * N-2「再大声一点」联网进 AgentLoop（没有相对调音量能力）；
 * TRI-7「能不能把亮度调高」l0=-（问句前缀没剥、「亮度调高」语序没收）→ 进 AgentLoop。
 * 现在都 L0 离线直出、不进循环。
 */
public class L0VolumeBrightnessTest {

    static CapabilityRegistry reg;
    static L0RuleRouter l0;

    @BeforeClass public static void setUp() {
        reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        l0 = L0RuleRouter.forRegistry(reg);
    }

    static String sign(String q) {
        Invocation inv = l0.route(q);
        return inv == null ? null : inv.sign;
    }

    static void all(String expect, String... qs) {
        for (String q : qs) assertEquals(q, expect, sign(q));
    }

    @Test public void volumeUp() {
        all("volume.media.up", "再大声一点", "大声一点", "大声点", "声音大一点", "把声音调大一点", "调大声音",
                "音量调高", "调高音量", "音量大一点", "能不能大声一点", "可以大声一点吗", "再大声点", "帮我把音量调大");
    }

    @Test public void volumeDown() {
        all("volume.media.down", "小声一点", "再小声一点", "小声点", "声音小一点", "把声音调小一点", "音量调低",
                "调低音量", "能不能小声一点", "音量小一点");
    }

    @Test public void brightnessUp() {
        all("settings.brightness.up", "能不能把亮度调高", "亮度调高", "亮度调高一点", "再亮一点", "屏幕亮一点",
                "调高亮度", "亮一点", "可以把亮度调高吗");
    }

    @Test public void brightnessDown() {
        all("settings.brightness.down", "亮度调低", "亮度调低一点", "再暗一点", "能不能把亮度调低", "暗一点", "调低亮度");
    }

    @Test public void absoluteStillWins() {
        all("volume.media.set", "媒体音量调到5", "声音调到8");
        all("settings.brightness.set", "屏幕亮度给我设成三十");
    }

    @Test public void triageNoAgentLoop() {
        for (String q : new String[]{"再大声一点", "小声一点", "能不能把亮度调高", "亮度调低"}) {
            String s = sign(q);
            assertNotNull(q, s);
            TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(s, false, false));
            assertEquals(q + " → " + d, TriageDecider.Label.DO_ACT, d.label);
            assertFalse(q + " → " + d, d.useAgentLoop);
        }
    }

    @Test public void stepCapsAreSafe() {
        for (String s : new String[]{"volume.media.up", "volume.media.down"}) {
            Capability c = reg.describe(s);
            assertNotNull(s, c);
            assertEquals(SideEffect.DEVICE_CONTROL, c.sideEffect);
            assertFalse(s + " 不需确认", c.confirm);
            assertEquals("模板没有槽位", c.cmdTemplate, ShellCommand.fill(c.cmdTemplate, new HashMap<String, String>()));
            assertTrue(c.cmdTemplate.contains("--stream 3"));
        }
        assertTrue(reg.describe("volume.media.up").cmdTemplate.contains("v + 2"));
        assertTrue(reg.describe("volume.media.down").cmdTemplate.contains("v - 2"));
    }

    /** 不是本次目标的句子不能被新说法 / 新前缀抢走：语料 L0 结果与修改前逐句一致（基线由 dumpCorpus 生成）。 */
    @Test public void dumpCorpus() throws Exception {
        if (System.getProperty("bixby.l0dump") == null) return;
        StringBuilder sb = new StringBuilder();
        for (String[] c : TriageDeciderTest.all()) sb.append(c[0]).append('\t').append(sign(c[0])).append('\n');
        java.nio.file.Files.write(new java.io.File(System.getProperty("bixby.l0dump")).toPath(), sb.toString().getBytes("UTF-8"));
    }
}
