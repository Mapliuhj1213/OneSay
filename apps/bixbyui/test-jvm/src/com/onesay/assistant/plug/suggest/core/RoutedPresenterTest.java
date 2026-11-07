package com.onesay.assistant.plug.suggest.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** ★ fix/shot-1104 (e)：非岛模式时退回旧芯片卡给出可见反馈（以前直接 SUPPRESSED，截图后什么都没有）。 */
public class RoutedPresenterTest {

    static final class Mode implements RoutedPresenter.Mode {
        boolean island = true;

        @Override public boolean islandMode() { return island; }
    }

    static Suggestion shot() {
        return Suggestion.builder("screenshot").title("要不要分析这张截图？").subtitle("刚刚").icon("screenshot")
                .action(SAction.run("analyze", "分析")).action(SAction.dismiss()).action(SAction.mute()).build();
    }

    @Test public void islandModeUsesIslandOtherwiseChip() {
        Mode m = new Mode();
        SuggestFakes.FakePresenter isl = new SuggestFakes.FakePresenter(), chip = new SuggestFakes.FakePresenter();
        RoutedPresenter rp = new RoutedPresenter(m, isl, chip);
        Suggestion a = shot();
        assertTrue(rp.show(a));
        assertEquals(1, isl.shown.size());
        assertEquals(0, chip.shown.size());
        m.island = false;
        assertEquals("chip", rp.route());
        Suggestion b = shot();
        assertTrue("非岛模式 → 旧芯片卡，有可见反馈", rp.show(b));
        assertEquals(1, chip.shown.size());
        rp.withdraw(b.id);
        rp.update(a.id, "2 张截图");
        assertEquals("撤卡发给展示它的那一路", b.id, chip.withdrawn.get(0));
        assertEquals(a.id + "|2 张截图", isl.updates.get(0));
        isl.accept = false;
        m.island = true;
        assertFalse("岛模式但岛忙 → 仍按原逻辑拒绝（不弹旧卡）", rp.show(shot()));
        assertEquals(1, chip.shown.size());
    }

    @Test public void actionsReachEngineSysCardsDoNot() {
        Mode m = new Mode();
        SuggestFakes.FakePresenter isl = new SuggestFakes.FakePresenter(), chip = new SuggestFakes.FakePresenter();
        RoutedPresenter rp = new RoutedPresenter(m, isl, chip);
        final List<String> engine = new ArrayList<String>(), sys = new ArrayList<String>();
        rp.setListener(new Presenter.Listener() {
            @Override public void onAction(String id, String actionId) { engine.add("act:" + id + ":" + actionId); }
            @Override public void onTimeout(String id) { engine.add("to:" + id); }
            @Override public void onSwipedAway(String id) { engine.add("sw:" + id); }
        });
        rp.setSysHandler(new RoutedPresenter.SysHandler() {
            @Override public void onSysAction(String id, String actionId) { sys.add("act:" + id + ":" + actionId); }
            @Override public void onSysClosed(String id, boolean timeout) { sys.add((timeout ? "to:" : "sw:") + id); }
        });
        chip.listener.onAction("screenshot:1", "analyze");
        isl.listener.onTimeout("screenshot:2");
        chip.listener.onSwipedAway("screenshot:3");
        assertEquals("[act:screenshot:1:analyze, to:screenshot:2, sw:screenshot:3]", engine.toString());
        isl.listener.onAction("sys:storage_perm", "grant");
        chip.listener.onTimeout("sys:storage_perm");
        assertEquals("[act:sys:storage_perm:grant, to:sys:storage_perm]", sys.toString());
        assertEquals("系统卡不进引擎", 3, engine.size());
    }

    @Test public void hostUsesRoutedPresenterWithChipFallback() throws Exception {
        String host = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/suggest/SuggestHost.java");
        assertTrue(host.contains("new RoutedPresenter("));
        assertTrue(host.contains("new ChipSuggestPresenter()"));
        String chip = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/suggest/ChipSuggestPresenter.java");
        assertTrue(chip.contains("S1Hooks.showSuggestChip("));
        String hooks = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/s1/S1Hooks.java");
        assertTrue(hooks.contains("public static synchronized boolean showSuggestChip("));
    }
}
