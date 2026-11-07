package com.onesay.assistant.v2.shortcut;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两边格式一致：OneSay ShortcutModel / ShortcutActions 与计划 App core/Contract + core/Shortcuts 的常量逐项比对。
 * 计划 App 源码找不到（单独 clone OneSay）时跳过：-Dplan.root 指定，默认 ../../bixby-plan/wt-shortcuts（相对 bixby.root）。
 */
public class ShortcutFormatTest {

    static File planRoot() {
        String p = System.getProperty("plan.root");
        if (p != null) return new File(p);
        return new File(System.getProperty("bixby.root", "."), "../../bixby-plan/wt-shortcuts");
    }

    static String read(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    @Test public void constantsMatchPlannerApp() throws Exception {
        File c = new File(planRoot(), "app/src/com/onesay/plan/core/Contract.java");
        File s = new File(planRoot(), "app/src/com/onesay/plan/core/Shortcuts.java");
        assumeTrue("计划 App 源码不在旁边，跳过", c.exists() && s.exists());
        String contract = read(c), shortcuts = read(s);
        for (String v : new String[]{ShortcutModel.T_SHORTCUTS, ShortcutModel.T_RUNS, ShortcutModel.M_SHORTCUT_ACTIONS, ShortcutModel.SC_REQUESTED,
                ShortcutModel.SC_QUEUED, ShortcutModel.SC_RUNNING, ShortcutModel.SC_OK, ShortcutModel.SC_FAIL, ShortcutModel.SC_FALLBACK_OK,
                ShortcutModel.SC_FALLBACK_FAIL, ShortcutModel.SC_CANCELLED, ShortcutModel.SC_DENIED, ShortcutModel.SC_STALE, ShortcutModel.SC_TIME_SRC_PREFIX}) {
            assertTrue("计划 App Contract 缺 " + v, contract.contains("\"" + v + "\""));
        }
        assertTrue(contract.contains("SHORTCUT_FORMAT = " + ShortcutModel.FORMAT + ";"));
        assertTrue(shortcuts.contains(ShortcutModel.DEFAULT_TRIGGERS.replace("\"", "\\\"")));
        assertTrue(shortcuts.contains("MAX_STEPS = " + ShortcutModel.MAX_STEPS + ";"));
        assertTrue(shortcuts.contains("HISTORY_KEEP = " + ShortcutModel.HISTORY_KEEP + ";"));
        assertTrue(shortcuts.contains("WRONG_TO_FLAG = " + ShortcutModel.WRONG_TO_FLAG + ";"));
        assertTrue(shortcuts.contains("\"执行快捷指令#\" + id + \"（\""));
        Set<String> mine = signs(read(new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/shortcut/ShortcutActions.java")));
        assertTrue("解析出了动作清单", mine.size() >= 15);
        assertEquals("两边动作清单的签名一致", signs(read(new File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/shortcut/ShortcutActions.java"))), signs(shortcuts));
    }

    static final Pattern ACTION = Pattern.compile("new Action\\((?:\"([a-z0-9_.]+)\"|([A-Z_]+))");

    static Set<String> signs(String src) {
        Set<String> out = new TreeSet<String>();
        Matcher m = ACTION.matcher(src);
        while (m.find()) {
            String v = m.group(1);
            if (v == null) {
                Matcher k = Pattern.compile(m.group(2) + " = \"([a-z0-9_.]+)\"").matcher(src);
                if (!k.find()) {   // 多个常量写在一行：LLM_ASK = "llm.ask", EVENT_ADD = …
                    k = Pattern.compile("\\b" + m.group(2) + " = \"([a-z0-9_.]+)\"").matcher(src);
                    if (!k.find()) continue;
                }
                v = k.group(1);
            }
            out.add(v);
        }
        return out;
    }
}
