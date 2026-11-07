package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.onesay.assistant.v2.ui.wake.WakeStage.Effect;
import com.onesay.assistant.v2.ui.wake.WakeStage.Event;
import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;
import com.onesay.assistant.v2.ui.wake.WakeStage.Transition;

/**
 * ★ LC-5：任务落岛后再来新请求（ACTION_SHOW 文字注入 / 面板注入 / ⌨ 输入 / 语音指令都汇到 onSubmitCommand），
 * 卡片不能卡在 ISLAND：新建卡片接住请求（→ THINKING → 回答），岛上任务不受影响。
 */
public class Lc5NewRequestTest {

    static WakeStage at(Stage target) {
        WakeStage w = new WakeStage();
        switch (target) {
            case IDLE: return w;
            case ISLAND:
                w.on(Event.WAKE); w.on(Event.TASK); w.on(Event.COLLAPSE_TO_ISLAND); w.on(Event.LANDED_IN_ISLAND);
                break;
            case HANDOFF:
                w.on(Event.WAKE); w.on(Event.TASK); w.on(Event.COLLAPSE_TO_ISLAND);
                break;
            case DISMISSING:
                w.on(Event.WAKE); w.on(Event.RESULT); w.on(Event.DISMISS);
                break;
            case LISTENING: w.on(Event.WAKE); break;
            case THINKING: w.on(Event.WAKE); w.on(Event.THINK); break;
            case ANSWERING: w.on(Event.WAKE); w.on(Event.ANSWER_STREAM); break;
            case RESULT: w.on(Event.WAKE); w.on(Event.RESULT); break;
            case TASK: w.on(Event.WAKE); w.on(Event.TASK); break;
            case CONFIRM: w.on(Event.WAKE); w.on(Event.CONFIRM); break;
            case ERROR: w.on(Event.WAKE); w.on(Event.ERROR); break;
            case TEXT: w.on(Event.WAKE); w.on(Event.TEXT_MODE); break;
            default: throw new IllegalArgumentException(String.valueOf(target));
        }
        assertEquals(target, w.stage());
        return w;
    }

    @Test public void island_new_request_opens_a_new_card_in_thinking() {
        WakeStage w = at(Stage.ISLAND);
        Transition t = w.on(Event.NEW_REQUEST);
        assertEquals(Stage.THINKING, t.to);
        assertTrue(t.changed());
        assertTrue(t.has(Effect.CARD_IN));
        assertTrue(t.has(Effect.CONTENT_IN));
        assertFalse("不是形变回程：岛上任务留在岛上", t.has(Effect.UNMORPH_FROM_ISLAND));
        // 随后的「正在解析」→ THINK 不再被吞，结果能上卡片
        assertEquals(Stage.THINKING, w.on(Event.THINK).to);
        Transition r = w.on(Event.RESULT);
        assertEquals(Stage.RESULT, r.to);
        assertTrue(r.has(Effect.CHIPS_IN));
    }

    @Test public void handoff_new_request_releases_stage_and_opens_card() {
        WakeStage w = at(Stage.HANDOFF);
        Transition t = w.on(Event.NEW_REQUEST);
        assertEquals(Stage.THINKING, t.to);
        assertTrue(t.has(Effect.RELEASE_STAGE));
        assertTrue(t.has(Effect.CARD_IN));
        // 形变落岛的回调晚到：不能把新卡片拉回 ISLAND
        assertEquals(Stage.THINKING, w.on(Event.LANDED_IN_ISLAND).to);
    }

    @Test public void island_task_steps_still_do_not_touch_the_card() {
        // 回归保护：岛上任务自己的步骤 / 结果（THINK / TASK / RESULT 事件）仍然只让岛画，卡片不动
        for (Event e : new Event[] {Event.THINK, Event.TASK, Event.RESULT, Event.ANSWER_STREAM, Event.LISTEN_UPDATE}) {
            WakeStage w = at(Stage.ISLAND);
            Transition t = w.on(e);
            assertEquals(e.toString(), Stage.ISLAND, t.to);
            assertTrue(e.toString(), t.effects.isEmpty());
        }
    }

    @Test public void card_on_screen_new_request_equals_think() {
        Stage[] on = {Stage.LISTENING, Stage.THINKING, Stage.ANSWERING, Stage.RESULT, Stage.TASK, Stage.CONFIRM,
                Stage.ERROR, Stage.TEXT, Stage.IDLE, Stage.DISMISSING};
        for (Stage s : on) {
            List<Effect> a = new ArrayList<Effect>();
            List<Effect> b = new ArrayList<Effect>();
            Stage x = WakeStage.next(s, Event.NEW_REQUEST, a);
            Stage y = WakeStage.next(s, Event.THINK, b);
            assertEquals(s.toString(), y, x);
            assertEquals(s.toString(), b, a);
        }
    }

