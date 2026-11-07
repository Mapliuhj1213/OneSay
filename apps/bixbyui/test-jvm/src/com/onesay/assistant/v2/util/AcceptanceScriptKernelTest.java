package com.onesay.assistant.v2.util;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * 第 3 阶段：统一验收脚本 —— 每个插件一个模块（plugins 依次跑），内核自身一个 kernel 模块；
 * 非交互模式下人工项记 MANUAL。脚本里的插件清单 / 接管顺序与内核实况逐条比对，防止两边走样。
 */
public class AcceptanceScriptKernelTest {

    static String script() throws Exception { return AcceptanceScriptRound5Test.src("acceptance/device_acceptance.sh"); }

    static String var(String s, String name) {
        int i = s.indexOf("\n" + name + "=\"");
        assertTrue("脚本缺变量 " + name, i >= 0);
        int a = i + name.length() + 3;
        return s.substring(a, s.indexOf('"', a));
    }

    @Test public void everyPluginHasAModuleAndPluginsRunsThemAll() throws Exception {
        String s = script();
        Set<String> want = new LinkedHashSet<String>();
        for (FeaturePlugin p : FeatureKernel.get().plugins()) if (p.acceptanceModule() != null) want.add(p.acceptanceModule());
        want.add("kernel");
        Set<String> got = new LinkedHashSet<String>(Arrays.asList(var(s, "PLUGIN_MODULES").split(" ")));
        assertEquals("PLUGIN_MODULES 必须 = 每个插件的验收模块 + kernel", want, got);
        for (String m : got) {
            assertTrue("缺 cmd_" + m, s.contains("\ncmd_" + m + "() {"));
            assertTrue("分发缺 " + m, s.contains("  " + m + ")") || s.contains("  " + m + " ") || s.contains(m + ")    cmd_" + m));
        }
        assertTrue(s.contains("  plugins)   cmd_plugins ;;"));
        assertTrue(s.contains("  kernel)    cmd_kernel ;;"));
        assertTrue("用法里写了 kernel / plugins", s.contains("device_acceptance.sh kernel ") && s.contains("device_acceptance.sh plugins "));
    }

    @Test public void kernelPluginListAndTakeoverOrderMatchKernel() throws Exception {
        String s = script();
        List<String> ids = new ArrayList<String>();
        for (FeaturePlugin p : FeatureKernel.get().plugins()) ids.add(p.id());
        assertEquals(ids, Arrays.asList(var(s, "KERNEL_PLUGINS").split(" ")));
        String actual = "order TAKEOVER = " + FeatureKernel.get().orderIds(FeatureHook.TAKEOVER);
        assertTrue(actual, actual.startsWith(var(s, "KERNEL_TAKEOVER_PREFIX")));
    }

    /** 用内核真实的状态 / 顺序输出拼出日志，实跑 cmd_kernel（非交互）。 */
    @Test public void cmdKernelNonInteractivePassesOnRealOutput() throws Exception {
        StringBuilder log = new StringBuilder();
        for (FeaturePlugin p : FeatureKernel.get().plugins()) {
            String st = "hello".equals(p.id()) || "quicknote".equals(p.id()) ? "DISABLED" : "ENABLED";
            log.append("I Kernel: [KERNEL-TEST] status ").append(p.id()).append(" state=").append(st)
                    .append(" module=").append(p.acceptanceModule()).append(" tag=").append(p.logTag()).append(" | x\n");
        }
        for (FeatureHook h : FeatureHook.values()) {
            log.append("I Kernel: [KERNEL-TEST] order ").append(h).append(" = ").append(FeatureKernel.get().orderIds(h)).append('\n');
        }
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "cmd_kernel", "VARIANT=debug", log.toString(), "");
        assertEquals(r.toString(), "PASS", r.get("KN-1"));
        assertEquals(r.toString(), "PASS", r.get("KN-2"));
        assertEquals(r.toString(), "PASS", r.get("KN-3"));
        assertEquals("人工项非交互记 MANUAL", "MANUAL", r.get("KN-4"));
    }

    @Test public void cmdKernelFailsWhenPluginMissingOrHelloOn() throws Exception {
        String log = "[KERNEL-TEST] status s1 state=ENABLED\n[KERNEL-TEST] status hello state=ENABLED\n"
                + "[KERNEL-TEST] order TAKEOVER = [summary, imgsave]\n";
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "cmd_kernel", "VARIANT=debug", log, "");
        assertEquals("FAIL", r.get("KN-1"));
        assertTrue(r.get("KN-1#note"), r.get("KN-1#note").contains("island"));
        assertEquals("FAIL", r.get("KN-2"));
        assertEquals("FAIL", r.get("KN-3"));
    }

    @Test public void cmdKernelReleaseSkipsAutoParts() throws Exception {
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "cmd_kernel", "VARIANT=release", "", "");
        assertEquals("SKIP", r.get("KN-1"));
        assertEquals("SKIP", r.get("KN-3"));
        assertEquals("MANUAL", r.get("KN-4"));
    }

    /** plugins 非交互实跑：10 个模块依次跑完、不卡在任何提问上，每个模块都留下结果；人工项一律 MANUAL。 */
    @Test public void cmdPluginsRunsEveryModuleNonInteractively() throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "cmd_plugins", "VARIANT=debug", "", "");
        assertTrue("应在 60 s 内跑完", System.currentTimeMillis() - t0 < 60000);
        assertEquals("INFO", r.get("PLUGINS"));
        for (String prefix : new String[]{"S1-", "IL-", "S2-", "RO-", "DG-", "SU-", "IS-", "S3-", "SR-", "KN-"}) {
            boolean any = false;
            for (String k : r.keySet()) if (k.startsWith(prefix) && !k.endsWith("#note")) { any = true; break; }
            assertTrue("模块没留下结果：" + prefix, any);
        }
        assertEquals("MANUAL", r.get("KN-4"));
        int manual = 0;
        for (Map.Entry<String, String> e : r.entrySet()) if ("MANUAL".equals(e.getValue())) manual++;
        assertTrue("非交互下人工项应记 MANUAL（实际 " + manual + " 条）", manual >= 50);
    }
}
