package com.onesay.assistant.v2.kernel;

import static org.junit.Assert.*;

import android.content.Context;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;

/** 第 3 阶段内核收尾：依赖声明（只提示不改行为）、调试入口插件化、识屏登记进内核。 */
public class KernelPhase3Test {

    static final class P extends FeaturePlugin {
        final String id; final boolean on; final String[] deps;
        P(String id, boolean on, String... deps) { this.id = id; this.on = on; this.deps = deps; }
        @Override public String id() { return id; }
        @Override public String title() { return id; }
        @Override public boolean enabled(Context ctx) { return on; }
        @Override public String[] dependsOn() { return deps; }
    }

    @Test public void builtinDepsDeclaredAndKnown() {
        FeatureKernel k = FeatureKernel.get();
        assertTrue("依赖 id 都已注册：" + k.unknownDeps(), k.unknownDeps().isEmpty());
        assertEquals(Arrays.asList("s1", "island"), Arrays.asList(k.find("s3").dependsOn()));
        assertEquals(Arrays.asList("s2"), Arrays.asList(k.find("s1").dependsOn()));
        assertEquals(Arrays.asList("s2"), Arrays.asList(k.find("summary").dependsOn()));
        assertEquals(Arrays.asList("s2"), Arrays.asList(k.find("roles").dependsOn()));
    }

    @Test public void missingDepIsReportedButDoesNotDisableDependent() {
        FeatureKernel k = FeatureKernel.newEmpty()
                .register(new P("base", false))
                .register(new P("feat", true, "base", "ghost"));
        k.assemble(null);
        assertEquals(Arrays.asList("base", "ghost"), k.missingDeps(null, "feat"));
        assertEquals("依赖被关，本插件照常启用（只提示）", FeatureKernel.State.ENABLED, k.state("feat"));
        String dump = k.dump(null);
        assertTrue(dump, dump.contains("feat state=ENABLED") && dump.contains("deps-off=[base, ghost]"));
        assertFalse("没缺依赖不打提示", dump.contains("base state=DISABLED module=base tag=[BASE] deps-off"));
        assertEquals(Arrays.asList("feat→ghost"), k.unknownDeps());
    }

    @Test public void debugExtrasMatchDriverTable() {
        Map<String, String> m = FeatureKernel.get().debugExtras();
        assertEquals(Arrays.asList("s1", "island", "s2", "roles", "diag", "summary", "imgsave", "s3", "quicknote", "conn", "event", "notice"),   // +notice；10-23 +event（CallbackPlugin：事件任务注入）
                new java.util.ArrayList<String>(m.keySet()));
        String drv = KernelWiring.src("app/src/com/onesay/assistant/v2/test/PluginDebugDrivers.java");
        String trr = KernelWiring.src("app/src/com/onesay/assistant/v2/test/TestRunReceiver.java");
        for (String e : m.keySet()) {
            assertTrue("PluginDebugDrivers 缺 --es " + e, drv.contains("\"" + e + "\".equals(extra)"));
        }
        assertTrue(trr.contains("PluginDebugDrivers.dispatch("));
        for (String d : new String[]{"S1TestDriver", "IslandTestDriver", "S3TestDriver", "S2TestDriver",
                "DiagTestDriver", "RolesTestDriver", "ImageSaveTestDriver", "SummaryTestDriver"}) {
            assertFalse("TestRunReceiver 不再直调 " + d, trr.contains(d + ".handle("));
        }
        assertTrue("round3 / kernel 入口不属于插件，保留", trr.contains("Round3TestDriver.handle(") && trr.contains("KernelTestDriver.handle("));
    }

    @Test public void screenRefRegistered() throws Exception {
        FeaturePlugin p = FeatureKernel.get().find("screenref");
        assertNotNull(p);
        assertEquals("screenref", p.acceptanceModule());
        assertEquals("bixby_screenref", p.prefsName());
        assertEquals(Arrays.asList("screenref.off"), Arrays.asList(p.killFiles()));
        // 第 4 阶段（D-3a）起挂钩由本插件经内核提供，见 KernelPhase4Test
        assertFalse(p.kernelGated());
    }
}
