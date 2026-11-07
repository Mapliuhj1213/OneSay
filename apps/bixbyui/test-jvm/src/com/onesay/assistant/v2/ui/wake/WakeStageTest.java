package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.onesay.assistant.v2.ui.wake.WakeStage.Effect;
import com.onesay.assistant.v2.ui.wake.WakeStage.Event;
import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;
import com.onesay.assistant.v2.ui.wake.WakeStage.Transition;

/** [R13] 唤醒界面状态机：转移表、效果、旧徽标适配、每态视觉参数。 */
public class WakeStageTest {

    @Test public void wakeListenThinkAnswerResult() {
        WakeStage m = new WakeStage();
        assertEquals(Stage.IDLE, m.stage());
        Transition t = m.on(Event.WAKE);
        assertEquals(Stage.LISTENING, t.to);
        assertTrue(t.has(Effect.GLOW_IGNITE) && t.has(Effect.CARD_IN) && t.has(Effect.SHOW_ORB) && t.has(Effect.HAPTIC_IGNITE));
        t = m.on(Event.LISTEN_UPDATE);
        assertFalse("转写更新不重新点火", t.has(Effect.GLOW_IGNITE));
        assertTrue(t.has(Effect.CONTENT_IN));
        t = m.on(Event.THINK);
        assertEquals(Stage.THINKING, t.to);
        assertTrue("说完：光吸进卡片", t.has(Effect.GLOW_INHALE) && t.has(Effect.HIDE_ORB));
        t = m.on(Event.ANSWER_STREAM);
        assertEquals(Stage.ANSWERING, t.to);
        assertTrue(t.has(Effect.CARD_RESIZE) && t.has(Effect.CONTENT_IN));
        t = m.on(Event.ANSWER_STREAM);
        assertFalse("流式续写不重复换入", t.has(Effect.CONTENT_IN));
        t = m.on(Event.RESULT);
        assertEquals(Stage.RESULT, t.to);
        assertTrue(t.has(Effect.CHIPS_IN) && t.has(Effect.HAPTIC_RESULT));
        t = m.on(Event.RESULT);
        assertFalse("同一结果只震一次", t.has(Effect.HAPTIC_RESULT));
    }

    @Test public void wakeWhileListeningIsNoop() {
        WakeStage m = new WakeStage();
        m.on(Event.WAKE);
        Transition t = m.on(Event.WAKE);
        assertFalse(t.changed());
        assertTrue(t.effects.isEmpty());
    }

    @Test public void taskMorphsIntoIslandAndFlowsBack() {
        WakeStage m = new WakeStage();
        m.on(Event.WAKE);
        m.on(Event.TASK);
        Transition t = m.on(Event.COLLAPSE_TO_ISLAND);
        assertEquals(Stage.HANDOFF, t.to);
        assertTrue(t.has(Effect.MORPH_TO_ISLAND) && t.has(Effect.GLOW_OFF));
        assertFalse("形变途中卡片已不在屏上", WakeStage.cardOnScreen(Stage.HANDOFF));
        assertEquals("形变途中的步骤更新不改状态", Stage.HANDOFF, m.on(Event.TASK).to);
        t = m.on(Event.LANDED_IN_ISLAND);
        assertEquals(Stage.ISLAND, t.to);
        assertTrue("落进岛「咚」一下", t.has(Effect.HAPTIC_LAND));
        assertEquals("岛上的进度 / 结果岛自己画", Stage.ISLAND, m.on(Event.RESULT).to);
        assertEquals(Stage.ISLAND, m.on(Event.THINK).to);
        t = m.on(Event.RETURN_FROM_ISLAND);
        assertEquals(Stage.RESULT, t.to);
        assertTrue("长结果从岛流回卡片", t.has(Effect.UNMORPH_FROM_ISLAND));
    }

    @Test public void interruptHandoffWithNewWakeReleasesStage() {
        WakeStage m = new WakeStage();
        m.on(Event.WAKE);
        m.on(Event.TASK);
        m.on(Event.COLLAPSE_TO_ISLAND);
        Transition t = m.on(Event.WAKE);
        assertEquals(Stage.LISTENING, t.to);
        assertTrue(t.has(Effect.RELEASE_STAGE) && t.has(Effect.CARD_IN));
    }

    @Test public void dismissAnimatesThenIdle() {
        WakeStage m = new WakeStage();
        m.on(Event.WAKE);
        m.on(Event.RESULT);
        Transition t = m.on(Event.DISMISS);
        assertEquals(Stage.DISMISSING, t.to);
        assertTrue("✕ / 点外部也走退场动画（不再硬拆）", t.has(Effect.CARD_DISMISS) && t.has(Effect.GLOW_OFF));
        assertEquals(Stage.DISMISSING, m.on(Event.DISMISS).to);
        assertEquals(Stage.IDLE, m.on(Event.DISMISSED).to);
        // 退场途中新唤醒 → 反向接回
        m.on(Event.WAKE);
        m.on(Event.DISMISS);
        t = m.on(Event.WAKE);
        assertEquals(Stage.LISTENING, t.to);
        assertTrue(t.has(Effect.CARD_IN));
    }

    @Test public void errorAndConfirmAndText() {
        WakeStage m = new WakeStage();
        m.on(Event.WAKE);
        Transition t = m.on(Event.ERROR);
        assertEquals(Stage.ERROR, t.to);
        assertTrue(t.has(Effect.HAPTIC_ERROR) && t.has(Effect.GLOW_DIM_ERROR));
        assertFalse("同一错误只震一次", m.on(Event.ERROR).has(Effect.HAPTIC_ERROR));
        t = m.on(Event.CONFIRM);
        assertEquals(Stage.CONFIRM, t.to);
        assertTrue("确认卡出现时卡片轻压一下", t.has(Effect.CARD_PRESS));
        t = m.on(Event.TEXT_MODE);
        assertEquals(Stage.TEXT, t.to);
        assertTrue(t.has(Effect.SHOW_KEYBOARD) && t.has(Effect.GLOW_OFF));
        assertEquals(Stage.IDLE, m.on(Event.RESET).to);
    }

