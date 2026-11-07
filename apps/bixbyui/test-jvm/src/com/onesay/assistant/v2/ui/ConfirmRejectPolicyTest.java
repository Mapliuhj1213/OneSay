package com.onesay.assistant.v2.ui;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * round3 #1：确认卡被拒绝 → 「已拒绝」终态 → 2.5 s 中性态 → 收起；另一张卡未决 / 循环 / 任务 / 文字输入时不收（S1 不变量）。
 */
public class ConfirmRejectPolicyTest {

    static ConfirmRejectPolicy.Plan p(ConfirmRejectPolicy.Path path, boolean onScreen, boolean other, boolean task, boolean text) {
        return ConfirmRejectPolicy.plan(path, onScreen, other, task, text);
    }

    @Test public void singleStepRejectShowsRejectedThenCollapsesOnErrorTiming() {
        ConfirmRejectPolicy.Plan pl = p(ConfirmRejectPolicy.Path.SINGLE_STEP, true, false, false, false);
        assertTrue(pl.apply);
        assertTrue("先显示「已拒绝」终态，不是立刻收起", pl.showRejected);
        assertEquals(ErrorStatePolicy.ERROR_VISIBLE_MS, pl.neutralAfterMs);
        assertEquals(ErrorStatePolicy.ERROR_VISIBLE_MS, pl.hideAfterMs);
        assertEquals(2500L, pl.hideAfterMs);
    }

    @Test public void anotherPendingCardBlocksCollapse() {
        ConfirmRejectPolicy.Plan pl = p(ConfirmRejectPolicy.Path.SINGLE_STEP, true, true, false, false);
        assertTrue(pl.showRejected);
        assertEquals(-1, pl.neutralAfterMs);
        assertEquals(-1, pl.hideAfterMs);
        // 到点复核同样拦住（期间又弹出了新卡）
        assertFalse(ErrorStatePolicy.stillHide(true, true, false, false));
        assertFalse(ErrorStatePolicy.stillNeutral(true, true, false));
    }

    @Test public void agentLoopRejectNeverCollapses() {
        ConfirmRejectPolicy.Plan pl = p(ConfirmRejectPolicy.Path.AGENT_LOOP, true, false, false, false);
        assertTrue(pl.showRejected);
        assertEquals("循环拿到 DENY 继续收尾，面板归任务", -1, pl.hideAfterMs);
        assertEquals(-1, pl.neutralAfterMs);
        assertEquals(-1, p(ConfirmRejectPolicy.Path.SINGLE_STEP, true, false, true, false).hideAfterMs);
    }

    @Test public void textModeOnlyNeutral() {
        ConfirmRejectPolicy.Plan pl = p(ConfirmRejectPolicy.Path.SINGLE_STEP, true, false, false, true);
        assertEquals(ErrorStatePolicy.ERROR_VISIBLE_MS, pl.neutralAfterMs);
        assertEquals(-1, pl.hideAfterMs);
    }

    @Test public void offScreenIgnored() {
        ConfirmRejectPolicy.Plan pl = p(ConfirmRejectPolicy.Path.SINGLE_STEP, false, false, false, false);
        assertFalse(pl.apply);
        assertFalse(pl.showRejected);
        assertEquals(-1, pl.hideAfterMs);
    }

    @Test public void cardText() {
        assertEquals("【清空记忆库】该操作不会执行。", ConfirmRejectPolicy.cardText("清空记忆库"));
        assertEquals("该操作不会执行。", ConfirmRejectPolicy.cardText(null));
        assertEquals("已拒绝", ConfirmRejectPolicy.TITLE);
        assertNotNull(ConfirmRejectPolicy.plan(null, true, false, false, false));
    }

    /**
     * 宿主接线守卫：红卡「取消」与黄卡「拒绝」都走 onConfirmChoice(REJECT) → showRejectedState + applyRejectPolicy；
     * 「已拒绝」卡不算未决确认卡；FSS 保持 CRLF。
     */
    @Test public void hostWiring() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String fss = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath()), Charset.forName("UTF-8"));
        String view = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java").toPath()), Charset.forName("UTF-8"));
        assertTrue("CRLF", fss.contains("\r\n"));
        assertTrue(fss.contains("applyRejectPolicy(com.onesay.assistant.v2.ui.ConfirmRejectPolicy.Path.SINGLE_STEP)"));
        assertTrue(fss.contains("applyRejectPolicy(com.onesay.assistant.v2.ui.ConfirmRejectPolicy.Path.AGENT_LOOP)"));
        assertEquals("单步与循环两处都换成「已拒绝」终态卡", 2, count(fss, "mSheetView.showRejectedState("));
        assertFalse("不再用旧的橙色「已拒绝」状态行代替终态卡", fss.contains("updateState(\"已拒绝\""));
        // 红卡取消键：先走拒绝通道
        int c = view.indexOf("mBtnCancel.setOnClickListener");
        String redCancel = view.substring(c, view.indexOf("mConfirmRowRed.addView(mBtnCancel", c));
        assertTrue(redCancel.contains("CHOICE_REJECT"));
        assertTrue("拒绝通道在前、旧的立即收起只作兜底", redCancel.indexOf("CHOICE_REJECT") < redCancel.lastIndexOf("mListener.onCancelAction()"));
        assertTrue(view.contains("&& !mConfirmDecided"));
        assertTrue(view.contains("if (mConfirmDecided) hideConfirmCard();"));
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
