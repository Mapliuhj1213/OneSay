package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * 2026-11-06 真机冒烟发现的快捷指令问题（计划 App 一侧）：
 * <ol>
 *   <li>沉浸全屏时 OneSay 的岛被隐藏、结果只剩通知 → 计划 App 盯住自己发起的请求，结束时 App 内提示（{@link Shortcuts#finishedText}）；</li>
 *   <li>删除快捷指令不撤桌面固定图标 → 删除时 disableShortcuts（按 API 探测）；旧图标点了给明确提示，且不把计划 App 拉到前台；</li>
 *   <li>关掉「定时」后编辑器仍显示时间行和「开启 开」→ 时间 / 重复跟着定时开关显示，「开启」挪出定时一组改名「启用这条快捷指令」。</li>
 * </ol>
 */
public class ShortcutSmokeFixTest {

    static Row run(String status, String result) {
        return new Row().with("status", status).with("result", result);
    }

    @Test public void finishedTextOnlyForTerminalStates() {
        assertNull(Shortcuts.finishedText("晚安", run(Contract.SC_REQUESTED, "")));
        assertNull(Shortcuts.finishedText("晚安", run(Contract.SC_QUEUED, "")));
        assertNull(Shortcuts.finishedText("晚安", run(Contract.SC_RUNNING, "")));
        assertNull(Shortcuts.finishedText("晚安", run("", "")));
        assertNull(Shortcuts.finishedText("晚安", null));
        assertEquals("「晚安」已执行", Shortcuts.finishedText("晚安", run(Contract.SC_OK, "已执行")));
        assertEquals("「晚安」已执行：已加待办：买牛奶", Shortcuts.finishedText("晚安", run(Contract.SC_OK, "已加待办：买牛奶")));
        assertEquals("「晚安」没做完：没做完：第 1 步「加待办」", Shortcuts.finishedText("晚安", run(Contract.SC_FAIL, "没做完：第 1 步「加待办」")));
        assertEquals("「晚安」已取消", Shortcuts.finishedText("晚安", run(Contract.SC_CANCELLED, "已取消")));
        assertTrue(Shortcuts.finishedText("晚安", run(Contract.SC_DENIED, "")).startsWith("「晚安」没授权"));
        assertEquals("「晚安」没执行：OneSay 没在运行", Shortcuts.finishedText("晚安", run(Contract.SC_STALE, "")));
        assertTrue(Shortcuts.finishedText("晚安", run(Contract.SC_FALLBACK_OK, "x")).startsWith("「晚安」已执行"));
        String longRes = new String(new char[200]).replace('\0', '字');
        assertTrue(Shortcuts.finishedText("晚安", run(Contract.SC_OK, longRes)).length() < 100);
        assertFalse("提示里不出现内部指令文字", Shortcuts.finishedText("晚安", run(Contract.SC_OK, "已执行")).contains("#"));
        assertTrue(Shortcuts.WATCH_MS > Shortcuts.REQUEST_STALE_MS);
    }

    @Test public void pinIdIsStable() {
        assertEquals("sc-12", Shortcuts.pinId(12));
        assertTrue(Shortcuts.PIN_GONE.contains("删除"));
        assertTrue(Shortcuts.PIN_GONE.contains("移除"));
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("plan.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    @Test public void wiring() throws Exception {
        String store = src("app/src/com/onesay/plan/data/Store.java");
        int del = store.indexOf("public static int delete(");
        assertTrue(del > 0);
        assertTrue("删快捷指令时禁用桌面图标", store.indexOf("PinnedShortcuts.disable(", del) > del);

        String pin = src("app/src/com/onesay/plan/platform/PinnedShortcuts.java");
        assertTrue("按 API 能力探测（25+）", pin.contains("SDK_INT < 25"));
        assertTrue(pin.contains("getPinnedShortcuts()"));
        assertTrue(pin.contains("disableShortcuts("));

        String act = src("app/src/com/onesay/plan/ui/ShortcutRunActivity.java");
        assertTrue("旧图标：明确提示", act.contains("Shortcuts.PIN_GONE"));
        assertTrue(act.contains("PinnedShortcuts.disable("));
        String mf = src("app/AndroidManifest.xml");
        int a = mf.indexOf(".ui.ShortcutRunActivity");
        assertTrue(a > 0);
        assertTrue("落点自成一个任务，点完不把计划 App 拉到前台", mf.substring(a, mf.indexOf("/>", a)).contains("android:taskAffinity=\"\""));

        String ui = src("app/src/com/onesay/plan/ui/ShortcutUi.java");
        assertTrue("点运行后盯结果", ui.contains("a.watchShortcutRun(runId"));
        assertTrue("时间 / 重复放进跟着定时开关的 timeBox", ui.contains("ed.whenField(timeBox") && ui.contains("ed.spinner(timeBox"));
        assertTrue(ui.contains("timeBox.setVisibility(checked ? View.VISIBLE : View.GONE)"));
        assertFalse("「开启」不再紧跟在定时后面", ui.contains("ed.toggle(f, \"开启\""));
        assertTrue(ui.contains("enableIfDisabled(a, id)"));
        String main = src("app/src/com/onesay/plan/ui/MainActivity.java");
        assertTrue(main.contains("checkWatchedRun();"));
        assertTrue(main.contains("Shortcuts.finishedText(watchRunName, run)"));
    }
}
