package com.onesay.assistant.v2.ui.wake;

import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 11「回答完成之后工具栏为什么丢上面而不是下面」：结果态 / 对话窗里底栏（ChatBottomBar）贴屏幕底，
 * 键盘弹出时贴键盘上沿；聆听卡 / 打字阶段排布不变。
 */
public class BarPinBottomTest {

    static String src(String rel) {
        return KernelWiring.src(rel);
    }

    @Test public void pinOnlyWhenResultCardFullWithBar() {
        assertTrue("出结果 + 底栏在卡片上 → 贴底", WindowFlowPolicy.pinBarBottom(WakeStage.Stage.RESULT, true, true, true));
        assertFalse("聆听卡外观不变", WindowFlowPolicy.pinBarBottom(WakeStage.Stage.LISTENING, true, true, true));
        assertFalse("打字阶段不变（本来就贴键盘）", WindowFlowPolicy.pinBarBottom(WakeStage.Stage.TEXT, true, true, true));
        assertFalse("出错是底部紧凑卡（本来就在底）", WindowFlowPolicy.pinBarBottom(WakeStage.Stage.ERROR, true, true, true));
        assertFalse("底栏没显示不动", WindowFlowPolicy.pinBarBottom(WakeStage.Stage.RESULT, true, true, false));
        assertFalse("wrap 窗不做", WindowFlowPolicy.pinBarBottom(WakeStage.Stage.RESULT, true, false, true));
        assertFalse("旧界面不做", WindowFlowPolicy.pinBarBottom(WakeStage.Stage.RESULT, false, true, true));
        assertFalse(WindowFlowPolicy.pinBarBottom(null, true, true, true));
    }

    /** Note8 1080×2220（窗口 = 屏幕高），导航让位 126px，底栏 150px：底栏 y = 屏幕高 − 底栏高 − 让位，跟答案长短无关。 */
    @Test public void pinnedBarYIsScreenMinusBarMinusInset() {
        int H = 2220, nav = 126, bar = 150, padTop = 60, fixed = 120;
        for (int content : new int[]{40, 300, 900, 5000}) {
            assertEquals("答案 " + content + "px：底栏贴屏幕底", H - bar - nav,
                    WindowFlowPolicy.barTop(H, padTop, nav, fixed, content, bar, 20, 0, true, true));
        }
        // 键盘弹出：根布局底部内边距 = 导航让位 + 键盘高 → 底栏贴键盘上沿
        int ime = 840;
        assertEquals(H - bar - nav - ime, WindowFlowPolicy.barTop(H, padTop, nav + ime, fixed, 300, bar, 20, 0, true, true));
    }

    /** 修复前的排布（从上往下 + 内容区 WRAP）：短答案时底栏停在屏幕中上部 —— 这就是用户看到的「丢上面」。 */
    @Test public void unpinnedTopGravityIsTheBug() {
        int H = 2220, nav = 126, bar = 150;
        int y = WindowFlowPolicy.barTop(H, 60, nav, 120, 300, bar, 20, 0, false, true);
        assertEquals(60 + 120 + 300 + 20, y);
        assertTrue("修复前底栏离屏幕底还差一大截", H - nav - bar - y > 1000);
        assertEquals("长答案顶满时才会落到底", H - nav - bar, WindowFlowPolicy.barTop(H, 60, nav, 120, 5000, bar, 20, 0, false, true));
        assertEquals("贴底排（紧凑卡）本来就在底", H - nav - bar, WindowFlowPolicy.barTop(H, 60, nav, 120, 300, bar, 20, 0, false, false));
    }

    @Test public void resultStageIsCardFull() {
        assertTrue("出结果时卡片拉满（Gravity.TOP 从上往下排）—— 根因前提", WindowFlowPolicy.cardFull(WakeStage.Stage.RESULT));
    }

    @Test public void wiring() {
        String v = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue("渲染时按规则贴底", v.contains("v2PinBar(com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.pinBarBottom(st, mV2, mV2FullWindow, mChat != null && mChat.shownOnCard()))"));
        int pin = v.indexOf("private void v2PinBar(boolean pin)");
        String body = v.substring(pin, v.indexOf("boolean barPinned()", pin));
        assertTrue("贴底 = 内容区 (0, weight 1)", body.contains("new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)"));
        assertTrue("取消贴底还原原来的 LayoutParams", body.contains("mContentScroll.setLayoutParams(mPinSavedScrollLp);"));
        assertTrue("对话窗不碰（ChatWindowController 自己管）", body.contains("chatActive()"));
        // 对话窗：同样是内容区 weight=1、底栏在根布局最后
        String cw = src("app/src/com/onesay/assistant/v2/ui/chat/ChatWindowController.java");
        assertTrue(cw.contains("new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)"));
        assertTrue("底栏加在根布局末尾", cw.contains("root.addView(mBar, bl);") && cw.contains("mP.root.addView(mBar, bl);"));
        // 键盘让位：根布局底部内边距 += 键盘高（Android 8 overlay 不支持 ADJUST_RESIZE）
        assertTrue(v.contains("if (mV2) setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), v2PadBottomBase() + px);"));
        // 结果态底栏在渲染里先于贴底判定刷新（聆听阶段底栏先隐藏 → 不贴底）
        assertTrue(v.indexOf("if (!chat) v2RefreshCardBar(st);") < v.indexOf("if (!chat) v2PinBar("));
    }
}
