package com.onesay.assistant.plug.suggest.core;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 规则守卫（参照 PgPlugRulesTest）：core 只 import java.* / org.json.* / plug.api；core 默认关；构造零副作用；start/stop 可重入。 */
public class SuggestPlugRulesTest {

    static File coreDir() {
        String root = System.getProperty("bixby.root");
        String[] cands = {root == null ? null : root + "/app/src/com/onesay/assistant/plug/suggest/core", "app/src/com/onesay/assistant/plug/suggest/core",
                "<内部路径>"};
        for (String c : cands) if (c != null && new File(c).isDirectory()) return new File(c);
        return null;
    }

    static void collect(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    @Test public void coreDependsOnlyOnJavaOrgJsonAndPlugApi() throws Exception {
        File d = coreDir();
        if (d == null) return;   // 源码不在（只跑字节码时）：跳过
        List<File> fs = new ArrayList<File>();
        collect(d, fs);
        assertTrue(fs.size() > 20);
        for (File f : fs) {
            for (String line : Files.readAllLines(f.toPath(), Charset.forName("UTF-8"))) {
                String t = line.trim();
                if (!t.startsWith("import ")) continue;
                boolean ok = t.startsWith("import java.") || t.startsWith("import org.json.") || t.startsWith("import com.onesay.assistant.plug.api.")
                        || t.startsWith("import com.onesay.assistant.plug.suggest.core") || t.startsWith("import static java.");
                assertTrue(f.getName() + " 不许依赖：" + t, ok);
            }
        }
    }

    @Test public void shellConstantsAndDefaultOff() {
        assertEquals("suggest", SuggestPlug.ID);
        assertFalse("core 出厂默认关（宿主按变体：debug 开 release 关）", SuggestPlug.DEFAULT_ON);
        assertEquals(0, SuggestPlug.DEPS.length);
        assertEquals("全部自写，无移植文件", 0, SuggestPlug.PORTED.length);
    }

    @Test public void constructorHasNoSideEffects() {
        int before = Thread.activeCount();
        SuggestPlug p = new SuggestPlug();
        assertFalse(p.running());
        assertNull(p.engine());
        assertEquals(before, Thread.activeCount());
        p.stop();   // 没起也能停
    }

    @Test public void startStopReentrant() {
        final List<String> events = new ArrayList<String>();
        SignalSource src = new SignalSource() {
            @Override public String type() { return "fake"; }
            @Override public void attach(Sink sink) { events.add("attach"); }
            @Override public void detach() { events.add("detach"); }
        };
        SuggestPlug p = new SuggestPlug();
        SuggestPlug.Deps d = new SuggestPlug.Deps();
        d.config = new SuggestFakes.FakeConfig();
        d.host = new SuggestFakes.FakeHost();
        d.sources.add(src);
        assertTrue(p.start(d));
        assertFalse("重复 start 不重复挂源", p.start(d));
        p.stop();
        p.stop();
        assertEquals("[attach, detach]", events.toString());
        assertTrue(p.start(d));
        p.stop();
        assertEquals(4, events.size());
    }
}