    @Test public void contentWithoutWakeBringsCardIn() {
        WakeStage m = new WakeStage();
        Transition t = m.on(Event.RESULT);   // 面板注入 / 剪贴板芯片 / 冷启动补发
        assertEquals(Stage.RESULT, t.to);
        assertTrue(t.has(Effect.CARD_IN));
        WakeStage m2 = new WakeStage();
        t = m2.on(Event.RETURN_FROM_ISLAND);
        assertEquals(Stage.RESULT, t.to);
        assertTrue(t.has(Effect.CARD_IN));
        assertEquals("空闲时的收起事件不出卡片", Stage.IDLE, new WakeStage().on(Event.DISMISS).to);
    }

    @Test public void legacyBadgesMapToEvents() {
        assertEquals(Event.LISTEN_UPDATE, WakeStage.classifyBadge("正在聆听"));
        assertEquals(Event.LISTEN_UPDATE, WakeStage.classifyBadge("正在听"));
        assertEquals(Event.THINK, WakeStage.classifyBadge("正在解析"));
        assertEquals(Event.THINK, WakeStage.classifyBadge("正在识别"));
        assertEquals(Event.THINK, WakeStage.classifyBadge("正在识屏"));
        assertEquals(Event.THINK, WakeStage.classifyBadge("正在作答…"));
        assertEquals(Event.THINK, WakeStage.classifyBadge("正在检索资料…"));
        assertEquals(Event.THINK, WakeStage.classifyBadge("正在生成答案…"));
        assertEquals("单步执行是短暂的「正在想」", Event.THINK, WakeStage.classifyBadge("正在执行"));
        assertEquals(Event.TASK, WakeStage.classifyBadge("正在处理"));
        assertEquals(Event.TEXT_MODE, WakeStage.classifyBadge("键盘输入"));
        assertEquals(Event.CONFIRM, WakeStage.classifyBadge("安全拦截"));
        assertEquals(Event.CONFIRM, WakeStage.classifyBadge("操作待确认"));
        assertEquals(Event.ERROR, WakeStage.classifyBadge("没听清"));
        assertEquals(Event.ERROR, WakeStage.classifyBadge("启动超时"));
        // 终态先于进行态（「执行完毕」含「执行」是旧实现误吞的根源）
        for (String b : new String[] {"执行完毕", "执行成功", "执行异常", "执行未达成", "部分完成", "已启动应用", "未能完成", "已中止",
                "智能问答", "识屏完成", "识屏失败", "已拒绝", "已允许", "已被拦截", "未能识别", "异常", "确认超时", "确认已过期",
                "闹钟已开启", "闹钟已关闭", "未开启", "开关未生效"}) {
            assertEquals(b, Event.RESULT, WakeStage.classifyBadge(b));
        }
        assertNull(WakeStage.classifyBadge(null));
        assertNull(WakeStage.classifyBadge("  "));
        assertEquals("不认识的徽标当作结果（不会卡在进行态）", Event.RESULT, WakeStage.classifyBadge("随便什么"));
        assertEquals("emoji 前缀也认", Event.LISTEN_UPDATE, WakeStage.classifyBadge("\uD83C\uDFA4 说吧"));
    }

    @Test public void visualParamsPerStage() {
        assertEquals("D3′ 聆听 30%", 0.30f, WakeStage.dimLevel(Stage.LISTENING), 0f);
        assertEquals("D3′ 作答 50%", 0.50f, WakeStage.dimLevel(Stage.ANSWERING), 0f);
        assertEquals(0.50f, WakeStage.dimLevel(Stage.RESULT), 0f);
        assertEquals("形变 / 岛 / 空闲不压暗", 0f, WakeStage.dimLevel(Stage.HANDOFF), 0f);
        assertEquals(0f, WakeStage.dimLevel(Stage.ISLAND), 0f);
        assertEquals(0f, WakeStage.dimLevel(Stage.IDLE), 0f);
        assertEquals(0.45f, WakeStage.dimStartFrac(Stage.LISTENING), 0f);
        assertEquals(0.25f, WakeStage.dimStartFrac(Stage.RESULT), 0f);
        assertTrue(WakeStage.glowOn(Stage.LISTENING));
        assertFalse("光只代表「在听」", WakeStage.glowOn(Stage.ANSWERING));
        assertTrue(WakeStage.orbOn(Stage.LISTENING));
        assertFalse("聆听时不放关闭键", WakeStage.closeOn(Stage.LISTENING));
        assertTrue(WakeStage.closeOn(Stage.RESULT));
        assertFalse(WakeStage.closeOn(Stage.ISLAND));
        assertEquals(0.22f, WakeStage.innerGlow(Stage.LISTENING), 0f);
        assertEquals(0.30f, WakeStage.innerGlow(Stage.ANSWERING), 0f);
        assertEquals(0.12f, WakeStage.innerGlow(Stage.RESULT), 0f);
    }

    @Test public void everyStageHandlesEveryEvent() {
        for (Stage s : Stage.values()) {
            for (Event e : Event.values()) {
                java.util.List<Effect> fx = new java.util.ArrayList<Effect>();
                Stage to = WakeStage.next(s, e, fx);
                assertTrue(s + "+" + e, to != null);
                if (e == Event.RESET) assertEquals(Stage.IDLE, to);
                if (e == Event.WAKE) assertEquals(Stage.LISTENING, to);
            }
        }
    }
}
