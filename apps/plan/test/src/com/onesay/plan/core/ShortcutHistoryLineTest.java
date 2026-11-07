package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/** 快捷指令详情「最近运行」显示「已执行 · 已执行」→ 结果和状态相同就只说一次。 */
public class ShortcutHistoryLineTest {

    @Test public void duplicateResultIsDropped() {
        assertEquals("已执行", Shortcuts.historyStatus("已执行", "已执行"));
        assertEquals("已执行", Shortcuts.historyStatus("已执行", " 已执行 "));
        assertEquals("已执行（已更新）", Shortcuts.historyStatus("已执行（已更新）", "已执行"));
        assertEquals("已执行", Shortcuts.historyStatus("已执行", ""));
        assertEquals("已执行", Shortcuts.historyStatus("已执行", null));
    }

    @Test public void realResultIsKept() {
        assertEquals("没做完 · 计划 App 连不上", Shortcuts.historyStatus("没做完", "计划 App 连不上"));
        assertEquals("已执行 · 已加待办「买牛奶」", Shortcuts.historyStatus("已执行", "已加待办「买牛奶」"));
        assertEquals("未授权 · 你拒绝了", Shortcuts.historyStatus("未授权", "你拒绝了"));
        assertEquals("结果", Shortcuts.historyStatus("", "结果"));
    }

    @Test public void detailPageUsesIt() throws Exception {
        String root = System.getProperty("plan.root", ".");
        String ui = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/plan/ui/ShortcutUi.java").toPath()), Charset.forName("UTF-8"));
        assertTrue(ui.contains("Shortcuts.historyStatus(statusZh(st), x.s(\"result\"))"));
        assertFalse(ui.contains("statusZh(st) + (x.s(\"result\").length() > 0 ? \" · \""));
    }
}
