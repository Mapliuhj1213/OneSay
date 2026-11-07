package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.island.IslandModel.Effect;
import com.onesay.assistant.v2.island.IslandModel.Kind;
import com.onesay.assistant.v2.island.IslandModel.Mode;

/** [R13 D4=A] 短结果在岛上就地展开，长结果流回卡片；旧界面保持 R12 行为。 */
public class IslandResultPolicyTest {

    static IslandModel collapsed() {
        IslandModel x = new IslandModel();
        x.setMode(Mode.ISLAND);
        x.configure(true, true);
        x.drainTimers();
        x.taskStarted(1000L, "整理今天的邮件");
        x.autoCollapseDue(x.taskSeq());
        x.drainTimers();
        return x;
    }

    @Test public void policy() {
        assertTrue(IslandResultPolicy.onIsland("3 封需要你回复", true));
        assertTrue(IslandResultPolicy.onIsland("邮件整理好了\n3 封需要你回复", true));
        assertFalse("旧界面：一律挂回面板（R12）", IslandResultPolicy.onIsland("3 封需要你回复", false));
        assertFalse("三行以上 = 长", IslandResultPolicy.onIsland("a\nb\nc", true));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 61; i++) sb.append('字');
        assertFalse("超过 60 字 = 长", IslandResultPolicy.onIsland(sb.toString(), true));
        assertFalse("闹钟卡要看卡片", IslandResultPolicy.onIsland("alarm", true));
        assertFalse(IslandResultPolicy.onIsland("  ", true));
        assertFalse(IslandResultPolicy.onIsland(null, true));
        String[] s = IslandResultPolicy.split("邮件整理好了。3 封需要你回复，其余已读");
        assertEquals("邮件整理好了", s[0]);
        assertEquals("3 封需要你回复，其余已读", s[1]);
        assertEquals("没有首行时整段当主句", "比特币现价约 86,258 美元，24 小时内上涨约 3.28%",
                IslandResultPolicy.split("比特币现价约 86,258 美元，24 小时内上涨约 3.28%")[0]);
    }

    @Test public void shortResultExpandsOnIslandWithoutReattachingSheet() {
        IslandModel m = collapsed();
        List<Effect> e = m.taskEndedShort(false, false, true, "3 封需要你回复", "王老师 · 教务处", "查看", null, 5000L);
        assertFalse("不挂回面板", e.contains(Effect.ATTACH_SHEET));
        assertTrue(e.contains(Effect.RENDER));
        IslandModel.Snapshot v = m.view();
        assertEquals("岛就地展开结果卡", Kind.EXP_NOTE, v.kind);
        assertTrue(v.key.startsWith(IslandModel.TASK_RESULT_PREFIX));
        assertEquals("3 封需要你回复", v.title);
        assertEquals("已完成 · 整理今天的邮件", v.noteMeta);
        assertEquals(1, v.noteActions.size());
        assertEquals("查看", v.noteActions.get(0));
        assertFalse("任务已收工", m.isRunning());
        List<IslandModel.Timer> ts = m.drainTimers();
        boolean hide = false;
        for (IslandModel.Timer t : ts) if ("noteHide".equals(t.name)) hide = true;
        assertTrue("几秒后自己收起", hide);
        // 「查看」→ NOTE_ACTION，目标带前缀（宿主据此流回卡片，而不是打开记一记详情）
        List<Effect> a = m.noteAction(0);
        assertTrue(a.contains(Effect.NOTE_ACTION));
        assertTrue(m.lastTarget().startsWith(IslandModel.TASK_RESULT_PREFIX));
    }

    @Test public void fallsBackToOldRulesWhenNotApplicable() {
        IslandModel m = collapsed();
        List<Effect> e = m.taskEndedShort(false, true, true, "已打开微信", null, "查看", null, 5000L);
        assertFalse("拉起了界面 → 原规则（对勾 1.8 s），不展开", e.contains(Effect.ATTACH_SHEET));
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
        IslandModel f = collapsed();
        f.taskEndedShort(false, false, false, "叫车超时", null, "查看", "叫车超时", 5000L);
        assertEquals("失败 → 失败卡（原规则）", Kind.EXP_FAIL, f.view().kind);
        IslandModel n = new IslandModel();
        n.setMode(Mode.ISLAND);
        n.configure(true, true);
        n.taskStarted(1000L, "x");
        assertTrue("没收起（面板还在）→ 原规则：无动作", n.taskEndedShort(false, false, true, "好了", null, "查看", null, 5000L).isEmpty());
    }
}