    @Test public void keyboard_path_is_not_affected() {
        // ⌨ 只在卡片在屏上时可点（聆听 / 结果 / 出错）；从岛「查看」流回后点 ⌨ 输入新内容 → TEXT → 正常进思考
        assertEquals(android.view.View.GONE, WakeStage.iconRowVisibility(Stage.ISLAND));
        WakeStage w = at(Stage.ISLAND);
        assertEquals(Stage.RESULT, w.on(Event.RETURN_FROM_ISLAND).to);
        assertEquals(Stage.TEXT, w.on(Event.TEXT_MODE).to);
        assertEquals(Stage.THINKING, w.on(Event.NEW_REQUEST).to);
        assertEquals(Stage.RESULT, w.on(Event.RESULT).to);
    }

    @Test public void wake_from_island_unchanged() {
        WakeStage w = at(Stage.ISLAND);
        Transition t = w.on(Event.WAKE);
        assertEquals(Stage.LISTENING, t.to);
        assertTrue(t.has(Effect.CARD_IN));
    }

    @Test public void leaves_island_only_for_island_and_handoff() {
        for (Stage s : Stage.values()) {
            boolean want = s == Stage.ISLAND || s == Stage.HANDOFF;
            assertEquals(s.toString(), want, WakeStage.newRequestLeavesIsland(s));
        }
    }

    @Test public void task_result_routing() {
        assertTrue(TaskResultRouting.writeToCard(3, 3));
        assertFalse("派发后来过新请求：结论不覆盖新回答", TaskResultRouting.writeToCard(3, 4));
        assertFalse(TaskResultRouting.restoreOnView(3, 3));
        assertTrue("「查看」时卡片已是新请求的内容 → 先重画", TaskResultRouting.restoreOnView(3, 5));
    }

    @Test public void task_result_store_keeps_latest() {
        TaskResultRouting.Store st = new TaskResultRouting.Store();
        for (int i = 1; i <= TaskResultRouting.Store.CAP + 3; i++) st.put("task-result-errand-" + i, new TaskResultRouting.Result("q" + i, "a" + i, i));
        assertEquals(TaskResultRouting.Store.CAP, st.size());
        assertNull(st.get("task-result-errand-1"));
        assertNull(st.get("task-result-errand-3"));
        assertNotNull(st.get("task-result-errand-4"));
        assertEquals("a11", st.get("task-result-errand-11").answer);
        st.put("x", null);
        st.put(null, new TaskResultRouting.Result("q", "a", 1));
        assertNull(st.get(null));
        assertEquals(TaskResultRouting.Store.CAP, st.size());
    }

    // ------------------------------------------------------------------ 接线守卫（源码）

    static String src(String rel) throws Exception {
        java.io.File dir = com.onesay.assistant.v2.testutil.TestRoot.start(Lc5NewRequestTest.class);
        for (int i = 0; i < 6 && dir != null; i++) {
            java.io.File f = new java.io.File(dir, "app/src/" + rel);
            if (f.exists()) return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        throw new java.io.FileNotFoundException(rel);
    }

    @Test public void wiring_submit_begins_request_before_parsing_state() throws Exception {
        String s = src("com/onesay/assistant/v2/ui/FloatingSheetService.java");
        int sub = s.indexOf("public void onSubmitCommand(final String command)");
        int begin = s.indexOf("beginForegroundRequest();", sub);
        int parse = s.indexOf("mSheetView.updateState(\"正在解析\", Color.parseColor(\"#0A84FF\"), \"“\" + command + \"”\");", sub);
        assertTrue(sub > 0 && begin > sub && parse > begin);
        assertTrue(s.contains("if (!com.onesay.assistant.v2.ui.wake.WakeStage.newRequestLeavesIsland(st)) return;"));
        assertTrue(s.contains("mSheetView.v2BeginRequest();"));
        String v = src("com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(v.contains("v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.NEW_REQUEST);"));
    }

    @Test public void wiring_errand_result_does_not_overwrite_new_answer() throws Exception {
        String h = src("com/onesay/assistant/v2/errand/ErrandHooks.java");
        assertTrue(h.contains("final int seq = svc.requestSeq();"));
        assertTrue(h.contains("boolean ownsCard = com.onesay.assistant.v2.ui.wake.TaskResultRouting.writeToCard(seq, svc.requestSeq());"));
        assertTrue(h.contains("if (sheet != null && !cancelled && ownsCard) sheet.showAskReply(command, card.full);"));
        assertTrue(h.contains("svc.s1RememberTaskResult(IslandModel.TASK_RESULT_PREFIX + id, command, card.full, seq);"));
        String c = src("com/onesay/assistant/v2/s1/IslandController.java");
        assertEquals(2, count(c, "mSvc.s1ShowTaskResult("));
    }

    static int count(String s, String k) {
        int n = 0;
        for (int i = s.indexOf(k); i >= 0; i = s.indexOf(k, i + 1)) n++;
        return n;
    }
}
