package com.onesay.assistant.v2.ui.chat;

import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;

import org.junit.Test;

import static org.junit.Assert.*;

/** 结果态底栏统一成 ChatBottomBar；结果态点麦克风 / 发送 / 输入框一次就地展开成对话窗；展开中连点不触发两次。 */
public class ResultBarTest {

    @Test public void cardBarReplacesOldRowOnlyAfterResult() {
        assertTrue(ChatWindowPolicy.cardBarVisible(Stage.RESULT, true, false));
        assertTrue("出错", ChatWindowPolicy.cardBarVisible(Stage.ERROR, true, false));
        assertTrue("打字", ChatWindowPolicy.cardBarVisible(Stage.TEXT, true, false));
        for (Stage s : new Stage[]{Stage.LISTENING, Stage.THINKING, Stage.ANSWERING, Stage.TASK, Stage.CONFIRM, Stage.IDLE,
                Stage.HANDOFF, Stage.ISLAND, Stage.DISMISSING}) {
            assertFalse("第一次唤醒的聆听 / 思考卡片外观不变：" + s, ChatWindowPolicy.cardBarVisible(s, true, false));
        }
        assertFalse("对话窗开着：底栏归对话窗", ChatWindowPolicy.cardBarVisible(Stage.RESULT, true, true));
        assertFalse("旧界面", ChatWindowPolicy.cardBarVisible(Stage.RESULT, false, false));
        assertFalse(ChatWindowPolicy.cardBarVisible(null, true, false));
    }

    @Test public void barTapExpandsOnceWhenCardHasAnswer() {
        assertTrue(ChatWindowPolicy.barExpands(true, true, false, true));
        assertFalse("已经展开", ChatWindowPolicy.barExpands(true, true, true, true));
        assertFalse("第一次唤醒还没回答（打字态 / 没听清）：留在卡片", ChatWindowPolicy.barExpands(true, true, false, false));
        assertFalse("wrap 窗没有对话窗", ChatWindowPolicy.barExpands(true, false, false, true));
        assertFalse("旧界面", ChatWindowPolicy.barExpands(false, true, false, true));
    }

    @Test public void rapidRepeatTapIsSwallowedDuringMorph() {
        assertEquals(ChatWindowPolicy.EXPAND_MS, ChatWindowPolicy.BAR_REPEAT_MS);
        assertFalse("第一次", ChatWindowPolicy.swallowRepeatTap(10_000L, 0L));
        assertTrue("100 ms 后连点", ChatWindowPolicy.swallowRepeatTap(10_100L, 10_000L));
        assertTrue("329 ms", ChatWindowPolicy.swallowRepeatTap(10_329L, 10_000L));
        assertFalse("动画完了再点 = 正常（录音中 → 停）", ChatWindowPolicy.swallowRepeatTap(10_330L, 10_000L));
        assertFalse("时钟回拨不吞", ChatWindowPolicy.swallowRepeatTap(9_000L, 10_000L));
    }

    @Test public void wiringUsesOneBottomBarEverywhere() throws Exception {
        String ctl = InputFocusTest.src("app/src/com/onesay/assistant/v2/ui/chat/ChatWindowController.java");
        assertTrue(ctl.contains("public void showOnCard(boolean on)"));
        assertTrue("麦克风先展开再开录", ctl.indexOf("expandForBar(\"麦克风\");") < ctl.indexOf("mHost.micTap();\n    }"));
        assertTrue("麦克风连点闸门", ctl.contains("ChatWindowPolicy.swallowRepeatTap(now, mLastBarTapAt)"));
        assertTrue("发送先展开", ctl.contains("expandForBar(\"发送\")"));
        assertTrue("点输入框先展开", ctl.contains("expandForBar(\"输入框\")"));
        assertTrue("底栏已在卡片上：展开时原地留用不闪", ctl.contains("if (!barWasShown) {"));
        String view = InputFocusTest.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue("结果态藏旧图标行", view.contains("if (com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.cardBarVisible(st, mV2, chat)) vis = GONE;"));
        assertTrue("每次重排按阶段挂底栏", view.contains("if (!chat) v2RefreshCardBar(st);"));
        int t = view.indexOf("public void switchToTextMode()");
        String v2Branch = view.substring(view.indexOf("if (mV2) {", t), view.indexOf("return;", view.indexOf("if (mV2) {", t)));
        assertFalse("新界面打字不再弹旧 PillSearchBar", v2Branch.contains("mSearchBar.setVisibility(VISIBLE)"));
        assertTrue(view.contains("@Override public boolean hasAnswer() { return mV2Final || mV2Body != null; }"));
    }
}
